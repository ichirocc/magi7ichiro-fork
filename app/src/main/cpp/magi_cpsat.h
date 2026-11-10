// CP-SAT（OR-Tools）で勤務表全体を 1 つのモデルにして解く。今の探索と併走し、①下限（これ以上良くならない値）
// ②より良い勤務表 を返す。モデルの目的は fullEvalParts と同じ式（必須の件数 → 重み付きの点数の 2 段）。
// fair（達成率の中央値）は線形に書けないので 0 以上の緩和＝下限は常に正しい（実際の値以下）。
// magi_native.cpp の末尾から include される（MAGI_WITH_CPSAT のときだけ）＝MagiProblem などを同じ翻訳単位で使う。
#pragma once
#include "ortools/sat/cp_model.h"
#include "ortools/sat/cp_model_solver.h"
#include "ortools/sat/sat_parameters.pb.h"
#include "ortools/util/time_limit.h"
#include <array>
#include <atomic>
#include <chrono>
#include <functional>

namespace magi_cpsat {

namespace sat = operations_research::sat;

// 必須の族の重み（Kotlin MirrorKeys.weights と同じ値。.claude/rules/weights.md の同期先）。
struct HardW { long long groupViol = 11000, covU = 10000, c3n = 9000, c3w = 9000, pref = 8000, extWish = 8000; };

struct Options {
    double maxSeconds = 10.0;
    int workers = 4;
    long long randomSeed = 1;
    // >= 0: 必須の件数をこの値以下に固定して第 2 段だけ解く（第 1 段を省く）。
    long long hardCap = -1;
    // 解の更新ごとに呼ぶ（盤面・必須件数・重み付き）。false を返すと打ち切る。
    std::function<bool(const std::vector<int>&, long long, long long)> onSolution;
    std::atomic<bool>* stop = nullptr;
    // 指定したセルを指定値に固定する（-1 は固定しない）。説明（直す代償）で使う。
    std::vector<int> force;
    // 盤面の全セルを hint の値に固定する（モデルの採点を照合するテスト用）。
    bool fixToHint = false;
    // 各セルの選択肢に hint（入力）の値を足す（入力が置けないシフトを含んでも、エンジンと同じく残せる）。
    bool keepInputValues = true;
    // 下限を上げる探索（線形緩和の強化・下限専用のワーカー）を使う。
    int linearizationLevel = 2;
};

struct Result {
    int status = 0;                 // CpSolverStatus
    long long hard = -1;            // 見つけた解の必須件数（モデルの値）
    long long weighted = -1;        // 見つけた解の重み付き（必須の重み込み・fair を除く）
    long long hardBound = -1;       // 必須件数の下限
    long long weightedBound = -1;   // 重み付きの下限（hard が hardBound と同じ解の範囲で）
    bool hardProven = false;        // 必須件数が最適と証明できた
    std::vector<int> board;         // 見つけた最良の盤面（無ければ空）
    double wallSeconds = 0.0;
};

// モデルの組み立て。x[i][j][k] は domain[i][j] にある k だけ作る（置けないシフト・拡張希望の禁止は作らない、
//   希望・手動固定のセルは固定値 1 つ）。目的の各項は fullEvalParts と同じ順に足す。
class Model {
public:
    Model(const MagiProblem& p, const HardW& hw) : p_(p), hw_(hw), S(p.S), T(p.T), K(p.K) {}

    void build(const std::vector<int>& hint, const Options& opt) {
        makeVars(hint, opt);
        addC1(); addC2(); addC41(); addC42(); addC3All(); addCells(); addCounts(); addWeekly(); addCoverage();
    }

    const sat::LinearExpr& hardCount() const { return hardCount_; }
    const sat::LinearExpr& weighted() const { return weighted_; }
    sat::CpModelBuilder& cp() { return cp_; }

