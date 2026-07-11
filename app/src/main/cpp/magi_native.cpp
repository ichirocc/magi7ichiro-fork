#include <jni.h>
#include <cstdint>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <random>
#include <vector>

// [ネイティブ加速 Stage2] Evaluator.kt の忠実な C++ 移植（fullEvalParts 相当）。
// Kotlin 実装が常に「正」: 実行時に Kotlin Evaluator と同一盤面で hard/soft を照合し
// （V6FinalPort の NativeBridge ログ）、不一致ならネイティブ経路を使わない。
// Stage3 でこの評価器の上に SA チャンク（Δ評価＋受理）を載せる。
//
// 移植規約:
// - 添字・番兵は Kotlin と同一（rangeLo/Hi の未設定 = INT32_MIN/INT32_MAX、apt/wish 未設定 = -1）。
// - Java の Math.round(double) は floor(x+0.5)（half-up）。jround() で同一化。
// - c3RunMode=true 固定（Evaluator の既定と同じ。非forbidden 単一シフト連は run-deficit）。

namespace {

inline long long jround(double x) { return (long long)std::floor(x + 0.5); }

struct C1r { int d1, si, d2; };
struct C2r { int si, c; };
struct C41r { int g, s, l, u; };
struct C42r { int g1, s1, g2, s2; };
struct C3r { std::vector<int> seq; bool singleRun; };

struct MagiProblem {
    int S = 0, T = 0, K = 0, G = 0, restIdx = 0, dow0 = 0;
    bool use2 = false;
    std::vector<int> sgrp, ssk;              // S
    std::vector<uint8_t> canDo;              // S*K
    std::vector<int> wish;                   // S*T (-1 = none)
    std::vector<int> need1, need2;           // K*T (-1 = none)
    std::vector<int> rangeLo, rangeHi, apt;  // S*K
    std::vector<C1r> cons1;
    std::vector<C2r> cons2;
    std::vector<C41r> cons41, cons41s;
    std::vector<C42r> cons42, cons42s;
    std::vector<C3r> cons3, cons3n, cons3m, cons3mn;
    std::vector<std::vector<int>> bucket;    // G: 群の担当ONシフト
    std::vector<std::vector<int>> members;   // G: 群のメンバー（sgrp から導出）
    std::vector<uint8_t> bucketHas;          // G*K: 群 g がシフト k を担当できるか（fair 用）
    std::vector<std::vector<int>> staffForShift;  // K: シフト k を担当できる職員（opBlockFill 用）

