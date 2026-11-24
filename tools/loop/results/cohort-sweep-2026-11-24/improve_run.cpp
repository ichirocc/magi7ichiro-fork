// 既存の解き直し（magi_cpsat::improve、アプリの CpsatResolve と同じ）を同じ盤面に N 秒かける。組スイープの最良と比べる用。
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
    if (argc < 2 || !loadFlat(argv[1], p, base)) return 2;
    setPriority(p);
    const double secs = argc > 2 ? atof(argv[2]) : 30.0;
    magi_cpsat::HardW hw;
    magi_cpsat::ImproveOptions io; io.maxSeconds = secs; io.threads = 4; io.randomSeed = 1;
    auto s0 = magi_cpsat::improve_detail::scoreOf(p, hw, base);
    auto r = magi_cpsat::improve(p, base, io, hw);
    printf("IMPROVE %s %.0f秒 必須 %lld→%lld 重み %lld→%lld 部分=%lld 採用=%lld 最適=%lld\n", argv[1], secs, s0.hard, r.hard, s0.w, r.weighted, (long long)r.iters, (long long)r.accepted, (long long)r.optimal);
    return 0;
}
