// CP-SAT の段の切り分け（docs/cpsat_relay.md）。mode 0=今（第1段→第1.5段→第2段 hint=第1.5段の盤面）、1=第1.5段なし（第2段 hint=入力）、2=第2段の開始解を入力と第1.5段の良いほう
//   ビルドは tools/native/cpsat_bench.cpp と同じ。cpsat_stage_bench <flat> <秒> <mode> [seed]。環境変数 W（ワーカー数）・SUBS（探索役）・TRACE（改善の経過を stderr へ）
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
#include <chrono>
using namespace magi_cpsat;
static void setPriority(MagiProblem& p) {
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = 20;
    p.w.covO = p.w.weekly = 1; p.w.fair = 50; p.w.c2 = 20; p.w.apt = 20;
}
static HardW hw;
static long long checkW(const MagiProblem& p, const std::vector<int>& b, long long& hard, long long& fairW) {
    long long out[2], bd[kBreakdownCount]; fullEvalParts(p, b.data(), out, bd); hard = out[0];
    long long h = bd[15]*hw.groupViol + bd[10]*hw.covU + bd[3]*hw.c3n + bd[19]*hw.c3w + bd[12]*hw.pref + bd[20]*hw.extWish;
    fairW = bd[17]*p.w.fair; return out[1] + h;   // チェッカーの weightedScore（fair 込み）
}
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> input;
    if (!loadFlat(argv[1], p, input)) return 2;
    double total = atof(argv[2]); int mode = atoi(argv[3]); int seed = argc > 4 ? atoi(argv[4]) : 1;
    setPriority(p);
    long long ih, ifw; long long iw = checkW(p, input, ih, ifw);
    Options opt; opt.workers = 2; opt.randomSeed = seed; opt.maxSeconds = total; opt.subsolvers = {"max_lp", "core"};
    if (const char* w = getenv("W")) opt.workers = atoi(w);
    if (const char* sb = getenv("SUBS")) { opt.subsolvers.clear(); std::string x(sb), t; for (char ch : x + ",") { if (ch == ',') { if (!t.empty()) opt.subsolvers.push_back(t); t.clear(); } else t += ch; } }
    auto t0 = std::chrono::steady_clock::now();
    auto el = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    // 第1段
    Model m1(p, hw); m1.build(input, opt); m1.cp().Minimize(m1.hardCount());
    sat::Model e1; e1.Add(sat::NewSatParameters(params(opt, std::min(total*0.3, opt.phase1MaxSeconds))));
    auto r1 = sat::SolveCpModel(m1.cp().Build(), &e1);
    long long cap = std::llround(r1.objective_value());
    std::vector<int> hint2 = input;
    long long tierW = -1, tierFair = -1, tierH = -1;
    if (mode != 1) {
        Model mt(p, hw);
        if (mt.tierPhase()) {
            mt.build(input, opt); mt.cp().AddLessOrEqual(mt.hardCount(), cap); mt.cp().Minimize(mt.topTier());
            sat::Model e; e.Add(sat::NewSatParameters(params(opt, std::min(opt.tierPhaseMaxSeconds, std::max(0.05, (total - el())*0.3)))));
            auto r = sat::SolveCpModel(mt.cp().Build(), &e);
            if (r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE) {
                auto tb = mt.boardFrom(r); tierW = checkW(p, tb, tierH, tierFair);
                if (mode == 0 || tierW < iw) hint2 = tb;
            }
        }
    }
    double t2 = el();
    Model m(p, hw); m.build(hint2, opt); m.cp().AddLessOrEqual(m.hardCount(), cap); m.cp().Minimize(m.objective());
    sat::Model env; env.Add(sat::NewSatParameters(params(opt, std::max(0.05, total - el()))));
    long long bestW = 1LL<<60; std::vector<int> best; double firstAt = -1, bestAt = -1; long long firstW = -1; long long lastObj = -1;
    env.Add(sat::NewFeasibleSolutionObserver([&](const sat::CpSolverResponse& r) {
        auto b = m.boardFrom(r); long long h, f; long long w = checkW(p, b, h, f);
        if (firstAt < 0) { firstAt = el(); firstW = w; }
        if (w < bestW) { bestW = w; best = b; bestAt = el(); lastObj = std::llround(r.objective_value()); if (getenv("TRACE")) fprintf(stderr, "  %.1fs %lld %s\n", el(), w, r.solution_info().c_str()); }
    }));
    auto r = sat::SolveCpModel(m.cp().Build(), &env);
    long long bh = -1, bf = -1; if (!best.empty()) checkW(p, best, bh, bf);
    printf("mode=%d seed=%d input=%lld(fair %lld) tier=%lld(fair %lld,h%lld) phase2start=%.1fs first=%lld@%.1fs best=%lld@%.1fs(h%lld fair %lld, model obj %lld) bound=%lld end=%.1fs %s\n",
        mode, seed, iw, ifw, tierW, tierFair, tierH, t2, firstW, firstAt, bestW, bestAt, bh, bf, lastObj, (long long)std::llround(r.best_objective_bound()), el(),
        bestW < iw ? "BETTER-THAN-INPUT" : "worse-or-equal");
}