    inline bool cd(int i, int k) const {
        if (i < 0 || i >= S || k < 0 || k >= K) return false;
        return canDo[(size_t)i * K + k] != 0;
    }
    // Problem.covUCell / covOCell と同式（per-cell OR/AND, #4b）。
    inline int covUCell(int k, int j, int got) const {
        int lo1 = need1[(size_t)k * T + j];
        int lo2 = use2 ? need2[(size_t)k * T + j] : -1;
        int u1 = lo1 >= 0 ? (lo1 - got > 0 ? lo1 - got : 0) : -1;
        int u2 = lo2 >= 0 ? (lo2 - got > 0 ? lo2 - got : 0) : -1;
        if (u1 >= 0 && u2 >= 0) return u1 < u2 ? u1 : u2;
        if (u1 >= 0) return u1;
        if (u2 >= 0) return u2;
        return 0;
    }
    inline int covOCell(int k, int j, int got) const {
        int lo1 = need1[(size_t)k * T + j];
        int lo2 = use2 ? need2[(size_t)k * T + j] : -1;
        int o1 = lo1 >= 0 ? (got - lo1 > 0 ? got - lo1 : 0) : -1;
        int o2 = lo2 >= 0 ? (got - lo2 > 0 ? got - lo2 : 0) : -1;
        if (o1 >= 0 && o2 >= 0) return o1 < o2 ? o1 : o2;
        if (o1 >= 0) return o1;
        if (o2 >= 0) return o2;
        return 0;
    }
};

// MirrorCore.weeklyDevOfBucket と同式。
inline long long weeklyDevOfBucket(const int wd[7]) {
    int sum = 0;
    for (int w = 0; w < 7; w++) sum += wd[w];
    long long tgt = jround((double)sum / 7.0);
    long long d = 0;
    for (int w = 0; w < 7; w++) d += std::llabs((long long)wd[w] - tgt);
    return d;
}

// C3Run.rowDeficit と同式（行走査で run の不足 L-r を加算）。
inline long long rowDeficit(const int* row, int T, int k, int L) {
    long long sub = 0;
    int r = 0;
    for (int j = 0; j <= T; j++) {
        bool on = j < T && row[j] == k;
        if (on) {
            r++;
        } else if (r > 0) {
            int d = L - r;
            if (d > 0) sub += d;
            r = 0;
        }
    }
    return sub;
}

// Evaluator.c3check と同式（forbidden=窓マッチ#fire / 非forbidden単一連=run-deficit）。
long long c3check(const MagiProblem& p, const int* a, const std::vector<C3r>& list, bool forbidden) {
    long long sub = 0;
    for (const auto& c : list) {
        const int D = (int)c.seq.size();
        if (D == 0) continue;
        const int first = c.seq[0];
        if (!forbidden && c.singleRun) {
            for (int i = 0; i < p.S; i++) sub += rowDeficit(a + (size_t)i * p.T, p.T, first, D);
            continue;
        }
        for (int i = 0; i < p.S; i++) {
            const int* row = a + (size_t)i * p.T;
            for (int j = 0; j <= p.T - D; j++) {
                if (row[j] == first) {
                    int z = 0;
                    for (int l = 1; l < D; l++) if (row[j + l] == c.seq[l]) z++;
                    bool fire = forbidden ? (z == D - 1) : (z < D - 1);
                    if (fire) sub += 1;
                }
            }
        }
    }
    return sub;
}

// Evaluator.fullEvalParts の忠実移植。a は S*T の平坦配列。out[0]=hard1, out[1]=soft。
void fullEvalParts(const MagiProblem& p, const int* a, long long out[2]) {
    const int S = p.S, T = p.T, K = p.K;
    long long hard1 = 0, soft = 0;

    // c1（canDo ガード＋#fire×重み4）
    for (const auto& c : p.cons1) {
        for (int i = 0; i < S; i++) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a + (size_t)i * T;
            for (int j = 0; j <= T - c.d1; j++) {
                int z = 0;
                for (int l = 0; l < c.d1; l++) if (row[j + l] == c.si) z++;
                if (z < c.d2) soft += 4;
            }
        }
    }

