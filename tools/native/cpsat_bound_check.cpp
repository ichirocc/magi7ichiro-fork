// CP-SAT の重みの下限の検査（docs/cpsat_formal.md「下限の検査と fair の式」）: 定義域のどの盤面でも「盤面を固定したモデルの目的（重み付き＋fair）
//   の最小 ≤ チェッカーの weightedScore」か（fair 以外は等号も）。実データの盤面と、定義域の中で無作為に書き換えた盤面で確かめる。
//   組み方は cpsat_bench.cpp と同じ。cpsat_bound_check <flat> [boards=200] [seed=1] [priority=1] [extra.flat ...]（extra＝同じ問題の別の盤面も起点に）
//   出力: 違反（モデル > 実際）の件数・fair の差（実際 − モデル）の分布。違反があれば終了コード 1。
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include "magi_cpsat.h"

static void setPriority(MagiProblem& p) {   // Kotlin ObjectiveWeights の優先順位（cpsat_bench.cpp と同じ）
    p.w.low = p.w.c1 = p.w.high = p.w.c3mn = 200;
    p.w.c3 = p.w.c3m = p.w.c41 = p.w.c42 = p.w.c41s = p.w.c42s = p.w.gap = 20;
    p.w.weekly = 1; p.w.covO = p.w.fair = 50; p.w.apt = p.w.c2 = 20;
}

