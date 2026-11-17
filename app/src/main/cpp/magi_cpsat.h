// CP-SAT（OR-Tools）で勤務表全体を 1 つのモデルにして解く。今の探索と併走し、①下限（これ以上良くならない値）
// ②より良い勤務表 を返す。モデルの目的は fullEvalParts と同じ式（必須の件数 → 重み付きの点数の 2 段）。
// fair（達成率の中央値）は線形に書けないので、実際の値以下になる近似（[addFair]）を目的にだけ足す＝下限は常に正しい（実際の値以下）。
// 点数 [weighted]（解の重み・盤面固定の照合に使う）は fair を含めない。
// magi_native.cpp の末尾から include される（MAGI_WITH_CPSAT のときだけ）＝MagiProblem などを同じ翻訳単位で使う。
#pragma once
#include "ortools/sat/cp_model.h"
#include "ortools/sat/cp_model_solver.h"
#include "ortools/sat/sat_parameters.pb.h"
#include "ortools/util/time_limit.h"
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <functional>
#include <mutex>
#include <random>
#include <thread>

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
    // 第 2 段の下限が上がるたびに呼ぶ（重み付きの下限）。第 1 段の結果（必須の件数・下限・証明済みか）は onHard。
    std::function<void(long long)> onBound;
    std::function<void(long long, long long, bool)> onHard;
    std::atomic<bool>* stop = nullptr;
    // 指定したセルを指定値に固定する（-1 は固定しない）。説明（直す代償）で使う。
    std::vector<int> force;
    // 盤面の全セルを hint の値に固定する（モデルの採点を照合するテスト用）。
    bool fixToHint = false;
    // 各セルの選択肢に hint（入力）の値を足す（入力が置けないシフトを含んでも、エンジンと同じく残せる）。
    bool keepInputValues = true;
    // 下限を上げる探索（線形緩和の強化・下限専用のワーカー）を使う。
    int linearizationLevel = 2;
    // 使う探索役（空なら CP-SAT の既定の並び）。少ないワーカーでも下限を担う役（max_lp など）を必ず入れるため。
    std::vector<std::string> subsolvers;
    // 族ごとの組み立てを切る（検証用。bit: 0 c1,1 c2,2 c41,3 c42,4 c3,5 cells,6 counts,7 weekly,8 coverage,9 fair の近似）。
    unsigned families = ~0u;
    // 第 1 段の持ち時間の上限（秒）。
    double phase1MaxSeconds = 6.0;
    // fair の基準（達成率の基準・従来方式の平均）を hint の盤面の値に固定する（解き直し [improve] 用）。固定すると偏差が一次式になり、
    //   hint の近くでは実際の fair にほぼ一致する。実際の値以下とは限らない＝下限の計算（[solve]）では使わない。
    bool fairFixedRef = false;
};

