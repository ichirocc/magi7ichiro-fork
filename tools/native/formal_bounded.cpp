// 有界の全数検証（small-scope）: 小さい問題の「すべての盤面 × すべての 1 マスの書き換え」で実際の C++ コードを走らせ、
//   ①差分評価（SaChunk::deltaApply、規則ごとの表を使う版）の点数 == 全体評価（fullEvalCombined）
//   ②（MAGI_WITH_CPSAT のとき）CP-SAT モデルの採点（盤面を固定）== チェッカー同等の fullEvalParts（必須の重み込み・fair を除く）
//   を確かめる。乱数の試し打ちと違い、範囲内は漏れなく見る。
//   g++ -O2 -std=c++20 -DMAGI_HOST_TEST -I app/src/main/cpp -I tools/native tools/native/formal_bounded.cpp -o formal_bounded
//   formal_bounded [configs=200]
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include <climits>
#ifdef MAGI_WITH_CPSAT
#include "magi_cpsat.h"
#endif

// 合成問題に手動固定と拡張希望の禁止を足す（buildProblem は持たない）。
static void addPinsAndExtBan(MagiProblem& p, uint64_t seed) {
    std::mt19937_64 rng(seed ^ 0x9e3779b97f4a7c15ULL);
    p.pin.assign((size_t)p.S * p.T, -1);
    p.extBan.assign((size_t)p.S * p.T * p.K, 0);
    for (int i = 0; i < p.S; i++) for (int j = 0; j < p.T; j++) {
        if (rng() % 7 == 0) p.pin[(size_t)i * p.T + j] = (int)(rng() % p.K);
        for (int k = 0; k < p.K; k++) if (rng() % 9 == 0) p.extBan[((size_t)i * p.T + j) * p.K + k] = 1;
    }
    finalizeProblem(p);
}

#ifdef MAGI_WITH_CPSAT
// 盤面が説明の仮定 1 件を守っているか（magi_cpsat::HardExplainer の符号化とは独立に、意味どおりに書く）。
static bool holds(const MagiProblem& p, const std::vector<int>& b, const magi_cpsat::Cause& c) {
    const int T = p.T;
    switch (c.kind) {
        case magi_cpsat::kWish: {
            if (b[(size_t)c.i * T + c.j] != c.k) return false;
            if (c.j == 0) return true;
            for (const auto& r : p.cons3w) if (r.wishK == c.k && b[(size_t)c.i * T + c.j - 1] == r.prevK) return false;
            return true;
        }
        case magi_cpsat::kPin: return b[(size_t)c.i * T + c.j] == c.k;
        case magi_cpsat::kNeed: { int got = 0; for (int i = 0; i < p.S; i++) got += b[(size_t)i * T + c.j] == c.k; return got >= c.rule; }
        case magi_cpsat::kForbidden: {
            const auto& seq = p.cons3n[(size_t)c.rule].seq; const int D = (int)seq.size();
            for (int i = 0; i < p.S; i++) for (int j = 0; j + D <= T; j++) {
                bool m = true; for (int l = 0; l < D && m; l++) m = b[(size_t)i * T + j + l] == seq[(size_t)l];
                if (m) return false;
            }
            return true;
        }
        case magi_cpsat::kOutside:
            for (int j = 0; j < T; j++) { const int k = b[(size_t)c.i * T + j]; if (!p.cd(c.i, k) || p.extBanned(c.i, j, k)) return false; }
            return true;
    }
    return false;
}
#endif

static bool nextBoard(std::vector<int>& b, int lo, int hi) {
    for (size_t x = 0; x < b.size(); x++) { if (b[x] < hi) { b[x]++; return true; } b[x] = lo; }
    return false;
}