int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> base;
    if (argc < 2 || !loadFlat(argv[1], p, base)) return 2;
    const int boards = argc > 2 ? atoi(argv[2]) : 200;
    const unsigned long long seed = argc > 3 ? strtoull(argv[3], nullptr, 10) : 1ULL;
    if (argc <= 4 || atoi(argv[4]) == 1) setPriority(p);
    const int S = p.S, T = p.T, K = p.K;
    std::vector<std::vector<int>> starts{base};
    for (int a = 5; a < argc; a++) {
        MagiProblem q; std::vector<int> b;
        if (loadFlat(argv[a], q, b) && b.size() == base.size()) starts.push_back(b);
    }
    magi_cpsat::HardW hw;
    // エンジンの採点: [必須の件数, 必須の重み込みの重み付き（fair 込み）, fair の重み]。
    auto actual = [&](const std::vector<int>& b, long long& hard, long long& w, long long& fairW) {
        long long out[2], bd[kBreakdownCount]; fullEvalParts(p, b.data(), out, bd);
        hard = out[0];
        fairW = bd[17] * p.w.fair;
        w = out[1] + bd[15] * hw.groupViol + bd[10] * hw.covU + bd[3] * hw.c3n + bd[19] * hw.c3w + bd[12] * hw.pref + bd[20] * hw.extWish;
    };
    // 書き換えに使う定義域＝magi_cpsat::Model::makeVars と同じ（置けるシフトから拡張希望の禁止を除き、入力の値を足す。手動固定は動かさない。
    //   希望は希望か入力の値）。
    std::vector<std::vector<int>> dom((size_t)S * T);
    for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
        const size_t c = (size_t)i * T + j;
        const int h = base[c];
        if (wishLockedN(p, i, j)) {   // 手動固定は動かさない。希望は希望か入力の値（規則 A、3.727.103）
            if ((p.pin.empty() || p.pin[c] < 0) && h >= 0 && h < K && h != p.wish[c]) dom[c] = {p.wish[c], h};
            continue;
        }
        for (int k : p.allowed[i]) if (!p.extBanned(i, j, k)) dom[c].push_back(k);
        if (h >= 0 && h < K && std::find(dom[c].begin(), dom[c].end(), h) == dom[c].end()) dom[c].push_back(h);
    }
    std::mt19937_64 rng(seed);
    long long checked = 0, violations = 0, parityMiss = 0, notOptimal = 0, skipped = 0;
    long long gapSum = 0, gapMax = 0, exact = 0;
    std::vector<long long> gaps;
    for (int n = 0; n < boards; n++) {
        // 起点を選び、書き換えの割合を 0（起点そのまま）〜1（全部を無作為）で変える。
        std::vector<int> b = starts[(size_t)n % starts.size()];
        const double q = n < (int)starts.size() ? 0.0 : std::vector<double>{0.02, 0.1, 0.3, 1.0}[(size_t)n % 4];
        for (size_t c = 0; c < b.size(); c++) {
            if (dom[c].empty() || std::uniform_real_distribution<double>(0, 1)(rng) >= q) continue;
            b[c] = dom[c][(size_t)(rng() % dom[c].size())];
        }
        const bool pinned = n % 2 == 1;
        if (pinned) {   // 探索が届く盤面に揃える: 希望・手動固定のマスはその値、未割当のマスは定義域の値（無ければ makeVars と同じ値）
            for (int i = 0; i < S; i++) for (int j = 0; j < T; j++) {
                const size_t c = (size_t)i * T + j;
                if (wishLockedN(p, i, j)) { if (dom[c].empty() || std::find(dom[c].begin(), dom[c].end(), b[c]) == dom[c].end()) b[c] = lockToN(p, i, j); }
                else if (b[c] < 0) b[c] = dom[c].empty() ? p.restIdx : dom[c][(size_t)(rng() % dom[c].size())];
            }
        }
        long long h0, w0, f0; actual(b, h0, w0, f0);
        // 奇数番目は solve と同じ定義域（入力＝起点の盤面）でモデルを組み、盤面を制約で縛る（母集団が盤面で変わり得る群の式も通る）。
        //   偶数番目は全マスを定数にして組む（fixToHint）。
        magi_cpsat::Options o; o.fixToHint = !pinned; o.workers = 1; o.maxSeconds = 30;
        magi_cpsat::Model m(p, hw);
        m.build(pinned ? base : b, o);
        if (pinned && !m.pinBoard(b)) { skipped++; continue; }
        m.cp().Minimize(m.objective());
        magi_cpsat::sat::Model env;
        env.Add(magi_cpsat::sat::NewSatParameters(magi_cpsat::params(o, o.maxSeconds)));
        const auto r = magi_cpsat::sat::SolveCpModel(m.cp().Build(), &env);
        checked++;
        if (r.status() != magi_cpsat::sat::OPTIMAL) {
            notOptimal++;
            // 最適まで解けなければ、目的の下限（best_objective_bound）が実際以下かだけを見る。
            if (std::llround(r.best_objective_bound()) > w0) { violations++; printf("VIOLATION(bound) board#%d q=%.2f model-bound %lld > actual %lld\n", n, q, std::llround(r.best_objective_bound()), w0); }
            continue;
        }
        const long long v = std::llround(r.objective_value());
        const long long wNoFair = magi_cpsat::sat::SolutionIntegerValue(r, m.weighted());
        const long long hModel = magi_cpsat::sat::SolutionIntegerValue(r, m.hardCount());
        if (wNoFair != w0 - f0 || hModel != h0) {
            parityMiss++;
            printf("PARITY MISS board#%d q=%.2f model hard=%lld weighted(noFair)=%lld | engine hard=%lld weighted(noFair)=%lld\n", n, q, hModel, wNoFair, h0, w0 - f0);
        }
        if (v > w0) { violations++; printf("VIOLATION board#%d q=%.2f model %lld > actual %lld (fair: actual %lld, approx %lld)\n", n, q, v, w0, f0, v - wNoFair); }
        const long long g = f0 - (v - wNoFair);   // fair の重みのうち近似が拾えていない分
        gaps.push_back(g); gapSum += g; gapMax = std::max(gapMax, g); if (g == 0) exact++;
    }
    std::sort(gaps.begin(), gaps.end());
    auto pct = [&](double x) { return gaps.empty() ? 0LL : gaps[(size_t)std::min<double>(gaps.size() - 1, x * gaps.size())]; };
    printf("BOUNDCHECK %s boards=%lld（定義域外で飛ばした %lld） violations=%lld parityMiss=%lld notOptimal=%lld | fair の取りこぼし（重み）: 平均 %.1f・中央 %lld・90%% %lld・最大 %lld・一致 %lld/%zu\n",
           argv[1], checked, skipped, violations, parityMiss, notOptimal, gaps.empty() ? 0.0 : (double)gapSum / gaps.size(), pct(0.5), pct(0.9), gapMax, exact, gaps.size());
    return violations == 0 && parityMiss == 0 ? 0 : 1;
}