    std::vector<int> boardFrom(const sat::CpSolverResponse& r) const {
        std::vector<int> b((size_t)S * T, -1);
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            const auto& cell = vars_[(size_t)i * T + j];
            for (const auto& kv : cell) if (sat::SolutionBooleanValue(r, kv.second)) { b[(size_t)i * T + j] = kv.first; break; }
            if (b[(size_t)i * T + j] < 0) b[(size_t)i * T + j] = fixed_[(size_t)i * T + j];
        }
        return b;
    }

private:
    const MagiProblem& p_;
    HardW hw_;
    const int S, T, K;
    sat::CpModelBuilder cp_;
    // セルごとの (k, 変数)。変数が 1 つだけのセルは fixed_ に値を置き、変数は作らない。
    std::vector<std::vector<std::pair<int, sat::BoolVar>>> vars_;
    std::vector<int> fixed_;
    sat::LinearExpr hardCount_, weighted_;
    std::vector<sat::LinearExpr> cnt_;   // S*K: 回数

    // セル (i,j) がシフト k か（定数 0/1 または変数）。
    sat::LinearExpr X(int i, int j, int k) const {
        const size_t c = (size_t)i * T + j;
        if (fixed_[c] >= 0) return sat::LinearExpr(fixed_[c] == k ? 1 : 0);
        for (const auto& kv : vars_[c]) if (kv.first == k) return sat::LinearExpr(kv.second);
        return sat::LinearExpr(0);
    }
    bool mayBe(int i, int j, int k) const {
        const size_t c = (size_t)i * T + j;
        if (fixed_[c] >= 0) return fixed_[c] == k;
        for (const auto& kv : vars_[c]) if (kv.first == k) return true;
        return false;
    }
    // 定数か（全項が固定値）を判定して定数値を返す（違反の補助変数を作らないため）。
    static bool isConst(const sat::LinearExpr& e, long long& v) {
        if (!e.variables().empty()) return false;
        v = e.constant(); return true;
    }
    void addHard(const sat::LinearExpr& e, long long w) { hardCount_ += e; weighted_ += w * e; }
    void addSoft(const sat::LinearExpr& e, long long w) { if (w != 0) weighted_ += w * e; }