    // c2（canDo ガード）
    for (const auto& c : p.cons2) {
        for (int i = 0; i < S; i++) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a + (size_t)i * T;
            int z = 0;
            for (int j = 0; j < T; j++) if (row[j] == c.si) z++;
            if (z < c.c) soft += 1;
        }
    }

    // c41 / c42（通常群）
    for (const auto& c : p.cons41) {
        for (int j = 0; j < T; j++) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.sgrp[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) soft += 1;
        }
    }
    for (const auto& c : p.cons42) {
        for (int j = 0; j < T; j++) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int v = a[(size_t)i * T + j];
                if (p.sgrp[i] == c.g1 && v == c.s1) n1++;
                if (p.sgrp[i] == c.g2 && v == c.s2) n2++;
            }
            soft += n1 * n2;
        }
    }

    // c41s / c42s（スキル群）
    for (const auto& c : p.cons41s) {
        for (int j = 0; j < T; j++) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.ssk[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) soft += 1;
        }
    }
    for (const auto& c : p.cons42s) {
        for (int j = 0; j < T; j++) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int v = a[(size_t)i * T + j];
                if (p.ssk[i] == c.g1 && v == c.s1) n1++;
                if (p.ssk[i] == c.g2 && v == c.s2) n2++;
            }
            soft += n1 * n2;
        }
    }

    // c3 族（重み: c3=3 / c3n=HARD / c3m=2 / c3mn=12）
    soft += c3check(p, a, p.cons3, false) * 3;
    hard1 += c3check(p, a, p.cons3n, true);
    soft += c3check(p, a, p.cons3m, false) * 2;
    soft += c3check(p, a, p.cons3mn, true) * 12;

    // pref（実現可能な希望のみ）
    for (int i = 0; i < S; i++) {
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int w = p.wish[(size_t)i * T + j];
            if (w >= 0 && p.cd(i, w) && row[j] != w) hard1 += 1;
        }
    }

    // 回数行列 ssn（range/apt/fair が共有）
    std::vector<int> ssn((size_t)S * K, 0);
    for (int i = 0; i < S; i++) {
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int k = row[j];
            if (k >= 0 && k < K) ssn[(size_t)i * K + k]++;
        }
    }

    // range low(90)/high(45) ＋ apt（L1偏差×1）
    for (int i = 0; i < S; i++) {
        for (int k = 0; k < K; k++) {
            int lo = p.rangeLo[(size_t)i * K + k];
            int hi = p.rangeHi[(size_t)i * K + k];
            int n = ssn[(size_t)i * K + k];
            if (lo != INT32_MIN && lo != 0 && n < lo && p.cd(i, k)) soft += (long long)(lo - n) * 90;
            if (hi != INT32_MAX && n > hi) soft += (long long)(n - hi) * 45;
            int t = p.apt[(size_t)i * K + k];
            if (t >= 0) soft += std::llabs((long long)n - t);
        }
    }

    // fair（群×担当ONシフト、round(平均) からの L1 偏差）
    for (int g = 0; g < p.G; g++) {
        const auto& mem = p.members[g];
        const int m = (int)mem.size();
        if (m < 2) continue;
        for (int k : p.bucket[g]) {
            int sum = 0;
            for (int x : mem) sum += ssn[(size_t)x * K + k];
            long long tgt = jround((double)sum / m);
            for (int x : mem) soft += std::llabs((long long)ssn[(size_t)x * K + k] - tgt);
        }
    }

    // weekly（職員×曜日、round(勤務日/7) からの L1 偏差）
    for (int i = 0; i < S; i++) {
        int wd[7] = {0, 0, 0, 0, 0, 0, 0};
        const int* row = a + (size_t)i * T;
        for (int j = 0; j < T; j++) {
            int k = row[j];
            if (k != p.restIdx && k >= 0 && k < K) wd[(p.dow0 + j) % 7]++;
        }
        soft += weeklyDevOfBucket(wd);
    }

    // 被覆（covU=HARD / covO=SOFT, per-cell OR/AND）
    long long covU = 0;
    // Kotlin は j→k→i の三重ループだが、dsn[k] を日ごとに1回で数える（結果は同一・O(T*(S+K))）。
    std::vector<int> dsn(K, 0);
    for (int j = 0; j < T; j++) {
        for (int k = 0; k < K; k++) dsn[k] = 0;
        for (int i = 0; i < S; i++) {
            int k = a[(size_t)i * T + j];
            if (k >= 0 && k < K) dsn[k]++;
        }
        for (int k = 0; k < K; k++) {
            covU += p.covUCell(k, j, dsn[k]);
            soft += p.covOCell(k, j, dsn[k]);
        }
    }
    hard1 += covU;

    out[0] = hard1;
    out[1] = soft;
}

inline long long fullEvalCombined(const MagiProblem& p, const int* a) {
    long long v[2];
    fullEvalParts(p, a, v);
    return v[0] * 1000000LL + v[1];
}

// ============ [Stage3] SA チャンク: 差分評価つき冷却ラダーを C++ 内で完走 ============
// 設計: 1チャンク=1冷却ラダー（t0→tf, 温度毎 chain 反復 ≒ 数千反復・数ms）。
// スコアは「変更セルの影響スライスを before/after 再計算」する差分方式で維持し、
// チャンク末尾に fullEval と照合（自己整合検査）。不一致は status!=0 で Kotlin 側が破棄する。
// 乱数は mt19937_64（Kotlin と経路一致は狙わない。パリティはスコアと盤面で取る）。
struct SaChunk {
    const MagiProblem& p;
    const int S, T, K, M = 1000000;
    std::vector<int> a;    // S*T
    std::vector<int> ssn;  // S*K
    std::vector<int> dsn;  // T*K
    std::vector<int> wd;   // S*7
    long long score = 0;   // hard*1e6+soft（combined）
    std::mt19937_64 rng;

    SaChunk(const MagiProblem& prob, const int* cur, uint64_t seed)
        : p(prob), S(prob.S), T(prob.T), K(prob.K), rng(seed) {
        a.assign(cur, cur + (size_t)S * T);
        ssn.assign((size_t)S * K, 0);
        dsn.assign((size_t)T * K, 0);
        wd.assign((size_t)S * 7, 0);
        for (int i = 0; i < S; i++) {
            for (int j = 0; j < T; j++) {
                int k = a[(size_t)i * T + j];
                if (k >= 0 && k < K) { ssn[(size_t)i * K + k]++; dsn[(size_t)j * K + k]++; }
                if (k != p.restIdx && k >= 0 && k < K) wd[(size_t)i * 7 + (p.dow0 + j) % 7]++;
            }
        }
        score = fullEvalCombined(p, a.data());
    }

