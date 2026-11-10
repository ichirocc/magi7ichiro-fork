// CP-SAT の JNI 入口（MAGI_WITH_CPSAT のときだけ magi_native.cpp の末尾から include）。Kotlin `CpsatBridge` が呼ぶ。
//   作成 → 解く（呼び出し側のスレッドで終わるまで戻らない）／別スレッドから最良と下限を読む・止める → 破棄。
//   問題のハンドル（nativeCreateProblem）はジョブより長く生かすこと（ジョブは MagiProblem を参照するだけ）。
#include "magi_cpsat.h"
#include <mutex>

namespace {
struct CpsatJob {
    const MagiProblem* p;
    std::atomic<bool> stop{false};
    std::mutex mu;
    std::vector<int> best;
    long long version = 0, hard = -1, weighted = -1, weightedBound = -1, hardBound = -1;
    bool hardProven = false;
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatCreate(JNIEnv*, jclass, jlong problemHandle) {
    if (problemHandle == 0) return 0;
    auto* j = new CpsatJob();
    j->p = reinterpret_cast<MagiProblem*>(problemHandle);
    return reinterpret_cast<jlong>(j);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatDestroy(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<CpsatJob*>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatStop(JNIEnv*, jclass, jlong h) {
    if (h != 0) reinterpret_cast<CpsatJob*>(h)->stop.store(true);
}

// 解く。戻り値 [status, hard, weighted, hardBound, weightedBound, hardProven, wallMillis]。最良の盤面は poll で読む。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatSolve(JNIEnv* env, jclass, jlong h, jintArray hintArr,
                                                 jdouble maxSeconds, jint workers, jlong seed, jlong hardCap) {
    jlongArray out = env->NewLongArray(7);
    if (h == 0 || out == nullptr) return out;
    auto* job = reinterpret_cast<CpsatJob*>(h);
    const MagiProblem& p = *job->p;
    std::vector<int> hint((size_t)p.S * p.T, -1);
    if (hintArr != nullptr && env->GetArrayLength(hintArr) == (jsize)hint.size())
        env->GetIntArrayRegion(hintArr, 0, (jsize)hint.size(), hint.data());
    magi_cpsat::Options o;
    o.maxSeconds = maxSeconds; o.workers = workers; o.randomSeed = seed; o.hardCap = hardCap; o.stop = &job->stop;
    // 少ないワーカーでも下限を担う役を必ず入れる（2 本: 強い線形緩和 max_lp と下限を詰める core。実データ 6 件でギャップが既定の 4 本より小さい、
    //   docs/cpsat_formal.md）。3 本目以降は解を探す役。
    static const char* const kRoles[] = {"max_lp", "core", "default_lp", "quick_restart", "reduced_costs", "pseudo_costs", "no_lp", "lb_tree_search"};
    for (int r = 0; r < std::min<int>(std::max(1, (int)workers), 8); r++) o.subsolvers.push_back(kRoles[r]);
    o.onHard = [job](long long hard, long long bound, bool proven) {
        std::lock_guard<std::mutex> g(job->mu); job->hardBound = bound; job->hardProven = proven;
    };
    o.onBound = [job](long long b) { std::lock_guard<std::mutex> g(job->mu); if (b > job->weightedBound) { job->weightedBound = b; job->version++; } };
    o.onSolution = [job](const std::vector<int>& b, long long hard, long long weighted) {
        std::lock_guard<std::mutex> g(job->mu);
        if (job->hard < 0 || hard < job->hard || (hard == job->hard && weighted < job->weighted)) {
            job->best = b; job->hard = hard; job->weighted = weighted; job->version++;
        }
        return !job->stop.load();
    };
    auto r = magi_cpsat::solve(p, hint, o);
    {
        std::lock_guard<std::mutex> g(job->mu);
        if (!r.board.empty() && (job->hard < 0 || r.hard < job->hard || (r.hard == job->hard && r.weighted < job->weighted))) {
            job->best = r.board; job->hard = r.hard; job->weighted = r.weighted; job->version++;
        }
        if (r.weightedBound > job->weightedBound) { job->weightedBound = r.weightedBound; job->version++; }
        if (r.hardBound >= 0) { job->hardBound = r.hardBound; job->hardProven = r.hardProven; }
    }
    jlong vals[7] = {r.status, r.hard, r.weighted, r.hardBound, r.weightedBound, r.hardProven ? 1 : 0, (jlong)(r.wallSeconds * 1000)};
    env->SetLongArrayRegion(out, 0, 7, vals);
    return out;
}

// 読む。戻り値 [version, hard, weighted, weightedBound, hardBound, hardProven]。version が変わっていて out の長さが S*T なら盤面も写す。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatPoll(JNIEnv* env, jclass, jlong h, jlong knownVersion, jintArray outBoard) {
    jlongArray out = env->NewLongArray(6);
    if (h == 0 || out == nullptr) return out;
    auto* job = reinterpret_cast<CpsatJob*>(h);
    std::lock_guard<std::mutex> g(job->mu);
    if (job->version != knownVersion && outBoard != nullptr && !job->best.empty() &&
        env->GetArrayLength(outBoard) == (jsize)job->best.size())
        env->SetIntArrayRegion(outBoard, 0, (jsize)job->best.size(), job->best.data());
    jlong vals[6] = {job->version, job->hard, job->weighted, job->weightedBound, job->hardBound, job->hardProven ? 1 : 0};
    env->SetLongArrayRegion(out, 0, 6, vals);
    return out;
}

// 必須違反の原因（S4）。戻り値 [核の数, 各核: minimal(0/1), 件数, 件数×(kind, i, j, k, rule)]。
extern "C" JNIEXPORT jintArray JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatExplain(JNIEnv* env, jclass, jlong problemHandle, jintArray inputArr,
                                                   jint maxCores, jdouble secondsPerSolve, jint workers, jdouble totalSeconds) {
    std::vector<jint> flat{0};
    if (problemHandle != 0) {
        const MagiProblem& p = *reinterpret_cast<MagiProblem*>(problemHandle);
        std::vector<int> input((size_t)p.S * p.T, -1);
        if (inputArr != nullptr && env->GetArrayLength(inputArr) == (jsize)input.size())
            env->GetIntArrayRegion(inputArr, 0, (jsize)input.size(), input.data());
        magi_cpsat::HardExplainer ex(p, input);
        const auto cores = ex.explain(maxCores, secondsPerSolve, workers, totalSeconds);
        flat[0] = (jint)cores.size();
        for (const auto& c : cores) {
            flat.push_back(c.minimal ? 1 : 0); flat.push_back((jint)c.causes.size());
            for (const auto& x : c.causes) { flat.push_back(x.kind); flat.push_back(x.i); flat.push_back(x.j); flat.push_back(x.k); flat.push_back(x.rule); }
        }
    }
    jintArray out = env->NewIntArray((jsize)flat.size());
    if (out != nullptr) env->SetIntArrayRegion(out, 0, (jsize)flat.size(), flat.data());
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magi_app_v6_CpsatBridge_nativeCpsatAvailable(JNIEnv*, jclass) { return JNI_TRUE; }