struct Result {
    int status = 0;                 // CpSolverStatus
    long long hard = -1;            // 見つけた解の必須件数（モデルの値）
    long long weighted = -1;        // 見つけた解の重み付き（必須の重み込み・fair を除く）
    long long hardBound = -1;       // 必須件数の下限
    long long weightedBound = -1;   // 重み付き＋fair の近似の下限（hard が hardBound と同じ解の範囲で）＝チェッカーの weightedScore 以下
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
        const unsigned f = opt.families;
        if (f & 1u) addC1(); if (f & 2u) addC2(); if (f & 4u) addC41(); if (f & 8u) addC42(); if (f & 16u) addC3All();
        if (f & 32u) addCells();
        addCountsMaybe(f & 64u);
        if (f & 128u) addWeekly(); if (f & 256u) addCoverage();
        if (f & 512u) addFair(hint, opt);
    }

    const sat::LinearExpr& hardCount() const { return hardCount_; }
    const sat::LinearExpr& weighted() const { return weighted_; }
    // 第 2 段で最小にする式＝重み付き＋fair の近似（[addFair]、実際の fair 以下）。
    sat::LinearExpr objective() const { return weighted_ + fair_; }
    sat::CpModelBuilder& cp() { return cp_; }
    // 変数を持つマスのすべてに hint の値があったか（[completeHint] で全変数のヒントへ広げられる）。
    bool fullyHinted() const { return fullyHinted_; }

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
    bool fullyHinted_ = true;
    sat::LinearExpr hardCount_, weighted_, fair_;
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
            if (h < 0) fullyHinted_ = false;
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
                if (isConst(z, cz)) { if (cz < c.d2) addSoft(sat::LinearExpr(c.d2 - cz), p_.w.c1); continue; }
                auto f = cp_.NewIntVar({0, c.d2});
                cp_.AddGreaterOrEqual(z + f, c.d2);
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
                    // fire＝先頭が一致し、残りのどれかが一致しない。
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
                    // f >= x0 − r_l（各 l）。max_l(x0 − r_l) = x0 ∧ ¬全一致（補助変数を置かない＝線形緩和が強い）。
                    auto f = cp_.NewBoolVar();
                    for (auto& r : rest) cp_.AddGreaterOrEqual(f + r, x0);
                    add(f);
                }
            }
        }
    }
    // run の不足（C3Run.rowDeficit）: 長さ r<L の連ごとに L−r。連の始まり s ごとに Σ_{t=1}^{L-1} y_t、y_t＝[始まり ∧ 連が s+t まで届かない]。
    //   届かない(t) ＝ 届かない(t−1) ∨ x_{s+t}=0 なので y_t >= y_{t−1}・y_t >= start − x_{s+t}（補助の「全部 1」を置かない＝線形緩和が強い）。
    //   月末を越える日・置けない日は x=0（rowDeficit は j==T で連を閉じる）。start は x_s − x_{s−1}（最小化の下側で十分）。
    template <typename Add> void addRunDeficit(int k, int L, Add add) {
        if (L <= 1) return;
        for (int i = 0; i < S; i++) for (int s = 0; s < T; s++) {
            if (!mayBe(i, s, k)) continue;
            sat::LinearExpr start = X(i, s, k);
            if (s > 0) start -= X(i, s - 1, k);
            long long sv;
            if (isConst(start, sv) && sv <= 0) continue;
            sat::LinearExpr prev(0);
            for (int t = 1; t < L; t++) {
                const int e = s + t;
                sat::LinearExpr xe = (e < T) ? X(i, e, k) : sat::LinearExpr(0);
                auto y = cp_.NewBoolVar();
                cp_.AddGreaterOrEqual(y + xe, start);
                if (t > 1) cp_.AddGreaterOrEqual(y, prev);
                add(y);
                prev = sat::LinearExpr(y);
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
                if (p_.extWishBanned(i, j, k)) addHard(X(i, j, k), hw_.extWish);
            }
        }
    }

    void addCountsMaybe(bool penalties) {
        cnt_.assign((size_t)S * K, sat::LinearExpr(0));
        for (int i = 0; i < S; i++) for (int k = 0; k < K; k++) {
            sat::LinearExpr n;
            for (int j = 0; j < T; j++) n += X(i, j, k);
            cnt_[(size_t)i * K + k] = n;
            if (!penalties) continue;
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

    // fair の近似（3.727.81）。群×担当シフトごとに、実際の fair（MirrorCore.fairDevCore）以下になる値を足す。
    //   達成率モード: 偏差の和＋最大＝Σ|c'−中心−r×幅| と max の、基準 r を自由にした最小（r は 1/Q 刻み）。丸めと刻みの誤差ぶんを引く。
    //   従来の round(平均) 方式（基準の無い人がいる群）: 基準の代わりに自由な整数 m を置いた Σ|c−m|＋max。
    //   どちらも母集団の一部（置ける人で幅 > 0）だけで数える＝実際の値以下。置けない人が基準なしで入りうる群は数えない。
    void addFair(const std::vector<int>& hint, const Options& opt) {
        if (p_.w.fair == 0) return;
        const long long Q = 24;
        const long long big = 4LL * Q * T * (T + 2);
        const bool fixedRef = opt.fairFixedRef && !hint.empty();
        auto hintCount = [&](int x, int k) { long long n = 0; for (int j = 0; j < T; j++) if (hint[(size_t)x * T + j] == k) n++; return n; };
        for (int g = 0; g < (int)p_.members.size() && g < (int)p_.bucket.size(); g++) for (int k : p_.bucket[g]) {
            std::vector<int> pop;
            bool legacy = false, skip = false;
            for (int x : p_.members[g]) {
                const size_t xk = (size_t)x * K + k;
                const int lo = p_.rangeLo[xk], hi = p_.rangeHi[xk], t = p_.apt[xk];
                const bool basis = (lo != INT32_MIN && hi != INT32_MAX) || t >= 0;
                if (p_.pl(x, k)) { pop.push_back(x); if (!basis) legacy = true; continue; }
                if (basis) continue;
                for (int j = 0; j < T && !skip; j++) if (mayBe(x, j, k)) skip = true;
            }
            if (skip || pop.size() < 2) continue;
            if (legacy) {
                sat::LinearExpr m;
                if (fixedRef) {
                    long long tot = 0; for (int x : pop) tot += hintCount(x, k);
                    m = sat::LinearExpr((long long)std::llround((double)tot / (double)pop.size()));
                } else {
                    m = sat::LinearExpr(cp_.NewIntVar({0, T}));
                }
                auto mx = cp_.NewIntVar({0, T});
                sat::LinearExpr sum;
                for (int x : pop) {
                    const auto& n = cnt_[(size_t)x * K + k];
                    auto d = cp_.NewIntVar({0, T});
                    cp_.AddGreaterOrEqual(d - n + m, 0);
                    cp_.AddGreaterOrEqual(d + n - m, 0);
                    cp_.AddGreaterOrEqual(mx - d, 0);
                    sum += d;
                }
                fair_ += p_.w.fair * (sum + mx);
                continue;
            }
            // 基準を持つ人（帯 [lo,hi] か適切回数 t）。center2＝中心の 2 倍、s2＝幅の 2 倍。
            struct Who { int x; bool band; int lo, hi; long long center2, s2; };
            std::vector<Who> who;
            for (int x : pop) {
                const size_t xk = (size_t)x * K + k;
                const int lo = p_.rangeLo[xk], hi = p_.rangeHi[xk], t = p_.apt[xk];
                if (lo != INT32_MIN && hi != INT32_MAX) {
                    if (hi <= lo || lo < 0) continue;
                    who.push_back({x, true, lo, hi, (long long)lo + hi, (long long)hi - lo});
                } else {
                    if (t <= 0) continue;
                    who.push_back({x, false, 0, 0, 2LL * t, 2LL * t});
                }
            }
            if (who.size() < 2) continue;
            sat::LinearExpr r;
            if (fixedRef) {
                // hint の盤面の基準＝幅を重みにした達成率の中央値（1/Q 刻みへ丸める）。
                std::vector<std::pair<double, long long>> rate;
                long long wsum = 0;
                for (const auto& w : who) {
                    long long c = hintCount(w.x, k);
                    if (w.band) c = std::max<long long>(w.lo, std::min<long long>(w.hi, c));
                    rate.push_back({(double)Q * (double)(2 * c - w.center2) / (double)w.s2, w.s2});
                    wsum += w.s2;
                }
                std::sort(rate.begin(), rate.end());
                long long acc = 0; double med = rate.back().first;
                for (const auto& rw : rate) { acc += rw.second; if (2 * acc >= wsum) { med = rw.first; break; } }
                r = sat::LinearExpr((long long)std::llround(med));
            } else {
                r = sat::LinearExpr(cp_.NewIntVar({-Q, Q * T}));
            }
            auto mx = cp_.NewIntVar({0, big});
            sat::LinearExpr sum;
            long long n = 0, s2sum = 0, s2max = 0;
            for (const auto& w : who) {
                const auto& c = cnt_[(size_t)w.x * K + k];
                sat::LinearExpr e = c;
                if (w.band) {
                    auto upper = cp_.NewIntVar({0, T});
                    cp_.AddMinEquality(upper, {c, sat::LinearExpr(w.hi)});
                    auto clamped = cp_.NewIntVar({w.lo, w.hi});
                    cp_.AddMaxEquality(clamped, {sat::LinearExpr(upper), sat::LinearExpr(w.lo)});
                    e = clamped;
                }
                auto d = cp_.NewIntVar({0, big});
                cp_.AddGreaterOrEqual(d - 2 * Q * e + w.s2 * r, -Q * w.center2);
                cp_.AddGreaterOrEqual(d + 2 * Q * e - w.s2 * r, Q * w.center2);
                cp_.AddGreaterOrEqual(mx - d, 0);
                sum += d; n++; s2sum += w.s2; s2max = std::max(s2max, w.s2);
            }
            // 基準が自由なときは丸めと刻みの誤差ぶんを引いて実際の値以下にする。固定のときは四捨五入だけ。
            const long long slack = fixedRef ? Q : Q * (n + 1) + (s2sum + s2max + 1) / 2;
            auto fv = cp_.NewIntVar({0, (n + 1) * big / (2 * Q) + 1});
            cp_.AddGreaterOrEqual(2 * Q * fv - sum - mx, -slack);
            fair_ += p_.w.fair * fv;
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
    for (const auto& name : opt.subsolvers) prm.add_subsolvers(name);
    return prm;
}

// 外からの停止を段ごとの停止に結ぶ。CP-SAT は解き終えると 1 つ目の停止フラグを自分で立てる＝段ごとに別のフラグを渡し、
//   呼び出し側のフラグは 2 つ目（読むだけ）として登録する（共有すると第 2 段が始まった瞬間に止まっていた）。
inline void linkStop(sat::Model& env, std::atomic<bool>& phaseStop, const Options& opt) {
    auto* tl = env.GetOrCreate<operations_research::TimeLimit>();
    tl->RegisterExternalBooleanAsLimit(&phaseStop);
    if (opt.stop) tl->RegisterSecondaryExternalBooleanAsLimit(opt.stop);
}

// マスの値だけのヒントを、補助変数を含む全変数のヒントへ広げる（3.727.89）。マスの変数だけのヒントは「不完全」と扱われ、CP-SAT は
//   渡した盤面から始めずに別の解から作り直す（実測: 5866 変数のうち 1698 だけ、最初の解まで約 5 秒）。マスをヒントの値に固定して
//   補助変数まで解き、その解を全変数のヒントにすると、渡した盤面が最初の解になる。盤面が条件（必須の件数の上限など）を
//   満たさない・時間内に解けないときは false（ヒントは元のまま）。
inline bool completeHint(sat::CpModelProto& proto, const Options& opt, double seconds) {
    if (proto.solution_hint().vars_size() == 0) return false;
    sat::SatParameters prm;
    prm.set_num_workers(1);
    prm.set_max_time_in_seconds(std::max(0.05, seconds));
    prm.set_random_seed((int)opt.randomSeed);
    prm.set_log_search_progress(false);
    prm.set_fix_variables_to_their_hinted_value(true);
    sat::Model env;
    env.Add(sat::NewSatParameters(prm));
    std::atomic<bool> phaseStop{false};
    linkStop(env, phaseStop, opt);
    const auto r = sat::SolveCpModel(proto, &env);
    if (r.status() != sat::OPTIMAL && r.status() != sat::FEASIBLE) return false;
    auto* h = proto.mutable_solution_hint();
    h->Clear();
    for (int v = 0; v < proto.variables_size(); v++) { h->add_vars(v); h->add_values(r.solution(v)); }
    return true;
}

// 2 段で解く: ①必須の件数を最小 ②その件数以下で重み付きを最小（今の採否＝必須の件数 → 重み と同じ順）。
inline Result solve(const MagiProblem& p, const std::vector<int>& hint, const Options& opt, const HardW& hw = HardW()) {
    Result res;
    auto t0 = std::chrono::steady_clock::now();
    auto elapsed = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    long long hardCap = opt.hardCap;
    // 第 1 段（必須の最少化）は短く切る: 証明できればそこで終わり、できなくても重みの段（下限・ギャップの本体）へ時間を残す。
    const double phase1Seconds = std::min(opt.maxSeconds * 0.3, opt.phase1MaxSeconds);
    if (hardCap < 0) {
        Model m(p, hw);
        m.build(hint, opt);
        m.cp().Minimize(m.hardCount());
        sat::Model env;
        env.Add(sat::NewSatParameters(params(opt, phase1Seconds)));
        std::atomic<bool> phaseStop{false};
        linkStop(env, phaseStop, opt);
        auto r = sat::SolveCpModel(m.cp().Build(), &env);
        res.status = r.status();
        if (r.status() != sat::OPTIMAL && r.status() != sat::FEASIBLE) { res.wallSeconds = elapsed(); return res; }
        res.hardBound = (long long)std::llround(r.best_objective_bound());
        res.hard = (long long)std::llround(r.objective_value());
        res.hardProven = r.status() == sat::OPTIMAL;
        hardCap = res.hard;
        res.board = m.boardFrom(r);
        if (opt.onHard) opt.onHard(res.hard, res.hardBound, res.hardProven);
    }
    // 第 2 段は T1〜T3 の重み付きを一度に最小にする（T1 の件数だけを先に詰める段は 3.727.88 で外した。経緯は docs/cpsat_formal.md）。
    const std::vector<int>& hint2 = hint.empty() ? res.board : hint;
    Model m(p, hw);
    m.build(hint2, opt);
    m.cp().AddLessOrEqual(m.hardCount(), hardCap);
    m.cp().Minimize(m.objective());
    sat::Model env;
    env.Add(sat::NewSatParameters(params(opt, std::max(0.05, opt.maxSeconds - elapsed()))));
    std::atomic<bool> phaseStop{false};
    linkStop(env, phaseStop, opt);
    if (opt.onBound) env.Add(sat::NewBestBoundCallback([&](double b) { opt.onBound((long long)std::ceil(b - 1e-6)); }));
    if (opt.onSolution) {
        env.Add(sat::NewFeasibleSolutionObserver([&](const sat::CpSolverResponse& r) {
            auto b = m.boardFrom(r);
            if (!opt.onSolution(b, (long long)std::llround(sat::SolutionIntegerValue(r, m.hardCount())), (long long)sat::SolutionIntegerValue(r, m.weighted()))) {
                phaseStop.store(true);
            }
        }));
    }
    auto proto = m.cp().Build();
    if (m.fullyHinted()) completeHint(proto, opt, std::min(2.0, std::max(0.05, (opt.maxSeconds - elapsed()) * 0.1)));
    auto r = sat::SolveCpModel(proto, &env);
    res.status = r.status();
#ifdef MAGI_HOST_TEST
    if (getenv("CPSAT_DEBUG")) fprintf(stderr, "phase2 status=%d wall=%.2f stop=%d info=%s\n", (int)r.status(), r.wall_time(), opt.stop ? (int)opt.stop->load() : -1, r.solution_info().c_str());
#endif
    if (r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE) {
        res.board = m.boardFrom(r);
        res.weighted = sat::SolutionIntegerValue(r, m.weighted());
        res.weightedBound = (long long)std::llround(r.best_objective_bound());
        res.hard = sat::SolutionIntegerValue(r, m.hardCount());
    }
    res.wallSeconds = elapsed();
    return res;
}

// 一部だけ解き直す（3.727.89、docs/cpsat_formal.md「解き直し」）。盤面の一部（職員数人・数日の窓）だけを自由にし、残りを固定した小さい
//   問題を 1 スレッドの CP-SAT で解く。threads 本が別々の部分を同時に解き、最良を共有する。採否はエンジンの採点（fullEvalParts＝
//   チェッカーと同値、必須の件数 → 必須の重み込みの重み付き）で良くなったときだけ＝開始の盤面より悪い盤面は返さない。
struct ImproveResult {
    std::vector<int> board;          // 最良の盤面（改善が無ければ開始の盤面）
    long long hard = -1, weighted = -1;   // 最良の採点（必須の件数・必須の重み込みの重み付き、fair 込み）
    long long startWeighted = -1;
    long long iters = 0, accepted = 0, optimal = 0;   // 解いた部分の数・採った数・最適まで解けた数
    double wallSeconds = 0.0;
};

struct ImproveOptions {
    double maxSeconds = 30.0;
    int threads = 1;
    long long randomSeed = 1;
    // 部分 1 つの持ち時間の上限（秒）。
    double subSeconds = 4.0;
    std::atomic<bool>* stop = nullptr;
    // 最良が更新されるたびに呼ぶ（盤面・必須の件数・重み付き）。ロックの中から呼ぶ＝短く。
    std::function<void(const std::vector<int>&, long long, long long)> onBest;
};

namespace improve_detail {

struct Score { long long hard, w; };
inline Score scoreOf(const MagiProblem& p, const HardW& hw, const std::vector<int>& b) {
    long long out[2], bd[21];
    fullEvalParts(p, b.data(), out, bd);
    return {out[0], out[1] + bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish};
}
inline bool better(const Score& a, const Score& b) { return a.hard != b.hard ? a.hard < b.hard : a.w < b.w; }

// 職員 i の行だけで決まる T1 の量（c1 の不足・下限割れ・上限超過）。狙う部分を選ぶ重みに使う（採点ではない）。
inline long long rowLoad(const MagiProblem& p, const std::vector<int>& b, int i) {
    const int T = p.T, K = p.K;
    long long v = 0;
    std::vector<int> cnt((size_t)K, 0);
    for (int j = 0; j < T; j++) { const int k = b[(size_t)i * T + j]; if (k >= 0 && k < K) cnt[k]++; }
    for (int k = 0; k < K; k++) {
        const int lo = p.rangeLo[(size_t)i * K + k], hi = p.rangeHi[(size_t)i * K + k];
        if (lo != INT32_MIN && cnt[k] < lo) v += lo - cnt[k];
        if (hi != INT32_MAX && cnt[k] > hi) v += cnt[k] - hi;
    }
    for (const auto& c : p.cons1) {
        if (!p.cd(i, c.si)) continue;
        for (int j = 0; j <= T - c.d1; j++) {
            int z = 0;
            for (int l = 0; l < c.d1; l++) if (b[(size_t)i * T + j + l] == c.si) z++;
            if (z < c.d2) v += c.d2 - z;
        }
    }
    return v;
}

// 重み w に比例して 1 つ選ぶ（全部 0 なら -1）。
template <class Rng> inline int pickWeighted(const std::vector<long long>& w, Rng& rng) {
    long long tot = 0; for (long long x : w) tot += x;
    if (tot <= 0) return -1;
    long long r = (long long)(rng() % (unsigned long long)tot);
    for (size_t i = 0; i < w.size(); i++) { if (r < w[i]) return (int)i; r -= w[i]; }
    return (int)w.size() - 1;
}

// 自由にするマスを決める（force＝固定する値、自由なマスは -1）。無作為 3 種＋狙う 2 種を順に回す。
//   0 日の窓×職員（多い職場は 14 人まで）／1 職員 2〜3 人×全日／2 職員 4〜5 人×日の窓
//   3 T1 の量が多い職員＋同じグループの 1〜2 人×全日／4 fair の偏りが大きいグループ×日の窓
template <class Rng> inline std::vector<int> pickRegion(const MagiProblem& p, const std::vector<int>& b, int kind, Rng& rng) {
    const int S = p.S, T = p.T, K = p.K;
    std::vector<int> force(b);
    auto ri = [&](int lo, int hi) { return lo + (int)(rng() % (unsigned long long)(hi - lo + 1)); };
    auto freeCells = [&](const std::vector<int>& staff, int j0, int len) {
        for (int i : staff) for (int j = j0; j < j0 + len; j++) force[(size_t)i * T + j] = -1;
    };
    auto shuffled = [&](std::vector<int> v) { std::shuffle(v.begin(), v.end(), rng); return v; };
    std::vector<int> all((size_t)S); for (int i = 0; i < S; i++) all[i] = i;
    auto window = [&](int lo, int hi, int& j0, int& len) { len = std::min(T, ri(lo, hi)); j0 = ri(0, T - len); };
    int j0 = 0, len = T;
    if (kind == 3) {
        std::vector<long long> load((size_t)S); for (int i = 0; i < S; i++) load[i] = rowLoad(p, b, i);
        const int i0 = pickWeighted(load, rng);
        if (i0 >= 0) {
            std::vector<int> staff{i0}, mates;
            for (int x : all) if (x != i0 && p.sgrp[x] == p.sgrp[i0]) mates.push_back(x);
            if (mates.empty()) for (int x : all) if (x != i0) mates.push_back(x);
            mates = shuffled(mates);
            const int n = std::min<int>((int)mates.size(), ri(1, 2));
            staff.insert(staff.end(), mates.begin(), mates.begin() + n);
            freeCells(staff, 0, T);
            return force;
        }
        kind = 1;
    }
    if (kind == 4) {
        std::vector<int> ssn((size_t)S * K, 0);
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) { const int k = b[(size_t)i * T + j]; if (k >= 0 && k < K) ssn[(size_t)i * K + k]++; }
        std::vector<long long> dev;
        for (int g = 0; g < (int)p.members.size() && g < (int)p.bucket.size(); g++) {
            long long d = 0;
            if (p.members[g].size() >= 2) for (int k : p.bucket[g]) d += fairDevOfBucket(p, g, k, [&](int x) { return ssn[(size_t)x * K + k]; });
            dev.push_back(d);
        }
        const int g = pickWeighted(dev, rng);
        if (g >= 0) {
            auto staff = shuffled(p.members[g]);
            if (staff.size() > 6) staff.resize(6);
            if (staff.size() <= 3) { freeCells(staff, 0, T); return force; }
            window(12, 16, j0, len);
            freeCells(staff, j0, len);
            return force;
        }
        kind = 2;
    }
    if (kind == 0) {
        auto staff = shuffled(all);
        if (staff.size() > 14) staff.resize(14);
        window(4, 7, j0, len);
        freeCells(staff, j0, len);
    } else if (kind == 1) {
        auto staff = shuffled(all);
        staff.resize((size_t)std::min(S, ri(2, 3)));
        freeCells(staff, 0, T);
    } else {
        auto staff = shuffled(all);
        staff.resize((size_t)std::min(S, ri(4, 5)));
        window(10, 14, j0, len);
        freeCells(staff, j0, len);
    }
    return force;
}

}  // namespace improve_detail

inline ImproveResult improve(const MagiProblem& p, const std::vector<int>& start, const ImproveOptions& io, const HardW& hw = HardW()) {
    using namespace improve_detail;
    ImproveResult res;
    const auto t0 = std::chrono::steady_clock::now();
    auto elapsed = [&] { return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(); };
    if (start.size() != (size_t)p.S * p.T) return res;
    std::mutex mu;
    std::vector<int> best = start;
    Score bestScore = scoreOf(p, hw, start);
    res.startWeighted = bestScore.w;
    auto stopped = [&] { return (io.stop && io.stop->load()) || elapsed() >= io.maxSeconds; };
    auto work = [&](int t) {
        std::mt19937_64 rng((unsigned long long)io.randomSeed * 1000003ULL + (unsigned long long)t * 7919ULL + 1ULL);
        int kind = t % 5;
        while (!stopped()) {
            std::vector<int> cur; Score curScore;
            { std::lock_guard<std::mutex> g(mu); cur = best; curScore = bestScore; }
            Options o;
            o.workers = 1; o.randomSeed = (long long)(rng() & 0x7fffffff); o.stop = io.stop; o.fairFixedRef = true;
            o.force = pickRegion(p, cur, kind, rng);
            kind = (kind + 1) % 5;
            Model m(p, hw);
            m.build(cur, o);
            m.cp().AddLessOrEqual(m.hardCount(), curScore.hard);
            m.cp().Minimize(m.objective());
            auto proto = m.cp().Build();
            const double left = io.maxSeconds - elapsed();
            if (left <= 0.05) break;
            completeHint(proto, o, std::min(1.0, left));
            sat::Model env;
            env.Add(sat::NewSatParameters(params(o, std::min(io.subSeconds, io.maxSeconds - elapsed()))));
            std::atomic<bool> phaseStop{false};
            linkStop(env, phaseStop, o);
            const auto r = sat::SolveCpModel(proto, &env);
            const bool solved = r.status() == sat::OPTIMAL || r.status() == sat::FEASIBLE;
            std::vector<int> nb; Score ns{0, 0};
            if (solved) { nb = m.boardFrom(r); ns = scoreOf(p, hw, nb); }
            std::lock_guard<std::mutex> g(mu);
            res.iters++;
            if (r.status() == sat::OPTIMAL) res.optimal++;
            if (solved && better(ns, bestScore)) {
                best = nb; bestScore = ns; res.accepted++;
                if (io.onBest) io.onBest(best, bestScore.hard, bestScore.w);
            }
        }
    };
    const int n = std::max(1, io.threads);
    std::vector<std::thread> th;
    for (int t = 1; t < n; t++) th.emplace_back(work, t);
    work(0);
    for (auto& x : th) x.join();
    res.board = best; res.hard = bestScore.hard; res.weighted = bestScore.w;
    res.wallSeconds = elapsed();
    return res;
}

// 必須違反の原因（S4、docs/cpsat_formal.md）。希望・手動固定・必要人数・禁止の並びの規則を 1 件ずつ「守る」仮定にして、
//   必須 0 が成り立たない最小の組（矛盾の核）を CP-SAT の仮定の核で求め、削って最小化し、外して繰り返す（互いに素な核）。
//   どの核も「この中の 1 件を緩めないと必須違反は消えない」の証明。核の数は必須件数の下限にもなる。
struct Cause { int kind; int i, j, k, rule; };   // kind: 0 希望 1 手動固定 2 必要人数 3 禁止の並び 4 担当外・拡張希望の禁止（職員 i）
struct Core { std::vector<Cause> causes; bool minimal; };   // minimal: どの 1 件を外しても成り立つと確かめた
enum CauseKind { kWish = 0, kPin = 1, kNeed = 2, kForbidden = 3, kOutside = 4 };

class HardExplainer {
public:
    // 置ける値は最適化のモデルと同じ（担当できるシフト＋入力の値＋希望・手動固定）。担当外・拡張希望の禁止の値は職員ごとの仮定で止める。
    HardExplainer(const MagiProblem& p, const std::vector<int>& input) : p_(p), S(p.S), T(p.T), K(p.K) { build(input); }

    // 互いに素な核を最大 maxCores 個、全体で totalSeconds まで。各核は最小（どれか 1 件を外すと成り立つ）。
    std::vector<Core> explain(int maxCores, double secondsPerSolve, int workers, double totalSeconds = 1e9) {
        std::vector<Core> cores;
        const auto t0 = std::chrono::steady_clock::now();
        auto left = [&] {
            return totalSeconds - std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        };
        std::vector<int> active(lits_.size());
        for (size_t a = 0; a < lits_.size(); a++) active[a] = (int)a;
        for (int round = 0; round < maxCores; round++) {
            std::vector<int> core;
            if (left() <= 0 || check(active, std::min(secondsPerSolve, left()), workers, &core) != kInfeasible || core.empty()) break;
            // 削って最小化: 1 件ずつ外して、まだ成り立たないなら外したままにする。外すと成り立つ（解が見つかる）と確かめられた
            //   件だけが残れば最小。時間切れで分からなかった件が残れば minimal=false。
            bool minimal = true;
            for (size_t x = 0; x < core.size();) {
                std::vector<int> trial = core; trial.erase(trial.begin() + (long)x);
                const int v = trial.empty() ? kFeasible
                            : left() <= 0 ? kUnknown : check(trial, std::min(secondsPerSolve, left()), workers, nullptr);
                if (v == kInfeasible) { core = trial; continue; }
                if (v == kUnknown) minimal = false;
                x++;
            }
            Core out{{}, minimal};
            for (int a : core) out.causes.push_back(desc_[(size_t)a]);
            cores.push_back(out);
            std::vector<int> next;
            for (int a : active) if (std::find(core.begin(), core.end(), a) == core.end()) next.push_back(a);
            active.swap(next);
        }
        return cores;
    }

private:
    const MagiProblem& p_;
    const int S, T, K;
    sat::CpModelBuilder cp_;
    std::vector<std::vector<std::pair<int, sat::BoolVar>>> vars_;
    std::vector<sat::BoolVar> lits_;
    std::vector<Cause> desc_;
    std::vector<int> wishLit_;   // S*T: 希望の仮定の添字（無ければ -1）

    sat::LinearExpr X(int i, int j, int k) const {
        for (const auto& kv : vars_[(size_t)i * T + j]) if (kv.first == k) return sat::LinearExpr(kv.second);
        return sat::LinearExpr(0);
    }
    int addLit(Cause c) { lits_.push_back(cp_.NewBoolVar()); desc_.push_back(c); return (int)lits_.size() - 1; }

    void build(const std::vector<int>& input) {
        vars_.assign((size_t)S * T, {});
        wishLit_.assign((size_t)S * T, -1);
        std::vector<int> outsideLit(S, -1);
        for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
            const size_t c = (size_t)i * T + j;
            std::vector<int> dom;
            for (int k : p_.allowed[i]) if (!p_.extWishBanned(i, j, k)) dom.push_back(k);
            const int w = p_.wish[c], pin = p_.pin.empty() ? -1 : p_.pin[c];
            const int h = input.empty() ? -1 : input[c];
            for (int extra : {w, pin, h}) if (extra >= 0 && extra < K && std::find(dom.begin(), dom.end(), extra) == dom.end()) dom.push_back(extra);
            if (dom.empty()) dom.push_back(p_.restIdx);
            std::vector<sat::BoolVar> one;
            for (int k : dom) { auto v = cp_.NewBoolVar(); vars_[c].push_back({k, v}); one.push_back(v); }
            cp_.AddExactlyOne(one);
            // 担当できないシフト・拡張希望の禁止（評価器と同じく表どおり。希望の日は表を作るときに除かれる）は置かない。
            for (int k : dom) if (!p_.cd(i, k) || p_.extWishBanned(i, j, k)) {
                if (outsideLit[i] < 0) outsideLit[i] = addLit({kOutside, i, -1, -1, -1});
                cp_.AddEquality(X(i, j, k), 0).OnlyEnforceIf(lits_[(size_t)outsideLit[i]]);
            }
            if (w >= 0 && p_.cd(i, w)) {
                const int a = addLit({kWish, i, j, w, -1});
                wishLit_[c] = a;
                cp_.AddEquality(X(i, j, w), 1).OnlyEnforceIf(lits_[(size_t)a]);
            }
            if (pin >= 0) {
                const int a = addLit({kPin, i, j, pin, -1});
                cp_.AddEquality(X(i, j, pin), 1).OnlyEnforceIf(lits_[(size_t)a]);
            }
        }
        // 希望の前日に禁止（c3w）: 翌日の希望を守るなら前日に禁止のシフトを置かない。
        for (const auto& r : p_.cons3w) for (int i = 0; i < S; i++) for (int j = 0; j + 1 < T; j++) {
            const int a = wishLit_[(size_t)i * T + j + 1];
            if (a < 0 || p_.wish[(size_t)i * T + j + 1] != r.wishK) continue;
            cp_.AddEquality(X(i, j, r.prevK), 0).OnlyEnforceIf(lits_[(size_t)a]);
        }
        // 禁止の並び（c3n）: 規則ごとに 1 つの仮定。
        for (size_t e = 0; e < p_.cons3n.size(); e++) {
            const auto& seq = p_.cons3n[e].seq; const int D = (int)seq.size();
            if (D == 0) continue;
            const int a = addLit({kForbidden, -1, -1, -1, (int)e});
            for (int i = 0; i < S; i++) for (int j = 0; j + D <= T; j++) {
                sat::LinearExpr m;
                for (int l = 0; l < D; l++) m += X(i, j + l, seq[(size_t)l]);
                cp_.AddLessOrEqual(m, D - 1).OnlyEnforceIf(lits_[(size_t)a]);
            }
        }
        // 必要人数（covU）: 日×シフトごとの仮定。
        for (int k = 0; k < K; k++) for (int j = 0; j < T; j++) {
            const int lo1 = p_.need1[(size_t)k * T + j];
            const int lo2 = p_.use2 ? p_.need2[(size_t)k * T + j] : -1;
            if (lo1 < 0 && lo2 < 0) continue;
            const int lmin = (lo1 >= 0 && lo2 >= 0) ? std::min(lo1, lo2) : (lo1 >= 0 ? lo1 : lo2);
            if (lmin <= 0) continue;
            sat::LinearExpr got;
            for (int i = 0; i < S; i++) got += X(i, j, k);
            const int a = addLit({kNeed, -1, j, k, lmin});
            cp_.AddGreaterOrEqual(got, lmin).OnlyEnforceIf(lits_[(size_t)a]);
        }
    }

    enum { kFeasible, kInfeasible, kUnknown };
    // 仮定 [assume] をすべて守ると成り立つか。成り立たなければ core に CP-SAT の核（仮定の添字）を入れる。
    int check(const std::vector<int>& assume, double seconds, int workers, std::vector<int>* core) {
        cp_.ClearAssumptions();
        std::vector<sat::BoolVar> as;
        for (int a : assume) as.push_back(lits_[(size_t)a]);
        cp_.AddAssumptions(as);
        sat::SatParameters prm;
        prm.set_num_workers(std::max(1, workers));
        prm.set_max_time_in_seconds(seconds);
        auto r = sat::SolveWithParameters(cp_.Build(), prm);
        if (r.status() == sat::FEASIBLE || r.status() == sat::OPTIMAL) return kFeasible;
        if (r.status() != sat::INFEASIBLE) return kUnknown;
        if (core) {
            core->clear();
            for (int ref : r.sufficient_assumptions_for_infeasibility()) {
                for (size_t a = 0; a < lits_.size(); a++) if (lits_[a].index() == ref) { core->push_back((int)a); break; }
            }
        }
        return kInfeasible;
    }
};

}  // namespace magi_cpsat
