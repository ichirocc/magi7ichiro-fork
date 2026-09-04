package com.magi.app.v6

import com.magi.app.model.MagiState
import java.util.Random
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native replacements for the Web-only post-optimization hotfix modules.
 *
 * The Web V6 calls HF80 -> HF67 -> HF66 -> HF70 after each optimizer run from
 * inside App.handleOptimize().  Android does not have window.HFxx modules, so the
 * passes live here as pure Kotlin and can be called from ViewModel/tests.
 */
data class HF80Result(
    val newSchedule: Array<IntArray>,
    val beforeHard: Int,
    val afterHard: Int,
    val beforeScore: Double,
    val afterScore: Double,
    val cycles: Int,
    val applied: Boolean,
    val reason: String,
    val logs: List<MirrorLog>,
)

data class HF67Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val swapsApplied: Int,
    val shortageSwaps: Int,
    val capacitySwaps: Int,
    val swapsRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF66Result(
    val newSchedule: Array<IntArray>,
    val beforeTotal: Int,
    val afterTotal: Int,
    val movesApplied: Int,
    val shortageMoves: Int,
    val capacityMoves: Int,
    val movesRollback: Int,
    val logs: List<MirrorLog>,
)

data class HF70Result(
    val anomalies: Int,
    val message: String,
    val advice: String,
    val logs: List<MirrorLog>,
)

data class V6PostOptimizationResult(
    val schedule: Array<IntArray>,
    val report: ViolationReport,
    val hf80: HF80Result,
    val hf67: HF67Result,
    val hf66: HF66Result,
    val hf70: HF70Result,
    val logs: List<MirrorLog>,
    /** [3.322.0] 窓の要件(c1)が最後まで残った理由の構造化診断（残存なしなら null）。 */
    val c1Plateau: C1PlateauDiagnosis? = null,
    /**
     * [3.323.0] 厳密ピン(lo==hi)だけが却下した候補の**計測できた試行数**。
     * これらは `isBetter` が採用を認めた手で、ピンのガードだけが止めている。
     *
     * **正確な読み方（3.324.0/外部レビューで是正）**:
     *  - 「手の数」ではなく「試行の回数」。巡回研磨は最大4巡するので、同じ手が複数の巡で
     *    数えられうる（重複排除していない）。
     *  - **全パス横断ではない**。[3.349.0 で 9→18パスへ訂正 → 3.350.0 で最終LNS 2本を追加 →
     *    **3.409.9 で広域ビームの合流漏れを修正＝21パス**]
     *    `V6HotfixPasses` の19パスに加え、`C1JointLnsPolish` と `PersonalBalanceJointLnsPolish` を計測する。
     *    （広域ビームは `PinBlockAttribution` を作って返すのに `runPostOptimization` 側の merge だけが
     *    無く、**この1つだけが終端集計から抜けていた**。他20サイトは元から merge 済み。）
     *    後者2本は却下するだけで一切数えておらず、実データ real_state で **1,898件**（V6HotfixPasses 側の
     *    計測値の30倍以上）が UI から丸ごと抜けていた。配線後の実測は総数 181→**1,617**で、
     *    上位対象も入れ替わる（モニカ/休 が新たに可視化）。
     *    残る計測外は `EliteIntegrationPolish`(4)・`C1TemporalFlowPolish`(1)・`CombinatorialRepair`(2)・
     *    `C1RepairAnalysis`(1) の計8箇所と、ピン保護を持たない探索本体(SA/ALNS/LAHC)。
     *  - よって「N 件の手が緩和で通る」ではなく「**少なくとも N 回、回数固定だけが却下の理由だった**」
     *    が言えることの上限。0 でも「緩めても何も変わらない」の証明にはならない（未計測分がある）。
     */
    val observedPinBlockedAttempts: Int = 0,
    /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
    val pinBlocks: PinBlockAttribution? = null,
)

/**
 * [後処理研磨のユーザー設定ゲート] UI トグル → エンジン内部フラグの受け渡し。
 * `NativeGate`（ネイティブ加速／Kotlin照合）と同じ形で、呼び出し鎖に引数を通さずに設定を届ける。
 * セッション内のみ（state には保存しない＝勤務表データに影響しない実行時の調整）。
 */
object PolishGate {
    /**
     * [c3n 回避の範囲拡張, 3.303.0] 禁止連続を崩しに行く日を j±1 固定から「パターンがまたぐ全日」へ
     * 広げる。3連（`Dﾃ→休→A4`）の先頭 j-2 に届くようになる**正しい**一般化だが、
     * **実データ3件で利得が一貫しなかったため既定 OFF**（golden=中立 / real=weighted −1674 だが
     * covU 2件を c3n 2件へ付け替え・c1 +14 / user=weighted +73 悪化）。
     *
     * 個々の手は keep-best なので退化しないが、候補が増えると探索の経路が変わり、着地する局所解が
     * データによって良くも悪くもなる（2.55.0 の戦略的振動・3.94.0 の in-loop レバーと同じ結論＝
     * 「安全であること」と「有益であること」は別）。計測が支持しない既定変更はしない。
     */
    @Volatile
    var wideC3nBreakDays: Boolean = false

    // [3.409.21/ユーザー選択「両方削除」] adaptiveEscapeControl（停滞脱出の適応制御・3.306.0）と
    //   portfolioRoleParallelSa/portfolioRoleChains（ロール内並列SA・3.371.0）は削除した。
    //   単体 A/B（1プロセス=1実行・各15ペア・基準は測定前に固定「12/15 で採否」）の結果:
    //   parallelSa = ON7/OFF8（中立。しかも ON は反復数中央値が2/3データセットで**低い**＝
    //   チェーン分割が希釈になっていた: blocked 45M vs 57M・sample 53M vs 60M）、
    //   escape = ON5/OFF10（中立〜OFF寄り。3.306.0 の n=24 と合わせ2度目の中立）。
    //   hard 中央値はどちらも全データセットで不変。docs/algorithm_portfolio.md「廃止・統合済み」参照。

    /**
     * ブロック巡回交換で、禁止連続(c3n)が正味増える候補を**候補生成の段階で**捨てるか。既定 false。
     *
     * c3n は HARD なので増える候補は最終的に `isBetter` が必ず却下する＝ON/OFF で**採用結果は変わらない**
     * （3.296.0 の A/B 実測で最終盤面・採用数が完全一致することを確認済み）。ON にすると構造的に詰んだ
     * 候補へフル checker を呼ばなくなり、評価枠を soft 判定まで進める候補へ回せる
     * （実測: 正式評価 48→14〜38 件）。
     */
    @Volatile var filterC3nIncrease: Boolean = false

    /**
     * [3.422.0/ユーザー報告「停滞の早期終了が実質効いていない」への対応・Part B]
     * `V6FinalPort` の停滞ウォッチドッグ「通常」分岐（HARD が構造床にまだ届いていない＝
     * 解ける可能性がある局面）の停滞閾値の割合。既定 **0.9** ＝旧来の固定値 `9/10` と厳密に同一。
     *
     * [3.424.0で意味論を是正] 適用は `V6FinalPort.normalStallMs`＝**予算×この割合**が基本で、
     * その値が探索区間内で一度も発火し得ない帯（実測60秒帯）だけ**探索区間×この割合**へ
     * フォールバックする（3.422.0 初版の無条件 `searchWindowMs×割合` は到達可能な帯まで無計測で
     * 厳格化していたため復元）。値は `normalStallMs` 側の require で **(0,1) 排他・有限のみ**＝
     * 1.0 以上は「閾値>=探索区間」という Part A が直した到達不能バグの再現、NaN は 20秒床への
     * 暗黙の崩落になるため、丸めず落とす。**UI トグルは無し**＝コード/計測ハーネスからのみ設定
     * （`filterC3nIncrease` 等と違い設定タブには出していない）。
     *
     * **なぜ `STALL_OVERRIDE_FACTOR`（上書き倍率）でなく、この割合自体を対象にしたか**:
     * ユーザーが選択した AskUserQuestion の選択肢は文面上「上書き倍率を予算内に収まる値へ改める」
     * だったが、実装前に算術で検算したところ**この経路は数学的にほぼ無力**と判明した。
     * `stallMs = 0.9 × 区間` に対し、上書きが区間内に収まるには `factor < 1/0.9 ≈ 1.111` が必要。
     * しかし factor が 1.0 に近いほど上書きは基準閾値と重複するだけ
     * （上書きは本来「フェーズ猶予が満たせない場合の保険」＝基準より緩い＝より長く待つ側であるべき）で、
     * factor が 1.111 に近いほど上書き閾値は区間の `≈0.999` 倍（探索締切と実質同じ）＝早期終了の
     * 意味を失う。**有効な範囲(1.0〜1.111)のどこを取っても、通常分岐が「実質早く終わる」効果は出ない**
     * （選択肢の副文言「または残り予算の一定割合」がこの袋小路を示唆していたため、そちらの精神＝
     * 「基準閾値そのものを対象にする」を採った）。
     *
     * **歴史的後悔との関係**: 旧 `stallMs=budgetMs/6`（300s予算で50s）は HARD=1（まだ解ける可能性がある）
     * を早すぎるタイミングで諦め、実機ログで残り250sを無駄にした（`V6FinalPort` の [5分強化] コメント参照）。
     * この割合を下げすぎると同じ後悔を再現しうる＝**A/B で実データにより支持された値のみを既定にする**
     * （2.55.0/2.56.0/3.310.1/3.341.1 の規律）。
     */
    @Volatile var normalStallFraction: Double = 0.9
}

