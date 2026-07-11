#include <jni.h>
#include <cstdint>
#include <cmath>
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
    return 2;
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
