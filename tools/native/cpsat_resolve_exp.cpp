// CP-SAT の使い方の実験（2026-11-16、docs/cpsat_formal.md「T1 だけの段を外す」「解き直し」の測定に使ったもの）。製品の経路ではない。
//   組み方は cpsat_bench.cpp と同じ（OR-Tools C++ v9.12 の配布物、-pthread）。優先順位方式の重み（fair 50・c2 20・apt 20）で測る。
//   cpsat_resolve_exp <flat> <mode> <秒> <workers> [seed]
//     mono  … magi_cpsat::solve をそのまま（役は max_lp＋core、CPSAT_SUBSOLVERS=default で既定の役）。DUMP=<path> で盤面を JSON に
//     full  … 必須の件数を今の値以下に固定して第 2 段だけ
//     log   … マスだけのヒントで解く（CPLOG=1 で CP-SAT のログ＝ヒントが不完全と出る）
//     chint … 完全なヒント（補助変数まで）で丸ごと解く
//     lns   … 試作の解き直し（無作為 3 種、SUB_SECS=部分 1 つの秒、LNS_COMPLETE=1 で完全なヒント、VERBOSE=1）
//   製品に入れた解き直しは magi_cpsat::improve（cpsat_bench の CPSAT_IMPROVE）。結果の記録は tools/loop/results/cpsat-resolve-2026-11-16/。
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"
#include <random>
static void setPriority(MagiProblem& p) {
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = 20;
    p.w.covO = p.w.weekly = 1; p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}
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
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> board;
    if (!loadFlat(argv[1], p, board)) return 2;
    setPriority(p);
    std::string mode = argv[2]; double secs = atof(argv[3]); int workers = atoi(argv[4]); int seed = argc > 5 ? atoi(argv[5]) : 1;
    const int S = p.S, T = p.T;
    auto t0 = std::chrono::steady_clock::now();
    auto el = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    Sc cur = score(p, board); show("start", cur, 0);
    if (mode == "mono") {   // 今の使い方: 3 段を丸ごと。役は max_lp と core（CPSAT_SUBSOLVERS=default で既定の並び）
        magi_cpsat::Options o; o.maxSeconds = secs; o.workers = workers; o.randomSeed = seed;
        const char* sb = getenv("CPSAT_SUBSOLVERS");
        if (!sb) o.subsolvers = {"max_lp", "core"};
        Sc best = cur; bool have = false;
        o.onSolution = [&](const std::vector<int>& b, long long, long long) { Sc s = score(p, b); if (!have || better(s, best)) { best = s; have = true; show("mono-sol", s, el()); } return true; };
        auto r = magi_cpsat::solve(p, board, o, hw);
        if (!r.board.empty()) show("mono-end", score(p, r.board), el());
        if (!r.board.empty() && getenv("DUMP")) { FILE* f = fopen(getenv("DUMP"), "w"); fprintf(f, "["); for (int i = 0; i < S; i++) { fprintf(f, "%s[", i ? "," : ""); for (int j = 0; j < T; j++) fprintf(f, "%s%d", j ? "," : "", r.board[(size_t)i * T + j]); fprintf(f, "]"); } fprintf(f, "]"); fclose(f); }
        printf("bound=%lld hardBound=%lld proven=%d\n", r.weightedBound, r.hardBound, (int)r.hardProven);
    } else if (mode == "log") {
        std::vector<int> force(board.size(), -1); int st = 0;
        auto nb = sub(p, board, force, cur.hard, secs, workers, seed, &st);
        printf("status=%d t=%.1f\n", st, el()); if (!nb.empty()) show("log-end", score(p, nb), el());
    } else if (mode == "chint") {
        std::vector<int> force(board.size(), -1); int st = 0; double prep = 0;
        auto nb = subComplete(p, board, force, cur.hard, secs, workers, seed, &st, &prep);
        printf("status=%d prep=%.2fs t=%.1f\n", st, prep, el()); if (!nb.empty()) show("chint-end", score(p, nb), el());
    } else if (mode == "full") {
        magi_cpsat::Options o; o.workers = workers; o.randomSeed = seed; o.maxSeconds = secs; o.hardCap = cur.hard;
        if (!getenv("CPSAT_SUBSOLVERS")) o.subsolvers = {"max_lp", "core"};
        Sc best = cur;
        o.onSolution = [&](const std::vector<int>& b, long long, long long) { Sc s = score(p, b); if (better(s, best)) { best = s; show("full-sol", s, el()); } return true; };
        auto r = magi_cpsat::solve(p, board, o, hw);
        if (!r.board.empty()) show("full-end", score(p, r.board), el());
        show("full-best", best, el());
        printf("bound=%lld\n", r.weightedBound);
    } else {                // LNS: 盤面の一部だけ自由にして解き直す
        double subSecs = getenv("SUB_SECS") ? atof(getenv("SUB_SECS")) : 2.0;
        std::mt19937 rng(seed);
        int iter = 0, acc = 0, opt = 0; 
        while (el() < secs) {
            std::vector<int> force(board);
            int kind = iter % 3; char desc[64];
            if (kind == 0) {         // 連続する日の窓 × 全職員
                int len = 4 + rng() % 4, j0 = rng() % (T - len + 1);
                for (int i = 0; i < S; i++) for (int j = j0; j < j0 + len; j++) force[(size_t)i * T + j] = -1;
                snprintf(desc, 64, "days %d..%d", j0, j0 + len - 1);
            } else if (kind == 1) {  // 職員 2〜3 人 × 全日
                int n = 2 + rng() % 2; std::vector<int> ids(S); for (int i = 0; i < S; i++) ids[i] = i; std::shuffle(ids.begin(), ids.end(), rng);
                for (int a = 0; a < n; a++) for (int j = 0; j < T; j++) force[(size_t)ids[a] * T + j] = -1;
                snprintf(desc, 64, "staff x%d", n);
            } else {                 // 職員 4〜5 人 × 日の窓 10〜14 日
                int n = 4 + rng() % 2, len = 10 + rng() % 5, j0 = rng() % (T - len + 1); std::vector<int> ids(S); for (int i = 0; i < S; i++) ids[i] = i; std::shuffle(ids.begin(), ids.end(), rng);
                for (int a = 0; a < n; a++) for (int j = j0; j < j0 + len; j++) force[(size_t)ids[a] * T + j] = -1;
                snprintf(desc, 64, "staff x%d days %d..%d", n, j0, j0 + len - 1);
            }
            int st = 0; double ts = el();
            auto nb = getenv("LNS_COMPLETE") ? subComplete(p, board, force, cur.hard, std::min(subSecs, secs - el()), workers, seed + iter, &st, nullptr)
                                             : sub(p, board, force, cur.hard, std::min(subSecs, secs - el()), workers, seed + iter, &st);
            iter++; if (st == (int)magi_cpsat::sat::OPTIMAL) opt++;
            if (nb.empty()) continue;
            Sc s = score(p, nb);
            if (better(s, cur)) { acc++; board = nb; cur = s; char tag[96]; snprintf(tag, 96, "lns#%d", iter); show(tag, s, el()); if (getenv("VERBOSE")) printf("   (%s, %.2fs, status=%d)\n", desc, el() - ts, st); }
        }
        printf("iters=%d accepted=%d optimal=%d\n", iter, acc, opt);
        show("lns-end", cur, el());
    }
    return 0;
}