    void makeVars(const std::vector<int>& hint, const Options& opt) {
        vars_.assign((size_t)S * T, {});
        fixed_.assign((size_t)S * T, -1);
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            const size_t c = (size_t)i * T + j;
            const int h = hint.empty() ? -1 : hint[c];
            int forced = (!opt.force.empty() && opt.force[c] >= 0) ? opt.force[c] : -1;
            if (opt.fixToHint) forced = h;
            if (forced < 0 && wishLockedN(p_, i, j)) forced = lockToN(p_, i, j);
            if (forced >= 0) { fixed_[c] = forced; continue; }
            std::vector<int> dom;
            for (int k : p_.allowed[i]) if (!p_.extBanned(i, j, k)) dom.push_back(k);
            // 入力の値はエンジンが動かさずに残せる（置かない規則は新しく置く手だけ）＝到達できる範囲に入れる。
            if (opt.keepInputValues && h >= 0 && h < K && std::find(dom.begin(), dom.end(), h) == dom.end()) dom.push_back(h);
            if (dom.empty()) { fixed_[c] = h >= 0 ? h : p_.restIdx; continue; }
            if (dom.size() == 1) { fixed_[c] = dom[0]; continue; }
            std::vector<sat::BoolVar> lits;
            for (int k : dom) {
                auto v = cp_.NewBoolVar();
                vars_[c].push_back({k, v}); lits.push_back(v);
                if (h >= 0) cp_.AddHint(v, h == k);
            }
            cp_.AddExactlyOne(lits);
        }
    }

    void addC1() {
        for (const auto& c : p_.cons1) for (int i = 0; i < S; i++) {
            if (!p_.cd(i, c.si)) continue;
            for (int j = 0; j <= T - c.d1; j++) {
                sat::LinearExpr z;
                for (int l = 0; l < c.d1; l++) z += X(i, j + l, c.si);
                long long cz;
                if (isConst(z, cz)) { if (cz < c.d2) addSoft(sat::LinearExpr(1), p_.w.c1); continue; }
                auto f = cp_.NewBoolVar();
                cp_.AddGreaterOrEqual(z + c.d2 * f, c.d2);
                addSoft(f, p_.w.c1);
            }
        }
    }

    void addC2() {
        for (const auto& c : p_.cons2) for (int i = 0; i < S; i++) {
            if (!p_.cd(i, c.si)) continue;
            sat::LinearExpr z;
            for (int j = 0; j < T; j++) z += X(i, j, c.si);
            long long cz;
            if (isConst(z, cz)) { if (cz < c.c) addSoft(sat::LinearExpr(1), p_.w.c2); continue; }
            auto f = cp_.NewBoolVar();
            cp_.AddGreaterOrEqual(z + c.c * f, c.c);
            addSoft(f, p_.w.c2);
        }
    }

    sat::LinearExpr groupDay(const std::vector<int>& grp, int g, int s, int j) const {
        sat::LinearExpr z;
        for (int i = 0; i < S; i++) if (grp[i] == g) z += X(i, j, s);
        return z;
    }
    int groupSize(const std::vector<int>& grp, int g) const { int n = 0; for (int i = 0; i < S; i++) if (grp[i] == g) n++; return n; }

    void addC41Fam(const std::vector<C41r>& list, const std::vector<int>& grp, long long w) {
        for (const auto& c : list) {
            const int m = groupSize(grp, c.g);
            for (int j = 0; j < T; j++) {
                auto z = groupDay(grp, c.g, c.s, j);
                long long cz;
                if (isConst(z, cz)) { if (cz < c.l || c.u < cz) addSoft(sat::LinearExpr(1), w); continue; }
                auto f = cp_.NewBoolVar();
                if (c.l > 0) cp_.AddGreaterOrEqual(z + (long long)c.l * f, c.l);
                if (c.u < m) cp_.AddLessOrEqual(z - (long long)(m - c.u) * f, c.u);
                addSoft(f, w);
            }
        }
    }
    void addC41() { addC41Fam(p_.cons41, p_.sgrp, p_.w.c41); addC41Fam(p_.cons41s, p_.ssk, p_.w.c41s); }

    void addC42Fam(const std::vector<C42r>& list, const std::vector<int>& grp, long long w) {
        for (const auto& c : list) for (int j = 0; j < T; j++) {
            auto z1 = groupDay(grp, c.g1, c.s1, j);
            const bool same = c.g1 == c.g2 && c.s1 == c.s2;
            long long c1v, c2v;
            if (same) {
                if (isConst(z1, c1v)) { addSoft(sat::LinearExpr(c1v * (c1v - 1) / 2), w); continue; }
                const int m = groupSize(grp, c.g1);
                auto pr = cp_.NewIntVar({0, (long long)m * (m - 1) / 2});
                // n(n-1)/2 は凸＝整数点の接線の上側で表せる（最小化で等号になる）。
                for (int t = 1; t < m; t++) cp_.AddGreaterOrEqual(pr, t * z1 - (long long)t * (t + 1) / 2);
                addSoft(pr, w);
                continue;
            }
            auto z2 = groupDay(grp, c.g2, c.s2, j);
            if (isConst(z1, c1v) && isConst(z2, c2v)) { addSoft(sat::LinearExpr(c1v * c2v), w); continue; }
            if (isConst(z1, c1v)) { addSoft(c1v * z2, w); continue; }
            if (isConst(z2, c2v)) { addSoft(c2v * z1, w); continue; }
            const int m1 = groupSize(grp, c.g1), m2 = groupSize(grp, c.g2);
            auto n1 = cp_.NewIntVar({0, m1}); cp_.AddEquality(n1, z1);
            auto n2 = cp_.NewIntVar({0, m2}); cp_.AddEquality(n2, z2);
            auto pr = cp_.NewIntVar({0, (long long)m1 * m2});
            cp_.AddMultiplicationEquality(pr, {n1, n2});
            addSoft(pr, w);
        }
    }
    void addC42() { addC42Fam(p_.cons42, p_.sgrp, p_.w.c42); addC42Fam(p_.cons42s, p_.ssk, p_.w.c42s); }

    // c3 族。forbidden（c3n/c3mn）と非 forbidden の複数シフト連は窓マッチの #fire、非 forbidden の単一シフト連は run の不足。
    void addC3Fam(const std::vector<C3r>& list, bool forbidden, long long w, bool hard) {
        auto add = [&](const sat::LinearExpr& e) { if (hard) addHard(e, hw_.c3n); else addSoft(e, w); };
        for (const auto& c : list) {
            const int D = (int)c.seq.size();
            if (D == 0) continue;
            if (!forbidden && c.singleRun) { addRunDeficit(c.seq[0], D, add); continue; }
            for (int i = 0; i < S; i++) for (int j = 0; j + D <= T; j++) {
                if (!mayBe(i, j, c.seq[0])) continue;
                if (forbidden) {
                    sat::LinearExpr m; bool possible = true;
                    for (int l = 0; l < D && possible; l++) { if (!mayBe(i, j + l, c.seq[l])) possible = false; else m += X(i, j + l, c.seq[l]); }
                    if (!possible) continue;
                    long long cm;
                    if (isConst(m, cm)) { if (cm == D) add(sat::LinearExpr(1)); continue; }
                    auto f = cp_.NewBoolVar();
                    cp_.AddGreaterOrEqual(f, m - (D - 1));
                    add(f);
                } else {
                    // fire＝先頭が一致し、残りのどれかが一致しない。f >= X0 − A、A <= 残りの各一致（最小化で A＝全一致）。
                    sat::LinearExpr x0 = X(i, j, c.seq[0]);
                    bool restAll = true; std::vector<sat::LinearExpr> rest;
                    for (int l = 1; l < D; l++) { if (!mayBe(i, j + l, c.seq[l])) restAll = false; rest.push_back(X(i, j + l, c.seq[l])); }
                    long long c0;
                    if (!restAll) {   // 残りが一致し得ない＝先頭が一致すれば必ず fire
                        add(x0); continue;
                    }
                    bool restConst = true; long long rmin = 1;
                    for (auto& r : rest) { long long rv; if (isConst(r, rv)) rmin = std::min(rmin, rv); else restConst = false; }
                    if (restConst) { if (isConst(x0, c0)) { if (c0 == 1 && rmin == 0) add(sat::LinearExpr(1)); } else if (rmin == 0) add(x0); continue; }
                    auto a = cp_.NewBoolVar();
                    for (auto& r : rest) cp_.AddLessOrEqual(a, r);
                    auto f = cp_.NewBoolVar();
                    cp_.AddGreaterOrEqual(f + a, x0);
                    add(f);
                }
            }
        }
    }
    // run の不足（C3Run.rowDeficit）: 長さ r<L の連ごとに L−r。連の始まり s ごとに Σ_{t=1}^{L-1} [連が s..s+t を覆わない]。
    //   月末を越える日は「シフトでない」扱い（rowDeficit は j==T で連を閉じる）。
    template <typename Add> void addRunDeficit(int k, int L, Add add) {
        if (L <= 1) return;
        for (int i = 0; i < S; i++) for (int s = 0; s < T; s++) {
            if (!mayBe(i, s, k)) continue;
            sat::LinearExpr start = X(i, s, k);
            if (s > 0) start -= X(i, s - 1, k);
            // start は x[s] ∧ ¬x[s-1] の下限（x[s]−x[s−1]）。最小化では y >= start − A_t で十分。
            std::vector<sat::LinearExpr> seg{X(i, s, k)};
            for (int t = 1; t < L; t++) {
                const int e = s + t;
                long long sv;
                if (e >= T || !mayBe(i, e, k)) {
                    // s..s+t を覆えない＝t 以降は全部 1 ずつ。start が 1 なら L−t 件。
                    if (isConst(start, sv)) { if (sv >= 1) add(sat::LinearExpr(L - t)); }
                    else {
                        auto y = cp_.NewBoolVar();
                        cp_.AddGreaterOrEqual(y, start);
                        add((long long)(L - t) * sat::LinearExpr(y));
                    }
                    break;
                }
                seg.push_back(X(i, e, k));
                auto a = cp_.NewBoolVar();
                for (auto& r : seg) cp_.AddLessOrEqual(a, r);
                auto y = cp_.NewBoolVar();
                cp_.AddGreaterOrEqual(y + a, start);
                add(y);
            }
        }
    }
    void addC3All() {
        addC3Fam(p_.cons3, false, p_.w.c3, false);
        addC3Fam(p_.cons3n, true, 0, true);
        addC3Fam(p_.cons3m, false, p_.w.c3m, false);
        addC3Fam(p_.cons3mn, true, p_.w.c3mn, false);
    }

    // セル単位の必須: pref（実現可能な希望）・groupViol・c3w・extWish。
    void addCells() {
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            const int w = p_.wish[(size_t)i * T + j];
            if (w >= 0 && p_.cd(i, w)) addHard(1 - X(i, j, w), hw_.pref);
            for (int k = 0; k < K; k++) {
                if (!mayBe(i, j, k)) continue;
                if (!p_.cd(i, k)) addHard(X(i, j, k), hw_.groupViol);
                if (p_.c3wBanned(i, j, k)) addHard(X(i, j, k), hw_.c3w);
                if (p_.extBanned(i, j, k)) addHard(X(i, j, k), hw_.extWish);
            }
        }
    }

    void addCounts() {
        cnt_.assign((size_t)S * K, sat::LinearExpr(0));
        for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
            sat::LinearExpr n;
            for (int j = 0; j < T; j++) n += X(i, j, k);
            cnt_[(size_t)i * K + k] = n;
            const int lo = p_.rangeLo[(size_t)i * K + k], hi = p_.rangeHi[(size_t)i * K + k], t = p_.apt[(size_t)i * K + k];
            long long cn; const bool cst = isConst(n, cn);
            if (lo != INT32_MIN && lo != 0 && p_.cd(i, k)) {
                if (cst) addSoft(sat::LinearExpr(std::max(0LL, (long long)lo - cn)), p_.w.low);
                else { auto d = cp_.NewIntVar({0, lo}); cp_.AddGreaterOrEqual(d + n, lo); addSoft(d, p_.w.low); }
            }
            if (hi != INT32_MAX) {
                if (cst) addSoft(sat::LinearExpr(std::max(0LL, cn - hi)), p_.w.high);
                else if (hi < T) { auto d = cp_.NewIntVar({0, T}); cp_.AddGreaterOrEqual(d - n, -(long long)hi); addSoft(d, p_.w.high); }
            }
            if (t >= 0) {
                if (cst) addSoft(sat::LinearExpr(std::llabs(cn - t)), p_.w.apt);
                else { auto d = cp_.NewIntVar({0, std::max(T, t)}); cp_.AddGreaterOrEqual(d - n, -(long long)t); cp_.AddGreaterOrEqual(d + n, (long long)t); addSoft(d, p_.w.apt); }
            }
        }
    }

    // weekly: Σ_d |7 w_d − c| を 7 で割った切り捨て。7q >= Σe − 6 の最小 q。
    void addWeekly() {
        if (p_.w.weekly == 0) return;
        for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
            const auto& n = cnt_[(size_t)i * K + k];
            std::array<sat::LinearExpr, 7> wd;
            for (int j = 0; j < T; j++) wd[(size_t)((p_.dow0 + j) % 7)] += X(i, j, k);
            long long cn;
            bool allConst = isConst(n, cn);
            for (auto& e : wd) { long long v; if (!isConst(e, v)) allConst = false; }
            if (allConst) {
                int arr[7]; for (int d = 0; d < 7; d++) { long long v; isConst(wd[d], v); arr[d] = (int)v; }
                addSoft(sat::LinearExpr(weeklyDevOfBucket(arr)), p_.w.weekly);
                continue;
            }
            sat::LinearExpr sum;
            for (int d = 0; d < 7; d++) {
                auto e = cp_.NewIntVar({0, 7LL * T});
                cp_.AddGreaterOrEqual(e - 7 * wd[d] + n, 0);
                cp_.AddGreaterOrEqual(e + 7 * wd[d] - n, 0);
                sum += e;
            }
            auto q = cp_.NewIntVar({0, 2LL * T});
            cp_.AddGreaterOrEqual(7 * q - sum, -6);
            addSoft(q, p_.w.weekly);
        }
    }

    // 被覆: covU＝max(0, min(定義された必要数) − got)、covO＝max(0, got − max(定義された必要数))（per-cell OR/AND と同値）。
    void addCoverage() {
        for (int k = 0; k < K; k++) for (int j = 0; j < T; j++) {
            const int lo1 = p_.need1[(size_t)k * T + j];
            const int lo2 = p_.use2 ? p_.need2[(size_t)k * T + j] : -1;
            if (lo1 < 0 && lo2 < 0) continue;
            const int lmin = (lo1 >= 0 && lo2 >= 0) ? std::min(lo1, lo2) : (lo1 >= 0 ? lo1 : lo2);
            const int lmax = (lo1 >= 0 && lo2 >= 0) ? std::max(lo1, lo2) : (lo1 >= 0 ? lo1 : lo2);
            sat::LinearExpr got;
            for (int i = 0; i < S; i++) got += X(i, j, k);
            long long cg;
            if (isConst(got, cg)) {
                addHard(sat::LinearExpr(std::max(0LL, (long long)lmin - cg)), hw_.covU);
                addSoft(sat::LinearExpr(std::max(0LL, cg - lmax)), p_.w.covO);
                continue;
            }
            if (lmin > 0) { auto u = cp_.NewIntVar({0, lmin}); cp_.AddGreaterOrEqual(u + got, lmin); addHard(u, hw_.covU); }
            if (lmax < S) { auto o = cp_.NewIntVar({0, S}); cp_.AddGreaterOrEqual(o - got, -(long long)lmax); addSoft(o, p_.w.covO); }
        }
    }
};