    inline int nextInt(int bound) { return (int)(rng() % (uint64_t)bound); }
    inline double nextDouble() { return (double)(rng() >> 11) * 0x1.0p-53; }

    // ---- 影響スライスの寄与（combined: HARD族は ×1e6）----
    long long contribC1Row(int i) const {
        long long v = 0;
        for (const auto& c : p.cons1) {
            if (!p.cd(i, c.si)) continue;
            const int* row = a.data() + (size_t)i * T;
            for (int j = 0; j <= T - c.d1; j++) {
                int z = 0;
                for (int l = 0; l < c.d1; l++) if (row[j + l] == c.si) z++;
                if (z < c.d2) v += 4;
            }
        }
        return v;
    }
    long long contribC2Row(int i) const {
        long long v = 0;
        for (const auto& c : p.cons2) {
            if (!p.cd(i, c.si)) continue;
            if (ssn[(size_t)i * K + c.si] < c.c) v += 1;
        }
        return v;
    }
    long long contribC3RowFam(int i, const std::vector<C3r>& list, bool forbidden, long long w) const {
        long long v = 0;
        const int* row = a.data() + (size_t)i * T;
        for (const auto& c : list) {
            const int D = (int)c.seq.size();
            if (D == 0) continue;
            const int first = c.seq[0];
            if (!forbidden && c.singleRun) { v += rowDeficit(row, T, first, D) * w; continue; }
            for (int j = 0; j <= T - D; j++) {
                if (row[j] == first) {
                    int z = 0;
                    for (int l = 1; l < D; l++) if (row[j + l] == c.seq[l]) z++;
                    bool fire = forbidden ? (z == D - 1) : (z < D - 1);
                    if (fire) v += w;
                }
            }
        }
        return v;
    }
    long long contribC3Row(int i) const {
        return contribC3RowFam(i, p.cons3, false, 3)
             + contribC3RowFam(i, p.cons3n, true, (long long)M)
             + contribC3RowFam(i, p.cons3m, false, 2)
             + contribC3RowFam(i, p.cons3mn, true, 12);
    }
    long long contribPrefCell(int i, int j) const {
        int w = p.wish[(size_t)i * T + j];
        if (w >= 0 && p.cd(i, w) && a[(size_t)i * T + j] != w) return (long long)M;
        return 0;
    }
    long long contribRangeApt(int i, int k) const {
        long long v = 0;
        int lo = p.rangeLo[(size_t)i * K + k];
        int hi = p.rangeHi[(size_t)i * K + k];
        int n = ssn[(size_t)i * K + k];
        if (lo != INT32_MIN && lo != 0 && n < lo && p.cd(i, k)) v += (long long)(lo - n) * 90;
        if (hi != INT32_MAX && n > hi) v += (long long)(n - hi) * 45;
        int t = p.apt[(size_t)i * K + k];
        if (t >= 0) v += std::llabs((long long)n - t);
        return v;
    }
    long long contribFair(int g, int k) const {
        if (g < 0 || g >= p.G || k < 0 || k >= K) return 0;
        if (!p.bucketHas[(size_t)g * K + k]) return 0;
        const auto& mem = p.members[g];
        const int m = (int)mem.size();
        if (m < 2) return 0;
        int sum = 0;
        for (int x : mem) sum += ssn[(size_t)x * K + k];
        long long tgt = jround((double)sum / m);
        long long v = 0;
        for (int x : mem) v += std::llabs((long long)ssn[(size_t)x * K + k] - tgt);
        return v;
    }
    long long contribWeekly(int i) const { return weeklyDevOfBucket(&wd[(size_t)i * 7]); }
    long long contribDayGroups(int j) const {
        long long v = 0;
        for (const auto& c : p.cons41) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.sgrp[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) v += 1;
        }
        for (const auto& c : p.cons42) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int x = a[(size_t)i * T + j];
                if (p.sgrp[i] == c.g1 && x == c.s1) n1++;
                if (p.sgrp[i] == c.g2 && x == c.s2) n2++;
            }
            v += n1 * n2;
        }
        for (const auto& c : p.cons41s) {
            int z = 0;
            for (int i = 0; i < S; i++) if (p.ssk[i] == c.g && a[(size_t)i * T + j] == c.s) z++;
            if (z < c.l || c.u < z) v += 1;
        }
        for (const auto& c : p.cons42s) {
            long long n1 = 0, n2 = 0;
            for (int i = 0; i < S; i++) {
                int x = a[(size_t)i * T + j];
                if (p.ssk[i] == c.g1 && x == c.s1) n1++;
                if (p.ssk[i] == c.g2 && x == c.s2) n2++;
            }
            v += n1 * n2;
        }
        return v;
    }
    long long contribCov(int k, int j) const {
        if (k < 0 || k >= K) return 0;
        int got = dsn[(size_t)j * K + k];
        return (long long)p.covUCell(k, j, got) * M + p.covOCell(k, j, got);
    }

    // セル(i,j)を nw へ差分適用（影響スライスの before/after 再計算で score を維持）。
    void deltaApply(int i, int j, int nw) {
        int old = a[(size_t)i * T + j];
        if (old == nw) return;
        int g = p.sgrp[i];
        long long before = contribC1Row(i) + contribC2Row(i) + contribC3Row(i)
            + contribPrefCell(i, j)
            + contribRangeApt(i, old) + contribRangeApt(i, nw)
            + contribFair(g, old) + contribFair(g, nw)
            + contribWeekly(i)
            + contribDayGroups(j)
            + contribCov(old, j) + contribCov(nw, j);
        a[(size_t)i * T + j] = nw;
        ssn[(size_t)i * K + old]--; ssn[(size_t)i * K + nw]++;
        dsn[(size_t)j * K + old]--; dsn[(size_t)j * K + nw]++;
        bool oldWork = old != p.restIdx;
        bool newWork = nw != p.restIdx;
        if (oldWork != newWork) wd[(size_t)i * 7 + (p.dow0 + j) % 7] += newWork ? 1 : -1;
        long long after = contribC1Row(i) + contribC2Row(i) + contribC3Row(i)
            + contribPrefCell(i, j)
            + contribRangeApt(i, old) + contribRangeApt(i, nw)
            + contribFair(g, old) + contribFair(g, nw)
            + contribWeekly(i)
            + contribDayGroups(j)
            + contribCov(old, j) + contribCov(nw, j);
        score += after - before;
    }
};

