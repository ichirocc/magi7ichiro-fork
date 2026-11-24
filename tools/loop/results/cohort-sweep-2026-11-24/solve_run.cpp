// CP-SAT の全体の解（アプリの併走 CpsatCoRun と同じ magi_cpsat::solve、2 ワーカー）を N 秒。最良の盤面を 1 行ずつ書き出す。
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
static void setPriority(MagiProblem& p) {
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = p.w.gap = 20;
    p.w.weekly = 1; p.w.covO = p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> base;
    if (argc < 3 || !loadFlat(argv[1], p, base)) return 2;
    setPriority(p);
    const double secs = argc > 3 ? atof(argv[3]) : 120.0;
    magi_cpsat::HardW hw;
    magi_cpsat::Options o; o.workers = 2; o.maxSeconds = secs;
    auto r = magi_cpsat::solve(p, base, o, hw);
    auto s0 = magi_cpsat::improve_detail::scoreOf(p, hw, base);
    std::vector<int> b = r.board.empty() ? base : r.board;
    auto s1 = magi_cpsat::improve_detail::scoreOf(p, hw, b);
    if (s1.hard > s0.hard || (s1.hard == s0.hard && s1.w > s0.w)) { b = base; s1 = s0; }   // 併走は良いときだけ採る（アプリと同じ）
    FILE* f = fopen(argv[2], "w");
    for (int i = 0; i < p.S; i++) { fprintf(f, "["); for (int j = 0; j < p.T; j++) fprintf(f, "%s%d", j ? "," : "", b[(size_t)i * p.T + j]); fprintf(f, "]\n"); }
    fclose(f);
    printf("SOLVE %s %.0f秒 入力 必須%lld 重み%lld → 必須%lld 重み%lld 下限 必須%lld 重み%lld\n", argv[1], secs, s0.hard, s0.w, s1.hard, s1.w, r.hardBound, r.weightedBound);
    return 0;
}
