// Host-buildable parity + benchmark harness for magi_native.cpp.
//
//   g++ -O3 -std=c++17 -DMAGI_HOST_TEST -I app/src/main/cpp \
//       tools/native/host_parity_bench.cpp -o /tmp/magi_host && /tmp/magi_host
//
// Parity: builds synthetic MagiProblem fixtures, then over millions of random
//   single-cell moves checks that the incrementally maintained SaChunk.score
//   (delta) exactly equals a fresh scalar fullEvalCombined (the Kotlin oracle's
//   C++ mirror). This is the runtime self-consistency check, run offline and
//   exhaustively. Mismatch count MUST be 0.
// Bench: times raw deltaApply throughput (apply+revert) so bit-op changes can be
//   measured A/B via SaChunk.useBits.
#ifndef MAGI_HOST_TEST
#define MAGI_HOST_TEST
#endif
#include "magi_native.cpp"

#include <cstdio>
#include <chrono>
#include <random>
#include <algorithm>

// Fill the derived tables the JNI decode normally builds (members / bucketHas /
// staffForShift), so a hand-built MagiProblem behaves like a decoded one.
static void finalizeProblem(MagiProblem& p) {
    p.members.assign((size_t)p.G, {});
    for (int i = 0; i < p.S; i++) { int g = p.sgrp[i]; if (g >= 0 && g < p.G) p.members[g].push_back(i); }
    p.bucketHas.assign((size_t)p.G * p.K, 0);
    for (int g = 0; g < p.G; g++) for (int k : p.bucket[g]) if (k >= 0 && k < p.K) p.bucketHas[(size_t)g * p.K + k] = 1;
    p.staffForShift.assign((size_t)p.K, {});
    for (int i = 0; i < p.S; i++) { int g = p.sgrp[i]; if (g < 0 || g >= p.G) continue;
        for (int k : p.bucket[g]) if (k >= 0 && k < p.K) p.staffForShift[k].push_back(i); }
}

static C3r mkC3(std::initializer_list<int> seq) {
    C3r r; for (int x : seq) r.seq.push_back(x);
    r.singleRun = !r.seq.empty();
    for (size_t l = 1; l < r.seq.size(); l++) if (r.seq[l] != r.seq[0]) { r.singleRun = false; break; }
    return r;
}