// SA チャンク実行（SaOptimizer PhaseA の冷却ラダー1本ぶん、Kotlin と同じ4オペレータ＋Metropolis）。
// out: [status, curScore, bestScore, iters, improvedInChunk, tailIters]
// status: 0=OK / 1=cur整合性NG / 2=best整合性NG（いずれもKotlin側で破棄・退化）。
void runSaChunk(const MagiProblem& p, int* cur, int* best, long long bestScoreIn,
                uint64_t seed, double t0, double tf, double alpha, int chain, long long out[6]) {
    const int S = p.S, T = p.T;
    SaChunk st(p, cur, seed);
    long long curVal = st.score;
    std::vector<int> bestSol(best, best + (size_t)S * T);
    long long bestScore = bestScoreIn;
    bool improved = false;
    long long iters = 0, tail = 0;

    const int cap = T + S + 16;
    std::vector<int> bi(cap), bj(cap), bOld(cap);
    int bn = 0;
    auto applyCell = [&](int i, int j, int nw) {
        if (bn >= cap) return;
        bi[bn] = i; bj[bn] = j; bOld[bn] = st.a[(size_t)i * T + j]; bn++;
        st.deltaApply(i, j, nw);
    };
    auto revert = [&]() {
        for (int k = bn - 1; k >= 0; k--) st.deltaApply(bi[k], bj[k], bOld[k]);
        bn = 0;
    };
    auto randShiftFor = [&](int i) -> int {
        const auto& b = p.bucket[p.sgrp[i]];
        return b.empty() ? st.a[(size_t)i * T] : b[st.nextInt((int)b.size())];
    };
    auto opSingle = [&]() {
        int i = st.nextInt(S), j = st.nextInt(T);
        const auto& b = p.bucket[p.sgrp[i]];
        if (b.empty()) return;
        applyCell(i, j, b[st.nextInt((int)b.size())]);
    };
    auto opSwapDays = [&]() {
        if (T < 2) return;
        int i = st.nextInt(S);
        int j1 = st.nextInt(T), j2 = st.nextInt(T);
        if (j1 == j2) j2 = (j2 + 1) % T;
        int o1 = st.a[(size_t)i * T + j1], o2 = st.a[(size_t)i * T + j2];
        if (o1 == o2) return;
        applyCell(i, j1, o2);
        applyCell(i, j2, o1);
    };
    auto opBlockFill = [&]() {
        if (p.cons1.empty()) { opSingle(); return; }
        const auto& c = p.cons1[st.nextInt((int)p.cons1.size())];
        const auto& pool = p.staffForShift[c.si];
        if (pool.empty()) { opSingle(); return; }
        int i = pool[st.nextInt((int)pool.size())];
        int maxStart = T - c.d1;
        if (maxStart < 0) { opSingle(); return; }
        int js = st.nextInt(maxStart + 1);
        for (int l = 0; l < c.d1; l++) applyCell(i, js + l, c.si);
    };
    auto opLns = [&]() {
        switch (st.nextInt(3)) {
            case 0: { int i = st.nextInt(S); int cnt = 2 + st.nextInt(T < 7 ? T : 7);
                for (int k = 0; k < cnt; k++) applyCell(i, st.nextInt(T), randShiftFor(i)); break; }
            case 1: { int j = st.nextInt(T);
                for (int i = 0; i < S; i++) applyCell(i, j, randShiftFor(i)); break; }
            default: { int cnt = 3 + st.nextInt(8);
                for (int k = 0; k < cnt; k++) { int i = st.nextInt(S); applyCell(i, st.nextInt(T), randShiftFor(i)); } break; }
        }
    };
    const bool hasC1 = !p.cons1.empty();
    auto pickOperator = [&]() {
        int r = st.nextInt(100);
        if (r < 60) opSingle();
        else if (r < 80) opSwapDays();
        else if (r < 92) { if (hasC1) opBlockFill(); else opSingle(); }
        else opLns();
    };

    // 冷却ラダー（暴走ガード: パラメータ異常でも反復上限で必ず返る）。
    const long long maxIters = 200000;
    for (double t = t0; t >= tf && iters < maxIters; t *= alpha) {
        for (int ls = 0; ls < chain && iters < maxIters; ls++) {
            bn = 0;
            pickOperator();
            long long cand = st.score;
            long long dE = cand - curVal;
            if (dE <= 0 || std::exp(-(double)dE / t) > st.nextDouble()) {
                curVal = cand;
                if (cand < bestScore) {
                    bestScore = cand;
                    std::memcpy(bestSol.data(), st.a.data(), sizeof(int) * (size_t)S * T);
                    improved = true;
                    tail = 0;
                } else tail++;
                bn = 0;
            } else { revert(); tail++; }
            iters++;
        }
    }

    // 自己整合検査（差分スコア==フル再計算）。best 更新があれば best 側も検査。
    long long full = fullEvalCombined(p, st.a.data());
    long long status = 0;
    if (full != curVal) status = 1;
    else if (improved && fullEvalCombined(p, bestSol.data()) != bestScore) status = 2;

    if (status == 0) {
        std::memcpy(cur, st.a.data(), sizeof(int) * (size_t)S * T);
        std::memcpy(best, bestSol.data(), sizeof(int) * (size_t)S * T);
    }
    out[0] = status;
    out[1] = curVal;
    out[2] = bestScore;
    out[3] = iters;
    out[4] = improved ? 1 : 0;
    out[5] = tail;
}

