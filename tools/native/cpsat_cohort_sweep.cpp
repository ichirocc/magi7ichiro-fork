// K 人の組の厳密スイープ（TOOL・出力不変、docs/cpsat_formal.md「K 人の組の厳密スイープ」）: すべての K 人の組で、ほかの職員を固定して組の行だけを CP-SAT で解く
//   （1 段目＝必須を減らせるか、2 段目＝重みの最小）。モデルの目的は実際以下＝下限が入力以上なら K 最適の証明、見つけた盤面は fullEvalParts で確かめる。
//   K 最適は前線の上（本物の交換）の証明ではない（前線は S2b）。組み方は cpsat_bound_check.cpp と同じ（-pthread）。
//   cpsat_cohort_sweep <flat> [K=3] [秒/組=20] [priority=1] [workers=4] [良くなった盤面の書き出し先]
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
namespace sat = magi_cpsat::sat;

static void setPriority(MagiProblem& p) {   // Kotlin ObjectiveWeights の優先順位（cpsat_bound_check.cpp と同じ）
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = p.w.gap = 20;
    p.w.weekly = 1; p.w.covO = p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}

int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> base;
    if (argc < 2 || !loadFlat(argv[1], p, base)) return 2;
    const int K = argc > 2 ? atoi(argv[2]) : 3;
    const double secs = argc > 3 ? atof(argv[3]) : 20.0;
    if (argc <= 4 || atoi(argv[4]) == 1) setPriority(p);
    const int workers = argc > 5 ? atoi(argv[5]) : 4;
    const char* outPath = argc > 6 ? argv[6] : nullptr;
    const int S = p.S, T = p.T;
    if (K < 1 || K > S) return 2;
    magi_cpsat::HardW hw;
    // エンジンの採点: [必須の件数, 必須の重み込みの重み付き（fair 込み）]（cpsat_bound_check.cpp と同じ）。
    auto score = [&](const std::vector<int>& b, long long& hard, long long& w, long long* bd) {
        long long out[2]; fullEvalParts(p, b.data(), out, bd);
        hard = out[0];
        w = out[1] + bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish;
    };
    long long h0, w0, bd0[kBreakdownCount]; score(base, h0, w0, bd0);
    auto solve = [&](const std::vector<int>& force, bool fewerHard, sat::CpSolverResponse& r, magi_cpsat::Model& m) {
        magi_cpsat::Options o; o.workers = workers; o.force = force; o.maxSeconds = secs;
        m.build(base, o);
        if (fewerHard) { m.cp().AddLessOrEqual(m.hardCount(), h0 - 1); m.cp().Minimize(m.hardCount()); }
        else { m.cp().AddLessOrEqual(m.hardCount(), h0); m.cp().Minimize(m.objective()); }
        auto proto = m.cp().Build();
        magi_cpsat::completeHint(proto, o, std::min(1.0, secs));
        sat::Model env;
        env.Add(sat::NewSatParameters(magi_cpsat::params(o, secs)));
        r = sat::SolveCpModel(proto, &env);
    };
    std::vector<int> idx((size_t)K); for (int i = 0; i < K; i++) idx[(size_t)i] = i;
    long long cohorts = 0, proven = 0, better = 0, undecided = 0;
    long long bestH = h0, bestW = w0; std::vector<int> bestBoard; std::vector<int> bestCohort;
    const auto t0 = std::chrono::steady_clock::now();
    for (;;) {
        cohorts++;
        std::vector<int> force(base);
        for (int s : idx) for (int j = 0; j < T; j++) force[(size_t)s * T + j] = -1;
        bool decided = false, improved = false;
        for (int stage = (h0 > 0 ? 0 : 1); stage < 2 && !improved; stage++) {
            magi_cpsat::Model m(p, hw);
            sat::CpSolverResponse r;
            solve(force, stage == 0, r, m);
            const bool found = r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE;
            if (found) {
                const auto nb = m.boardFrom(r);
                long long h1, w1, bd1[kBreakdownCount]; score(nb, h1, w1, bd1);
                if (h1 < h0 || (h1 == h0 && w1 < w0)) {
                    improved = true; better++;
                    printf("BETTER 組=[");
                    for (size_t q = 0; q < idx.size(); q++) printf("%s%d", q ? "," : "", idx[q]);
                    printf("] 必須 %lld→%lld 重み %lld→%lld |", h0, h1, w0, w1);
                    for (int f = 0; f < kBreakdownCount; f++) if (bd1[f] != bd0[f]) printf(" %s %lld→%lld", kBreakdownNames[f], bd0[f], bd1[f]);
                    printf("\n");
                    if (h1 < bestH || (h1 == bestH && w1 < bestW)) { bestH = h1; bestW = w1; bestBoard = nb; bestCohort = idx; }
                }
            }
            if (improved) break;
            if (stage == 0) {   // 必須を減らせないと証明できたか（解なし）。できなければ未決
                if (r.status() != sat::INFEASIBLE) { decided = false; break; }
                continue;
            }
            // 2 段目: 下限（最適なら最適値）が入力の重み以上なら K 最適。下限が入力より小さいのに良い盤面が出ない（fair の下限の差）は未決
            decided = found && std::llround(r.best_objective_bound()) >= w0;
        }
        if (!improved) { if (decided) proven++; else undecided++; }
        int pos = K - 1;   // 次の組（辞書順）
        while (pos >= 0 && idx[(size_t)pos] == S - K + pos) pos--;
        if (pos < 0) break;
        idx[(size_t)pos]++;
        for (int q = pos + 1; q < K; q++) idx[(size_t)q] = idx[(size_t)(q - 1)] + 1;
    }
    const double wall = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    printf("SWEEP %s K=%d 組=%lld K最適と証明=%lld 良くなる組=%lld 未決=%lld 入力 必須%lld・重み%lld 最良 必須%lld・重み%lld %.1f秒\n",
           argv[1], K, cohorts, proven, better, undecided, h0, w0, bestH, bestW, wall);
    if (outPath && !bestBoard.empty()) {
        FILE* f = fopen(outPath, "w");
        if (f) { for (int i = 0; i < S; i++) { for (int j = 0; j < T; j++) fprintf(f, "%d ", bestBoard[(size_t)i * T + j]); fprintf(f, "\n"); } fclose(f); }
    }
    return 0;
}