// Build a varied but self-consistent problem. rest = shift 0.
static MagiProblem buildProblem(int S, int T, int K, int G, uint64_t seed, bool use2) {
    std::mt19937_64 rng(seed);
    auto ri = [&](int lo, int hi) { return lo + (int)(rng() % (uint64_t)(hi - lo + 1)); };
    MagiProblem p;
    p.S = S; p.T = T; p.K = K; p.G = G; p.restIdx = 0; p.dow0 = ri(0, 6); p.use2 = use2;
    p.sgrp.resize(S); p.ssk.resize(S);
    int Gs = std::max(1, G - 1);
    for (int i = 0; i < S; i++) { p.sgrp[i] = ri(0, G - 1); p.ssk[i] = ri(0, Gs - 1); }
    // bucket[g] = rest + a couple of random work shifts (1..K-1)
    p.bucket.assign((size_t)G, {});
    for (int g = 0; g < G; g++) {
        p.bucket[g].push_back(0);
        int nShift = std::min(K - 1, ri(1, 3));
        for (int s = 0; s < nShift; s++) { int k = ri(1, K - 1); if (std::find(p.bucket[g].begin(), p.bucket[g].end(), k) == p.bucket[g].end()) p.bucket[g].push_back(k); }
    }
    // canDo(i,k) = k in bucket[sgrp[i]]
    p.canDo.assign((size_t)S * K, 0);
    for (int i = 0; i < S; i++) for (int k : p.bucket[p.sgrp[i]]) p.canDo[(size_t)i * K + k] = 1;
    // wish: ~10% of cells wished to an allowed shift
    p.wish.assign((size_t)S * T, -1);
    for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) if (ri(0, 9) == 0) {
        const auto& b = p.bucket[p.sgrp[i]]; p.wish[(size_t)i * T + j] = b[ri(0, (int)b.size() - 1)];
    }
    // needs: a few critical shifts get P1 (and P2 if use2)
    p.need1.assign((size_t)K * T, -1); p.need2.assign((size_t)K * T, -1);
    for (int k = 1; k < K; k++) if (ri(0, 1) == 0) for (int j = 0; j < T; j++) {
        p.need1[(size_t)k * T + j] = ri(1, 2);
        if (use2 && ri(0, 1) == 0) p.need2[(size_t)k * T + j] = ri(1, 2);
    }
    // ranges + apt: sparse
    p.rangeLo.assign((size_t)S * K, INT32_MIN); p.rangeHi.assign((size_t)S * K, INT32_MAX); p.apt.assign((size_t)S * K, -1);
    for (int i = 0; i < S; i++) for (int k = 1; k < K; k++) if (p.canDo[(size_t)i * K + k]) {
        if (ri(0, 2) == 0) p.rangeLo[(size_t)i * K + k] = ri(1, 4);
        if (ri(0, 2) == 0) p.rangeHi[(size_t)i * K + k] = ri(3, 8);
        if (ri(0, 2) == 0) p.apt[(size_t)i * K + k] = ri(0, 6);
    }
    // cons1 windows
    int nC1 = ri(2, 5);
    for (int c = 0; c < nC1; c++) { int d1 = ri(2, std::min(T, 7)); p.cons1.push_back({d1, ri(1, K - 1), ri(1, std::max(1, d1 - 1))}); }
    int nC2 = ri(1, 4); for (int c = 0; c < nC2; c++) p.cons2.push_back({ri(1, K - 1), ri(1, T / 3 + 1)});
    int nC41 = ri(2, 5); for (int c = 0; c < nC41; c++) { int l = ri(0, 2); p.cons41.push_back({ri(0, G - 1), ri(1, K - 1), l, l + ri(0, 2)}); }
    int nC42 = ri(1, 4); for (int c = 0; c < nC42; c++) p.cons42.push_back({ri(0, G - 1), ri(1, K - 1), ri(0, G - 1), ri(1, K - 1)});
    int nC41s = ri(1, 3); for (int c = 0; c < nC41s; c++) { int l = ri(0, 2); p.cons41s.push_back({ri(0, Gs - 1), ri(1, K - 1), l, l + ri(0, 2)}); }
    int nC42s = ri(1, 3); for (int c = 0; c < nC42s; c++) p.cons42s.push_back({ri(0, Gs - 1), ri(1, K - 1), ri(0, Gs - 1), ri(1, K - 1)});
    // c3 families: mix of single-run and multi-shift sequences
    for (int c = 0; c < ri(1, 3); c++) p.cons3.push_back(mkC3({ri(1, K - 1), ri(1, K - 1)}));
    for (int c = 0; c < ri(1, 3); c++) { int s = ri(1, K - 1); p.cons3n.push_back(mkC3({s, s, s})); }   // forbidden triple-run
    for (int c = 0; c < ri(1, 2); c++) { int s = ri(1, K - 1); p.cons3m.push_back(mkC3({s, s})); }        // single-run want
    for (int c = 0; c < ri(1, 2); c++) p.cons3mn.push_back(mkC3({ri(1, K - 1), 0}));
    // [c3 窓マッチのビット化(3.174.0)を明示的に踏む] 多シフト D>=3 の非forbidden/forbidden を追加。
    //   これらは singleRun=false の窓マッチ経路＝新しい popcount パスの主対象。
    if (K >= 4) {
        p.cons3.push_back(mkC3({ri(1, K - 1), ri(1, K - 1), ri(1, K - 1)}));                       // 非forbidden 多シフト D=3
        p.cons3mn.push_back(mkC3({ri(1, K - 1), ri(1, K - 1), ri(1, K - 1), ri(1, K - 1)}));       // forbidden 多シフト D=4
    }
    finalizeProblem(p);
    return p;
}