/**
 * [3.356.0/ユーザー指示「オプションを減らせるようにログ強化する」] 設定タブ→詳細設定の調整トグルが
 * **その実行で実際に何をしたか**を数える。旧: トグルは6つあるのに、ログを見ても「ONにした意味が
 * あったか」が読めず、減らす判断ができなかった（`禁止連続の崩し範囲`・`立て直し方` に至っては
 * 実行の痕跡が一切出ない）。数回まわして毎回「観測なし」なら、そのトグルは消してよい、と言える。
 * [3.409.21] この計測が実際に判断を支えた＝立て直し方(adaptiveEscapeControl)とロール内並列SA
 * (portfolioRoleParallelSa)は単体 A/B の中立を根拠に削除（PolishGate 冒頭の記録参照）。
 *
 * 読み取り専用の計数のみ＝探索・採否・スコアには一切影響しない。`optimize()` 入口で reset する。
 */
object TuningTelemetry {
    // [3.360.1/敵対検証] 旧実装は `@Volatile var Int` に `++`＝read-modify-write で、**8並列ワーカーから
    //   加算されるため取りこぼしていた**（parityChecks は SA/LAHC/ALNS/研磨の4経路×全ワーカーから毎チャンク）。
    //   ログは「1240回」と断定するので、下限を実数として出していたことになる。AtomicInteger へ。
    //   加算は最も多い wideC3nCalls でも実行あたり1万回弱＝checker 1回より桁違いに安く、速度への影響はない。
    //   ※「この実行では観測なし(==0)」の判定は旧実装でも健全だった（真の回数が1以上なら必ず1は書かれる）。
    //     壊れていたのは大きさだけ。3.356.0 の「0ならトグルを消してよい」という判断根拠は無傷。
    /** 禁止連続の事前フィルタが checker を呼ばずに落とした候補数。 */
    val c3nFilterSkipped = java.util.concurrent.atomic.AtomicInteger(0)
    /** 禁止連続の崩し範囲が既定(前後1日)と違う候補日を返した回数（広がる／狭まるの両方）。 */
    val wideC3nDiffered = java.util.concurrent.atomic.AtomicInteger(0)
    /** 同・呼ばれた回数（広がらなかった分も含む）。 */
    val wideC3nCalls = java.util.concurrent.atomic.AtomicInteger(0)
    /** 仕上げ最適化により PhaseB(LAHC) へ切り替わった回数。 */
    val lahcEntered = java.util.concurrent.atomic.AtomicInteger(0)
    /** Kotlin照合を実施した回数（ネイティブ結果を採用する直前の再評価）。 */
    val parityChecks = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 実行ごとに 0 へ戻す（`optimize()` 入口）。
     *
     * **既知の限界（意図的に残す）**: これは実行をまたぐ static なので、実行が重なると
     * （WorkManager の REPLACE で旧 Worker が協調キャンセルを待つ間など）後発の reset が
     * 先行実行の計数を消し、両者が同じ箱へ加算する。3.335.0 は同型の問題を `RunSlot`
     * （コルーチンのコンテキストで実行ごとの箱を運ぶ）で解いたが、加算元の
     * [breakableDaysFor] などは非 suspend の純関数でコンテキストを読めないため同じ手が使えない。
     * 影響は**片方のログの診断値がずれる**だけで、勤務表・採否・スコアには一切触れない。
     */
    fun reset() {
        c3nFilterSkipped.set(0); wideC3nDiffered.set(0); wideC3nCalls.set(0)
        lahcEntered.set(0); parityChecks.set(0)
    }

    /** 各トグルの ON/OFF と、その実行で観測できた効果を1行にまとめる。 */
    fun summary(nativeOn: Boolean, parityOn: Boolean, softPolishOn: Boolean): String {
        fun eff(on: Boolean, n: Int, unit: String): String =
            if (!on) "OFF" else if (n > 0) "ON($n$unit)" else "ON(この実行では観測なし)"
        // 同一の値を2回読むと表示内で食い違う（別スレッドが加算しうる）ため、判定も表示も1回の読みで済ませる。
        val calls = wideC3nCalls.get()
        val differed = wideC3nDiffered.get()
        val wide = when {
            !PolishGate.wideC3nBreakDays -> "OFF"
            calls == 0 -> "ON(この実行では出番なし)"
            differed == 0 -> "ON(${calls}回呼ばれたが既定(前後1日)と同じ範囲＝OFFと差なし)"
            else -> "ON(${calls}回中${differed}回は既定(前後1日)と違う範囲を探索)"
        }
        // タグ（MirrorLog tag="設定の効き"）が同じ語を出すため、ここに前置きを付けると
        // 実機ログで「設定の効き: 設定の効き: …」と二重になる（3.409.16 で実機ログにより発覚）。本文だけを返す。
        return "ネイティブ加速=" + (if (nativeOn) "ON" else "OFF") +
            " / Kotlin照合=" + eff(parityOn, parityChecks.get(), "回") +
            " / 禁止連続の事前フィルタ=" + eff(PolishGate.filterC3nIncrease, c3nFilterSkipped.get(), "件の無駄な検査を省略・勤務表は不変") +
            " / 禁止連続の崩し範囲=" + wide +
            " / 仕上げ最適化=" + eff(softPolishOn, lahcEntered.get(), "回LAHCへ切替")
    }
}

object V6HotfixPasses {
    /**
     * [頭打ち調査・「なぜゼロにならないのか」] C1Polish/C3mnPolish/RangePolish/C3RunPolish は
     * `runPostOptimization`のフィックスポイント巡回(最大maxRounds=4)から**ラウンドごとに再呼出**
     * されるが、旧実装はseed引数を渡さず既定値固定のままだった。findCovUChainの候補順はrng由来
     * なので、ある(staff,shift)ペアがラウンドNで頭打ち(候補が構造的に全滅/isBetterに拒否)すると、
     * 盤面の当該箇所が変化しない限りラウンドN+1以降も**全く同じrng列＝同じ結果**を再生するだけで、
     * 永久に頭打ちのまま抜け出せなかった（桒澤美幸のAｱ超過が段階的にしか縮まらない実例で発覚）。
     * ラウンドごとに異なるseedを与え、再挑戦のたびに違う候補順を試せるようにする（isBetterによる
     * keep-best採否は不変＝退化不能。単なる探索の多様化）。
     */
    internal fun roundSeed(base: Long, tag: Long, round: Int) = base xor tag xor (round.toLong() * -0x61c8864680b583ebL)