// ---- JNI 平坦データの読み取りヘルパ ----
std::vector<int> readIntArray(JNIEnv* env, jintArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<int> v((size_t)n);
    if (n > 0) env->GetIntArrayRegion(arr, 0, n, reinterpret_cast<jint*>(v.data()));
    return v;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_magi_app_v6_NativeBridge_nativeAbiVersion(JNIEnv*, jclass) {
    return 3;
}

// [Stage3] SA チャンク（SaOptimizer PhaseA の冷却ラダー1本）。cur/best は S*T の平坦配列で、
// status==0 のときのみ書き戻される（in/out）。戻り値 long[6] = [status, curScore, bestScore,
// iters, improvedInChunk, tailIters]。パラメータ異常は status=3。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeSaChunk(
    JNIEnv* env, jclass, jlong handle, jintArray curArr, jintArray bestArr,
    jlong bestScoreIn, jlong seed, jdouble t0, jdouble tf, jdouble alpha, jint chain) {
    jlongArray outArr = env->NewLongArray(6);
    long long out[6] = {3, -1, -1, 0, 0, 0};
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    if (p != nullptr && t0 > 0 && tf > 0 && tf <= t0 && alpha > 0 && alpha < 1 && chain >= 1) {
        jsize n1 = env->GetArrayLength(curArr);
        jsize n2 = env->GetArrayLength(bestArr);
        if (n1 == p->S * p->T && n2 == n1) {
            std::vector<int> cur((size_t)n1), best((size_t)n1);
            env->GetIntArrayRegion(curArr, 0, n1, reinterpret_cast<jint*>(cur.data()));
            env->GetIntArrayRegion(bestArr, 0, n1, reinterpret_cast<jint*>(best.data()));
            runSaChunk(*p, cur.data(), best.data(), (long long)bestScoreIn,
                       (uint64_t)seed, (double)t0, (double)tf, (double)alpha, (int)chain, out);
            if (out[0] == 0) {
                env->SetIntArrayRegion(curArr, 0, n1, reinterpret_cast<jint*>(cur.data()));
                env->SetIntArrayRegion(bestArr, 0, n1, reinterpret_cast<jint*>(best.data()));
            }
        }
    }
    jlong jv[6];
    for (int x = 0; x < 6; x++) jv[x] = (jlong)out[x];
    env->SetLongArrayRegion(outArr, 0, 6, jv);
    return outArr;
}