static std::vector<int> randomBoard(const MagiProblem& p, std::mt19937_64& rng) {
    std::vector<int> a((size_t)p.S * p.T);
    for (int i = 0; i < p.S; i++) { const auto& b = p.bucket[p.sgrp[i]];
        for (int j = 0; j < p.T; j++) a[(size_t)i * p.T + j] = b[rng() % b.size()]; }
    return a;
}

int main() {
    struct Dim { int S, T, K, G; bool use2; const char* name; };
    Dim dims[] = {
        {10, 31, 6, 3, true,  "real-ish 10x31 K6 (P1+P2)"},
        {10, 31, 6, 3, false, "10x31 K6 (P1 only)"},
        {20, 31, 12, 4, true, "20x31 K12"},
        {8,  28, 5, 2, true,  "8x28 K5"},
        {40, 62, 20, 6, true, "40x62 K20 (large, still <=64)"},
    };
    long long totalMoves = 0, mismatches = 0;
    for (const Dim& d : dims) {
        for (uint64_t seed = 1; seed <= 6; seed++) {
            MagiProblem p = buildProblem(d.S, d.T, d.K, d.G, seed * 7919ULL, d.use2);
            std::mt19937_64 rng(seed * 104729ULL + 1);
            std::vector<int> board = randomBoard(p, rng);
            SaChunk st(p, board.data(), seed * 2654435761ULL);
            if (seed % 2 == 1) st.useBits = false;   // odd seed = scalar path, even seed = bit-op path
            // verify initial score
            if (st.score != fullEvalCombined(p, st.a.data())) { mismatches++; printf("INIT MISMATCH %s seed=%llu\n", d.name, (unsigned long long)seed); }
            const int MOVES = 40000;
            for (int m = 0; m < MOVES; m++) {
                int i = rng() % p.S, j = rng() % p.T;
                const auto& b = p.bucket[p.sgrp[i]];
                int nw = b[rng() % b.size()];
                int old = st.a[(size_t)i * p.T + j];
                st.deltaApply(i, j, nw);
                totalMoves++;
                long long full = fullEvalCombined(p, st.a.data());
                if (st.score != full) {
                    mismatches++;
                    if (mismatches <= 5) printf("MISMATCH %s seed=%llu move=%d (i=%d j=%d %d->%d) delta=%lld full=%lld\n",
                                                d.name, (unsigned long long)seed, m, i, j, old, nw, st.score, full);
                }
                // 25% chance revert (exercise reversibility)
                if ((rng() & 3) == 0) { st.deltaApply(i, j, old); totalMoves++;
                    long long f2 = fullEvalCombined(p, st.a.data());
                    if (st.score != f2) { mismatches++; if (mismatches <= 5) printf("REVERT MISMATCH %s\n", d.name); }
                }
            }
        }
    }
    printf("\nPARITY: %lld moves checked (scalar + bit-op paths), %lld mismatches\n", totalMoves, mismatches);

    // ---- benchmark: raw deltaApply throughput (apply only, no full recompute) ----
    auto benchOne = [](bool forceScalar) -> double {
        MagiProblem p = buildProblem(10, 31, 6, 3, 42, true);
        std::mt19937_64 rng(12345);
        std::vector<int> board = randomBoard(p, rng);
        SaChunk st(p, board.data(), 777);
        st.useBits = st.useBits && !forceScalar;   // force scalar path for A/B
        const long long N = 20'000'000;
        auto t0 = std::chrono::high_resolution_clock::now();
        for (long long m = 0; m < N; m++) {
            int i = rng() % p.S, j = rng() % p.T;
            const auto& b = p.bucket[p.sgrp[i]];
            st.deltaApply(i, j, b[rng() % b.size()]);
        }
        auto t1 = std::chrono::high_resolution_clock::now();
        double ns = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();
        return (double)N / (ns / 1e9);   // moves/sec
    };
    double scalar = benchOne(true);
    double bits   = benchOne(false);
    printf("BENCH deltaApply (10x31 K6): scalar %.2f M moves/s, bit-op %.2f M moves/s, speedup x%.2f\n",
           scalar / 1e6, bits / 1e6, bits / scalar);
    return mismatches == 0 ? 0 : 1;
}