    /**
     * [review: budget] 後処理チェーン HF80 -> HF67 -> HF66 -> HF70。
     * @param shouldStop true を返した時点で各パスの反復を打ち切る。全体予算(deadline)超過と
     *        coroutine キャンセルの両方を呼び出し側でこのラムダに束ねる。HF80/67/66 は
     *        deadline で短縮/打ち切り、HF70(異常検知=安価)は診断のため常に実行する。
     * @param onPhase 各パス開始時に呼ばれ、UI 進捗を後処理中も更新できる(ハング誤認の防止)。
     */
    fun runPostOptimization(
        state: MagiState,
        schedule: Array<IntArray>,
        algoName: String,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
        onPhase: (String) -> Unit = {},
        deadlineMs: Long = Long.MAX_VALUE,
    ): V6PostOptimizationResult {
        var work = schedule.copy2D()
        val logs = ArrayList<MirrorLog>()
        val t0 = EngineClock.nowMs()
        // [3.339.0/敵対レビュー A4] パスごとの消費 ms。3.269.0 の区間分割（HF80/HF67/HF66/巡回研磨/
        //   共同LNS×2）は「巡回研磨」が18パスの合計で、**どのパスが時間を食っているかが見えなかった**。
        //   実測（後処理研磨のみ）: golden は C1共同LNS 8.0s(42%)・C1広域ビーム 4.7s(25%)・
        //   個人回数共同LNS 3.4s(18%) で**上位3つが83%**、しかも採用は 0/0/1。user も同じ3つで82%・採用0。
        //   予算の縮小は探索の変更＝A/B が要るので、まず**見えるようにする**（読取専用）。
        val passMs = LinkedHashMap<String, Long>()

        onPhase("後処理 HF80 戦略的振動")
        val t80 = EngineClock.nowMs()
        val __t0 = EngineClock.nowMs()
        val r80 = applyHF80StrategicOscillation(state, work, maxCycles = 3, seed = seed xor 0x80L, shouldStop = shouldStop)
        passMs.merge("HF80StrategicOscillation", EngineClock.nowMs() - __t0) { a, b -> a + b }
        work = r80.newSchedule.copy2D()
        logs.addAll(r80.logs)

        onPhase("後処理 HF67 職員間スワップ")
        val t67 = EngineClock.nowMs()
        // [3.282.0] HF66 と同型の専用上限（残り予算の半分・絶対上限3s）。実機実測は数十ms＝通常は無影響で、
        //   大規模データでのフォールバック総当たり暴走だけを防ぐ保険。
        val hf67Cap = (EngineClock.remainingMs(deadlineMs, t67) / 2).coerceAtMost(3_000L)
        val __t1 = EngineClock.nowMs()
        val r67 = HfSwapPolish.applyHF67InterStaffSwap(state, work, maxSwaps = 30, shouldStop = shouldStop, deadlineMs = t67 + hf67Cap)
        passMs.merge("HF67InterStaffSwap", EngineClock.nowMs() - __t1) { a, b -> a + b }
        work = r67.newSchedule.copy2D()
        logs.addAll(r67.logs)

        onPhase("後処理 HF66 職員内再配分")
        val t66 = EngineClock.nowMs()
        // [残予算ガード] HF66 は手ごとに全候補をフル check する高コストパス。残予算の半分まで(残り半分を
        //   後段の研磨群へ確保)＋絶対上限6sで打ち切り、暴走で後続パスを予算超過で打ち切らせない。
        val hf66Cap = (EngineClock.remainingMs(deadlineMs, t66) / 2).coerceAtMost(6_000L)
        val __t2 = EngineClock.nowMs()
        val r66 = HfSwapPolish.applyHF66IntraStaffRedistribution(state, work, maxMoves = 30, shouldStop = shouldStop, deadlineMs = t66 + hf66Cap)
        passMs.merge("HF66IntraStaffRedistribution", EngineClock.nowMs() - __t2) { a, b -> a + b }
        work = r66.newSchedule.copy2D()
        logs.addAll(r66.logs)
        val t66Done = EngineClock.nowMs()

        // [3.271.0, 実機ログ2本連続で実証された飢餓の解消] 巡回研磨クラスタ（厳密日割当〜曜日平準化）は
        //   自身の締切を持たず shouldStop（全体予算）だけで走るため、探索フェーズが予算を使い切る実運用
        //   では後処理予約枠(8〜25s)を丸ごと消費し、後段の C1共同LNS/個人共同LNS が毎回「探索上限0=
        //   明示的に無効」でスキップされていた（両パスは実データで HARD削減の実績があるのに本番では
        //   一度も走れない＝事実上の死に機能）。HF66 の予算按分と同じ考え方で、クラスタ開始時点の
        //   残予算の半分（上限14s=両LNSの既定合計 8s+6s）を共同LNS用に確保し、クラスタには
        //   clusterStop（自前の締切つき）を渡す。クラスタが早期にフィックスポイント到達すれば共同LNSは
        //   確保分より多く使える（従来挙動と同一）。全パス keep-best のため時間配分の変更のみ＝退化不能。
        val jointLnsReserve = if (deadlineMs == Long.MAX_VALUE) 0L
            else ((deadlineMs - t66Done).coerceAtLeast(0L) / 2).coerceAtMost(14_000L)
        val clusterDeadline = if (deadlineMs == Long.MAX_VALUE) Long.MAX_VALUE else deadlineMs - jointLnsReserve
        val clusterStop: () -> Boolean = { shouldStop() || EngineClock.nowMs() >= clusterDeadline }

        // [3.326.0] 全研磨パス横断で「回数固定だけが却下した候補試行」を対象別に合算する
        //   （isBetter は採用を認めていた手＝緩めれば通ったはずの手）。最初の使用より前で宣言する。
        val pinBlocksAll = PinBlockAttribution()

        onPhase("後処理 厳密日割当")
        val __t3 = EngineClock.nowMs()
        val rAsg = DayAssignmentPolish.applyDayAssignmentPolish(state, work, shouldStop = clusterStop)
        passMs.merge("DayAssignmentPolish", EngineClock.nowMs() - __t3) { a, b -> a + b }
        rAsg.pinBlocks?.let { pinBlocksAll.merge(it) }
        work = rAsg.newSchedule.copy2D()
        logs.addAll(rAsg.logs)

        // [研磨可否の検証] ソフト研磨クラスタ(循環 / c1 / c1回転 / c3 / c3回転)の前後を測る基準。
        val preSoftRep = UnifiedViolationChecker.check(state, work)

        // [パス間フィックスポイント再ループ] 各パスは内部で自己収束するが、別パスの変更が他パスの改善を
        //   再び開く（例: c3の組替えで新たなc1充足余地が出る）。クラスタ全体を「1巡で1手も採用されなく
        //   なるまで」最大 maxRounds 巡だけ繰り返す。全パスkeep-best＝退化なし。shouldStop と maxRounds で
        //   上限。違反セル指向なので空巡は即終了（コスト0）。
        val c3Anchor = setOf("vio-c3", "vio-c3m", "vio-c3mn")
        val maxRounds = 4
        // [C1RepairIndex / 3.275.0] c1不足窓の索引用 Problem（state の純関数＝巡回間で不変。各オペレータが
        //   内部で構築する Problem(state) と同一）。C1DeltaPrefilter のクラスタ前段ゲートに使う。
        val pC1 = Problem(state)
        var round = 0
        var c1Plateau: C1PlateauDiagnosis? = null
        var totalCyc = 0; var totalC1 = 0; var totalC3 = 0; var totalC3r = 0; var totalC3mn = 0; var totalC3n = 0; var totalRange = 0; var totalC3run = 0; var totalC3pat = 0; var totalNightSwap = 0; var totalBlockSwap = 0; var totalApt = 0; var totalFair = 0
        while (round < maxRounds && !clusterStop()) {
            var roundApplied = 0

            onPhase("後処理 循環交換(k=2,3) [巡${round + 1}]")
            val __t4 = EngineClock.nowMs()
            val rCyc = CyclicSwapWeeklyPolish.applyCyclicSwapPolish(state, work, maxPasses = 4, shouldStop = clusterStop)
            passMs.merge("CyclicSwapPolish", EngineClock.nowMs() - __t4) { a, b -> a + b }
            rCyc.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = rCyc.newSchedule.copy2D(); totalCyc += rCyc.applied; roundApplied += rCyc.applied
            if (round == 0) logs.addAll(rCyc.logs)

            // [C1RepairOperators façade / 3.275.0] 散在していた C1 オペレータを図の1層へ集約（1:1委譲＝挙動同一）。
            //   自己内移設+同日swap(applyC1WindowPolish)は c1違反セルに**厳密アンカー**する＝不足窓ゼロなら必ず
            //   no-op。C1DeltaPrefilter で不足窓の有無を1回判定し、無ければ本オペレータのみ安全にスキップする
            //   （Index/Prefilter を hot path で実際に使う唯一の provably-safe な地点）。他3op(temporalFlow/
            //   wideBeam/exact)は c1中立の total改善手を出し得る／独自の内部ゲートを持つため gate せず従来どおり実行。
            val c1Index = C1RepairIndex.build(pC1, work)
            if (C1DeltaPrefilter.hasActionableC1(c1Index)) {
                onPhase("後処理 期間要件(c1)研磨 [巡${round + 1}]")
                val __t5 = EngineClock.nowMs()
                val rC1 = C1RepairOperators.selfRelocateAndSameDaySwap(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0x1C1L, round))
                passMs.merge("C1同日交換", EngineClock.nowMs() - __t5) { a, b -> a + b }
                work = rC1.newSchedule.copy2D(); totalC1 += rC1.applied; roundApplied += rC1.applied
                if (round == 0) logs.addAll(rC1.logs)
                // [構造化診断, 3.322.0] 巡ごとに上書きし最後の巡のものを残す（最終盤面に一番近い）。
                //   末尾で最終盤面に対して再フィルタするので、後続パスが直した箇所は落ちる。
                // [3.331.0] 巡ごとに上書きせず**合算**する。旧は最後の巡だけが残り、2巡目は
                //   1巡目が直したあとの盤面を見るので観測が少なく、説明できる箇所が減っていた。
                rC1.plateau?.let { fresh -> c1Plateau = c1Plateau?.mergedWith(fresh) ?: fresh }
                rC1.pinBlocks?.let { pinBlocksAll.merge(it) }

                // [C1IndexRepair / 3.276.0] index駆動の候補生成＋prefilter選別＋玉突き連鎖。C1RepairIndex/
                //   C1DeltaPrefilter を実駆動する経路。厳密c1アンカー＝不足窓ゼロで no-op のため本ゲート内に配置。
                //   生成する手は既存手B/beam/exactと重複しうるが keep-best で無害（退化不能）。
                onPhase("後処理 期間要件(c1)index駆動修復 [巡${round + 1}]")
                val __t6 = EngineClock.nowMs()
                val rC1idx = C1RepairOperators.indexChainRepair(state, work, shouldStop = clusterStop, seed = roundSeed(seed, 0x1C1D2L, round))
                passMs.merge("C1索引修復", EngineClock.nowMs() - __t6) { a, b -> a + b }
                rC1idx.pinBlocks?.let { pinBlocksAll.merge(it) }
                work = rC1idx.newSchedule.copy2D(); totalC1 += rC1idx.applied; roundApplied += rC1idx.applied
                if (round == 0) logs.addAll(rC1idx.logs)
            }

            // [3.254.0/C1TemporalFlowPolish, C1時系列DP+ジョイント再割当研磨=旧C1TemporalSwapPolish/
            // C1Rotate/BeamC1PolishV2 を置換] ユーザー指摘「applyC1WindowPolish(単一職員局所)・
            // applyC1BeamPolish(広域ビーム)・BeamC1PolishV2(同日swap束)・CombinatorialRepair の
            // 責任を整理し統合してほしい」に対する実測駆動の回答。ホストJVM実行で golden_state.json/
            // sample_state_v6.json に対しablation測定した結果:
            //  - 旧`C1TemporalSwapPolish`(DP+同日2人swap限定の実現)は単体でも他パスと組み合わせても
            //    寄与ゼロ(golden: DP単体0.0%改善、Window+DP+Rotateは Window単体と完全一致)。
            //    原因はDPが選ぶ目標パターンを「厳密に相補的なシフトを持つ同日1人との交換」でしか
            //    実現できず、そのような相手が存在しない日ではDPの改善が丸ごと死ぬため。
            //  - `applyBlockRotationPolish(c1Anchor)`(3者回転)も同様に寄与ゼロ(no-Rotateの結果が
            //    ALL5と完全一致)。
            //  - `BeamC1PolishV2`(同日swap束)も寄与ゼロ(no-BeamV2の結果がALL5と完全一致。3.252.0の
            //    実機ログでの「採用0/頭打ち」が本番ログ限定でなく実データでも構造的と確認)。
            // → 3者とも撤去し、DPの実現ステップを`FlexibleDayFlow`(3.245.0既存の同日全員参加min-cost
            //   flow)による同日ジョイント再割当へ置換した`C1TemporalFlowPolish`に一本化。実測:
            //   golden_state.json で c1 115→79(旧ALL5比 92→79 でさらに改善)・total 313→260
            //   (Window+Flow+BeamWideの順、旧ALL5の274より改善)。sample_state_v6.json で
            //   c1 7→2(71.4%改善、HARDも15→10へ同時改善)。順序が重要(Flow は BeamWide の**前**に
            //   置く。逆順だと golden で 278 止まりに劣化することを実測確認済み)。
            // CombinatorialRepair(3.249.0)はC1Window/C3mn/Range/Apt/Fairの内部augmentationで
            // C1系の別パスではないため対象外(廃止候補にはしない)。
            onPhase("後処理 期間要件(c1)時系列DP+ジョイント再割当研磨 [巡${round + 1}]")
            val __t7 = EngineClock.nowMs()
            val rC1flow = C1RepairOperators.temporalFlow(
                state, work, maxPasses = 2, maxRelocations = 4, trials = 4,
                shouldStop = clusterStop, seed = roundSeed(seed, 0xC1F10L, round),
            )
            passMs.merge("C1時系列フロー", EngineClock.nowMs() - __t7) { a, b -> a + b }
            work = rC1flow.newSchedule.copy2D(); totalC1 += rC1flow.applied; roundApplied += rC1flow.applied
            rC1flow.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1flow.logs)

