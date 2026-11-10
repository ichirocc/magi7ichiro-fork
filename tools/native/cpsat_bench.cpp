// CP-SAT モデル（app/src/main/cpp/magi_cpsat.h）のホスト検証。
//   g++ -O2 -std=c++20 -DMAGI_HOST_TEST -DOR_PROTO_DLL= -I app/src/main/cpp -I tools/native -I <ortools>/include \
//       tools/native/cpsat_bench.cpp -L<ortools>/lib -lortools -lprotobuf -o cpsat_bench
//   cpsat_bench <flat> [seconds=30] [workers=4] [priority=1]
// ①盤面を固定したモデルの採点が fullEvalParts（fair を除く・必須の重み込み）と一致するか ②下限・解・ギャップ。
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"

static void setPriority(MagiProblem& p) {   // Kotlin ObjectiveWeights の優先順位（T1 200 / T2 20 / T3 1）
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = 20;
    p.w.apt = p.w.c2 = p.w.covO = p.w.weekly = p.w.fair = 1;
}

int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> board;
    if (argc < 2 || !loadFlat(argv[1], p, board)) return 2;
    double secs = argc > 2 ? atof(argv[2]) : 30; int workers = argc > 3 ? atoi(argv[3]) : 4;
    if (argc <= 4 || atoi(argv[4]) == 1) setPriority(p);
    magi_cpsat::HardW hw;
    auto evalW = [&](const std::vector<int>& b, long long& hard, long long& wNoFair, long long& fairW) {
        long long out[2], bd[21]; fullEvalParts(p, b.data(), out, bd);
        hard = out[0];
        long long hw2 = bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish;
        fairW = bd[17] * p.w.fair;
        wNoFair = out[1] - fairW + hw2;
    };
    long long h0, w0, f0; evalW(board, h0, w0, f0);
    magi_cpsat::Options fix; fix.fixToHint = true; fix.maxSeconds = 20; fix.workers = workers;
    auto rf = magi_cpsat::solve(p, board, fix, hw);
    printf("PARITY board: engine hard=%lld weighted(noFair)=%lld fair=%lld | model hard=%lld weighted=%lld -> %s\n",
           h0, w0, f0, rf.hard, rf.weighted, (rf.hard == h0 && rf.weighted == w0) ? "MATCH" : "MISMATCH");
    magi_cpsat::Options o; o.maxSeconds = secs; o.workers = workers;
    if (const char* sub = getenv("CPSAT_SUBSOLVERS")) { std::string x(sub), t; for (char ch : x + ",") { if (ch == ',') { if (!t.empty()) o.subsolvers.push_back(t); t.clear(); } else t += ch; } }
    // CPSAT_STOP_AT: アプリの探索の終わり（秒）を模して外から止める。
    std::atomic<bool> stopFlag{false}; o.stop = &stopFlag;
    std::thread stopper;
    if (const char* sa = getenv("CPSAT_STOP_AT")) { double at = atof(sa); stopper = std::thread([&] { std::this_thread::sleep_for(std::chrono::milliseconds((long long)(at * 1000))); stopFlag = true; }); }
    auto r = magi_cpsat::solve(p, board, o, hw);
    if (stopper.joinable()) stopper.join();
    long long h1 = -1, w1 = -1, f1 = 0;
    if (!r.board.empty()) evalW(r.board, h1, w1, f1);
    printf("SOLVE status=%d %.1fs | hard %lld (bound %lld proven=%d) | weighted model %lld bound %lld | engine-check hard=%lld weighted(noFair)=%lld fair=%lld | input weighted %lld+fair %lld\n",
           r.status, r.wallSeconds, r.hard, r.hardBound, (int)r.hardProven, r.weighted, r.weightedBound, h1, w1, f1, w0, f0);
    if (!r.board.empty() && (h1 != r.hard || w1 != r.weighted)) {
        // 族ごとに盤面を固定したモデルとエンジンの内訳を比べる（不一致の切り分け）。
        long long out[2], bd[21]; fullEvalParts(p, r.board.data(), out, bd);
        const char* names[9] = {"c1", "c2", "c41/c41s", "c42/c42s", "c3 族", "セル(pref/groupViol/c3w/ext)", "low/high/apt", "weekly", "covU/covO"};
        long long eng[9] = {bd[0] * p.w.c1, bd[1] * p.w.c2, bd[6] * p.w.c41 + bd[8] * p.w.c41s, bd[7] * p.w.c42 + bd[9] * p.w.c42s,
            bd[2] * p.w.c3 + bd[3] * hw.c3n + bd[4] * p.w.c3m + bd[5] * p.w.c3mn,
            bd[12] * hw.pref + bd[15] * hw.groupViol + bd[19] * hw.c3w + bd[20] * hw.extWish,
            bd[13] * p.w.low + bd[14] * p.w.high + bd[16] * p.w.apt, bd[18] * p.w.weekly, bd[10] * hw.covU + bd[11] * p.w.covO};
        for (int fam = 0; fam < 9; fam++) {
            magi_cpsat::Options fo; fo.fixToHint = true; fo.workers = 1; fo.maxSeconds = 20; fo.families = 1u << fam;
            auto rr = magi_cpsat::solve(p, r.board, fo, hw);
            printf("  FAMILY %-28s model=%lld engine=%lld %s\n", names[fam], rr.weighted, eng[fam], rr.weighted == eng[fam] ? "" : "<-- MISMATCH");
        }
    }
    if (r.weightedBound >= 0) {
        long long ub = std::min(w0 + f0, w1 + f1);
        printf("GAP (best of input/cpsat incl. fair %lld vs bound %lld) = %.2f%%\n", ub, r.weightedBound, 100.0 * (ub - r.weightedBound) / std::max(1LL, ub));
    }
    return 0;
}