inline sat::SatParameters params(const Options& opt, double seconds) {
    sat::SatParameters prm;
    prm.set_num_workers(std::max(1, opt.workers));
    prm.set_max_time_in_seconds(std::max(0.05, seconds));
    prm.set_random_seed((int)opt.randomSeed);
    prm.set_log_search_progress(false);
    prm.set_linearization_level(opt.linearizationLevel);
    return prm;
}

// 2 段で解く: ①必須の件数を最小 ②その件数以下で重み付きを最小（今の採否＝必須の件数 → 重み と同じ順）。
inline Result solve(const MagiProblem& p, const std::vector<int>& hint, const Options& opt, const HardW& hw = HardW()) {
    Result res;
    auto t0 = std::chrono::steady_clock::now();
    auto elapsed = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    long long hardCap = opt.hardCap;
    double phase1Share = 0.3;
    if (hardCap < 0) {
        Model m(p, hw);
        m.build(hint, opt);
        m.cp().Minimize(m.hardCount());
        sat::Model env;
        env.Add(sat::NewSatParameters(params(opt, opt.maxSeconds * phase1Share)));
        if (opt.stop) env.GetOrCreate<operations_research::TimeLimit>()->RegisterExternalBooleanAsLimit(opt.stop);
        auto r = sat::SolveCpModel(m.cp().Build(), &env);
        res.status = r.status();
        if (r.status() != sat::OPTIMAL && r.status() != sat::FEASIBLE) { res.wallSeconds = elapsed(); return res; }
        res.hardBound = (long long)std::llround(r.best_objective_bound());
        res.hard = (long long)std::llround(r.objective_value());
        res.hardProven = r.status() == sat::OPTIMAL;
        hardCap = res.hard;
        res.board = m.boardFrom(r);
    }
    Model m(p, hw);
    m.build(hint.empty() ? res.board : hint, opt);
    m.cp().AddLessOrEqual(m.hardCount(), hardCap);
    m.cp().Minimize(m.weighted());
    sat::Model env;
    env.Add(sat::NewSatParameters(params(opt, std::max(0.05, opt.maxSeconds - elapsed()))));
    if (opt.stop) env.GetOrCreate<operations_research::TimeLimit>()->RegisterExternalBooleanAsLimit(opt.stop);
    if (opt.onSolution) {
        env.Add(sat::NewFeasibleSolutionObserver([&](const sat::CpSolverResponse& r) {
            auto b = m.boardFrom(r);
            if (!opt.onSolution(b, (long long)std::llround(sat::SolutionIntegerValue(r, m.hardCount())), (long long)std::llround(r.objective_value()))) {
                if (opt.stop) opt.stop->store(true);
            }
        }));
    }
    auto r = sat::SolveCpModel(m.cp().Build(), &env);
    res.status = r.status();
    if (r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE) {
        res.board = m.boardFrom(r);
        res.weighted = (long long)std::llround(r.objective_value());
        res.weightedBound = (long long)std::llround(r.best_objective_bound());
        res.hard = sat::SolutionIntegerValue(r, m.hardCount());
    }
    res.wallSeconds = elapsed();
    return res;
}

}  // namespace magi_cpsat