            // [C1BeamPolish, 外部パッチ受領→ランキング修正+keep-best安全網追加のうえ適用] BeamC1PolishV2
            // (厳密な単発bundle採否)とは別系統の、より広い時空間ビーム探索。実データ(golden_state.json/
            // sample_state_v6.json)の両方・全15シードでtotalが真に改善することを確認済み(applyC1BeamPolish
            // のdocを参照)。BeamC1PolishV2で見つからない残差にも届く可能性があるため直後に配線。
            onPhase("後処理 期間要件(c1)広域ビーム研磨 [巡${round + 1}]")
            val __t8 = EngineClock.nowMs()
            val rC1wide = C1RepairOperators.wideBeam(state, work, shouldStop = clusterStop, seed = roundSeed(seed, 0xC1BEAL, round))
            passMs.merge("C1広域ビーム", EngineClock.nowMs() - __t8) { a, b -> a + b }
            work = rC1wide.newSchedule.copy2D(); totalC1 += rC1wide.applied; roundApplied += rC1wide.applied
            // [3.409.9] 広域ビームは `PinBlockAttribution` を作って返すのに、ここだけ合流を書き忘れていた
            //   （他20サイトは全て merge 済み＝**この1つだけ**が終端の「回数の固定について」から抜けていた）。
            rC1wide.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1wide.logs)

            // [A2/A3 厳密窓修復] 上記の局所/ビーム系が届かない「別日で連動して初めて解ける多職員手」を、
            //   窓スコープの coverage保存 permutation 厳密探索で拾う（純Kotlin・依存ゼロ）。A1=解析駆動
            //   ディスパッチ: 証明された解消不能スパン(exhaustive && min==base)を memo で二度解かない。
            onPhase("後処理 期間要件(c1)厳密窓修復 [巡${round + 1}]")
            val __t9 = EngineClock.nowMs()
            val rC1exact = C1RepairOperators.exactWindow(state, work, shouldStop = clusterStop)
            passMs.merge("C1厳密窓", EngineClock.nowMs() - __t9) { a, b -> a + b }
            work = rC1exact.newSchedule.copy2D(); totalC1 += rC1exact.applied; roundApplied += rC1exact.applied
            rC1exact.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1exact.logs)

            onPhase("後処理 連続規則(c3系)研磨 [巡${round + 1}]")
            val __t10 = EngineClock.nowMs()
            val rC3 = C3RotationPolish.applyC3SequencePolish(state, work, maxPasses = 3, shouldStop = clusterStop)
            passMs.merge("C3SequencePolish", EngineClock.nowMs() - __t10) { a, b -> a + b }
            rC3.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = rC3.newSchedule.copy2D(); totalC3 += rC3.applied; roundApplied += rC3.applied
            if (round == 0) logs.addAll(rC3.logs)

            // [3.300.0 高コストの脱出手へ格下げ] 3者回転は O(候補^3) の全組合せをフル評価する重い手。
            //   ablation（3データセットで完全に外して実行）の結果、**採用0かつ結果がバイト一致**＝
            //   通常時の寄与はゼロと実測した（C1 用の同じ回転を 3.254.0 で撤去したのと同じ根拠）。
            //   撤去はせず、**主手 applyC3SequencePolish が1手も採れなかった巡（＝停滞）**と
            //   **最終巡**だけに限定する。別のデータ形状で主手が詰まる局面には従来どおり効く。
            //   c3 違反が無ければ applyBlockRotationPolish 自身がアンカー0で即 return する＝追加コストなし。
            if (rC3.applied == 0 || round == maxRounds - 1) {
                onPhase("後処理 連続規則(c3系)3者回転研磨 [巡${round + 1}]")
                val __t11 = EngineClock.nowMs()
                val rC3r = C3RotationPolish.applyBlockRotationPolish(state, work, c3Anchor, "C3Rotate", maxPasses = 2, shouldStop = clusterStop)
                passMs.merge("BlockRotationPolish", EngineClock.nowMs() - __t11) { a, b -> a + b }
                rC3r.pinBlocks?.let { pinBlocksAll.merge(it) }
                work = rC3r.newSchedule.copy2D(); totalC3r += rC3r.applied; roundApplied += rC3r.applied
                if (round == 0) logs.addAll(rC3r.logs)
            }

            // [C3mnPolish・玉突き連鎖の横展開] cons3n(HARD)で直接候補が全滅する局面向けに findCovUChain
            //   をc3mn(回避,SOFT)専用に反映（grilling 2026-07-19、金沢勇輝のDﾃ4連続実例）。
            onPhase("後処理 回避パターン(c3mn)玉突き研磨 [巡${round + 1}]")
            val __t12 = EngineClock.nowMs()
            val rC3mn = C3FamilyPolish.applyC3mnPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3AL, round))
            passMs.merge("C3mnPolish", EngineClock.nowMs() - __t12) { a, b -> a + b }
            work = rC3mn.newSchedule.copy2D(); totalC3mn += rC3mn.applied; roundApplied += rC3mn.applied
            rC3mn.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3mn.logs)

            // [C3nPolish, 3.303.0] 禁止連続(c3n, HARD)を、違反パターンが**またぐ全日**（前日・当日・翌日）を
            //   候補にして崩す。当日1セルしか触らない既存機構では3連の先頭に構造的に届かなかった。
            onPhase("後処理 禁止連続(c3n)研磨 [巡${round + 1}]")
            val __t13 = EngineClock.nowMs()
            val rC3n = C3FamilyPolish.applyC3nPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3EL, round))
            passMs.merge("C3nPolish", EngineClock.nowMs() - __t13) { a, b -> a + b }
            work = rC3n.newSchedule.copy2D(); totalC3n += rC3n.applied; roundApplied += rC3n.applied
            rC3n.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3n.logs)

            // [RangePolish・玉突き連鎖の横展開その2] 個人別回数(low/high)を、交換相手が構造的に存在しない
            //   局面(担当可能シフトが極端に少ない職員等)向けに findCovUChain で研磨（grilling不要・
            //   C3mnPolishと同型のためユーザー承認のうえ直接実装、桒澤美幸のAｱ超過実例）。
            onPhase("後処理 個人回数(low/high)玉突き研磨 [巡${round + 1}]")
            val __t14 = EngineClock.nowMs()
            val rRange = RangePolish.applyRangePolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0x8A9EL, round))
            passMs.merge("RangePolish", EngineClock.nowMs() - __t14) { a, b -> a + b }
            work = rRange.newSchedule.copy2D(); totalRange += rRange.applied; roundApplied += rRange.applied
            rRange.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rRange.logs)

            // [C3RunPolish・玉突き連鎖の横展開その3] cons3/cons3m(単一シフト連=run-deficit)を、
            //   相互交換の相手が構造的に存在しない局面向けに findCovUChain で研磨（grilling不要・
            //   C3mnPolish/RangePolishと同型のためユーザー承認のうえ直接実装）。
            onPhase("後処理 連続規則(c3/c3m単一シフト連)玉突き研磨 [巡${round + 1}]")
            val __t15 = EngineClock.nowMs()
            val rC3run = C3FamilyPolish.applyC3RunPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3A2L, round))
            passMs.merge("C3RunPolish", EngineClock.nowMs() - __t15) { a, b -> a + b }
            work = rC3run.newSchedule.copy2D(); totalC3run += rC3run.applied; roundApplied += rC3run.applied
            rC3run.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3run.logs)

            // [C3PatternPolish・玉突き連鎖の横展開その4] 複数シフトc3/c3mパターン(非single-shift)を、
            //   交換相手が構造的に存在しない局面向けに findCovUChain で研磨（棚卸し監査で発見、ユーザー承認）。
            onPhase("後処理 連続規則(c3/c3m複数シフトパターン)玉突き研磨 [巡${round + 1}]")
            val __t16 = EngineClock.nowMs()
            val rC3pat = C3FamilyPolish.applyC3PatternPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3B4L, round))
            passMs.merge("C3PatternPolish", EngineClock.nowMs() - __t16) { a, b -> a + b }
            work = rC3pat.newSchedule.copy2D(); totalC3pat += rC3pat.applied; roundApplied += rC3pat.applied
            rC3pat.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3pat.logs)

            // [NightRunSwapPolish・夜勤連交換, 3.493.0] 前日=夜勤×翌日=希望固定に挟まれたセルの違反を、夜勤の連を
            //   他職員の同じ長さの連と窓ごと交換して解く（1セル付替え・同日入替では前日の夜勤が動かず全部却下される穴）。
            //   実データでは改善手0＝ユーザー判断で keep-best 前提に導入（効かないデータでは採用0・無害）。
            onPhase("後処理 夜勤連交換研磨 [巡${round + 1}]")
            val __t16b = EngineClock.nowMs()
            val rNight = NightRunSwapPolish.applyNightRunSwapPolish(state, work, maxPasses = 2, maxEvaluations = 400, shouldStop = clusterStop)
            passMs.merge("NightRunSwapPolish", EngineClock.nowMs() - __t16b) { a, b -> a + b }
            work = rNight.newSchedule.copy2D(); totalNightSwap += rNight.applied; roundApplied += rNight.applied
            rNight.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rNight.logs)

            // [AdaptiveBlockSwap・長期ブロック丸ごと2人交換] 15日固定の旧手を、11/13/17/19/23/28日の
            //   非等間隔ポートフォリオへ拡張。同群に限らず、ブロック内の全セルを相互に担当可能な他者も
            //   候補にし、希望固定・厳密ピン・正式スコアの全ガードを通過した最良の1手だけを採用する。
            onPhase("後処理 長期ブロック丸ごと交換(11/13/17/19/23/28日) [巡${round + 1}]")
            val __t17 = EngineClock.nowMs()
            val rBlockSwap = AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
                state, work, maxPasses = 2, candidatesPerLength = 8, maxEvaluations = 48, shouldStop = clusterStop,
            )
            passMs.merge("AdaptiveBlockSwapPolish", EngineClock.nowMs() - __t17) { a, b -> a + b }
            rBlockSwap.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = rBlockSwap.newSchedule.copy2D(); totalBlockSwap += rBlockSwap.applied; roundApplied += rBlockSwap.applied
            if (round == 0) logs.addAll(rBlockSwap.logs)

            // [AptPolish・適切回数(apt)専用研磨] 自己振替→同一グループ相互交換→玉突きチェーンの順で
            //   apt(重み1)違反を専用に研磨（grilling 2026-07-19、大島愛の休/Pｼ実例）。
            onPhase("後処理 適切回数(apt)研磨 [巡${round + 1}]")
            val __t18 = EngineClock.nowMs()
            val rApt = AptFairPolish.applyAptPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xA97L, round))
            passMs.merge("AptPolish", EngineClock.nowMs() - __t18) { a, b -> a + b }
            work = rApt.newSchedule.copy2D(); totalApt += rApt.applied; roundApplied += rApt.applied
            rApt.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rApt.logs)

            // [FairPolish・グループ内公平化(fair)専用研磨] 棚卸し(c42/c42s以外の「動かせるか」欠如監査)で
            //   発見。AptPolishと同型の3段構成（自己振替→同一グループ相互交換→玉突きチェーン）。
            onPhase("後処理 グループ内公平化(fair)玉突き研磨 [巡${round + 1}]")
            val __t19 = EngineClock.nowMs()
            val rFair = AptFairPolish.applyFairPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xFA12L, round))
            passMs.merge("FairPolish", EngineClock.nowMs() - __t19) { a, b -> a + b }
            work = rFair.newSchedule.copy2D(); totalFair += rFair.applied; roundApplied += rFair.applied
            rFair.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rFair.logs)

            round++
            if (roundApplied == 0) break   // この巡で1手も採用なし＝joint局所最適に到達
        }

        // [研磨可否の検証ログ] ソフトc3系3種(c3/c3m/c3mn)とc1の増減・採用数・HARD不変・巡回数を集約。
        // 採用0かつ対象>0なら「頭打ち(改善手なし=正常)」、対象0なら「対象なし」と明示。
        run {
            val softAfter = UnifiedViolationChecker.check(state, work)
            fun bd(r: ViolationReport, k: String) = r.breakdown[k] ?: 0
            val adopted = totalCyc + totalC1 + totalC3 + totalC3r + totalC3mn + totalC3n + totalRange + totalC3run + totalC3pat + totalNightSwap + totalBlockSwap + totalApt + totalFair
            // [3.278.0/監査修正] CyclicSwap の正当な対象族(c2/c41/c42/c41s/c42s/covO)も対象数に含める
            //   （旧: c42等のみ違反の盤面で採用0のとき誤って「対象なし」と表示していた）。
            // [3.475.0/論理監査] c3n も対象に含める（C3nPolish=3.303.0 はこの塊の中で走り採用数は adopted に
            //   入るのに、対象数から漏れていた＝c3n だけの盤面で採用0だと「対象なし」と誤表示）。
            val targets = bd(preSoftRep, "c1") + bd(preSoftRep, "c3") + bd(preSoftRep, "c3m") + bd(preSoftRep, "c3mn") + bd(preSoftRep, "c3n") +
                bd(preSoftRep, "low") + bd(preSoftRep, "high") + bd(preSoftRep, "apt") + bd(preSoftRep, "fair") +
                bd(preSoftRep, "c2") + bd(preSoftRep, "c41") + bd(preSoftRep, "c42") +
                bd(preSoftRep, "c41s") + bd(preSoftRep, "c42s") + bd(preSoftRep, "covO")
            val verdict = when {
                adopted > 0 -> "有効(採用${adopted}手)"
                targets == 0 -> "対象なし"
                else -> "頭打ち(採用0=改善手なし・正常)"
            }
            val hardNote = if (softAfter.hard == preSoftRep.hard) "不変" else "変化${preSoftRep.hard}->${softAfter.hard}!"
            logs.add(MirrorLog(tag = "SoftPolishVerify", message =
                // [3.271.0, 外部レビューの誤読対策] 各パスの個別ログ行は巡1のみ表示（4巡ぶんのスパム防止）
                //   だが、この集約行の増減・採用内訳は全巡合計。旧表記では「C1Polish 採用0なのにc1が
                //   65→57に減った＝責務逆転?」という誤読を実際に生んだため、表示仕様を行内に明記する。
                "ソフトc1/c3系研磨 可否=$verdict (${round}巡・各パス行は巡1のみ表示/本行は全巡合計) | c1 ${bd(preSoftRep, "c1")}->${bd(softAfter, "c1")}" +
                    " / c3 ${bd(preSoftRep, "c3")}->${bd(softAfter, "c3")}" +
                    " / c3m ${bd(preSoftRep, "c3m")}->${bd(softAfter, "c3m")}" +
                    " / c3mn ${bd(preSoftRep, "c3mn")}->${bd(softAfter, "c3mn")}" +
                    " / low ${bd(preSoftRep, "low")}->${bd(softAfter, "low")}" +
                    " / high ${bd(preSoftRep, "high")}->${bd(softAfter, "high")}" +
                    " / apt ${bd(preSoftRep, "apt")}->${bd(softAfter, "apt")}" +
                    " / fair ${bd(preSoftRep, "fair")}->${bd(softAfter, "fair")}" +
                    " | HARD $hardNote / total ${preSoftRep.total}->${softAfter.total}" +
                    " (採用内訳 循環:${totalCyc} c1:${totalC1} c3:${totalC3} c3回転:${totalC3r} c3mn玉突き:${totalC3mn} c3n:${totalC3n} range玉突き:${totalRange} c3run玉突き:${totalC3run} c3pattern玉突き:${totalC3pat} 夜勤連交換:${totalNightSwap} ブロック交換:${totalBlockSwap} apt玉突き:${totalApt} fair玉突き:${totalFair})"))
        }

        // [weekly 研磨の穴を埋める] 曜日平準化(weekly)は同日2者スワップでは動かせない（勤務↔勤務は曜日別の
        //   勤務/休が不変）ため、被覆保存の2職員×2日 長方形交換で「過剰曜日→過少曜日」へ勤務を移す。実目的関数
        //   isBetter で採否＝退化なし。下の equalize 系(分散指標)より先に L1 指向のこのパスを走らせる。
        onPhase("後処理 曜日平準化(長方形交換)")
        val __t20 = EngineClock.nowMs()
        val rWrb = CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish(state, work, maxPasses = 2, shouldStop = clusterStop)
        passMs.merge("WeeklyRebalancePolish", EngineClock.nowMs() - __t20) { a, b -> a + b }
        rWrb.pinBlocks?.let { pinBlocksAll.merge(it) }
        work = rWrb.newSchedule.copy2D()
        logs.addAll(rWrb.logs)

        // [交互最適化(Alternating Optimization)] 長方形交換(クロス日)が届かない同日内の「休の割当先」を、日ブロック
        //   ごとの最小費用割当(Hungarian＝凸最適化)で weekly/range/apt 同時最適に再配置し、不動点まで巡回する。
        //   rectangle(クロス日)と AO(同日内)は相補的＝両方走らせて weekly の取りこぼしを二方向から詰める。keep-best。
        onPhase("後処理 交互最適化(日ブロック割当)")
        val __t21 = EngineClock.nowMs()
        val rAlt = DayAssignmentPolish.applyAlternatingSoftPolish(state, work, maxSweeps = 4, shouldStop = clusterStop)
        passMs.merge("AlternatingSoftPolish", EngineClock.nowMs() - __t21) { a, b -> a + b }
        rAlt.pinBlocks?.let { pinBlocksAll.merge(it) }
        work = rAlt.newSchedule.copy2D()
        logs.addAll(rAlt.logs)

        // [3.317.0] ここにあった分散指標ベースの平準化2パスは撤去した（実測で寄与ゼロ）。詳細は
        //   CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish 直前の撤去メモを参照
        //   （[責務別分割] 抽出により物理的な位置関係は分割前と変わっている）。fair/weekly の L1 研磨は
        //   applyFairPolish / applyWeeklyRebalancePolish / DayAssignmentPolish.applyAlternatingSoftPolish が担う。

        // [3.255.0/C1JointLnsPolish・PersonalBalanceJointLnsPolish, 受領・検証のうえ適用] ここまでの
        // 巡回研磨は各パスが候補を作った直後に正式目的関数で採否するため、C1改善や個人回数改善に伴う
        // coverage/range/c3系の副作用を別の手で相殺する前に候補を失うことがある。この2パスはdebt付き
        // beamで複数手を束ね、最終採用のみ正式順序(hard→weighted→total)のkeep-bestで判定する（中間ノードの
        // debtは探索のみに影響し退化不能）。ホストJVM実行でgolden_state.json/sample_state_v6.jsonに対し
        // 既存パイプライン適用後の追加効果を実測: golden_state.jsonでは両方とも0（既存パイプラインが
        // 既に汲み尽くし済み＝安全なno-op）、sample_state_v6.jsonではC1JointLnsPolishがHARD5→4（既存
        // パイプラインが見つけていなかったHARD削減）、PersonalBalanceJointLnsPolishが個人回数34→31
        // （total 196→195）を発見。実行コストが高い(既定8s/6s)ため巡回ループでなく最終1回のみ実行。
        // [予算按分, receiving-code-review→自己検証で訂正] 以前は各パスの既定Config(8s/6s)をそのまま
        //   使いshouldStopのみを渡していたため、外側deadlineMsの残りがそれより短くても内部deadlineは
        //   呼出時点から新規に8s/6s確保され、最大14秒ぶん外側締切を超過し得た。
        //   [訂正の経緯] 初版はHF66(187行)と同型の「残予算の半分を後段へ確保」を踏襲したが、HF66は
        //   後段に巡回ループ全体(多数のパス)を控えるのに対し、この2パスの後段はPersonalBalance
        //   JointLnsPolish単体(既定6s)+HF70(安価・常時実行)のみ＝文脈が異なり折半は不適切と判明。
        //   remaining=14000ms(=両者の既定合計値)ちょうどの境界で検算すると、折半案はC1に7000msしか
        //   与えず自身の既定8000msに届かず、Personalは残り7000msのうち自身の既定6000msしか使わず
        //   1000msが誰にも使われないまま終わる(半分確保がPersonalの実需要=6000msを知らずに一律確保
        //   するため)。既定比8:6の按分なら、この境界で双方とも過不足なく自身の既定を得られる。
        //   remainingは整数乗算オーバーフロー回避のため安全な上限(100秒、実運用の予算を大きく超える
        //   値)へ先にクランプしてから按分する。残0なら各パスのmaxMillis<=0ガードにより即スキップ
        //   (explicitly無効)される。
        onPhase("後処理 期間要件(c1)共同LNS")
        val tC1Lns = EngineClock.nowMs()
        val remainingForC1Lns = EngineClock.remainingMs(deadlineMs, tC1Lns).coerceAtMost(100_000L)
        val c1LnsCap = (remainingForC1Lns * 8_000L / 14_000L).coerceAtMost(8_000L)
        val __t22 = EngineClock.nowMs()
        val rC1Lns = C1RepairOperators.jointLns(
            state, work, config = C1JointLnsPolish.Config(maxMillis = c1LnsCap), shouldStop = shouldStop,
        )
        passMs.merge("C1共同LNS", EngineClock.nowMs() - __t22) { a, b -> a + b }
        work = rC1Lns.newSchedule.copy2D()
        // [3.350.0/敵対検証] 最終LNS 2パスのピン却下が pinBlocksAll へ合流していなかった
        //   （旧: この2パスは PinBlockAttribution を作らず pinBlocks が常に null だった）。
        rC1Lns.pinBlocks?.let { pinBlocksAll.merge(it) }
        logs.addAll(rC1Lns.logs)

        onPhase("後処理 個人回数/適切回数 共同LNS")
        val tPersonalLns = EngineClock.nowMs()
        val personalLnsCap = EngineClock.remainingMs(deadlineMs, tPersonalLns).coerceAtMost(6_000L)
        val __t23 = EngineClock.nowMs()
        val rPersonalLns = PersonalBalanceJointLnsPolish.apply(
            state, work, config = PersonalBalanceJointLnsPolish.Config(maxMillis = personalLnsCap), shouldStop = shouldStop,
        )
        passMs.merge("個人回数共同LNS", EngineClock.nowMs() - __t23) { a, b -> a + b }
        work = rPersonalLns.newSchedule.copy2D()
        rPersonalLns.pinBlocks?.let { pinBlocksAll.merge(it) }
        logs.addAll(rPersonalLns.logs)

        val tHf = EngineClock.nowMs()
        if (shouldStop()) {
            // [3.278.0/文言修正] この時点で残るのは最終検査(HF70)のみ＝「残りパスの打ち切り」は各パス内部の
            //   shouldStop で既に済んでいる事実に合わせる。
            logs.add(MirrorLog(level = "W", tag = "POST", message = "予算超過のため後処理は締切で短縮されました(各パスは内部で打ち切り済み・以降は最終検査のみ)"))
        }

        onPhase("後処理 HF70 異常検知")
        val report = UnifiedViolationChecker.check(state, work)
        val r70 = HfSwapPolish.detectHF70Anomalies(state, work, algoName, report)
        logs.addAll(r70.logs)

        val tEnd = EngineClock.nowMs()
        // [ログ精度修正] 旧表記は t66〜tHf の間(=HF66本体＋厳密日割当＋巡回研磨4巡＋曜日/交互研磨＋
        //   C1/個人共同LNS＝パイプライン成長で大半を占めるようになった区間)を丸ごと「HF66」と誤表示していた
        //   （HF66自身は t66+hf66Cap で内部上限≤6s に自己制限済みのため、実際にそれ以上かかっていたのは
        //   後続の巡回研磨クラスタ）。C1JointLNS/個人共同LNSが「探索上限0=明示的に無効」になる理由
        //   （＝ここまでの区間で後処理予算を使い切った）が読めるよう区間ごとに分割表示する。表示のみ・
        //   スコアリング不変。
        logs.add(MirrorLog(level = "I", tag = "POST",
            message = "後処理タイミング 総${tEnd - t0}ms: HF80=${t67 - t80}ms HF67=${t66 - t67}ms HF66=${t66Done - t66}ms" +
                " 巡回研磨(厳密日割当+c1/c3/range/apt/fair+曜日/交互)=${tC1Lns - t66Done}ms" +
                " C1共同LNS=${tPersonalLns - tC1Lns}ms 個人共同LNS=${tHf - tPersonalLns}ms" +
                // [3.278.0] 旧: 最終検査(フルcheck+HF70)が無区間で「区間合計 < 総」の不一致を生んでいた。
                " 最終検査+HF70=${tEnd - tHf}ms"))

        // [3.339.0] パスごとの内訳（多い順・上位8）。「時間を食っているのに採用0」のパスは各パス自身の
        //   行（採用N回）と突き合わせれば分かる。合計は上の区間合計とほぼ一致する（計測外＝ループ制御のみ）。
        if (passMs.isNotEmpty()) {
            val sum = passMs.values.sum().coerceAtLeast(1L)
            logs.add(MirrorLog(level = "I", tag = "POST",
                message = "後処理パス別 計${sum}ms: " + passMs.entries.sortedByDescending { it.value }
                    .take(8).joinToString(" ") { "${it.key}=${it.value}ms(${it.value * 100 / sum}%)" }))
        }

        // [構造化診断, 3.322.0] C1研磨の時点で作った診断を最終盤面に合わせ直す
        //   （そのあとの共同LNS等が直した箇所を「直せなかった」と見せない）。
        val plateau = c1Plateau?.let { d ->
            val pFin = cachedProblem(state)
            d.refreshedAgainst(report.breakdown["c1"] ?: 0) { i, x, ri ->
                val c = pFin.cons1.getOrNull(ri)
                c != null && c.shiftIdx == x && c.day1 > 0 &&
                    (0..pFin.T - c.day1).any { j -> inDeficientC1Window(pFin, work, i, x, c.day1, c.day2, j) }
            }
        }
        // [3.325.0] c1 が残っているなら、観測が1件も無くても診断を返す（UI が「原因未確定」と出す）。
        //   旧: hasEntries で null にしていたため、観測ゼロのときカードごと消えて「残っているのに
        //   何も説明されない」状態になっていた。
        val c1Left = report.breakdown["c1"] ?: 0
        val plateauOut = plateau?.takeIf { it.hasEntries || it.causeUnknown }
            ?: (if (c1Left > 0) C1PlateauDiagnosis(c1Left, emptyList()) else null)

        val allLogs = ArrayList<MirrorLog>()
        allLogs.addAll(logs)
        allLogs.addAll(report.logs)
        return V6PostOptimizationResult(work, report.copy(logs = allLogs), r80, r67, r66, r70, logs, plateauOut, pinBlocksAll.attempts, pinBlocksAll)
    }

    fun applyHF80StrategicOscillation(
        state: MagiState,
        schedule: Array<IntArray>,
        maxCycles: Int = 3,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
    ): HF80Result {
        val p = Problem(state)
        val rng = Random(seed)
        val before = UnifiedViolationChecker.check(state, schedule)
        var best = normalizeSchedule(schedule, p)
        var bestReport = before
        var applied = false
        var usedCycles = 0
        val cycleMax = max(0, maxCycles)
        var cycle = 0
        while (cycle < cycleMax) {
            if (shouldStop()) break
            val cand = best.copy2D()
            val strength = max(1, (p.S * p.T * (0.03 + cycle * 0.02)).toInt())
            var t = 0
            while (t < strength) {
                if (p.S > 0 && p.T > 0) {
                    val i = rng.nextInt(p.S)
                    val j = rng.nextInt(p.T)
                    // [3.311.0] 3.270.0 の wishLocked 統一の取り残し。生の `wish < 0` だと
                    //   **実現不能な希望**（担当できないシフトへの希望）のセルまで摂動対象から
                    //   外れ、そこに座礁した groupViol セルが永久に動かせなくなる。
                    if (!p.wishLocked(i, j)) {
                        val allowed = p.allowedShiftsForStaff(i)
                        if (allowed.isNotEmpty()) cand[i][j] = allowed[rng.nextInt(allowed.size)]
                    }
                }
                t++
            }
            val polished = localBestImprovement(state, cand, 250 + cycle * 120, rng, shouldStop)
            val rep = UnifiedViolationChecker.check(state, polished)
            usedCycles = cycle + 1
            if (isBetter(rep, bestReport)) {
                best = polished
                bestReport = rep
                applied = true
            }
            cycle++
        }
        val reason = if (applied) "strategic oscillation accepted" else "no improving oscillation"
        val logs = listOf(MirrorLog(tag = "HF80", message = "SO applied=$applied HARD ${before.hard}->${bestReport.hard} score ${before.weightedScore.toLong()}->${bestReport.weightedScore.toLong()} cycles=$usedCycles"))
        return HF80Result(best, before.hard, bestReport.hard, before.weightedScore, bestReport.weightedScore, usedCycles, applied, reason, logs)
    }

    data class CyclicSwapResult(
        val newSchedule: Array<IntArray>,
        val beforeTotal: Int,
        val afterTotal: Int,
        val applied: Int,
        val logs: List<MirrorLog>,
        /**
         * [C1 頭打ちの構造化診断, 3.322.0] `applyC1WindowPolish` だけが設定する。
         * 他パスは null のまま（既定値つき＝既存の構築サイトは非破壊）。
         */
        val plateau: C1PlateauDiagnosis? = null,
        /**
         * [3.323.0] 厳密ピン(lo==hi)を崩すため却下した候補の数。
         * これらは **`isBetter` が採用を認めた**手で、ピンのガードだけが止めている＝
         * 「回数固定を緩めれば通ったはずの手」の実測値（推測ではない）。
         */
        val observedPinBlockedAttempts: Int = 0,
        /** [3.326.0] どのピン(職員,シフト)が何回止めたか。緩和対象の提示に使う。 */
        val pinBlocks: PinBlockAttribution? = null,
    )

    /**
     * [3.451.0/largeHeap-OOMの根本修正] `UnifiedViolationChecker.check()` は Map/List フィールド7つを
     * 持つ重い `ViolationReport` を毎回新規アロケートする（3.450.0で確認: HF80戦略的振動の呼出元
     * `applyHF80StrategicOscillation` 経由でこの内側ループが1回のパスにつき1,000回超呼び、既定
     * ヒープ256MiBを使い切ってOutOfMemoryErrorを起こした実機ログを確認済み）。
     *
     * 内側の探索は**候補生成の当落判定**であって最終採否ではない——呼出元
     * `applyHF80StrategicOscillation` は本関数が返す `polished` 盤面を必ず
     * `UnifiedViolationChecker.check()` + `isBetter`(=betterReport, 3.287.0の単一ソース) で再評価してから
     * `best`/`bestReport` へ採否するため（外側ゲートは1サイクルにつき1回のみ・cycle≤3回）、内側の当落基準を
     * 変えても**最終的にHF80Resultへ採用される盤面の品質は退化しない**（3.290.0系の「候補生成は近似でよい・
     * 最終採否は必ずchecker+isBetter」という本コードベース全体の確立済み契約と同型）。
     *
     * `V6NativeOptimizer.runV5` が SA(native)の内側探索に `Evaluator.fullEval`（Mapを一切作らない
     * packed Long＝hard×SCORE_HARD_UNIT+soft）を使い、最終結果だけ checker で再検証するのと**同じ
     * 二層構成**をここへ持ち込む。`Evaluator.hard`/`soft` と Checker の `hard`/`weightedScore` の数値一致は
     * `ObjectiveParityTest`(3.337.0) が既に保証済み。唯一の差は「同一hard件数のときの内訳tie-break」
     * （旧: weightedScore=hard族の重み付き寄与を含む / 新: packed比較はhard件数で確定同点ならsoftのみで
     * 決める）で、これは内側探索の経路のみに影響し外側ゲートの正しさには無関係。
     *
     * 実測（HF80単体, 実データ相当の10職員×31日）: 1回のパスあたりの重い ViolationReport アロケートが
     * 最大1,470回超 → 最大4回（`before`1回＋cycle毎の外側`rep`1回×3）まで減少。
     *
     * **A/B実測（教訓#30: revert を scratch へ作り旧実装と突合）**: `runPostOptimization` の決定的ベンチ
     * （固定seed=12345）を3データセット全てで旧(checker毎回)実装と新(Evaluator)実装の両方で実行し、
     * hard/total/weightedScore/c1 が**すべてバイト一致**することを確認（golden 0/420/4258.0/c1 96・
     * sample_v6 9/336/73828.0/c1 4・blocked_covu 4/311/34149.0/c1 52＝いずれも既存の記録済みベースラインと
     * 一致）。tie-break差は理論上の懸念に留まり、この3データセットでは実際の探索経路に一切影響しなかった。
     */
    private fun localBestImprovement(state: MagiState, schedule: Array<IntArray>, tries: Int, rng: Random, shouldStop: () -> Boolean = { false }): Array<IntArray> {
        val p = Problem(state)
        val ev = Evaluator(p)
        var best = schedule.copy2D()
        var bestScore = ev.fullEval(best)
        var t = 0
        val maxTry = max(0, tries)
        while (t < maxTry) {
            if (shouldStop()) break
            if (p.S > 0 && p.T > 0) {
                val cand = best.copy2D()
                val i = rng.nextInt(p.S)
                val j = rng.nextInt(p.T)
                if (!p.wishLocked(i, j)) {
                    val allowed = p.allowedShiftsForStaff(i)
                    if (allowed.isNotEmpty()) {
                        cand[i][j] = allowed[rng.nextInt(allowed.size)]
                        val score = ev.fullEval(cand)
                        if (score < bestScore) {
                            best = cand
                            bestScore = score
                        }
                    }
                }
            }
            t++
        }
        return best
    }

    // [責務別分割] DayAssignmentPolish.kt からも参照されるため internal 化（写しを作らず単一ソースを共有）。
    internal fun effectiveHi(p: Problem, i: Int, k: Int): Int {
        val hi = p.rangeHi[i][k]
        return if (hi == Int.MAX_VALUE) Int.MAX_VALUE / 4 else hi
    }

    // [3.287.0 keep-best統一] hard→weightedScore→total（単一ソース betterReport へ委譲。MirrorCore.kt 参照）。
    private fun isBetter(a: ViolationReport, b: ViolationReport): Boolean = betterReport(a, b)

}