// Problem の平坦化データからネイティブ側の MagiProblem を構築してハンドル(jlong)を返す。
// 配列レイアウトは NativeEval.kt の flatten と厳密に一致させること。
extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_NativeBridge_nativeCreateProblem(
    JNIEnv* env, jclass,
    jintArray metaArr, jintArray staffArr, jintArray canDoArr, jintArray wishArr,
    jintArray needsArr, jintArray rangesArr, jintArray consArr, jintArray c3Arr,
    jintArray bucketArr) {
    auto meta = readIntArray(env, metaArr);
    if (meta.size() < 7) return 0;
    auto* p = new MagiProblem();
    p->S = meta[0]; p->T = meta[1]; p->K = meta[2]; p->G = meta[3];
    p->restIdx = meta[4]; p->dow0 = meta[5]; p->use2 = meta[6] != 0;
    const int S = p->S, T = p->T, K = p->K, G = p->G;
    if (S <= 0 || T <= 0 || K <= 0 || G < 0) { delete p; return 0; }

    auto staff = readIntArray(env, staffArr);          // sgrp(S) + ssk(S)
    auto canDo = readIntArray(env, canDoArr);          // S*K (0/1)
    auto wish = readIntArray(env, wishArr);            // S*T
    auto needs = readIntArray(env, needsArr);          // need1(K*T) + need2(K*T)
    auto ranges = readIntArray(env, rangesArr);        // lo(S*K) + hi(S*K) + apt(S*K)
    auto cons = readIntArray(env, consArr);
    auto c3 = readIntArray(env, c3Arr);
    auto bucket = readIntArray(env, bucketArr);        // per g: len, shifts...
    if ((int)staff.size() != 2 * S || (int)canDo.size() != S * K || (int)wish.size() != S * T ||
        (int)needs.size() != 2 * K * T || (int)ranges.size() != 3 * S * K) { delete p; return 0; }

    p->sgrp.assign(staff.begin(), staff.begin() + S);
    p->ssk.assign(staff.begin() + S, staff.end());
    p->canDo.resize((size_t)S * K);
    for (int x = 0; x < S * K; x++) p->canDo[x] = canDo[x] != 0 ? 1 : 0;
    p->wish = std::move(wish);
    p->need1.assign(needs.begin(), needs.begin() + (size_t)K * T);
    p->need2.assign(needs.begin() + (size_t)K * T, needs.end());
    p->rangeLo.assign(ranges.begin(), ranges.begin() + (size_t)S * K);
    p->rangeHi.assign(ranges.begin() + (size_t)S * K, ranges.begin() + 2 * (size_t)S * K);
    p->apt.assign(ranges.begin() + 2 * (size_t)S * K, ranges.end());

    // cons レイアウト: [n1,(d1,si,d2)*] [n2,(si,c)*] [n41,(g,s,l,u)*] [n42,(g1,s1,g2,s2)*]
    //                  [n41s,(g,s,l,u)*] [n42s,(g1,s1,g2,s2)*]
    size_t idx = 0;
    auto next = [&]() -> int { return idx < cons.size() ? cons[idx++] : 0; };
    int n1 = next();
    for (int r = 0; r < n1; r++) { C1r c{next(), next(), next()}; p->cons1.push_back(c); }
    int n2 = next();
    for (int r = 0; r < n2; r++) { C2r c{next(), next()}; p->cons2.push_back(c); }
    int n41 = next();
    for (int r = 0; r < n41; r++) { C41r c{next(), next(), next(), next()}; p->cons41.push_back(c); }
    int n42 = next();
    for (int r = 0; r < n42; r++) { C42r c{next(), next(), next(), next()}; p->cons42.push_back(c); }
    int n41s = next();
    for (int r = 0; r < n41s; r++) { C41r c{next(), next(), next(), next()}; p->cons41s.push_back(c); }
    int n42s = next();
    for (int r = 0; r < n42s; r++) { C42r c{next(), next(), next(), next()}; p->cons42s.push_back(c); }

    // c3 レイアウト: 4族順(c3, c3n, c3m, c3mn) に [count, (len, seq...)*count]
    size_t ci = 0;
    auto cnext = [&]() -> int { return ci < c3.size() ? c3[ci++] : 0; };
    std::vector<C3r>* fams[4] = {&p->cons3, &p->cons3n, &p->cons3m, &p->cons3mn};
    for (auto* fam : fams) {
        int cnt = cnext();
        for (int r = 0; r < cnt; r++) {
            int len = cnext();
            C3r row;
            row.seq.reserve((size_t)len);
            for (int l = 0; l < len; l++) row.seq.push_back(cnext());
            row.singleRun = !row.seq.empty();
            for (size_t l = 1; l < row.seq.size(); l++) if (row.seq[l] != row.seq[0]) { row.singleRun = false; break; }
            fam->push_back(std::move(row));
        }
    }

    // bucket レイアウト: per g: [len, shifts...]。members は sgrp から導出。
    p->bucket.resize((size_t)G);
    size_t bi = 0;
    auto bnext = [&]() -> int { return bi < bucket.size() ? bucket[bi++] : 0; };
    for (int g = 0; g < G; g++) {
        int len = bnext();
        p->bucket[g].reserve((size_t)len);
        for (int l = 0; l < len; l++) p->bucket[g].push_back(bnext());
    }
    p->members.resize((size_t)G);
    for (int i = 0; i < S; i++) {
        int g = p->sgrp[i];
        if (g >= 0 && g < G) p->members[g].push_back(i);
    }
    // fair の O(1) 判定用と opBlockFill 用の導出テーブル。
    p->bucketHas.assign((size_t)G * K, 0);
    for (int g = 0; g < G; g++) for (int k : p->bucket[g]) if (k >= 0 && k < K) p->bucketHas[(size_t)g * K + k] = 1;
    p->staffForShift.resize((size_t)K);
    for (int i = 0; i < S; i++) {
        int g = p->sgrp[i];
        if (g < 0 || g >= G) continue;
        for (int k : p->bucket[g]) if (k >= 0 && k < K) p->staffForShift[k].push_back(i);
    }

    return reinterpret_cast<jlong>(p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_NativeBridge_nativeDestroyProblem(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<MagiProblem*>(handle);
}

// フル評価。schedule は S*T の平坦配列。戻り値 long[2] = {hard1, soft}（Evaluator.fullEvalParts と同値）。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_NativeBridge_nativeFullEval(JNIEnv* env, jclass, jlong handle, jintArray schedArr) {
    auto* p = reinterpret_cast<MagiProblem*>(handle);
    jlongArray out = env->NewLongArray(2);
    long long v[2] = {-1, -1};
    if (p != nullptr) {
        jsize n = env->GetArrayLength(schedArr);
        if (n == p->S * p->T) {
            std::vector<int> a((size_t)n);
            env->GetIntArrayRegion(schedArr, 0, n, reinterpret_cast<jint*>(a.data()));
            fullEvalParts(*p, a.data(), v);
        }
    }
    jlong jv[2] = {(jlong)v[0], (jlong)v[1]};
    env->SetLongArrayRegion(out, 0, 2, jv);
    return out;
}