int main(int argc, char** argv) {
    const int configs = argc > 1 ? atoi(argv[1]) : 200;
    long long boards = 0, moves = 0, bad = 0, cpBoards = 0, cpBad = 0, exCases = 0, exBad = 0, exNotMin = 0, coreChecks = 0;
    struct Shape { int S, T, K, G; };
    const Shape shapes[] = {{2, 4, 3, 2}, {3, 3, 3, 2}, {2, 5, 3, 1}};
    for (int c = 0; c < configs; c++) {
        const Shape sh = shapes[c % 3];
        MagiProblem p = buildProblem(sh.S, sh.T, sh.K, sh.G, 1000 + c, c % 2 == 0);
        addPinsAndExtBan(p, 1000 + c);
        const int n = p.S * p.T;
        // ① 差分評価: 値は -1（未割当）〜K-1（8 セル以下の形）、それより大きい形は 0〜K-1。各盤面から全セル×全値へ書き換え→戻す。
        const int lo = n <= 8 ? -1 : 0;
        const bool explainOnly = getenv("FB_EXPLAIN_ONLY") != nullptr;
        std::vector<int> b(n, lo);
        if (!explainOnly) do {
            boards++;
            SaChunk st(p, b.data(), 1);
            const long long base = st.score;
            if (base != fullEvalCombined(p, b.data())) { bad++; continue; }
            for (int x = 0; x < n; x++) {
                const int i = x / p.T, j = x % p.T, old = b[x];
                for (int v = lo; v < p.K; v++) {
                    if (v == old) continue;
                    moves++;
                    st.deltaApply(i, j, v);
                    if (st.score != fullEvalCombined(p, st.a.data())) { if (bad++ < 5) printf("DELTA MISMATCH config=%d cell=(%d,%d) %d->%d\n", c, i, j, old, v); }
                    st.deltaApply(i, j, old);
                    if (st.score != base) { if (bad++ < 5) printf("REVERT MISMATCH config=%d cell=(%d,%d)\n", c, i, j); }
                }
            }
        } while (nextBoard(b, lo, p.K - 1));
#ifdef MAGI_WITH_CPSAT
        // ② CP-SAT の採点: 値は 0〜K-1 の全盤面（10 構成に 1 つ・小さい形だけ。1 盤面ごとに 1 回解く）。
        if (!explainOnly && c % 10 == 0 && n <= 8) {
            magi_cpsat::HardW hw;
            std::vector<int> cb(n, 0);
            do {
                long long out[2], bd[21]; fullEvalParts(p, cb.data(), out, bd);
                const long long hw2 = bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish;
                const long long want = out[1] - bd[17] * p.w.fair + hw2;
                magi_cpsat::Options o; o.fixToHint = true; o.workers = 1; o.maxSeconds = 5;
                auto r = magi_cpsat::solve(p, cb, o, hw);
                cpBoards++;
                if (r.hard != out[0] || r.weighted != want) { if (cpBad++ < 5) printf("CPSAT MISMATCH config=%d hard %lld/%lld weighted %lld/%lld\n", c, r.hard, out[0], r.weighted, want); }
            } while (nextBoard(cb, 0, p.K - 1));
        }
        // ③ 必須違反の原因（HardExplainer、小さい形すべて）: 互いに素な核の数 <= 総当たりの必須の最少（手動固定は守る・
        //   入力は全 0）、最少が 1 以上なら核は 1 つ以上。核ごとに 1 件は外す＝必須が 1 件は出る、の全数での確認。
        if (n <= 8) {
            std::vector<int> cb(n, 0);
            const std::vector<int> input(n, 0);
            long long minHard = LLONG_MAX;
            do {
                bool ok = true;
                for (int x = 0; x < n && ok; x++) {
                    const int i = x / p.T, j = x % p.T, v = cb[x], w = p.wish[x], pin = p.pin[x];
                    if (pin >= 0) { ok = v == pin; continue; }
                    bool inDom = v == w || v == input[x];
                    for (int k : p.allowed[i]) if (k == v && !p.extBanned(i, j, k)) inDom = true;
                    ok = inDom;
                }
                if (!ok) continue;
                long long out[2], bd[21]; fullEvalParts(p, cb.data(), out, bd);
                minHard = std::min(minHard, out[0]);
            } while (nextBoard(cb, 0, p.K - 1));
            magi_cpsat::HardExplainer ex(p, input);
            const auto cores = ex.explain(n * 4, 5.0, 1);
            exCases++;
            if ((long long)cores.size() > minHard || (minHard > 0 && cores.empty())) {
                if (exBad++ < 5) printf("EXPLAIN MISMATCH config=%d cores=%zu minHard=%lld\n", c, cores.size(), minHard);
            }
            for (const auto& core : cores) if (!core.minimal) exNotMin++;
            // 核を CP-SAT と独立に総当たりで確かめる（値の範囲は説明器と同じ）: ①核をすべて守る盤面は無い ②どの 1 件を外しても守れる盤面がある。
            auto inDomain = [&](const std::vector<int>& bb) {
                for (int x = 0; x < n; x++) {
                    const int i = x / p.T, v = bb[x];
                    bool ok = v == p.wish[x] || v == p.pin[x] || v == input[x];
                    for (int k : p.allowed[i]) if (k == v && !p.extBanned(i, x % p.T, k)) ok = true;
                    if (!ok) return false;
                }
                return true;
            };
            for (const auto& core : cores) {
                std::vector<char> canDrop(core.causes.size(), 0);
                bool allHeld = false;
                std::vector<int> eb(n, 0);
                do {
                    if (!inDomain(eb)) continue;
                    int miss = -1, nmiss = 0;
                    for (size_t q = 0; q < core.causes.size(); q++) if (!holds(p, eb, core.causes[q])) { miss = (int)q; nmiss++; }
                    if (nmiss == 0) allHeld = true;
                    if (nmiss == 1) canDrop[(size_t)miss] = 1;
                } while (nextBoard(eb, 0, p.K - 1));
                coreChecks++;
                bool minimalOk = true; for (char d : canDrop) minimalOk = minimalOk && d;
                if (allHeld || (core.minimal && !minimalOk)) { if (exBad++ < 5) printf("CORE MISMATCH config=%d allHeld=%d minimal=%d\n", c, allHeld, minimalOk); }
            }
        }
#endif
    }
    printf("BOUNDED: configs=%d boards=%lld moves=%lld delta-mismatch=%lld | cpsat boards=%lld cpsat-mismatch=%lld | explain cases=%lld cores=%lld mismatch=%lld not-minimal=%lld\n",
           configs, boards, moves, bad, cpBoards, cpBad, exCases, coreChecks, exBad, exNotMin);
    return (bad || cpBad || exBad) ? 1 : 0;
}
