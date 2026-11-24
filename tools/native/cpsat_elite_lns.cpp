// SpprcGridFixingLns（docs/seesaw.md §6、PROPOSAL）の数え上げ（2026-11-24、測定だけ・製品の経路ではない）。
//   SPPRC で行を作る部分は「同じセルを CP-SAT で解く」の上界にしかならないので、解放するセルの選び方だけを比べる:
//   a … 既存の解き直し magi_cpsat::improve（アプリの CpsatResolve と同じ区間 5 種）
//   b … エリートの不一致セル（盤面と違う値を持つエリートがあるセル）＋残差の行（rowLoad>0）のセルを、10〜14 日の窓の中で解放して解く
//   組み方は cpsat_resolve_exp.cpp と同じ（docs/cpsat_formal.md「再実行」、-pthread）。
//   cpsat_elite_lns <base.flat> <a|b> <秒> <workers> [elite.rows ...]（rows＝1 行 1 職員の JSON 配列）
#include <fstream>
#include <sstream>
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
#include <random>
static magi_cpsat::HardW hw;
struct Sc { long long hard, w; long long bd[kBreakdownCount]; };
static Sc score(const MagiProblem& p, const std::vector<int>& b) {
    Sc s; long long out[2]; fullEvalParts(p, b.data(), out, s.bd);
    s.hard = out[0];
    s.w = out[1] + s.bd[15] * hw.groupViol + s.bd[10] * hw.covU + s.bd[3] * hw.c3n + s.bd[19] * hw.c3w + s.bd[12] * hw.pref + s.bd[20] * hw.extWish;
    return s;
}
static bool better(const Sc& a, const Sc& b) { return a.hard != b.hard ? a.hard < b.hard : a.w < b.w; }
static void show(const char* tag, const Sc& s, double t) {
    printf("%-10s t=%5.1f hard=%lld W=%lld | c1=%lld low=%lld high=%lld c3mn=%lld | c3m=%lld c42=%lld c42s=%lld | apt=%lld c2=%lld covO=%lld fair=%lld weekly=%lld\n",
        tag, t, s.hard, s.w, s.bd[0], s.bd[13], s.bd[14], s.bd[5], s.bd[4], s.bd[7], s.bd[9], s.bd[16], s.bd[1], s.bd[11], s.bd[17], s.bd[18]);
    fflush(stdout);
}
// 第 2 段だけ（必須の件数は cap 以下、目的＝重み付き＋fair の近似）。force で固定。
static std::vector<int> sub(const MagiProblem& p, const std::vector<int>& board, const std::vector<int>& force, long long cap, double secs, int workers, int seed, int* status) {
    magi_cpsat::Options o; o.force = force; o.workers = workers; o.randomSeed = seed;
    magi_cpsat::Model m(p, hw);
    m.build(board, o);
    m.cp().AddLessOrEqual(m.hardCount(), cap);
    m.cp().Minimize(m.objective());
    magi_cpsat::sat::Model env;
    auto prm = magi_cpsat::params(o, secs);
    if (getenv("CPLOG")) { prm.set_log_search_progress(true); prm.add_subsolvers("max_lp"); prm.add_subsolvers("core"); }
    env.Add(magi_cpsat::sat::NewSatParameters(prm));
    auto r = magi_cpsat::sat::SolveCpModel(m.cp().Build(), &env);
    if (status) *status = (int)r.status();
    if (r.status() == magi_cpsat::sat::OPTIMAL || r.status() == magi_cpsat::sat::FEASIBLE) return m.boardFrom(r);
    return {};
}
// ヒントを完全にしてから解く: ①盤面のマスをヒントの値に固定して補助変数まで解く ②その解を全変数のヒントにして本番を解く。
static std::vector<int> subComplete(const MagiProblem& p, const std::vector<int>& board, const std::vector<int>& force, long long cap, double secs, int workers, int seed, int* status, double* prepSecs) {
    namespace sat = magi_cpsat::sat;
    magi_cpsat::Options o; o.force = force; o.workers = workers; o.randomSeed = seed;
    if (!getenv("CPSAT_SUBSOLVERS")) o.subsolvers = {"max_lp", "core"};
    magi_cpsat::Model m(p, hw);
    m.build(board, o);
    m.cp().AddLessOrEqual(m.hardCount(), cap);
    m.cp().Minimize(m.objective());
    auto proto = m.cp().Build();
    auto t0 = std::chrono::steady_clock::now();
    {
        sat::Model env; auto prm = magi_cpsat::params(o, 10.0); prm.set_fix_variables_to_their_hinted_value(true); prm.clear_subsolvers(); prm.set_num_workers(1);
        env.Add(sat::NewSatParameters(prm));
        auto r = sat::SolveCpModel(proto, &env);
        if (prepSecs) *prepSecs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        if (r.status() != sat::OPTIMAL && r.status() != sat::FEASIBLE) { if (status) *status = -(int)r.status(); return {}; }
        proto.mutable_solution_hint()->Clear();
        for (int v = 0; v < proto.variables_size(); v++) { proto.mutable_solution_hint()->add_vars(v); proto.mutable_solution_hint()->add_values(r.solution(v)); }
    }
    sat::Model env; auto prm = magi_cpsat::params(o, secs);
    if (getenv("CPLOG")) prm.set_log_search_progress(true);
    env.Add(sat::NewSatParameters(prm));
    auto r = sat::SolveCpModel(proto, &env);
    if (status) *status = (int)r.status();
    if (r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE) return m.boardFrom(r);
    return {};
}
static void setPriority(MagiProblem& p) {
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = p.w.gap = 20;
    p.w.weekly = 1; p.w.covO = p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}
