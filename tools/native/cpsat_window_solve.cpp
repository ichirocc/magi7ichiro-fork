// 日の範囲（全職員×[lo,hi]、1 始まり）だけを自由にして CP-SAT で解く（TOOL・出力不変）。「集めて配る」（ブロック移動つき）1 回分が届く範囲の上界（2026-11-27）。
// cpsat_window_solve <flat> <秒> lo-hi [lo-hi ...]（環境変数 GAPW＝測定の中だけ gap の重みをこの値にして解く。結果は製品の重みで採点して出す）。組み方は cpsat_cohort_sweep.cpp と同じ（-pthread）
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
namespace sat = magi_cpsat::sat;
static void setPriority(MagiProblem& p) {
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = p.w.gap = 20;
    p.w.weekly = 1; p.w.covO = p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> base;
    if (argc < 4 || !loadFlat(argv[1], p, base)) return 2;
    setPriority(p);
    const MagiProblem p0 = p;   // 採点は製品の（優先順位の）重みのまま
    if (const char* g = getenv("GAPW")) p.w.gap = atoi(g);
    const double secs = atof(argv[2]);
    magi_cpsat::HardW hw;
    auto score = [&](const std::vector<int>& b, long long& hard, long long& w, long long* bd) {
        long long out[2]; fullEvalParts(p0, b.data(), out, bd);
        hard = out[0];
        w = out[1] + bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish;
    };
    long long h0, w0, bd0[kBreakdownCount]; score(base, h0, w0, bd0);
    for (int a = 3; a < argc; a++) {
        int lo, hi; if (sscanf(argv[a], "%d-%d", &lo, &hi) != 2) continue;
        std::vector<int> force(base);
        for (int i = 0; i < p.S; i++) for (int j = lo - 1; j <= hi - 1; j++) force[(size_t)i * p.T + j] = -1;
        magi_cpsat::Options o; o.workers = 4; o.force = force; o.maxSeconds = secs;
        magi_cpsat::Model m(p, hw); m.build(base, o);
        m.cp().AddLessOrEqual(m.hardCount(), h0); m.cp().Minimize(m.objective());
        auto proto = m.cp().Build();
        magi_cpsat::completeHint(proto, o, std::min(1.0, secs));
        sat::Model env; env.Add(sat::NewSatParameters(magi_cpsat::params(o, secs)));
        auto r = sat::SolveCpModel(proto, &env);
        const char* st = r.status() == sat::OPTIMAL ? "最適" : r.status() == sat::FEASIBLE ? "可能解" : "解なし/時間切れ";
        if (r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE) {
            auto nb = m.boardFrom(r); long long h1, w1, bd1[kBreakdownCount]; score(nb, h1, w1, bd1);
            printf("窓 %d-%d日 %s 必須 %lld→%lld 重み %lld→%lld 下限 %lld |", lo, hi, st, h0, h1, w0, w1, std::llround(r.best_objective_bound()));
            for (int f = 0; f < kBreakdownCount; f++) if (bd1[f] != bd0[f]) printf(" %s %lld→%lld", kBreakdownNames[f], bd0[f], bd1[f]);
            printf("\n");
        } else printf("窓 %d-%d日 %s\n", lo, hi, st);
        fflush(stdout);
    }
    return 0;
}
