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
     * 長期ブロック交換の候補長。月次勤務表で「局所交換では越えにくい」谷を越えるための
     * 非等間隔ポートフォリオで、短い方から順に 11/13/17/19/23/28 日を試す。
     * 28 は 2月（28日）で「1か月まるごと」の交換を確保するための長さ（素数列ではない点は意図的）。
     * 15 日固定の旧 BlockSwapPolish は後方互換のため残すが、後処理ではこちらを使う。
     */
    private val adaptiveBlockLengths = intArrayOf(11, 13, 17, 19, 23, 28)

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
        val t0 = System.currentTimeMillis()
        // [3.339.0/敵対レビュー A4] パスごとの消費 ms。3.269.0 の区間分割（HF80/HF67/HF66/巡回研磨/
        //   共同LNS×2）は「巡回研磨」が18パスの合計で、**どのパスが時間を食っているかが見えなかった**。
        //   実測（後処理研磨のみ）: golden は C1共同LNS 8.0s(42%)・C1広域ビーム 4.7s(25%)・
        //   個人回数共同LNS 3.4s(18%) で**上位3つが83%**、しかも採用は 0/0/1。user も同じ3つで82%・採用0。
        //   予算の縮小は探索の変更＝A/B が要るので、まず**見えるようにする**（読取専用）。
        val passMs = LinkedHashMap<String, Long>()

        onPhase("後処理 HF80 戦略的振動")
        val t80 = System.currentTimeMillis()
        val __t0 = System.currentTimeMillis()
        val r80 = applyHF80StrategicOscillation(state, work, maxCycles = 3, seed = seed xor 0x80L, shouldStop = shouldStop)
        passMs.merge("HF80StrategicOscillation", System.currentTimeMillis() - __t0) { a, b -> a + b }
        work = r80.newSchedule.copy2D()
        logs.addAll(r80.logs)

        onPhase("後処理 HF67 職員間スワップ")
        val t67 = System.currentTimeMillis()
        // [3.282.0] HF66 と同型の専用上限（残り予算の半分・絶対上限3s）。実機実測は数十ms＝通常は無影響で、
        //   大規模データでのフォールバック総当たり暴走だけを防ぐ保険。
        val hf67Cap = ((deadlineMs - t67).coerceAtLeast(0L) / 2).coerceAtMost(3_000L)
        val __t1 = System.currentTimeMillis()
        val r67 = HfSwapPolish.applyHF67InterStaffSwap(state, work, maxSwaps = 30, shouldStop = shouldStop, deadlineMs = t67 + hf67Cap)
        passMs.merge("HF67InterStaffSwap", System.currentTimeMillis() - __t1) { a, b -> a + b }
        work = r67.newSchedule.copy2D()
        logs.addAll(r67.logs)

        onPhase("後処理 HF66 職員内再配分")
        val t66 = System.currentTimeMillis()
        // [残予算ガード] HF66 は手ごとに全候補をフル check する高コストパス。残予算の半分まで(残り半分を
        //   後段の研磨群へ確保)＋絶対上限6sで打ち切り、暴走で後続パスを予算超過で打ち切らせない。
        val hf66Cap = ((deadlineMs - t66).coerceAtLeast(0L) / 2).coerceAtMost(6_000L)
        val __t2 = System.currentTimeMillis()
        val r66 = HfSwapPolish.applyHF66IntraStaffRedistribution(state, work, maxMoves = 30, shouldStop = shouldStop, deadlineMs = t66 + hf66Cap)
        passMs.merge("HF66IntraStaffRedistribution", System.currentTimeMillis() - __t2) { a, b -> a + b }
        work = r66.newSchedule.copy2D()
        logs.addAll(r66.logs)
        val t66Done = System.currentTimeMillis()

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
        val clusterStop: () -> Boolean = { shouldStop() || System.currentTimeMillis() >= clusterDeadline }

        // [3.326.0] 全研磨パス横断で「回数固定だけが却下した候補試行」を対象別に合算する
        //   （isBetter は採用を認めていた手＝緩めれば通ったはずの手）。最初の使用より前で宣言する。
        val pinBlocksAll = PinBlockAttribution()

        onPhase("後処理 厳密日割当")
        val __t3 = System.currentTimeMillis()
        val rAsg = DayAssignmentPolish.applyDayAssignmentPolish(state, work, shouldStop = clusterStop)
        passMs.merge("DayAssignmentPolish", System.currentTimeMillis() - __t3) { a, b -> a + b }
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
        var totalCyc = 0; var totalC1 = 0; var totalC3 = 0; var totalC3r = 0; var totalC3mn = 0; var totalC3n = 0; var totalRange = 0; var totalC3run = 0; var totalC3pat = 0; var totalBlockSwap = 0; var totalApt = 0; var totalFair = 0
        while (round < maxRounds && !clusterStop()) {
            var roundApplied = 0

            onPhase("後処理 循環交換(k=2,3) [巡${round + 1}]")
            val __t4 = System.currentTimeMillis()
            val rCyc = applyCyclicSwapPolish(state, work, maxPasses = 4, shouldStop = clusterStop)
            passMs.merge("CyclicSwapPolish", System.currentTimeMillis() - __t4) { a, b -> a + b }
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
                val __t5 = System.currentTimeMillis()
                val rC1 = C1RepairOperators.selfRelocateAndSameDaySwap(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0x1C1L, round))
                passMs.merge("C1同日交換", System.currentTimeMillis() - __t5) { a, b -> a + b }
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
                val __t6 = System.currentTimeMillis()
                val rC1idx = C1RepairOperators.indexChainRepair(state, work, shouldStop = clusterStop, seed = roundSeed(seed, 0x1C1D2L, round))
                passMs.merge("C1索引修復", System.currentTimeMillis() - __t6) { a, b -> a + b }
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
            val __t7 = System.currentTimeMillis()
            val rC1flow = C1RepairOperators.temporalFlow(
                state, work, maxPasses = 2, maxRelocations = 4, trials = 4,
                shouldStop = clusterStop, seed = roundSeed(seed, 0xC1F10L, round),
            )
            passMs.merge("C1時系列フロー", System.currentTimeMillis() - __t7) { a, b -> a + b }
            work = rC1flow.newSchedule.copy2D(); totalC1 += rC1flow.applied; roundApplied += rC1flow.applied
            rC1flow.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1flow.logs)

            // [C1BeamPolish, 外部パッチ受領→ランキング修正+keep-best安全網追加のうえ適用] BeamC1PolishV2
            // (厳密な単発bundle採否)とは別系統の、より広い時空間ビーム探索。実データ(golden_state.json/
            // sample_state_v6.json)の両方・全15シードでtotalが真に改善することを確認済み(applyC1BeamPolish
            // のdocを参照)。BeamC1PolishV2で見つからない残差にも届く可能性があるため直後に配線。
            onPhase("後処理 期間要件(c1)広域ビーム研磨 [巡${round + 1}]")
            val __t8 = System.currentTimeMillis()
            val rC1wide = C1RepairOperators.wideBeam(state, work, shouldStop = clusterStop, seed = roundSeed(seed, 0xC1BEAL, round))
            passMs.merge("C1広域ビーム", System.currentTimeMillis() - __t8) { a, b -> a + b }
            work = rC1wide.newSchedule.copy2D(); totalC1 += rC1wide.applied; roundApplied += rC1wide.applied
            // [3.409.9] 広域ビームは `PinBlockAttribution` を作って返すのに、ここだけ合流を書き忘れていた
            //   （他20サイトは全て merge 済み＝**この1つだけ**が終端の「回数の固定について」から抜けていた）。
            rC1wide.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1wide.logs)

            // [A2/A3 厳密窓修復] 上記の局所/ビーム系が届かない「別日で連動して初めて解ける多職員手」を、
            //   窓スコープの coverage保存 permutation 厳密探索で拾う（純Kotlin・依存ゼロ）。A1=解析駆動
            //   ディスパッチ: 証明された解消不能スパン(exhaustive && min==base)を memo で二度解かない。
            onPhase("後処理 期間要件(c1)厳密窓修復 [巡${round + 1}]")
            val __t9 = System.currentTimeMillis()
            val rC1exact = C1RepairOperators.exactWindow(state, work, shouldStop = clusterStop)
            passMs.merge("C1厳密窓", System.currentTimeMillis() - __t9) { a, b -> a + b }
            work = rC1exact.newSchedule.copy2D(); totalC1 += rC1exact.applied; roundApplied += rC1exact.applied
            rC1exact.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC1exact.logs)

            onPhase("後処理 連続規則(c3系)研磨 [巡${round + 1}]")
            val __t10 = System.currentTimeMillis()
            val rC3 = C3RotationPolish.applyC3SequencePolish(state, work, maxPasses = 3, shouldStop = clusterStop)
            passMs.merge("C3SequencePolish", System.currentTimeMillis() - __t10) { a, b -> a + b }
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
                val __t11 = System.currentTimeMillis()
                val rC3r = C3RotationPolish.applyBlockRotationPolish(state, work, c3Anchor, "C3Rotate", maxPasses = 2, shouldStop = clusterStop)
                passMs.merge("BlockRotationPolish", System.currentTimeMillis() - __t11) { a, b -> a + b }
                rC3r.pinBlocks?.let { pinBlocksAll.merge(it) }
                work = rC3r.newSchedule.copy2D(); totalC3r += rC3r.applied; roundApplied += rC3r.applied
                if (round == 0) logs.addAll(rC3r.logs)
            }

            // [C3mnPolish・玉突き連鎖の横展開] cons3n(HARD)で直接候補が全滅する局面向けに findCovUChain
            //   をc3mn(回避,SOFT)専用に反映（grilling 2026-07-19、金沢勇輝のDﾃ4連続実例）。
            onPhase("後処理 回避パターン(c3mn)玉突き研磨 [巡${round + 1}]")
            val __t12 = System.currentTimeMillis()
            val rC3mn = C3FamilyPolish.applyC3mnPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3AL, round))
            passMs.merge("C3mnPolish", System.currentTimeMillis() - __t12) { a, b -> a + b }
            work = rC3mn.newSchedule.copy2D(); totalC3mn += rC3mn.applied; roundApplied += rC3mn.applied
            rC3mn.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3mn.logs)

            // [C3nPolish, 3.303.0] 禁止連続(c3n, HARD)を、違反パターンが**またぐ全日**（前日・当日・翌日）を
            //   候補にして崩す。当日1セルしか触らない既存機構では3連の先頭に構造的に届かなかった。
            onPhase("後処理 禁止連続(c3n)研磨 [巡${round + 1}]")
            val __t13 = System.currentTimeMillis()
            val rC3n = C3FamilyPolish.applyC3nPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3EL, round))
            passMs.merge("C3nPolish", System.currentTimeMillis() - __t13) { a, b -> a + b }
            work = rC3n.newSchedule.copy2D(); totalC3n += rC3n.applied; roundApplied += rC3n.applied
            rC3n.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3n.logs)

            // [RangePolish・玉突き連鎖の横展開その2] 個人別回数(low/high)を、交換相手が構造的に存在しない
            //   局面(担当可能シフトが極端に少ない職員等)向けに findCovUChain で研磨（grilling不要・
            //   C3mnPolishと同型のためユーザー承認のうえ直接実装、桒澤美幸のAｱ超過実例）。
            onPhase("後処理 個人回数(low/high)玉突き研磨 [巡${round + 1}]")
            val __t14 = System.currentTimeMillis()
            val rRange = RangePolish.applyRangePolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0x8A9EL, round))
            passMs.merge("RangePolish", System.currentTimeMillis() - __t14) { a, b -> a + b }
            work = rRange.newSchedule.copy2D(); totalRange += rRange.applied; roundApplied += rRange.applied
            rRange.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rRange.logs)

            // [C3RunPolish・玉突き連鎖の横展開その3] cons3/cons3m(単一シフト連=run-deficit)を、
            //   相互交換の相手が構造的に存在しない局面向けに findCovUChain で研磨（grilling不要・
            //   C3mnPolish/RangePolishと同型のためユーザー承認のうえ直接実装）。
            onPhase("後処理 連続規則(c3/c3m単一シフト連)玉突き研磨 [巡${round + 1}]")
            val __t15 = System.currentTimeMillis()
            val rC3run = C3FamilyPolish.applyC3RunPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3A2L, round))
            passMs.merge("C3RunPolish", System.currentTimeMillis() - __t15) { a, b -> a + b }
            work = rC3run.newSchedule.copy2D(); totalC3run += rC3run.applied; roundApplied += rC3run.applied
            rC3run.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3run.logs)

            // [C3PatternPolish・玉突き連鎖の横展開その4] 複数シフトc3/c3mパターン(非single-shift)を、
            //   交換相手が構造的に存在しない局面向けに findCovUChain で研磨（棚卸し監査で発見、ユーザー承認）。
            onPhase("後処理 連続規則(c3/c3m複数シフトパターン)玉突き研磨 [巡${round + 1}]")
            val __t16 = System.currentTimeMillis()
            val rC3pat = C3FamilyPolish.applyC3PatternPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xC3B4L, round))
            passMs.merge("C3PatternPolish", System.currentTimeMillis() - __t16) { a, b -> a + b }
            work = rC3pat.newSchedule.copy2D(); totalC3pat += rC3pat.applied; roundApplied += rC3pat.applied
            rC3pat.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rC3pat.logs)

            // [AdaptiveBlockSwap・長期ブロック丸ごと2人交換] 15日固定の旧手を、11/13/17/19/23/28日の
            //   非等間隔ポートフォリオへ拡張。同群に限らず、ブロック内の全セルを相互に担当可能な他者も
            //   候補にし、希望固定・厳密ピン・正式スコアの全ガードを通過した最良の1手だけを採用する。
            onPhase("後処理 長期ブロック丸ごと交換(11/13/17/19/23/28日) [巡${round + 1}]")
            val __t17 = System.currentTimeMillis()
            val rBlockSwap = applyAdaptiveBlockSwapPolish(
                state, work, maxPasses = 2, candidatesPerLength = 8, maxEvaluations = 48, shouldStop = clusterStop,
            )
            passMs.merge("AdaptiveBlockSwapPolish", System.currentTimeMillis() - __t17) { a, b -> a + b }
            rBlockSwap.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = rBlockSwap.newSchedule.copy2D(); totalBlockSwap += rBlockSwap.applied; roundApplied += rBlockSwap.applied
            if (round == 0) logs.addAll(rBlockSwap.logs)

            // [AptPolish・適切回数(apt)専用研磨] 自己振替→同一グループ相互交換→玉突きチェーンの順で
            //   apt(重み1)違反を専用に研磨（grilling 2026-07-19、大島愛の休/Pｼ実例）。
            onPhase("後処理 適切回数(apt)研磨 [巡${round + 1}]")
            val __t18 = System.currentTimeMillis()
            val rApt = applyAptPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xA97L, round))
            passMs.merge("AptPolish", System.currentTimeMillis() - __t18) { a, b -> a + b }
            work = rApt.newSchedule.copy2D(); totalApt += rApt.applied; roundApplied += rApt.applied
            rApt.pinBlocks?.let { pinBlocksAll.merge(it) }
            if (round == 0) logs.addAll(rApt.logs)

            // [FairPolish・グループ内公平化(fair)専用研磨] 棚卸し(c42/c42s以外の「動かせるか」欠如監査)で
            //   発見。AptPolishと同型の3段構成（自己振替→同一グループ相互交換→玉突きチェーン）。
            onPhase("後処理 グループ内公平化(fair)玉突き研磨 [巡${round + 1}]")
            val __t19 = System.currentTimeMillis()
            val rFair = applyFairPolish(state, work, maxPasses = 3, shouldStop = clusterStop, seed = roundSeed(seed, 0xFA12L, round))
            passMs.merge("FairPolish", System.currentTimeMillis() - __t19) { a, b -> a + b }
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
            val adopted = totalCyc + totalC1 + totalC3 + totalC3r + totalC3mn + totalC3n + totalRange + totalC3run + totalC3pat + totalBlockSwap + totalApt + totalFair
            // [3.278.0/監査修正] CyclicSwap の正当な対象族(c2/c41/c42/c41s/c42s/covO)も対象数に含める
            //   （旧: c42等のみ違反の盤面で採用0のとき誤って「対象なし」と表示していた）。
            val targets = bd(preSoftRep, "c1") + bd(preSoftRep, "c3") + bd(preSoftRep, "c3m") + bd(preSoftRep, "c3mn") +
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
                    " (採用内訳 循環:${totalCyc} c1:${totalC1} c3:${totalC3} c3回転:${totalC3r} c3mn玉突き:${totalC3mn} c3n:${totalC3n} range玉突き:${totalRange} c3run玉突き:${totalC3run} c3pattern玉突き:${totalC3pat} ブロック交換:${totalBlockSwap} apt玉突き:${totalApt} fair玉突き:${totalFair})"))
        }

        // [weekly 研磨の穴を埋める] 曜日平準化(weekly)は同日2者スワップでは動かせない（勤務↔勤務は曜日別の
        //   勤務/休が不変）ため、被覆保存の2職員×2日 長方形交換で「過剰曜日→過少曜日」へ勤務を移す。実目的関数
        //   isBetter で採否＝退化なし。下の equalize 系(分散指標)より先に L1 指向のこのパスを走らせる。
        onPhase("後処理 曜日平準化(長方形交換)")
        val __t20 = System.currentTimeMillis()
        val rWrb = applyWeeklyRebalancePolish(state, work, maxPasses = 2, shouldStop = clusterStop)
        passMs.merge("WeeklyRebalancePolish", System.currentTimeMillis() - __t20) { a, b -> a + b }
        rWrb.pinBlocks?.let { pinBlocksAll.merge(it) }
        work = rWrb.newSchedule.copy2D()
        logs.addAll(rWrb.logs)

        // [交互最適化(Alternating Optimization)] 長方形交換(クロス日)が届かない同日内の「休の割当先」を、日ブロック
        //   ごとの最小費用割当(Hungarian＝凸最適化)で weekly/range/apt 同時最適に再配置し、不動点まで巡回する。
        //   rectangle(クロス日)と AO(同日内)は相補的＝両方走らせて weekly の取りこぼしを二方向から詰める。keep-best。
        onPhase("後処理 交互最適化(日ブロック割当)")
        val __t21 = System.currentTimeMillis()
        val rAlt = DayAssignmentPolish.applyAlternatingSoftPolish(state, work, maxSweeps = 4, shouldStop = clusterStop)
        passMs.merge("AlternatingSoftPolish", System.currentTimeMillis() - __t21) { a, b -> a + b }
        rAlt.pinBlocks?.let { pinBlocksAll.merge(it) }
        work = rAlt.newSchedule.copy2D()
        logs.addAll(rAlt.logs)

        // [3.317.0] ここにあった分散指標ベースの平準化2パスは撤去した（実測で寄与ゼロ）。詳細は
        //   本ファイル内 applyWeeklyRebalancePolish 直前の撤去メモを参照
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
        val tC1Lns = System.currentTimeMillis()
        val remainingForC1Lns = (deadlineMs - tC1Lns).coerceAtLeast(0L).coerceAtMost(100_000L)
        val c1LnsCap = (remainingForC1Lns * 8_000L / 14_000L).coerceAtMost(8_000L)
        val __t22 = System.currentTimeMillis()
        val rC1Lns = C1RepairOperators.jointLns(
            state, work, config = C1JointLnsPolish.Config(maxMillis = c1LnsCap), shouldStop = shouldStop,
        )
        passMs.merge("C1共同LNS", System.currentTimeMillis() - __t22) { a, b -> a + b }
        work = rC1Lns.newSchedule.copy2D()
        // [3.350.0/敵対検証] 最終LNS 2パスのピン却下が pinBlocksAll へ合流していなかった
        //   （旧: この2パスは PinBlockAttribution を作らず pinBlocks が常に null だった）。
        rC1Lns.pinBlocks?.let { pinBlocksAll.merge(it) }
        logs.addAll(rC1Lns.logs)

        onPhase("後処理 個人回数/適切回数 共同LNS")
        val tPersonalLns = System.currentTimeMillis()
        val personalLnsCap = (deadlineMs - tPersonalLns).coerceAtLeast(0L).coerceAtMost(6_000L)
        val __t23 = System.currentTimeMillis()
        val rPersonalLns = PersonalBalanceJointLnsPolish.apply(
            state, work, config = PersonalBalanceJointLnsPolish.Config(maxMillis = personalLnsCap), shouldStop = shouldStop,
        )
        passMs.merge("個人回数共同LNS", System.currentTimeMillis() - __t23) { a, b -> a + b }
        work = rPersonalLns.newSchedule.copy2D()
        rPersonalLns.pinBlocks?.let { pinBlocksAll.merge(it) }
        logs.addAll(rPersonalLns.logs)

        val tHf = System.currentTimeMillis()
        if (shouldStop()) {
            // [3.278.0/文言修正] この時点で残るのは最終検査(HF70)のみ＝「残りパスの打ち切り」は各パス内部の
            //   shouldStop で既に済んでいる事実に合わせる。
            logs.add(MirrorLog(level = "W", tag = "POST", message = "予算超過のため後処理は締切で短縮されました(各パスは内部で打ち切り済み・以降は最終検査のみ)"))
        }

        onPhase("後処理 HF70 異常検知")
        val report = UnifiedViolationChecker.check(state, work)
        val r70 = HfSwapPolish.detectHF70Anomalies(state, work, algoName, report)
        logs.addAll(r70.logs)

        val tEnd = System.currentTimeMillis()
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
     * [ソフト研磨・T2] 被覆を保つ循環交換（k=2,3）研磨。各日の (日,シフト) 人数を保ったまま、職員の
     * シフトを **2職員スワップ / 3職員ローテーション** で組み替える。被覆は不変＝HARD充足を維持し、
     * 連続規則(c3/c3m) や希望・回数の相互作用を**実目的関数(UnifiedViolationChecker)で評価**して
     * 改善時のみ採用（keep-best＝退化なし）。日内Hungarian(range/apt最適)が触れない c3 を狙う。
     * 注: 提案サイクルは必ず実チェックで検証してから採用するため、サイクル生成が不完全でも悪化しない。
     */
    fun applyCyclicSwapPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 4, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        // [監査で発見・3.270.0] p.wish[i][j]<0 は「希望が一切ない」判定で、実現不能な希望
        //   (canDo(i,wish)==false)まで動かせないと誤判定していた（3.183.0 LightMirrorOptimizer と
        //   同型のバグ）。実現不能な希望はpref計上上も定数=動かして良い＝canDoガード込みの
        //   wishLocked が正しい判定。安全側（isBetter/checkerが最終ゲート）で候補が広がるのみ。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (j in 0 until p.T) {
                if (shouldStop()) break
                // --- k=2: 2職員スワップ（同日・被覆不変）---
                for (a in 0 until p.S) {
                    // [監査(未レビュー領域再監査)] HF66(2.65.0)/BlockRotationPolish(3.84.0)と同型の予算超過対策。
                    //   旧: 日(j)ループ先頭のみで確認していたため、1日分のO(S^2)スキャンが締切後も走り切っていた。
                    if (shouldStop()) break
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        val sa = work[a][j]; val sb = work[b][j]
                        if (sa == sb || !p.canDo(a, sb) || !p.canDo(b, sa)) continue
                        // [厳密ピン保護] 異なるシフト同士の同日交換はa/bの自身のシフト回数を変えるため、
                        //   staffRange厳密ピン(lo==hi)を新たに崩す候補は不採用にする（keep-best/重み不変）。
                        val workBeforeSwap2 = work.copy2D()
                        work[a][j] = sb; work[b][j] = sa
                        val rep = UnifiedViolationChecker.check(state, work)
                        if (isBetter(rep, bestRep) && !pinBlocks.blocksImproving(p, workBeforeSwap2, work)) { bestRep = rep; applied++; improved = true }
                        else { work[a][j] = sa; work[b][j] = sb }
                    }
                }
                // --- k=3: 3職員ローテーション（同日・被覆不変）---
                for (a in 0 until p.S) {
                    if (shouldStop()) break
                    if (!movable(a, j)) continue
                    for (b in a + 1 until p.S) {
                        if (!movable(b, j)) continue
                        for (c in b + 1 until p.S) {
                            if (!movable(c, j)) continue
                            if (shouldStop()) break
                            val sa = work[a][j]; val sb = work[b][j]; val sc = work[c][j]
                            if (sa == sb && sb == sc) continue
                            // a←sb, b←sc, c←sa（feasibleなら適用→評価→不採用なら巻き戻し）
                            if (p.canDo(a, sb) && p.canDo(b, sc) && p.canDo(c, sa)) {
                                val workBeforeRotate3 = work.copy2D()
                                work[a][j] = sb; work[b][j] = sc; work[c][j] = sa
                                val rep = UnifiedViolationChecker.check(state, work)
                                if (isBetter(rep, bestRep) && !pinBlocks.blocksImproving(p, workBeforeRotate3, work)) { bestRep = rep; applied++; improved = true; continue }
                                work[a][j] = sa; work[b][j] = sb; work[c][j] = sc
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "CyclicSwap",
            message = "循環交換(k=2,3)研磨: total ${before.total}->${bestRep.total} 採用${applied}回"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }


    // [3.317.0] 分散指標ベースの平準化2パス（applyGroupShiftEqualizePolish / applyWeeklyEqualizePolish）は
    //   ここにあったが撤去した。目的関数の fair/weekly は 3.72.0 以降 **L1偏差**で評価されるのに、この2パスは
    //   **分散**を下げる手を採っており、指標が目的関数と一致していなかった（3.84.0 で「目的関数外の整え＝冗長」
    //   と記録したまま未計測だった）。実データ3件で ablation を取り、採用0回・分散指標も1ミリも動かず・
    //   最終盤面も変わらないことを確認して撤去。L1 ベースの後継が役割を完全に代替している:
    //   fair → `applyFairPolish`(3.235.0) ／ weekly → `applyWeeklyRebalancePolish`(3.197.0 長方形交換)＋
    //   `applyAlternatingSoftPolish`(3.198.0 が weekly の限界費用を Hungarian の費用に含む)。

    /**
     * [ソフト研磨・weekly（7日周期のシフト平準化）＝長方形交換] weekly は「職員が特定の曜日にばかり同じシフトに
     * 入る」偏りで、L1偏差（`weeklyDevOfBucket`＝そのシフトの曜日別回数の round(回数/7) からの偏差和）で評価される。
     * **同日2者スワップ（CyclicSwap / equalize 系）は同じ日の中で入れ替えるだけなので、どの曜日に何が入るかを
     * 動かせない**。これが「weekly の研磨ができていない」実害の根本（実機ログで weekly＝SOFT 残差の最大級）。
     *
     * そこで **被覆保存の 2職員×2日 長方形交換** を導入する: 職員 i がシフト x について「過剰曜日の日 j1 で x・
     * 過少曜日の日 j2 で別のシフト y」、相手 i' が「j1 で別のシフト z・j2 で x」のとき、両者の j1/j2 を丸ごと
     * 入替える（i: j1→z / j2→x、i': j1→x / j2→y）。各日の各シフト人数は保存される（j1 の x は i→i'、j2 の x は
     * i'→i へ移るだけ）ため covU/covO・群レンジ・pref は不変で、i の x が過剰曜日→過少曜日へ移動して weekly が
     * 下がる。fair（群内シフト回数）や low/high/apt/c2 など per-staff 族も副次的に動く。
     * [3.345.0] 休を通常のシフト種として扱う定義に合わせ、x/y/z を勤務・休で区別しない（旧: x=勤務・y=z=休 の
     * 特殊形のみ＝休だけを「空き」とみなしていた）。旧形は新形の部分集合なので探索範囲は広がるだけ。
     * **採否は実目的関数 isBetter のみ**（hard→weighted→total、total は weekly/fair を含む）＝退化なし（keep-best）。
     * dev>0 の (職員,シフト) のみ起点＋first-improvement で空探索は即終了。変更セルは wish 固定なら不動
     * （4セルとも movable ガード）。covO/c42/c2 など per-day 族は同日 CyclicSwap（isBetter）が既に最適に研磨済みの
     * ため本パスの対象外（2.49.0 の「専用パスは冗長」の結論を踏襲）。
     */
    fun applyWeeklyRebalancePolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 2, shouldStop: () -> Boolean = { false }): CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun weekdayOf(j: Int) = (p.dow0 + j) % 7
        // [3.345.0] 職員×シフト×曜日のカウント（休も1シフト）。
        fun wdBucket(i: Int): Array<IntArray> {
            val wd = Array(p.K) { IntArray(7) }
            for (j in 0 until p.T) { val k = work[i][j]; if (k in 0 until p.K) wd[k][weekdayOf(j)]++ }
            return wd
        }
        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            for (i in 0 until p.S) {
                if (shouldStop()) break
                val wdAll = wdBucket(i)
                for (x in 0 until p.K) {
                    if (improved || shouldStop()) break
                    val wd = wdAll[x]
                    if (weeklyDevOfBucket(wd) == 0) continue
                    var sum = 0; for (w in wd) sum += w
                    val tgt = Math.round(sum.toDouble() / 7.0).toInt()
                    // シフト x が最も過剰な曜日と最も過少な曜日を1つずつ狙う。
                    var wOver = -1; var wUnder = -1; var maxOver = 0; var maxUnder = 0
                    for (w in 0 until 7) {
                        if (wd[w] - tgt > maxOver) { maxOver = wd[w] - tgt; wOver = w }
                        if (tgt - wd[w] > maxUnder) { maxUnder = tgt - wd[w]; wUnder = w }
                    }
                    if (wOver < 0 || wUnder < 0) continue
                    // i が過剰曜日に x に入っている日 / 過少曜日に x 以外に入っている日（どちらも movable）。
                    val overDays = (0 until p.T).filter { weekdayOf(it) == wOver && movable(i, it) && work[i][it] == x }
                    val underDays = (0 until p.T).filter { weekdayOf(it) == wUnder && movable(i, it) && work[i][it] != x && work[i][it] in 0 until p.K }
                    var done = false
                    for (j1 in overDays) {
                        if (done || shouldStop()) break
                        for (j2 in underDays) {
                            // [レビュー#6 3.213.0] 内側ループにも締切確認（各候補がフル check を伴うため、
                            //   キャンセル後のバーストを1候補以内に抑える。HF66=2.65.0/BlockRotation=3.84.0 と同方針）。
                            if (done || shouldStop()) break
                            val y = work[i][j2]
                            for (ip in 0 until p.S) {
                                if (done || shouldStop()) break
                                if (ip == i) continue
                                // 相手 i' は j1 で x 以外(z)・j2 で x、両日 movable。被覆保存には i←z(j1), i'←y(j2) が担当可であること。
                                if (!movable(ip, j1) || !movable(ip, j2)) continue
                                if (work[ip][j2] != x) continue
                                val z = work[ip][j1]
                                if (z == x || z !in 0 until p.K) continue
                                if (!p.canDo(i, z) || !p.canDo(ip, y)) continue
                                // 長方形交換を適用（被覆保存）→ フル評価 → 改善時のみ採用、不採用なら完全巻き戻し。
                                // [監査で発見・3.270.0] isBetter は hard→weightedScore→total の辞書式のため、
                                //   raw total が改善してもweightedScoreが悪化する組合せ(重い厳密ピン破りを軽い
                                //   weekly改善が数の上で上回る)がありうる。同型の全パスに既に適用済みの
                                //   exactPinRegression ガードをここにも追加（3.256.0の retrofit 漏れ）。
                                val workBeforeRect = work.copy2D()
                                work[i][j1] = z; work[i][j2] = x; work[ip][j1] = x; work[ip][j2] = y
                                val rep = UnifiedViolationChecker.check(state, work)
                                if (isBetter(rep, bestRep) && !pinBlocks.blocksImproving(p, workBeforeRect, work)) { bestRep = rep; applied++; improved = true; done = true; break }
                                work[i][j1] = x; work[i][j2] = y; work[ip][j1] = z; work[ip][j2] = x
                            }
                        }
                    }
                }
            }
            pass++
            if (!improved) break
        }
        val logs = listOf(MirrorLog(tag = "WeeklyRebalance",
            message = "曜日平準化(長方形交換): total ${before.total}->${bestRep.total} 採用${applied}回"))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }

    /**
     * [AptPolish・適切回数(apt, 重み1)専用の研磨パス] ユーザー指示「専用の研磨パスAptPolish的なものを
     * 賢く深く網羅的に作る」（grillingで確定: ①自己振替最優先 ②同一グループ内の相互交換(同日1対1・
     * 被覆総量保存で安全) ③RangePolish型の玉突きチェーン、の順で試す）。
     *
     * 動機（大島愛の実例）: 群目標(groupShiftApt)に対しaptHigh(超過)とaptLow(不足)が同一職員内に同時に
     * 存在するケース（休=超過・Pｼ=不足）は、本人内で1日分を振替えるだけで両方が同時に改善する「タダの
     * 交換」のはずだが、apt(重み1)はRSI探索中のfocus選択で軽視されやすく(3.169.0)、専用研磨が無いまま
     * 残っていた。
     *
     * アンカー: `report.countViolations`（"i,k"→"vio-aptHigh"/"vio-aptLow"、markCountの重み優先解決済）
     * から違反している(staff,shift)ペアを列挙。
     * 手①自己振替: 同一職員が別のシフトでaptLow(逆方向)を持つ場合、その2シフト間で1日を直接付け替える
     *   （他人に一切影響しない最安全な手）。付け替え元/先双方の被覆(covUCell)を悪化させない日のみ候補
     *   にする（悪化するならチェーンを使わず単に見送り＝真に無償の手のみを対象にする）。
     * 手②相互交換: 同一グループ(canDo完全一致)内に、同じシフトで逆方向のapt不均衡を持つ相手がいれば、
     *   同日の2人の割当をまるごと入替える（同日swap＝被覆総量保存＝構造的に安全、BlockSwapPolishと
     *   同型の安全性。相手のcanDoは同一グループのため保証済み）。
     * 手③玉突きチェーン: 上記いずれでも解消しない残りは、RangePolishと同型のfindCovUChain（候補が
     *   自身の新規apt違反を招くなら後回しにするavoid述語つき）で任意の担当可能シフトへ移す。
     * 採否はisBetter(hard→weighted→total)keep-best＝退化不能。全手とも希望固定(movable)・禁止連続
     * (makesForbiddenRun)を事前ガード。
     */
    fun applyAptPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xA97L): CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun label(i: Int, k: Int) = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(k)?.kigou ?: k.toString()}"
        val fixedNames = ArrayList<String>()
        // [汎用玉突き結合フレームワーク, 3.249.0] tryChainRelocate(手③)が単独では不採用だった候補を
        //   蓄積し末尾で束ねる。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()

        // [玉突きチェーンのavoid述語] 候補がfillShiftを1つ得ると自身のapt目標からちょうど新規に
        //   乖離するか（既に乖離済みなら「まだ動いていない」ので中立扱い＝対象外）。
        fun worsensOwnApt(staff: Int, fillShift: Int): Boolean {
            val t = p.apt[staff][fillShift]
            if (t < 0) return false
            var c = 0
            for (jj in 0 until p.T) if (work[staff][jj] == fillShift) c++
            return c == t
        }

        // [厳密ピン保護] 本パスの全手は i(・相手)の回数を直接変える(apt/fair研磨の本質)ため、staffRange
        //   厳密ピン(lo==hi)を新たに崩す候補だけは不採用にする（keep-best/重みは不変・追加ガードのみ）。
        fun applyAndCheck(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            val workBefore = work.copy2D()
            work[i][j] = toK
            val rep = UnifiedViolationChecker.check(state, work)
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBefore, work)
            if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            work[i][j] = fromK
            return false
        }

        // 手①: 自身の中でfromK(過多)→toK(過少)への1日付け替え。被覆非悪化の日のみ候補にする。
        fun trySelfSwap(i: Int, fromK: Int, toK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                if (work[i][j] != fromK || !movable(i, j)) continue
                if (p.makesForbiddenRun(work, i, j, toK)) continue
                var cntFrom = 0; var cntTo = 0
                for (s in 0 until p.S) { if (work[s][j] == fromK) cntFrom++; if (work[s][j] == toK) cntTo++ }
                if (p.covUCell(fromK, j, cntFrom - 1) > p.covUCell(fromK, j, cntFrom)) continue
                if (p.covUCell(toK, j, cntTo + 1) > p.covUCell(toK, j, cntTo)) continue
                if (applyAndCheck(i, j, fromK, toK)) return true
            }
            return false
        }

        // 手②: 同一グループ内で同日の2人の割当をまるごと入替（被覆総量保存＝安全）。
        fun tryMutualSwap(i: Int, i2: Int, sharedK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                val a = work[i][j]; val b = work[i2][j]
                if (a != sharedK || b == sharedK) continue
                if (!movable(i, j) || !movable(i2, j)) continue
                if (p.makesForbiddenRun(work, i, j, b) || p.makesForbiddenRun(work, i2, j, a)) continue
                val workBefore = work.copy2D()
                work[i][j] = b; work[i2][j] = a
                val rep = UnifiedViolationChecker.check(state, work)
                val pinBad = exactPinRegression(p, workBefore, work)
                if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBefore, work)
                if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = a; work[i2][j] = b
            }
            return false
        }

        // 手③: RangePolish型の玉突きチェーン。
        fun tryChainRelocate(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, j) || p.makesForbiddenRun(work, i, j, toK)) return false
            var cnt = 0
            for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
            val needsChain = p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)
            val workBeforeRelocate = work.copy2D()
            work[i][j] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work)
                val pinBad = exactPinRegression(p, workBeforeRelocate, work)
                if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBeforeRelocate, work)
                if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = fromK
                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)), "AptChain", label(i, fromK)))
                return false
            }
            val chain = findCovUChain(p, work, fromK, j, rng, exclude = i,
                rangeAvoid = { st, fk -> worsensOwnApt(st, fk) })
            if (chain == null) { work[i][j] = fromK; return false }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work)
            val pinBad = exactPinRegression(p, workBeforeRelocate, work)
            if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBeforeRelocate, work)
            if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][j] = fromK
            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)) + chain, "AptChain", label(i, fromK)))
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            val highTargets = ArrayList<Pair<Int, Int>>()
            val lowTargets = ArrayList<Pair<Int, Int>>()
            for ((key, cls) in rep0.countViolations) {
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val k = parts.getOrNull(1)?.toIntOrNull() ?: continue
                when (cls) {
                    "vio-aptHigh" -> highTargets.add(i to k)
                    "vio-aptLow" -> lowTargets.add(i to k)
                }
            }
            if (highTargets.isEmpty() && lowTargets.isEmpty()) break

            for ((i, k) in highTargets) {
                if (shouldStop()) break
                var done = false
                // 手①: 自身の別シフトでaptLowのものへ振替（同一(fromK,toK)ペアで解消するまで反復＝
                //   RangePolishの「上限まで反復して落とす」と同型に統一。他者に一切影響しない自己完結の
                //   手のためisBetterが認める限り繰り返して安全。旧実装は1回成功したら次のhighTargetsへ
                //   移っており、excess/deficitが複数単位ある職員は1パスにつき1単位しか解消できず、
                //   予算超過で後続パスが打ち切られると大きな乖離が残存し続けていた）。
                for (k2 in 0 until p.K) {
                    if (shouldStop()) break
                    if (k2 == k || !p.canDo(i, k2)) continue
                    if (lowTargets.none { it.first == i && it.second == k2 }) continue
                    while (trySelfSwap(i, k, k2)) { improved = true; done = true }
                }
                if (done) fixedNames.add(label(i, k))
                // 手②: 同一グループで逆方向(aptLow)の相手と相互交換。
                if (!done) {
                    for (i2 in 0 until p.S) {
                        if (done || shouldStop()) break
                        if (i2 == i || p.sgrp[i2] != p.sgrp[i]) continue
                        if (lowTargets.none { it.first == i2 && it.second == k }) continue
                        if (tryMutualSwap(i, i2, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                    }
                }
                // 手③: 玉突きチェーンで任意の担当可能シフトへ。
                if (!done) {
                    for (j in 0 until p.T) {
                        if (done || shouldStop()) break
                        if (work[i][j] != k) continue
                        for (alt in p.allowedShiftsForStaff(i)) {
                            if (done || shouldStop()) break
                            if (alt == k) continue
                            if (tryChainRelocate(i, j, k, alt)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                        }
                    }
                }
            }
            // 単独aptLow(自己振替/相互交換で解消しなかった残り)を玉突きチェーンで埋める。
            for ((i, k) in lowTargets) {
                if (shouldStop()) break
                if (!p.canDo(i, k)) continue
                var done = false
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    val oldK = work[i][j]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryChainRelocate(i, j, oldK, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames より前に実行し、結合で解消した箇所が
        //   「残存」に残らないようにする。
        val aptCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::isBetter, shouldStop = shouldStop, stats = aptCombStats, p = p,
        )
        applied += aptCombStats.combosAccepted
        val stuckNames = bestRep.countViolations.entries
            .filter { it.value == "vio-aptHigh" || it.value == "vio-aptLow" }
            .mapNotNull { (key, _) ->
                val parts = key.split(",")
                val i = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val k = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                label(i, k)
            }
        val aptCombSummary = aptCombStats.summary()
        val logs = listOf(MirrorLog(tag = "AptPolish",
            message = "適切回数(apt)研磨: apt ${before.breakdown["apt"] ?: 0}->${bestRep.breakdown["apt"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["apt"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                (if (fixedNames.isNotEmpty()) " 対象: ${fixedNames.joinToString(", ")}" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (aptCombSummary.isNotEmpty()) " / $aptCombSummary" else "")))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }

    /**
     * [FairPolish・グループ内公平化(fair, 重み1)専用の研磨パス] ユーザー指示「c42/c42s以外にも
     * 『動かせるか』専用オペレータの欠如が無いか棚卸しする」で発見（棚卸し結果はユーザー承認済み）。
     * fair は群×担当ONシフトごとにメンバー回数の round(平均)からのL1偏差和で、apt(3.223.0)と
     * ほぼ同型の違反構造。しかし当時の平準化パス（同日2者スワップ＋**分散**指標での山登り）はチェーン救済が
     * 無く、交換相手が構造的に不在（希望固定/禁止連続/候補不足）だと頭打ちする、covO/c41/c41s/c42/c42s/apt と
     * 同型の穴だった（その平準化パス自体は 3.317.0 で実測寄与ゼロを確認して撤去済み）。AptPolish(3.223.0)と同一の3段構成
     * （①自己振替 ②同一グループ内相互交換 ③玉突きチェーン）をfair向けに移植する。
     *
     * fair の目標(tgt)は「その時点のグループ合計の round(平均)」で apt の固定目標と異なり、1日の
     * 付け替えごとに動く。手①②③はいずれも候補選定のスナップショット近似（各手を試す時点で
     * counts/tgt を再計算）でよく、最終的な採否は常に isBetter(実目的関数)が担うため、tgt の近似が
     * ズレても安全性は損なわれない（見逃しても isBetter が拒否するだけ・過大選定しても isBetter が
     * 拒否するだけ）。採否はisBetter(hard→weighted→total)keep-best＝退化不能。全手とも希望固定
     * (movable)・禁止連続(makesForbiddenRun)を事前ガード。
     */
    fun applyFairPolish(state: MagiState, schedule: Array<IntArray>, maxPasses: Int = 3, shouldStop: () -> Boolean = { false }, seed: Long = 0xFA12L): CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        var bestRep = before
        var applied = 0
        val rng = Random(seed)
        // [監査で発見・3.270.0] p.wish[i][j]<0 は実現不能な希望まで動かせないと誤判定していた
        //   （3.183.0 LightMirrorOptimizer と同型のバグ）。wishLocked は canDo ガード込みで正しい。
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)
        fun label(i: Int, k: Int) = "${state.staff.getOrNull(i)?.name ?: "#$i"} ${state.shifts.getOrNull(k)?.kigou ?: k.toString()}"
        val fixedNames = ArrayList<String>()
        // [汎用玉突き結合フレームワーク, 3.249.0] tryChainRelocate(手③)が単独では不採用だった候補を
        //   蓄積し末尾で束ねる。
        val combinable = ArrayList<CombinatorialRepair.Candidate>()
        val rejectCulprits = RejectCulpritStats()

        fun fairTarget(g: Int, k: Int, counts: Array<IntArray>): Int {
            val mem = p.groupMembers.getOrNull(g) ?: return 0
            if (mem.isEmpty()) return 0
            var sum = 0
            for (x in mem) sum += counts[x][k]
            return Math.round(sum.toDouble() / mem.size).toInt()
        }

        // [玉突きチェーンのavoid述語] 候補がfillShiftを1つ得ると、候補自身の群目標(スナップショット近似)
        //   からちょうど新規に乖離するか（既に乖離済みなら中立扱い＝対象外）。
        fun worsensOwnFair(staff: Int, fillShift: Int): Boolean {
            val g = p.sgrp.getOrNull(staff) ?: return false
            if (g !in p.bucket.indices || fillShift !in p.bucket[g]) return false
            val counts = countMatrix(p, work)
            val tgt = fairTarget(g, fillShift, counts)
            return counts[staff][fillShift] == tgt
        }

        // [厳密ピン保護] 本パスの全手は i(・相手)の回数を直接変える(apt/fair研磨の本質)ため、staffRange
        //   厳密ピン(lo==hi)を新たに崩す候補だけは不採用にする（keep-best/重みは不変・追加ガードのみ）。
        fun applyAndCheck(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            val workBefore = work.copy2D()
            work[i][j] = toK
            val rep = UnifiedViolationChecker.check(state, work)
            val pinBad = exactPinRegression(p, workBefore, work)
            if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBefore, work)
            if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            work[i][j] = fromK
            return false
        }

        // 手①: 自身の中でfromK(過多)→toK(過少)への1日付け替え。被覆非悪化の日のみ候補にする。
        fun trySelfSwap(i: Int, fromK: Int, toK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                if (work[i][j] != fromK || !movable(i, j)) continue
                if (p.makesForbiddenRun(work, i, j, toK)) continue
                var cntFrom = 0; var cntTo = 0
                for (s in 0 until p.S) { if (work[s][j] == fromK) cntFrom++; if (work[s][j] == toK) cntTo++ }
                if (p.covUCell(fromK, j, cntFrom - 1) > p.covUCell(fromK, j, cntFrom)) continue
                if (p.covUCell(toK, j, cntTo + 1) > p.covUCell(toK, j, cntTo)) continue
                if (applyAndCheck(i, j, fromK, toK)) return true
            }
            return false
        }

        // 手②: 同一グループ内で同日の2人の割当をまるごと入替（被覆総量保存＝安全）。
        fun tryMutualSwap(i: Int, i2: Int, sharedK: Int): Boolean {
            for (j in 0 until p.T) {
                if (shouldStop()) return false
                val a = work[i][j]; val b = work[i2][j]
                if (a != sharedK || b == sharedK) continue
                if (!movable(i, j) || !movable(i2, j)) continue
                if (!p.canDo(i, b) || !p.canDo(i2, a)) continue
                if (p.makesForbiddenRun(work, i, j, b) || p.makesForbiddenRun(work, i2, j, a)) continue
                val workBefore = work.copy2D()
                work[i][j] = b; work[i2][j] = a
                val rep = UnifiedViolationChecker.check(state, work)
                val pinBad = exactPinRegression(p, workBefore, work)
                if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBefore, work)
                if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = a; work[i2][j] = b
            }
            return false
        }

        // 手③: RangePolish/AptPolish型の玉突きチェーン。
        fun tryChainRelocate(i: Int, j: Int, fromK: Int, toK: Int): Boolean {
            if (!movable(i, j) || p.makesForbiddenRun(work, i, j, toK)) return false
            var cnt = 0
            for (s in 0 until p.S) if (work[s][j] == fromK) cnt++
            val needsChain = p.covUCell(fromK, j, cnt - 1) > p.covUCell(fromK, j, cnt)
            val workBeforeRelocate = work.copy2D()
            work[i][j] = toK
            if (!needsChain) {
                val rep = UnifiedViolationChecker.check(state, work)
                val pinBad = exactPinRegression(p, workBeforeRelocate, work)
                if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBeforeRelocate, work)
                if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
                rejectCulprits.record(rep, bestRep, pinBad)
                work[i][j] = fromK
                combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)), "FairChain", label(i, fromK)))
                return false
            }
            val chain = findCovUChain(p, work, fromK, j, rng, exclude = i,
                rangeAvoid = { st, fk -> worsensOwnFair(st, fk) })
            if (chain == null) { work[i][j] = fromK; return false }
            val oldVals = IntArray(chain.size) { work[chain[it][0]][chain[it][1]] }
            chain.forEach { mv -> work[mv[0]][mv[1]] = mv[2] }
            val rep = UnifiedViolationChecker.check(state, work)
            val pinBad = exactPinRegression(p, workBeforeRelocate, work)
            if (pinBad && isBetter(rep, bestRep)) pinBlocks.record(p, workBeforeRelocate, work)
            if (isBetter(rep, bestRep) && !pinBad) { bestRep = rep; applied++; return true }
            rejectCulprits.record(rep, bestRep, pinBad)
            for (idx in chain.indices) work[chain[idx][0]][chain[idx][1]] = oldVals[idx]
            work[i][j] = fromK
            combinable.add(CombinatorialRepair.Candidate(listOf(intArrayOf(i, j, toK)) + chain, "FairChain", label(i, fromK)))
            return false
        }

        var pass = 0
        while (pass < maxPasses) {
            if (shouldStop()) break
            var improved = false
            val rep0 = if (pass == 0) before else UnifiedViolationChecker.check(state, work)
            val locs = rep0.distLocations["fair"].orEmpty()
            if (locs.isEmpty()) break
            val counts = countMatrix(p, work)
            val highTargets = ArrayList<Pair<Int, Int>>()   // (staff, shift) 過多
            val lowTargets = ArrayList<Pair<Int, Int>>()    // (staff, shift) 過少
            for (loc in locs) {
                val x = loc.getOrNull(0) ?: continue
                val k = loc.getOrNull(1) ?: continue
                if (x !in 0 until p.S || k !in 0 until p.K) continue
                val g = p.sgrp.getOrNull(x) ?: continue
                if (g !in p.bucket.indices) continue
                val tgt = fairTarget(g, k, counts)
                when {
                    counts[x][k] > tgt -> highTargets.add(x to k)
                    counts[x][k] < tgt -> lowTargets.add(x to k)
                }
            }
            if (highTargets.isEmpty() && lowTargets.isEmpty()) break

            for ((i, k) in highTargets) {
                if (shouldStop()) break
                var done = false
                // 手①: 自身の別シフトでfairLow(逆方向)のものへ振替（AptPolishと同型に統一。同一
                //   (fromK,toK)ペアで解消するまで反復。isBetterが認める限り繰り返して安全）。
                for (k2 in 0 until p.K) {
                    if (shouldStop()) break
                    if (k2 == k || !p.canDo(i, k2)) continue
                    if (lowTargets.none { it.first == i && it.second == k2 }) continue
                    while (trySelfSwap(i, k, k2)) { improved = true; done = true }
                }
                if (done) fixedNames.add(label(i, k))
                // 手②: 同一グループで逆方向(fairLow)の相手と相互交換。
                if (!done) {
                    for (i2 in 0 until p.S) {
                        if (done || shouldStop()) break
                        if (i2 == i || p.sgrp.getOrNull(i2) != p.sgrp.getOrNull(i)) continue
                        if (lowTargets.none { it.first == i2 && it.second == k }) continue
                        if (tryMutualSwap(i, i2, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                    }
                }
                // 手③: 玉突きチェーンで任意の担当可能シフトへ。
                if (!done) {
                    for (j in 0 until p.T) {
                        if (done || shouldStop()) break
                        if (work[i][j] != k) continue
                        for (alt in p.allowedShiftsForStaff(i)) {
                            if (done || shouldStop()) break
                            if (alt == k) continue
                            if (tryChainRelocate(i, j, k, alt)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                        }
                    }
                }
            }
            // 単独fairLow(自己振替/相互交換で解消しなかった残り)を玉突きチェーンで埋める。
            for ((i, k) in lowTargets) {
                if (shouldStop()) break
                if (!p.canDo(i, k)) continue
                var done = false
                for (j in 0 until p.T) {
                    if (done || shouldStop()) break
                    val oldK = work[i][j]
                    if (oldK == k || oldK !in 0 until p.K) continue
                    if (tryChainRelocate(i, j, oldK, k)) { improved = true; done = true; fixedNames.add(label(i, k)) }
                }
            }
            pass++
            if (!improved) break
        }
        // [汎用玉突き結合フレームワーク, 3.249.0] stuckNames(distLocations由来)より前に実行する。
        //   結合でwork/bestRepが変わってもdistLocationsはbestRep自身から再取得するため自動整合。
        val fairCombStats = CombinatorialRepair.Stats()
        bestRep = CombinatorialRepair.combineAndApply(
            state, work, bestRep, combinable.asReversed(), ::isBetter, shouldStop = shouldStop, stats = fairCombStats, p = p,
        )
        applied += fairCombStats.combosAccepted
        // [AptPolishと同型] work は毎手の成功時のみコミットしbestRepと同期を保つ（失敗時は必ず巻き戻し）
        //   ため、bestRep.distLocations がそのまま最終盤面の残存箇所＝再チェック不要。
        val stuckNames = bestRep.distLocations["fair"].orEmpty().mapNotNull { loc ->
            val i = loc.getOrNull(0) ?: return@mapNotNull null
            val k = loc.getOrNull(1) ?: return@mapNotNull null
            label(i, k)
        }
        val fairCombSummary = fairCombStats.summary()
        val logs = listOf(MirrorLog(tag = "FairPolish",
            message = "グループ内公平化(fair)研磨: fair ${before.breakdown["fair"] ?: 0}->${bestRep.breakdown["fair"] ?: 0} / total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard} 採用${applied}回" +
                (if (applied == 0 && (before.breakdown["fair"] ?: 0) > 0) " [頭打ち=改善手なし]" else "") +
                (if (fixedNames.isNotEmpty()) " 対象: ${fixedNames.joinToString(", ")}" else "") +
                rejectCulprits.summary() +
                (if (stuckNames.isNotEmpty()) " 残存: ${stuckNames.joinToString(", ")}" else "") +
                (if (fairCombSummary.isNotEmpty()) " / $fairCombSummary" else "")))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs, observedPinBlockedAttempts = pinBlocks.attempts, pinBlocks = pinBlocks)
    }

    /**
     * 指定した長さの勤務ブロックを、他職員と丸ごと交換／巡回交換する適応ポートフォリオ演算子。
     *
     * 旧 applyBlockSwapPolish（3.300.0 で削除）は「同一担当グループ × 15日 × 2者」に固定されていた。本演算子は
     * 11/13/17/19/23/28日を独立した候補プールとして持ち、各長さから有望候補を必ず残す。
     * これにより、短い窓では途中退化して届かない個人回数・apt・週偏り・連続規則の同時改善を、
     * 期間ごとのまとまりとして探索できる。
     *
     * **可変長の巡回交換（2者交換〜N者巡回, [maxCycle]）**: 2者交換だけでは「A の X を B へ渡したいが、
     * B の持ち札は A に不要」という局面で成立しない。3者以上の巡回（A←B←C←A）ならこの非対称な
     * 譲り合いが閉じる。既存 [applyBlockRotationPolish] も3者回転を持つが**窓が2〜3日固定・
     * 全日movable必須**のため、長期ブロックの巡回は本演算子だけが探索する。
     *
     * 候補生成は全列挙でなく **改善グラフ（cyclic exchange / VLSN）**:
     *  1. ブロック (start, length) ごとに、有向辺 u→v の重み＝「u が v のブロックを受け取ったときの
     *     u 個人の回数ペナルティ改善量」を [personalBalancePenalty] で見積もる。この重みは
     *     **各参加者が「自分の札を出して直前者の札を受け取る」ぶんだけで決まる**ため辺ごとに分解でき、
     *     巡回全体の見積り改善量は辺重みの単純和になる。
     *  2. 最小番号アンカー＋深さ [maxCycle] の DFS で、見積り改善量が正になる巡回を集める。
     *  3. 集めた巡回について**実際の**交換日集合（全参加者が movable かつ各辺の canDo を満たす日）を
     *     取り直し、正式な候補を作る。辺ごとの見積りは3者以上では交換日をやや広く見積もる近似だが、
     *     **順位付け専用**であり採否は必ず正式 checker が決めるため安全側。2者の場合は近似でなく厳密。
     *
     * 安全性:
     * - 同日の値を参加者間で巡回させるだけなので、日ごとのシフト多重集合＝全体の被覆量は保存する。
     * - 異なる担当グループ間でも、その日その辺の受け手が担当可能な場合だけ候補化する。
     * - 実現可能な希望で固定されたセル・担当不可の日は**据え置き**（その日は交換しない）、残りの日だけを
     *   入れ替える。ブロック全体を棄却しないため、希望が多いデータでも長い期間の候補が成立する。
     * - 厳密回数ピンを破る候補は exactPinRegression で除外する。
     * - 最終採否は [UnifiedViolationChecker] と [betterReport] の正式順
     *   （HARD → weightedScore → total）だけで決めるため、探索順にかかわらず退化しない。
     * - 正式評価（フル checker）の回数は [maxEvaluations] で据え置き。巡回を足しても**checker コストは
     *   増えない**（増えるのは安価な候補生成だけ）。候補プールは (ブロック長 × 巡回人数) ごとに分け
     *   ラウンドロビンで評価するため、長い28日案が11日案に、5者案が2者案に押し出されることもない。
     *   **[3.327.0/外部レビュー] [maxEvaluations] は pass ごとの枠**（呼び出し全体では
     *   `maxPasses × maxEvaluations`＝既定 2×48=96 まで走る）。レビューの指摘どおり旧 KDoc は
     *   これを呼び出し予算のように書いていたので**文書側を実装に合わせた**。
     *   「呼び出し1回ぶんの予算」へ変える版も作って実データで測ったが、pass 境界での候補再生成が減り
     *   real の採用手が 2→1 に落ちたので採らない（所要は実測 18〜70ms＝時間を理由に枠を絞る根拠もない）。
     *   **[maxCycleVisits] も全体予算ではない**＝DFS の分岐を (ブロック長, 開始日) ごとに抑える上限。
     *   ここを共通予算にすると後ろのブロックが一切探索されなくなるうえ、実測で DFS は 88万件/77ms＝
     *   時間のボトルネックでないため、そのままにしている（締切は各ブロックの入口で確認する）。
     */
    fun applyAdaptiveBlockSwapPolish(
        state: MagiState,
        schedule: Array<IntArray>,
        blockLens: IntArray = adaptiveBlockLengths,
        maxPasses: Int = 2,
        candidatesPerLength: Int = 8,
        maxEvaluations: Int = 48,
        maxFocusStaff: Int = 16,
        maxCycle: Int = 5,
        maxCycleVisits: Int = 50_000,
        /**
         * 禁止連続(c3n)が正味増える候補を**候補生成の段階で**捨てるか。
         * 既定は [PolishGate.filterC3nIncrease]（設定タブ→詳細設定のトグル・既定 false＝捨てない）。
         * c3n は HARD なので増える候補は最終的に `isBetter` が必ず却下する＝true/false で**採用結果は
         * 変わらない**。true にすると詰んだ候補へフル checker を呼ばなくなり評価枠を節約できる。
         */
        filterC3nIncrease: Boolean = PolishGate.filterC3nIncrease,
        shouldStop: () -> Boolean = { false },
    ): CyclicSwapResult {
        // [3.326.0] 回数固定(lo==hi)だけが却下した候補試行を対象別に数える（緩和対象の提示用）。
        val pinBlocks = PinBlockAttribution()
        val p = Problem(state)
        val work = normalizeSchedule(schedule, p)
        val before = UnifiedViolationChecker.check(state, work)
        val lengths = blockLens.asSequence()
            .filter { it in 1..p.T }
            .distinct()
            .sorted()
            .toList()
        val cycleCap = maxCycle.coerceAtLeast(2)
        if (p.S < 2 || lengths.isEmpty() || maxPasses <= 0 || candidatesPerLength <= 0 || maxEvaluations <= 0 || maxFocusStaff <= 0) {
            return CyclicSwapResult(work, before.total, before.total, 0,
                listOf(MirrorLog(tag = "AdaptiveBlockSwap", message = "対象長または職員ペアなし=スキップ")))
        }

        /**
         * 巡回交換の1候補。[cycle] は巡回順で、`cycle[t]` は `cycle[(t+1) % n]` のシフトを受け取る
         * （n=2 なら通常の2者交換と同一）。[days] は据え置き分を除いた実際の交換日。
         */
        data class Candidate(
            val cycle: IntArray,
            val start: Int,
            val length: Int,
            val priority: Long,
            val differences: Int,
            val days: IntArray,
        )

        fun name(i: Int) = state.staff.getOrNull(i)?.name ?: "#$i"
        fun movable(i: Int, j: Int) = !p.wishLocked(i, j)

        /**
         * 巡回を1つ回す。[forward] が真なら `cycle[t] <- cycle[t+1]`、偽なら逆回し。
         * 逆回しは順回しの厳密な逆変換なので、評価のための「適用→検査→巻き戻し」に使える
         * （2者交換は順逆が同一＝従来の swap と一致）。
         */
        fun rotate(candidate: Candidate, forward: Boolean) {
            val cycle = candidate.cycle
            val n = cycle.size
            val vals = IntArray(n)
            for (j in candidate.days) {
                for (t in 0 until n) vals[t] = work[cycle[t]][j]
                for (t in 0 until n) {
                    val src = if (forward) (t + 1) % n else (t + n - 1) % n
                    work[cycle[t]][j] = vals[src]
                }
            }
        }

        // range/apt は長い交換で動かしたい主対象。ここは候補の順位付け専用であり、採否は必ず正式checkerに委譲する。
        fun personalBalancePenalty(staff: Int, shift: Int, count: Int): Long {
            var out = 0L
            val lo = p.rangeLo[staff][shift]
            val hi = p.rangeHi[staff][shift]
            if (lo != Int.MIN_VALUE && count < lo) out += (lo - count).toLong() * 90L
            if (hi != Int.MAX_VALUE && count > hi) out += (count - hi).toLong() * 45L
            val apt = p.apt[staff][shift]
            if (apt >= 0) out += abs(count - apt).toLong()
            return out
        }

        fun staffPressure(report: ViolationReport): LongArray {
            val out = LongArray(p.S)
            fun add(key: String, cls: String) {
                val i = key.substringBefore(',').toIntOrNull() ?: return
                if (i !in 0 until p.S) return
                val family = cls.removePrefix("vio-")
                out[i] += MirrorKeys.weightOf(family).toLong().coerceAtLeast(1L)
            }
            report.violations.forEach { (key, cls) -> add(key, cls) }
            report.countViolations.forEach { (key, cls) -> add(key, cls) }
            report.distLocations.forEach { (family, rows) ->
                val weight = MirrorKeys.weightOf(family).toLong().coerceAtLeast(1L)
                for (row in rows) {
                    val i = row.firstOrNull() ?: continue
                    if (i in 0 until p.S) out[i] += weight
                }
            }
            return out
        }

        /**
         * [ピン保存交換] 交換日 [swapDays] を「厳密ピン(lo==hi)のシフト回数が1つも動かない」部分集合へ絞る。
         * 絞れた場合だけ true（[swapDays] を破壊的に更新）。
         *
         * 対象は**いま満たされている**厳密ピンだけ（`counts == lo == hi`）。すでに外れているピンは
         * 動かして直せる余地があるため拘束しない（悪化は従来どおり `exactPinRegression` が弾く）。
         *
         * 各日 j について、参加者 t のピン付きシフト k の増減
         * `d = [直前者が k] - [自分が k]`（∈ {-1,0,+1}）を並べた**符号ベクトル**を作る。
         * 交換日集合の総和がゼロベクトルならピンは1つも動かない。ゼロベクトルの日は常に採り、
         * 非ゼロの日は**符号が正反対の日と対にして**採る（打ち消し合う）。3日以上での相殺は拾わないが
         * 安価で安全側（採れなかった日を落とすだけ＝退化しない）。
         */
        fun balancePinnedDays(cycle: IntArray, swapDays: ArrayList<Int>, counts: Array<IntArray>): Boolean {
            val n = cycle.size
            // いま満たされている厳密ピン (参加者位置, シフト) を集める。
            var slots = 0
            val slotStaff = IntArray(32)
            val slotShift = IntArray(32)
            for (t in 0 until n) {
                val i = cycle[t]
                for (k in 0 until p.K) {
                    val lo = p.rangeLo[i][k]
                    if (lo == Int.MIN_VALUE || lo != p.rangeHi[i][k] || counts[i][k] != lo) continue
                    if (slots >= 31) return true   // 対象が多すぎる＝この安価な相殺では扱えない（従来どおり）
                    slotStaff[slots] = t; slotShift[slots] = k; slots++
                }
            }
            if (slots == 0) return true   // ピン無し＝制約なし（コストゼロで従来と同一）

            fun signatureOf(j: Int): Long {
                var sig = 0L
                for (s in 0 until slots) {
                    val t = slotStaff[s]
                    val k = slotShift[s]
                    val mine = if (work[cycle[t]][j] == k) 1 else 0
                    val incoming = if (work[cycle[(t + 1) % n]][j] == k) 1 else 0
                    val d = incoming - mine
                    if (d > 0) sig = sig or (1L shl (2 * s))
                    else if (d < 0) sig = sig or (2L shl (2 * s))
                }
                return sig
            }
            // 符号の反転（+1↔-1 のビットを入れ替える）。
            fun negate(sig: Long): Long = ((sig and 0x5555_5555_5555_5555L) shl 1) or ((sig ushr 1) and 0x5555_5555_5555_5555L)

            val bySig = LinkedHashMap<Long, ArrayList<Int>>()
            for (j in swapDays) bySig.getOrPut(signatureOf(j)) { ArrayList() }.add(j)

            val kept = ArrayList<Int>(swapDays.size)
            bySig[0L]?.let { kept.addAll(it) }
            val done = HashSet<Long>()
            done.add(0L)
            for ((sig, days) in bySig) {
                if (sig in done) continue
                done.add(sig)
                val opposite = negate(sig)
                done.add(opposite)
                val other = bySig[opposite] ?: continue
                val pairs = min(days.size, other.size)
                for (t in 0 until pairs) { kept.add(days[t]); kept.add(other[t]) }
            }
            if (kept.size < 2) return false
            kept.sort()
            swapDays.clear()
            swapDays.addAll(kept)
            return true
        }

        /**
         * 巡回 [cycle] をブロック (start, length) に適用する正式な候補を作る。
         * 交換日は「全参加者が movable」「その日の受け渡しが全辺 canDo」「実際に値が動く」を満たす日だけ。
         */
        fun candidateFor(
            cycle: IntArray,
            start: Int,
            length: Int,
            counts: Array<IntArray>,
            pressure: LongArray,
        ): Candidate? {
            val n = cycle.size
            val vals = IntArray(n)
            val swapDays = ArrayList<Int>(length)
            for (j in start until start + length) {
                var ok = true
                for (t in 0 until n) {
                    val i = cycle[t]
                    // [3.291.0 候補生成の緩和] 希望固定・担当不可の日はブロックごと棄却せず据え置く。
                    if (!movable(i, j)) { ok = false; break }
                    val v = work[i][j]
                    if (v !in 0 until p.K) { ok = false; break }
                    vals[t] = v
                }
                if (!ok) continue
                var changes = false
                for (t in 0 until n) {
                    val incoming = vals[(t + 1) % n]
                    if (incoming != vals[t]) changes = true
                    if (!p.canDo(cycle[t], incoming)) { ok = false; break }
                }
                if (!ok || !changes) continue
                swapDays.add(j)
            }
            if (swapDays.size < 2) return null
            // [3.294.0 ピン保存交換] 交換日の集合を「厳密ピン(lo==hi)の回数が変わらない」ように選び直す。
            //   3.293.0 の不採用内訳で、採用0の55〜80%が exactPinRegression のピン破りと判明した
            //   （実データは10名中9名の「休」が厳密ピン＝長いブロックを丸ごと交換すると必ず回数が動く）。
            if (!balancePinnedDays(cycle, swapDays, counts)) return null

            // [3.295.0 境界c3nの事前フィルタ / 3.296.0 で既定OFF] 3.294.0 でピン破りを消した結果、
            //   残る不採用は**全て**必須増＝c3n（禁止連続）になった（user 48/48・golden 39・real 34）。
            //   この巡回交換では covU/covO は同日置換で不変・groupViol は canDo・pref は movable で
            //   不変なので、**変化しうる HARD は c3n だけ**。c3n は職員行ローカルなので、参加者の行に
            //   交換を当てた fire 数を数えれば**近似でなく厳密**に判定できる。
            //
            //   **既定は OFF**（ユーザー指示 3.296.0「巡回交換の c3n フィルタを外す」）。フィルタは
            //   `firesAfter > firesBefore` の候補だけを落とす＝**減る・同数の候補は元から通している**ため、
            //   外しても採用は増えない（c3n は HARD なので増える候補は `isBetter` が第1キーで必ず却下）。
            //   ON にすると構造的に詰んだ候補へ checker を呼ばなくなり、評価枠を soft 判定まで進める
            //   候補へ回せる（実測: 正式評価 48→14〜38 件・不採用が全て soft のトレードオフになる）。
            if (filterC3nIncrease && p.cons3n.isNotEmpty()) {
                var firesBefore = 0
                var firesAfter = 0
                for (t in 0 until n) {
                    val self = cycle[t]
                    val giver = cycle[(t + 1) % n]
                    val row = work[self].copyOf()
                    firesBefore += C1DeltaPrefilter.staffC3nFires(p, row)
                    for (j in swapDays) row[j] = work[giver][j]
                    firesAfter += C1DeltaPrefilter.staffC3nFires(p, row)
                }
                if (firesAfter > firesBefore) { TuningTelemetry.c3nFilterSkipped.incrementAndGet(); return null }
            }

            val differences = swapDays.size
            // 1日だけの交換は既存の同日交換/同日3者回転(CyclicSwap)と同一＝「期間をまとめて入れ替える」手にならないので除外。
            if (differences < 2) return null

            var beforeBalance = 0L
            var afterBalance = 0L
            var pressureSum = 0L
            val delta = IntArray(p.K)
            for (t in 0 until n) {
                val self = cycle[t]
                val giver = cycle[(t + 1) % n]
                java.util.Arrays.fill(delta, 0)
                for (j in swapDays) { delta[work[self][j]]--; delta[work[giver][j]]++ }
                for (k in 0 until p.K) {
                    if (delta[k] == 0) continue
                    beforeBalance += personalBalancePenalty(self, k, counts[self][k])
                    afterBalance += personalBalancePenalty(self, k, counts[self][k] + delta[k])
                }
                pressureSum += pressure[self]
            }
            // 大きい推定改善を優先しつつ、違反に関与する職員と実際に変わるセル数をタイブレークに使う。
            val priority = (beforeBalance - afterBalance) * 1_000_000L + pressureSum * 16L + differences.toLong()
            return Candidate(cycle.copyOf(), start, length, priority, differences, swapDays.toIntArray())
        }

        var bestRep = before
        var applied = 0
        var generated = 0            // DFS が列挙した巡回の数（見積りキーだけで選別する安価な段）
        var builtCandidates = 0      // 実候補まで組み立てた数（プール上位のみ）
        val builtByCycleSize = sortedMapOf<Int, Int>()   // 巡回人数別の実候補数（多者交換が実際に出ているかの診断）
        val rejectReasons = LinkedHashMap<String, Int>() // 不採用の理由別件数（採用0のとき何に負けたか）
        val rejectCulprits = LinkedHashMap<String, Int>()// 悪化の主因になった族（重み付きで最も増えた族）
        var evaluated = 0
        var cycleHits = 0            // 3者以上の巡回として採用した手数（2者交換と区別してログに出す）
        val selectedLabels = ArrayList<String>()
        var pass = 0
        while (pass < maxPasses && !shouldStop()) {
            val counts = countMatrix(p, work)
            val pressure = staffPressure(bestRep)
            // 参加者の母集団は違反関与度の高い順に絞る（DFS の分岐を p.S でなく maxFocusStaff で抑える）。
            val focus = (0 until p.S)
                .sortedWith(compareByDescending<Int> { pressure[it] }.thenBy { it })
                .take(min(maxFocusStaff, p.S))
                .sorted()
            if (focus.size < 2) break
            val nf = focus.size

            // 候補は (ブロック長 × 巡回人数) ごとの固定サイズプールへ。どちらの軸でも押し出されない。
            // **2段階生成**: ①DFS は巡回を「見積りキーだけ」で選別する（実候補を作らない＝O(1)/巡回で
            //   数十万件を捌ける） ②各プールに残った上位だけ [candidateFor] で実候補にする。
            //   見積りキー = 個人回数の改善見積り×1e6 ＋ 参加者の違反関与度×16（実 priority と同順序）。
            //   **見積り0の巡回も捨てない**: ブロック交換の本命は c1/連続規則/曜日偏りの同時改善で、
            //   個人回数が動かない（見積り0の）手が実際に採用されることがあるため（3.291.0 実測）。
            //   段①のプール幅は段②より広く取る（[stageOneWidth]）。見積りキーの上位が実候補化で
            //   落ちる（交換成立日が1日以下）ことは巡回人数が増えるほど起きやすく、幅が狭いと
            //   その下にある成立候補まで一緒に失うため。実候補化は高々 バケット数×幅 回で安価。
            val bucketW = (cycleCap - 1).coerceAtLeast(1)
            val bucketCount = lengths.size * bucketW
            val stageOneWidth = candidatesPerLength * 8
            val poolKeys = LongArray(bucketCount * stageOneWidth)
            val poolNodes = LongArray(bucketCount * stageOneWidth)
            val poolStart = IntArray(bucketCount * stageOneWidth)
            val poolSize = IntArray(bucketCount)
            val poolMinKey = LongArray(bucketCount)
            fun record(bucket: Int, key: Long, nodes: Long, start: Int) {
                generated++
                val base = bucket * stageOneWidth
                val size = poolSize[bucket]
                if (size < stageOneWidth) {
                    poolKeys[base + size] = key; poolNodes[base + size] = nodes; poolStart[base + size] = start
                    poolSize[bucket] = size + 1
                    if (size == 0 || key < poolMinKey[bucket]) poolMinKey[bucket] = key
                    return
                }
                // 満杯後の却下は O(1)（最小キーを保持）。採用時のみ O(幅) で最小を取り直す。
                if (key <= poolMinKey[bucket]) return
                var worst = 0
                for (t in 1 until stageOneWidth) if (poolKeys[base + t] < poolKeys[base + worst]) worst = t
                poolKeys[base + worst] = key; poolNodes[base + worst] = nodes; poolStart[base + worst] = start
                var mn = poolKeys[base]
                for (t in 1 until stageOneWidth) if (poolKeys[base + t] < mn) mn = poolKeys[base + t]
                poolMinKey[bucket] = mn
            }

            val edge = Array(nf) { LongArray(nf) }        // 改善グラフ: focus 添字 u→v の見積り改善量
            val edgeOk = Array(nf) { BooleanArray(nf) }   // その辺で実際に動く日が1日でもあるか
            val delta = IntArray(p.K)
            val used = BooleanArray(nf)
            // 巡回は focus 添字を8bitずつ詰めた Long で持ち回す（最大5者=40bit）。
            val packable = nf <= 255

            for ((li, length) in lengths.withIndex()) {
                if (shouldStop() || !packable) break
                for (start in 0..(p.T - length)) {
                    if (shouldStop()) break
                    // 1) 辺重み（u が v のブロックを受け取ったときの u 個人の改善見積り）を作る。
                    for (ui in 0 until nf) {
                        val u = focus[ui]
                        for (vi in 0 until nf) {
                            edge[ui][vi] = 0L; edgeOk[ui][vi] = false
                            if (ui == vi) continue
                            val v = focus[vi]
                            java.util.Arrays.fill(delta, 0)
                            var any = false
                            for (j in start until start + length) {
                                if (!movable(u, j) || !movable(v, j)) continue
                                val a = work[u][j]
                                val b = work[v][j]
                                if (a == b || a !in 0 until p.K || b !in 0 until p.K) continue
                                if (!p.canDo(u, b)) continue
                                delta[a]--; delta[b]++; any = true
                            }
                            if (!any) continue
                            var gain = 0L
                            for (k in 0 until p.K) {
                                if (delta[k] == 0) continue
                                gain += personalBalancePenalty(u, k, counts[u][k]) -
                                    personalBalancePenalty(u, k, counts[u][k] + delta[k])
                            }
                            edge[ui][vi] = gain; edgeOk[ui][vi] = true
                        }
                    }

                    // 2) 最小番号アンカーの DFS で巡回を列挙し、見積りキーで各プールへ入れる。
                    var visits = 0
                    fun dfs(anchor: Int, depth: Int, last: Int, sum: Long, pres: Long, nodes: Long) {
                        if (depth >= 2 && edgeOk[last][anchor]) {
                            val key = (sum + edge[last][anchor]) * 1_000_000L + pres * 16L
                            record(li * bucketW + (depth - 2), key, nodes, start)
                        }
                        if (depth >= cycleCap) return
                        for (ni in anchor + 1 until nf) {
                            if (used[ni] || !edgeOk[last][ni]) continue
                            if (++visits > maxCycleVisits) return
                            used[ni] = true
                            dfs(anchor, depth + 1, ni, sum + edge[last][ni], pres + pressure[focus[ni]],
                                nodes or (ni.toLong() shl (8 * depth)))
                            used[ni] = false
                            if (visits > maxCycleVisits) return
                        }
                    }
                    for (ai in 0 until nf) {
                        if (visits > maxCycleVisits) break
                        used[ai] = true
                        dfs(ai, 1, ai, 0L, pressure[focus[ai]], ai.toLong())
                        used[ai] = false
                    }
                }
            }

            // 3) 各プールの上位だけを実候補にし、(ブロック長 × 巡回人数) からラウンドロビンで取り出す。
            val ordered = ArrayList<List<Candidate>>(bucketCount)
            for (b in 0 until bucketCount) {
                val size = poolSize[b]
                if (size == 0) continue
                val n = (b % bucketW) + 2
                val length = lengths[b / bucketW]
                val built = ArrayList<Candidate>(size)
                for (t in 0 until size) {
                    val packed = poolNodes[b * stageOneWidth + t]
                    val cyc = IntArray(n) { focus[((packed ushr (8 * it)) and 0xFFL).toInt()] }
                    candidateFor(cyc, poolStart[b * stageOneWidth + t], length, counts, pressure)?.let { built.add(it) }
                }
                if (built.isEmpty()) continue
                builtCandidates += built.size
                builtByCycleSize[n] = (builtByCycleSize[n] ?: 0) + built.size
                ordered.add(built.sortedWith(
                    compareByDescending<Candidate> { it.priority }
                        .thenByDescending { it.differences }
                        .thenBy { it.start }
                        .thenBy { it.cycle[0] },
                ).take(candidatesPerLength))
            }
            if (ordered.isEmpty()) break
            val ranked = ArrayList<Candidate>()
            var rank = 0
            while (ordered.any { rank < it.size }) {
                for (pool in ordered) pool.getOrNull(rank)?.let { ranked.add(it) }
                rank++
            }
            if (ranked.isEmpty()) break

            val base = work.copy2D()
            var chosen: Candidate? = null
            var chosenRep: ViolationReport? = null
            var checkedThisPass = 0
            for (candidate in ranked) {
                if (shouldStop() || checkedThisPass >= maxEvaluations) break
                rotate(candidate, forward = true)
                val report = UnifiedViolationChecker.check(state, work)
                val pinRegression = exactPinRegression(p, base, work)
                // [3.326.0] ピンだけが止めた候補を対象別に記録する。**盤面を戻す前に**呼ぶ
                //   （record は after 盤面を読むため、rotate で復元したあとでは間に合わない）。
                if (pinRegression && isBetter(report, bestRep)) pinBlocks.record(p, base, work)
                rotate(candidate, forward = false)
                checkedThisPass++
                evaluated++
                if (!pinRegression && isBetter(report, bestRep) && (chosenRep == null || isBetter(report, chosenRep!!))) {
                    chosen = candidate
                    chosenRep = report
                } else {
                    // [不採用理由の分類] 採用0のとき「何に負けたか」がログから読めるようにする
                    //   （RangePolish 3.222.0・C1Polish 3.236.0 の頭打ち理由と同じ趣旨）。
                    //   分類は isBetter の判定順（HARD → weightedScore → total）と厳密に一致させる。
                    val why = when {
                        pinRegression -> "ピン破り"
                        report.hard > bestRep.hard -> "必須増"
                        report.hard < bestRep.hard -> "採用手に劣後"   // bestRep には勝つが同パスの別候補に負けた
                        report.weightedScore > bestRep.weightedScore -> "重み悪化"
                        report.weightedScore < bestRep.weightedScore -> "採用手に劣後"
                        report.total < bestRep.total -> "採用手に劣後"
                        report.total > bestRep.total -> "件数悪化"
                        else -> "同値"
                    }
                    rejectReasons[why] = (rejectReasons[why] ?: 0) + 1
                    if (why == "重み悪化" || why == "必須増") {
                        // 重み付きで最も増えた族＝この手が壊した本体（共通ヘルパー worstWorsenedFamily）。
                        worstWorsenedFamily(report, bestRep)?.let { rejectCulprits[it] = (rejectCulprits[it] ?: 0) + 1 }
                    }
                }
            }
            val accepted = chosen ?: break
            rotate(accepted, forward = true)
            bestRep = chosenRep ?: break
            applied++
            if (accepted.cycle.size >= 3) cycleHits++
            val who = accepted.cycle.joinToString("←") { name(it) } + "←" + name(accepted.cycle[0])
            selectedLabels.add("${accepted.length}日${accepted.cycle.size}者:$who ${accepted.start + 1}〜${accepted.start + accepted.length}日(${accepted.differences}セル)")
            pass++
        }

        val lensLabel = lengths.joinToString("/")
        val logs = listOf(MirrorLog(tag = "AdaptiveBlockSwap",
            message = "可変長ブロック巡回交換[${lensLabel}日・最大${cycleCap}者]: total ${before.total}->${bestRep.total} HARD ${before.hard}->${bestRep.hard}" +
                " score ${before.weightedScore.toLong()}->${bestRep.weightedScore.toLong()} 採用${applied}回(うち3者以上${cycleHits}回)" +
                " 巡回${generated}件(実候補${builtCandidates}件" +
                (if (builtByCycleSize.isEmpty()) "" else " 内訳 " + builtByCycleSize.entries.joinToString(" ") { "${it.key}者:${it.value}" }) +
                ")/正式評価${evaluated}件" +
                (if (applied == 0) " [頭打ち=改善手なし]" else "") +
                (if (rejectReasons.isEmpty()) "" else " 不採用内訳: " +
                    rejectReasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}${it.value}" }) +
                (if (rejectCulprits.isEmpty()) "" else " (悪化の主因 " +
                    rejectCulprits.entries.sortedByDescending { it.value }.take(4).joinToString(" ") { "${it.key}:${it.value}" } + ")") +
                (if (selectedLabels.isNotEmpty()) " 対象: ${selectedLabels.joinToString(", ")}" else "")))
        return CyclicSwapResult(work, before.total, bestRep.total, applied, logs, pinBlocks = pinBlocks)
    }

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