static std::vector<int> loadRows(const char* path, int S, int T) {
    std::ifstream f(path); std::string line; std::vector<int> b; int rows = 0;
    while (std::getline(f, line)) {
        if (line.find('[') == std::string::npos) continue;
        for (char& ch : line) if (ch == '[' || ch == ']' || ch == ',') ch = ' ';
        std::istringstream is(line); int v, n = 0; while (is >> v) { b.push_back(v); n++; }
        if (n != T) return {};
        rows++;
    }
    return rows == S ? b : std::vector<int>{};
}
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> board;
    if (argc < 5 || !loadFlat(argv[1], p, board)) return 2;
    setPriority(p);
    const std::string mode = argv[2]; const double secs = atof(argv[3]); const int workers = atoi(argv[4]);
    const int S = p.S, T = p.T;
    auto t0 = std::chrono::steady_clock::now();
    auto el = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    Sc cur = score(p, board); const Sc start = cur;
    if (mode == "a") {
        magi_cpsat::ImproveOptions io; io.maxSeconds = secs; io.threads = workers; io.randomSeed = 1;
        auto r = magi_cpsat::improve(p, board, io, hw);
        printf("A %s 必須 %lld→%lld 重み %lld→%lld 部分=%lld 採用=%lld\n", argv[1], start.hard, r.hard, start.w, r.weighted, (long long)r.iters, (long long)r.accepted);
        return 0;
    }
    std::vector<std::vector<int>> elites;
    for (int a = 5; a < argc; a++) { auto e = loadRows(argv[a], S, T); if (!e.empty()) elites.push_back(e); }
    std::vector<int> disagree((size_t)S * T, 0);
    for (const auto& e : elites) for (size_t c = 0; c < disagree.size(); c++) if (e[c] != board[c]) disagree[c]++;
    long long nDis = 0; for (int d : disagree) if (d > 0) nDis++;
    std::mt19937 rng(1);
    int iter = 0, acc = 0; long long freeSum = 0;
    const double subSecs = 4.0;
    while (el() < secs - 0.2) {
        const int len = 10 + (int)(rng() % 5), j0 = (int)(rng() % (unsigned)(T - len + 1));
        std::vector<int> force(board);
        std::vector<int> loadRowsIdx;
        for (int i = 0; i < S; i++) if (magi_cpsat::improve_detail::rowLoad(p, board, i) > 0) loadRowsIdx.push_back(i);
        std::shuffle(loadRowsIdx.begin(), loadRowsIdx.end(), rng);
        if (loadRowsIdx.size() > 4) loadRowsIdx.resize(4);
        int nFree = 0;
        for (int i = 0; i < S; i++) for (int j = j0; j < j0 + len; j++) {
            const size_t c = (size_t)i * T + j;
            const bool res = std::find(loadRowsIdx.begin(), loadRowsIdx.end(), i) != loadRowsIdx.end();
            if (disagree[c] > 0 || res) { force[c] = -1; nFree++; }
        }
        iter++; freeSum += nFree;
        if (nFree == 0) continue;
        int st = 0;
        auto nb = subComplete(p, board, force, cur.hard, std::min(subSecs, secs - el()), workers, iter, &st, nullptr);
        if (nb.empty()) continue;
        Sc s = score(p, nb);
        if (better(s, cur)) { acc++; board = nb; cur = s; }
    }
    printf("B %s エリート=%zu 不一致セル=%lld 必須 %lld→%lld 重み %lld→%lld 部分=%d 採用=%d 平均の自由マス=%.0f\n", argv[1], elites.size(), nDis,
        start.hard, cur.hard, start.w, cur.w, iter, acc, iter ? (double)freeSum / iter : 0.0);
    return 0;
}
