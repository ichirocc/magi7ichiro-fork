// 探索 4 本（焼きなまし・LAHC・ALNS・研磨）を固定の種で回し、結果の盤面ハッシュと所要を出す（出力不変の確認と前後の速さの比較、
//   docs/delta_cpp_unification.md 付録 D）。旧版と新版を同じ入力で走らせ、ハッシュが同じなら探索動作は不変。
//   g++ -O2 -mpopcnt -DNDEBUG -std=c++20 -DMAGI_HOST_TEST -I app/src/main/cpp -I tools/native tools/native/runner_hash.cpp -o runner_hash
//   runner_hash <state.flat>    （CH_SEEDS=種の数、既定 3）
#define main parity_main
#include "host_parity_bench.cpp"
#undef main
#include <chrono>
static uint64_t h64(const std::vector<int>& v, uint64_t h = 1469598103934665603ULL) { for (int x : v) h = (h ^ (uint64_t)(x + 7)) * 1099511628211ULL; return h; }
int main(int argc, char** argv) {
    MagiProblem p; std::vector<int> board;
    if (!loadFlat(argv[1], p, board)) return 2;
    const int n = p.S * p.T;
    uint64_t H = 0; double tSa = 0, tLa = 0, tAl = 0, tPo = 0;
    for (uint64_t seed = 1; seed <= (getenv("CH_SEEDS") ? atoi(getenv("CH_SEEDS")) : 3); seed++) {
        auto t0 = std::chrono::steady_clock::now();
        std::vector<int> cur = board, best = board; long long out[6];
        long long bs = fullEvalCombined(p, board.data());
        for (int c = 0; c < 20; c++) { runSaChunk(p, cur.data(), best.data(), bs, seed * 100 + c, 50.0, 0.5, 0.95, 400, out); bs = out[2]; H = H * 31 + out[0] * 7 + out[1] + out[2]; }
        H ^= h64(cur) ^ h64(best);
        auto t1 = std::chrono::steady_clock::now();
        LahcState ls(p, board.data(), seed, 200); long long o5[5];
        for (int c = 0; c < 20; c++) { runLahcChunk(ls, 20000, o5); H = H * 31 + o5[0] + o5[1] + o5[2]; }
        H ^= h64(ls.st.a) ^ h64(ls.bestSol);
        auto t2 = std::chrono::steady_clock::now();
        AlnsState as(p, board.data(), seed, (int)(seed % 3), (int)(seed % 2), 0.5);
        for (int c = 0; c < 20; c++) { runAlnsChunk(as, 5000, c / 20.0, out); H = H * 31 + out[0] + out[1] + out[2]; }
        H ^= h64(as.st.a) ^ h64(as.bestSol);
        auto t3 = std::chrono::steady_clock::now();
        PolishState ps(p, board.data(), seed);
        for (int c = 0; c < 20; c++) { runPolishChunk(ps, 5000, o5); H = H * 31 + o5[0] + o5[1] + o5[2]; }
        H ^= h64(ps.st.a) ^ h64(ps.bestSol);
        auto t4 = std::chrono::steady_clock::now();
        auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
        tSa += ms(t0, t1); tLa += ms(t1, t2); tAl += ms(t2, t3); tPo += ms(t3, t4);
    }
    printf("%016llx SA %.0fms LAHC %.0fms ALNS %.0fms Polish %.0fms\n", (unsigned long long)H, tSa, tLa, tAl, tPo);
}
