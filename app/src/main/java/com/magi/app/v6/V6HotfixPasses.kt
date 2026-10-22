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
    val report: ViolationReport? = null,
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
    val report: ViolationReport? = null,
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
    val report: ViolationReport? = null,
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
    /** 後処理チェーンの段ごとの記録（採用数・所要・段の後の評価）。集計用で、盤面・採否には関わらない。 */
    val stageRecords: List<PostStageRecord> = emptyList(),
)

/**
 * 後処理チェーン 1 段の記録。[applied] は採用数（パスが数えないときは -1）。[hard]/[weightedScore]/[total] はパス自身が返した評価（無ければ null＝
 * 記録のために追加の check はしない）。[rolledBack] はチェーン内 keep-best で巻き戻された段。
 */
data class PostStageRecord(
    val key: String,
    val applied: Int,
    val ms: Long,
    val hard: Int?,
    val weightedScore: Double?,
    val total: Int?,
    val rolledBack: Boolean,
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

    /**
     * [測定中] C1 研磨の手A（同日交換）で、交換相手 i2 が受け取るシフト（または i の新シフト）が禁止連続(c3n)を
     * 作るとき、`tryFixForbiddenRunViaAdjacentDay`（手B と同じ隣接日の付け替え＋1段の玉突き）で崩してから
     * 結合手を 1 回の checker で判定する。採用基準は不変（betterReport＋厳密ピン）。既定 OFF。
     */
    @Volatile
    var c1MoveARepair: Boolean = false

    // [3.409.21/ユーザー選択「両方削除」] adaptiveEscapeControl（停滞脱出の適応制御・3.306.0）と
    //   portfolioRoleParallelSa/portfolioRoleChains（ロール内並列SA・3.371.0）は削除した。
    //   単体 A/B（1プロセス=1実行・各15ペア・基準は測定前に固定「12/15 で採否」）の結果:
    //   parallelSa = ON7/OFF8（中立。しかも ON は反復数中央値が2/3データセットで**低い**＝
    //   チェーン分割が希釈になっていた: blocked 45M vs 57M・sample 53M vs 60M）、
    //   escape = ON5/OFF10（中立〜OFF寄り。3.306.0 の n=24 と合わせ2度目の中立）。
    //   hard 中央値はどちらも全データセットで不変。docs/algorithm_portfolio.md「廃止・統合済み」参照。

    /** [E0/測定中] 前面の「つくる」で HARD が希望衝突の床に到達したら頭打ち（E0A＝後処理は通常／E0B＝研磨を省いて時間を返す）。
     *  既定 OFF＝実データ A/B を見て利用者が決める（docs/history 3.613.0）。背景 Worker には渡さない。 */
    @Volatile var wishConflictFloorMode: WishFloorMode = WishFloorMode.OFF

    /** [測定中] C1 共同 LNS の直後に月全体の玉突き連鎖（`C1EjectionChainPolish`）を走らせる。既定 OFF＝段そのものを呼ばない。 */
    @Volatile var c1EjectionChain: Boolean = false

    /** [測定中] 同じ位置で全族起点の玉突き連鎖を走らせる。既定 OFF。[c1EjectionChain] と両方 ON ならこちらだけを 1 回走らせる。 */
    @Volatile var allFamilyEjectionChain: Boolean = false

    /** 玉突きを予察つきのパイプライン（[EjectionChainPipeline]）で行う。OFF 以外のとき従来の玉突き（どの置き場所も）は走らせない。
     *  既定 **BOTH**（3.656.0 で昇格＝docs/history）。 */
    @Volatile internal var ejectionPipelineFocus: EjectionChainPipeline.Focus = EjectionChainPipeline.Focus.BOTH

    /** パイプラインの BOTH の先頭に、必須の族の違反を起点にする焦点を足す。設定タブ「必須の違反からも始める」。
     *  既定 false（単独では必須増 2 で統計ゲート不合格＝docs/history 3.657.0）。 */
    @Volatile var ejectionPipelineHardLeg: Boolean = false

    /** 玉突き連鎖 1 段あたりの上限ミリ秒（実時間モード）。残り時間の 1/4 との小さい方を使う。値は HF77 の明示指示で決める。 */
    @Volatile var ejectionChainMaxMillis: Long = CHAIN_RESERVE_SEC * 1000L   // 3.659.0 利用者指示: 6 秒→10 秒

    /** [測定中] 全SOFT玉突き連鎖（`SoftCascadePolish`）を後処理の最後に置く。既定 **false**（測定が終わるまで）。 */
    @Volatile var softCascade: Boolean = false

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

    /**
     * [3.514.0/UIトグル化] `combineAndApply`のexhaustPairs（経緯: history 3.512.6）。既定OFFで確定
     * （3.519.0、iter24＝170ペア。速度が平均-5.6%/中央値-26.3%と遅くなるのに品質改善は中央値0%、
     * かつ large/infeasible の1ペアで必須件数が119→120へ増加＝退行ゼロを満たさず不合格。
     * 詳細は `docs/algorithm_portfolio.md`「実装済みだが既定OFF」参照）。
     */
    @Volatile var combineExhaustPairs: Boolean = false

    /**
     * [3.535.0/HF77明示数値指示] `AptFairPolish.applyAptPolish`/`applyFairPolish`の採否で、対象家族
     * (apt/fair)以外のSOFT合計比+6%まで悪化を容認する（累積予算、`AptFairPolish.toleratedBetter`参照。
     * HARDの不増加・keep-bestの根幹は不変）。既定 **false**（詳細は`docs/algorithm_portfolio.md`参照）。
     */
    @Volatile var aptFairSoftTolerance: Boolean = false

    /** [3.540.0] 回数連鎖研磨（`CountChainPolish`）を後処理に入れる。既定 **false**（A/B 138 ペアで新2/同等135/旧1＝ゲート不合格、docs/algorithm_portfolio.md）。 */
    @Volatile var countChainPolish: Boolean = false

    /** [N9] PostChain の keep-best で巻き戻したパスの採用数を 0 と数える。既定 **false**（docs/backlog.md #36）。 */
    @Volatile var postChainRollbackCountsZero: Boolean = false

    /** 適応 portfolio の停滞中に後処理チェーンを全体最良へ当てて差し戻す（`StallPolishInjection`）。既定 **false**。 */
    @Volatile var stallPolishInjection: Boolean = false

    /** 後処理チェーンの先頭で貪欲な局所降下（`PrePostDescent`）をかける。既定 **false**（測定中）。 */
    @Volatile var prePostDescent: Boolean = false

    /** 必須違反が構造の下限に届いたら停滞閾値を短縮する（`effectiveStallMs` の basePlateau）。false＝下限に届いても通常閾値で粘る。
     *  既定 **true**＝従来どおり（実機ログ 2026-10-15: 300 s 予算で 59 s に終わった件の測定用。採否は測ってから）。 */
    @Volatile var floorShortStall: Boolean = true

    /** 根拠の精度（`docs/stall_escape.md` §5.3、3.643.0）: 経験的な c3n 壁（探索手の全滅）は、1 手探索（`FixSuggester`、上限 2 s）でも必須を減らす手が
     *  無いときだけ短縮に使う。希望固定だけの壁（証明相当）は従来どおり。既定 **false**（計測は経験的な壁のフィクスチャ待ち＝ベンチの腕 `deep`、`docs/stall_escape.md` §11）。 */
    @Volatile var c3nWallDeepCheck: Boolean = false
    /** [PROPOSAL C/3.643.0、`docs/stall_escape.md` §5.8] 適応閾値: 直近の改善間隔の最大×3 を [短, 通常] に挟み、通常分岐の停滞閾値を縮める。既定 false（2026-10-09 の計測で既定が良く否決＝§11。腕 `adaptive` は再計測用）。 */
    @Volatile var adaptiveStall: Boolean = false
    /** 背景実行の再開（プロセス終了後に WorkManager が Worker だけ起こす）で設定画面の値が既定へ戻らないよう、
     *  投入時の値をまとめて inputData へ載せる。鍵はフラグ名。フラグを足したらここと [restore] にも足す。 */
    fun snapshot(): Map<String, Any> = mapOf(
        "wideC3nBreakDays" to wideC3nBreakDays, "c1MoveARepair" to c1MoveARepair,
        
        "wishConflictFloorMode" to wishConflictFloorMode.name,
        "c1EjectionChain" to c1EjectionChain, "allFamilyEjectionChain" to allFamilyEjectionChain, "ejectionPipelineFocus" to ejectionPipelineFocus.name,
        "ejectionPipelineHardLeg" to ejectionPipelineHardLeg,
        "ejectionChainMaxMillis" to ejectionChainMaxMillis, "ejectionHoleFocus" to C1EjectionChainPolish.defaultHoleFocus, "ejectionHoleSoftOnly" to C1EjectionChainPolish.defaultHoleSoftOnly, "normalStallFraction" to normalStallFraction,
        "combineExhaustPairs" to combineExhaustPairs,
        "aptFairSoftTolerance" to aptFairSoftTolerance,
        "countChainPolish" to countChainPolish, "postChainRollbackCountsZero" to postChainRollbackCountsZero,
        "stallPolishInjection" to stallPolishInjection, "prePostDescent" to prePostDescent,
        "aptFairHeavySoftGuard" to AptFairPolish.heavySoftGuard, "floorShortStall" to floorShortStall, "c3nWallDeepCheck" to c3nWallDeepCheck, "adaptiveStall" to adaptiveStall,
    )

    /** [snapshot] の逆。鍵が無い・型が違う値は触らない（旧版で投入された Work は今の値のまま）。 */
    fun restore(m: Map<String, Any?>) {
        fun b(k: String, set: (Boolean) -> Unit) { (m[k] as? Boolean)?.let(set) }
        b("wideC3nBreakDays") { wideC3nBreakDays = it }; b("c1MoveARepair") { c1MoveARepair = it }
        
        (m["wishConflictFloorMode"] as? String)?.let { n -> WishFloorMode.entries.firstOrNull { it.name == n }?.let { wishConflictFloorMode = it } }
        b("c1EjectionChain") { c1EjectionChain = it }; b("allFamilyEjectionChain") { allFamilyEjectionChain = it }
        (m["ejectionPipelineFocus"] as? String)?.let { n -> EjectionChainPipeline.Focus.entries.firstOrNull { it.name == n }?.let { ejectionPipelineFocus = it } }
        b("ejectionPipelineHardLeg") { ejectionPipelineHardLeg = it }
        (m["normalStallFraction"] as? Double)?.let { normalStallFraction = it }
        (m["ejectionChainMaxMillis"] as? Long)?.let { ejectionChainMaxMillis = it }
        b("ejectionHoleFocus") { C1EjectionChainPolish.defaultHoleFocus = it }; b("ejectionHoleSoftOnly") { C1EjectionChainPolish.defaultHoleSoftOnly = it }
        b("combineExhaustPairs") { combineExhaustPairs = it }
        b("aptFairSoftTolerance") { aptFairSoftTolerance = it }
        b("countChainPolish") { countChainPolish = it }; b("postChainRollbackCountsZero") { postChainRollbackCountsZero = it }
        b("stallPolishInjection") { stallPolishInjection = it }; b("prePostDescent") { prePostDescent = it }
        b("aptFairHeavySoftGuard") { AptFairPolish.heavySoftGuard = it }; b("floorShortStall") { floorShortStall = it }; b("c3nWallDeepCheck") { c3nWallDeepCheck = it }; b("adaptiveStall") { adaptiveStall = it }
    }
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
    /** 必須が増える候補の事前フィルタ（ブロック巡回交換）が checker を呼ばずに落とした候補数。 */
    val c3nFilterSkipped = java.util.concurrent.atomic.AtomicInteger(0)
    /** 禁止連続の崩し範囲が既定(前後1日)と違う候補日を返した回数（広がる／狭まるの両方）。 */
    val wideC3nDiffered = java.util.concurrent.atomic.AtomicInteger(0)
    /** 同・呼ばれた回数（広がらなかった分も含む）。 */
    val wideC3nCalls = java.util.concurrent.atomic.AtomicInteger(0)
    /** 仕上げ最適化により PhaseB(LAHC) へ切り替わった回数。 */
    val lahcEntered = java.util.concurrent.atomic.AtomicInteger(0)
    /** Kotlin照合を実施した回数（ネイティブ結果を採用する直前の再評価）。 */
    val parityChecks = java.util.concurrent.atomic.AtomicInteger(0)
    /** [3.535.0] apt/fair研磨の6%許容が、素のbetterReportなら却下される手を採用に転じさせた回数。 */
    val aptFairToleranceUsed = java.util.concurrent.atomic.AtomicInteger(0)
    /** [3.540.0] 回数連鎖研磨が採用した連鎖数。 */
    val countChainApplied = java.util.concurrent.atomic.AtomicInteger(0)

    /** [3.694.0] 容認採用（素の betterReport なら却下される手を 6% 許容で採った）の中身。保持は先頭 [TOLERATED_NOTE_CAP] 件、総数は [aptFairToleranceUsed]。 */
    const val TOLERATED_NOTE_CAP = 5
    data class ToleratedNote(val family: String, val familyBefore: Int, val familyAfter: Int, val totalBefore: Int, val totalAfter: Int,
                             val hardBefore: Int, val hardAfter: Int, val rawDelta: Double, val forgiven: Double, val effectiveDelta: Double)
    private val toleratedNotes = ArrayList<ToleratedNote>()

    /** 容認採用の分岐（`AptFairPolish.toleratedBetter`）からだけ呼ぶ。満杯なら何もしない（件数は [aptFairToleranceUsed] が持つ）。 */
    fun noteTolerated(family: String, familyBefore: Int, familyAfter: Int, totalBefore: Int, totalAfter: Int, hardBefore: Int, hardAfter: Int,
                      rawDelta: Double, forgiven: Double, effectiveDelta: Double) {
        synchronized(toleratedNotes) {
            if (toleratedNotes.size < TOLERATED_NOTE_CAP) toleratedNotes.add(ToleratedNote(family, familyBefore, familyAfter, totalBefore, totalAfter, hardBefore, hardAfter, rawDelta, forgiven, effectiveDelta))
        }
    }

    fun toleratedNotes(): List<ToleratedNote> = synchronized(toleratedNotes) { toleratedNotes.toList() }

    /** 診断ログ「容認採用」（tag）の本文。保持分を 1 行ずつ、6 件目以降は 1 行の省略語。小数は Locale.ROOT（C# は InvariantCulture）。 */
    fun toleratedLines(total: Int): List<String> {
        val notes = toleratedNotes()
        val out = ArrayList<String>(notes.size + 1)
        notes.forEachIndexed { i, n -> out.add(toleratedLine(i + 1, total, n)) }
        if (total > notes.size) out.add("ほか ${total - notes.size} 件（先頭 ${TOLERATED_NOTE_CAP} 件のみ）")
        return out
    }

    fun toleratedLine(index: Int, total: Int, n: ToleratedNote): String =
        "$index/$total: ${n.family} 件数 ${n.familyBefore}→${n.familyAfter} ・ 合計 ${n.totalBefore}→${n.totalAfter} ・ 必須 ${n.hardBefore}→${n.hardAfter}" +
            " ・ 容認 ${fmt1(n.forgiven)} ・ 素の差 ${String.format(java.util.Locale.ROOT, "%+.1f", n.rawDelta)} ・ 実効差 ${fmt1(n.effectiveDelta)}"

    private fun fmt1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v)

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
        lahcEntered.set(0); parityChecks.set(0); aptFairToleranceUsed.set(0); countChainApplied.set(0)
        synchronized(toleratedNotes) { toleratedNotes.clear() }
    }

    /** [3.694.0] 9 項目の語と並びの単一ソース。「設定の効き」（[summary]）と「設定の開始時」（[startSettings]）が同じ語を使う。 */
    fun toggleWords(native: String, parity: String, filter: String, wide: String, softPolish: String,
                    combine: String, aptFair: String, countChain: String, settingSuffix: String = ""): String =
        "ネイティブ加速$settingSuffix=$native" +
            " / Kotlin照合$settingSuffix=$parity" +
            " / 必須が増える候補の事前フィルタ=$filter" +
            " / 禁止連続の崩し範囲=$wide" +
            " / 仕上げ最適化=$softPolish" +
            " / 結合探索を粘り強く=$combine" +
            " / 公平化/適切回数の他ソフト許容(6%)=$aptFair" +
            " / 回数連鎖研磨=$countChain"

    /** 実行の開始点で読んだ設定を ON/OFF だけで出す。「（設定）」は利用者の選択＝実際に使えたかは [summary]（設定の効き）が示す。 */
    fun startSettings(nativeOn: Boolean, parityOn: Boolean, wideOn: Boolean, softPolishOn: Boolean,
                      combineOn: Boolean, aptFairOn: Boolean, countChainOn: Boolean): String {
        fun w(b: Boolean) = if (b) "ON" else "OFF"
        return toggleWords(w(nativeOn), w(parityOn), w(true), w(wideOn), w(softPolishOn), w(combineOn), w(aptFairOn), w(countChainOn), settingSuffix = "（設定）")
    }

    /** 各トグルの ON/OFF と、その実行で観測できた効果を1行にまとめる。
     *  [3.587.0] 4トグルは呼び出し元が実行に使ったスナップショットをそのまま渡す（`PolishGate`直読みだと表示が食い違いうる）。 */
    fun summary(
        nativeOn: Boolean, parityOn: Boolean, softPolishOn: Boolean,
        combineExhaustPairs: Boolean, aptFairSoftTolerance: Boolean, countChainPolish: Boolean,
    ): String {
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
        return toggleWords(
            native = if (nativeOn) "ON" else "OFF",
            parity = eff(parityOn, parityChecks.get(), "回"),
            filter = eff(true, c3nFilterSkipped.get(), "件の必ず却下される検査を省略"),
            wide = wide,
            softPolish = eff(softPolishOn, lahcEntered.get(), "回LAHCへ切替"),
            combine = if (combineExhaustPairs) "ON" else "OFF",
            aptFair = eff(aptFairSoftTolerance, aptFairToleranceUsed.get(), "回、却下されるはずの手を採用"),
            countChain = eff(countChainPolish, countChainApplied.get(), "連鎖を採用"),
        )
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
     * 後処理チェーンの探索幅と予算（3.500.0 で集約。既定値はすべて従来の手書き値＝挙動不変）。
     * - HF67/HF66 の上限（[hf67CapMs]/[hf66CapMs]）は「残予算の半分・絶対上限」の保険（3.282.0）。実機は数十 ms＝通常は無影響で、
     *   大規模データでのフォールバック総当たり暴走だけを防ぐ。
     * - [jointLnsReserveMaxMs]: 巡回研磨クラスタの前に共同 LNS 2 本（既定 8s+6s）のための残予算の半分を確保する（3.271.0、
     *   実機ログ 2 本で「クラスタが予約枠を使い切り LNS が毎回 上限0 でスキップ」の飢餓を実証）。
     * - [maxRounds]: クラスタのフィックスポイント巡回。1 巡で 1 手も採用されなければ早期終了。
     * - [c1LnsMaxMs]/[personalLnsMaxMs]: 最終 LNS 2 本の既定上限。残予算は既定比 8:6 で按分し、[remainingClampMs] で
     *   乗算オーバーフローを避ける（3.255.0 の予算按分・境界 14,000ms で双方が既定を過不足なく得る）。
     * - 各 `*Passes`/`*Evaluations` は各パスへそのまま渡す探索幅。
     */
    data class PostOptimizationParams(
        val hf80MaxCycles: Int = 3,
        val hf67MaxSwaps: Int = 30,
        val hf67CapMs: Long = 3_000L,
        val hf66MaxMoves: Int = 30,
        val hf66CapMs: Long = 6_000L,
        val prePostDescentMaxMs: Long = 3_000L,
        val jointLnsReserveMaxMs: Long = 14_000L,
        val maxRounds: Int = 4,
        val cyclicSwapPasses: Int = 4,
        /** [3.511.2/測定中] 循環交換(CyclicSwapPolish)の最大人数。既定 3=既存 k=2,3 の全列挙のみ（挙動不変）。
         *  4/5 にすると k=4,5 の循環をランダム試行で追加する（backlog #12(b)/#13(e)）。既定 OFF。 */
        val cyclicSwapMaxK: Int = 3,
        val cyclicSwapKTrialsPerDay: Int = 20,
        /** [3.511.3/測定中] 個人合計(c2)専用研磨（backlog #12(b)）。既定 OFF。 */
        val c2PolishEnabled: Boolean = false,
        /** [3.580.0/測定中/backlog#26] c2PolishEnabledがOFFでも、c2違反が残っている局面でだけ試す。既定 OFF。 */
        val c2PolishReactivate: Boolean = false,
        val c2Passes: Int = 3,
        /** [3.511.7/測定中] 群ペア禁止(c42/c42s)専用の min-cost-flow 研磨（backlog #12(b)）。既定 OFF。 */
        val c42FlowPolishEnabled: Boolean = false,
        /** [3.580.0/測定中/backlog#26] c42FlowPolishEnabledがOFFでも、c42/c42s違反が残っている局面でだけ試す。既定 OFF。 */
        val c42FlowPolishReactivate: Boolean = false,
        val c42FlowPasses: Int = 3,
        val c1WindowPasses: Int = 3,
        val c1FlowPasses: Int = 2,
        val c1FlowRelocations: Int = 4,
        val c1FlowTrials: Int = 4,
        val c3SequencePasses: Int = 3,
        val c3RotatePasses: Int = 2,
        val c3mnPasses: Int = 3,
        val c3nPasses: Int = 3,
        val rangePasses: Int = 3,
        val c3RunPasses: Int = 3,
        val c3PatternPasses: Int = 3,
        val anchorWindowPasses: Int = 3,
        val anchorWindowEvaluations: Int = 48,
        val wishIslandPasses: Int = 3,
        val wishIslandEvaluations: Int = 120,
        val blockSwapPasses: Int = 2,
        val blockSwapCandidatesPerLength: Int = 8,
        val blockSwapEvaluations: Int = 48,
        val aptPasses: Int = 3,
        val fairPasses: Int = 3,
        val weeklyRebalancePasses: Int = 2,
        val alternatingSweeps: Int = 4,
        val c1LnsMaxMs: Long = 8_000L,
        val personalLnsMaxMs: Long = 6_000L,
        val remainingClampMs: Long = 100_000L,
        val passLogTopN: Int = 8,
        val componentRepair: ViolationComponentRepair.Params = ViolationComponentRepair.Params(),
        /** 休の必要人数を明示した日に休が余るとき、前後の窓を夜勤列の列挙＋人間移動＋ビームで組み直す（RestZeroWindowLns）。最終段・退避の前。
         *  既定 OFF＝ユーザー決定（実データでは 30 日の休希望 5 人で 29 日に夜勤できる人が足りず採用ゼロ、history 3.555.0）。 */
        val restZeroWindowLnsEnabled: Boolean = false,
        /** [測定中] 全SOFT玉突き連鎖（SoftCascadePolish）を後処理の最後の盤面変更（退避の直後・HF70 の前）に置く。既定 OFF。 */
        val softCascadeEnabled: Boolean = PolishGate.softCascade,
        val softCascade: SoftCascadePolish.Config = SoftCascadePolish.Config(),
        /** [N9] 巻き戻したパスの採用数を 0 と数える（巡の打ち切り判定・停滞検知へ流れる値）。既定は [PolishGate.postChainRollbackCountsZero]。 */
        val postChainRollbackCountsZero: Boolean = PolishGate.postChainRollbackCountsZero,
        /** [Iteration 7] 決定的モード＝時間（ms キャップ・締切・残り時間の判定）でなく回数で止める。同じ入力・seed なら同じ盤面。
         *  ベンチと再現性の検証用（実機は既定 false＝予算を使い切る）。外部の shouldStop は常に尊重する。 */
        val deterministic: Boolean = false,
        val c1LnsMaxEvaluations: Int = 90_000,
        val personalLnsMaxEvaluations: Int = 60_000,
        /** [測定中] 最終段の「c3n(禁止連続) 前後余白込みLNS」（C3nMarginLnsPolish）。採否は tools/loop のペア比較で決める＝既定 OFF。 */
        val c3nMarginLnsEnabled: Boolean = false,
        /** [3.580.0/測定中/backlog#26] c3nMarginLnsEnabledがOFFでも、c3n違反が残っている局面でだけ試す。既定 OFF。 */
        val c3nMarginLnsReactivate: Boolean = false,
        val c3nMarginLnsMarginDays: Int = 2,
        val c3nMarginLnsEvaluations: Int = 3_000,
        val c1LnsFirstEvaluations: Int = 20_000,
        val personalLnsFirstEvaluations: Int = 15_000,
        val c1LnsFirstMs: Long = 1_500L,
        val personalLnsFirstMs: Long = 1_500L,
        /** [C1 重複窓の連結成分化/測定中] 厳密窓修復(C1ExactRepair)の起点を、1件の違反でなく近接・重複窓を
         *  束ねた連結成分にする（backlog「C1 重複窓の連結成分化」）。既定 OFF＝挙動不変。 */
        val c1ComponentRepair: Boolean = false,
        /** [3.580.0/測定中/backlog#26] c1ComponentRepairがOFFでも、c1違反が残っている局面でだけ試す。既定 OFF。 */
        val c1ComponentRepairReactivate: Boolean = false,
        /** [backlog #12(a)・実験段階] c2/c41/c41sを二値でなく不足量/距離量で評価する（既定false=挙動不変）。
         *  全Polishパス・checker呼出へ明示伝播（Stage2）。 */
        val quantitativeRangeEval: Boolean = false,
        /** [測定中/3.512.6] `CombinatorialRepair.combineAndApply` の2人組(k=2)探索を既定の連続不採用200回より
         *  多くても打ち切らず試す（経緯: history 3.512.6）。既定false=挙動不変。 */
        val combineExhaustPairs: Boolean = false,
        /** [3.535.0/HF77明示数値指示] AptPolish/FairPolishの採否で、研磨開始時点の対象家族(apt/fair)
         *  以外のSOFT合計比+6%まで悪化を容認する（累積予算・keep-bestのHARD不増加は不変）。既定false。 */
        val aptFairSoftTolerance: Boolean = false,
        /** [3.540.0/測定中] 回数連鎖研磨（`CountChainPolish`）。既定 OFF。 */
        val countChainEnabled: Boolean = false,
        /** [3.580.0/測定中/backlog#26] countChainEnabledがOFFでも、high/apt超過が残っている局面でだけ試す。既定 OFF。 */
        val countChainReactivate: Boolean = false,
        /** [3.590.0/測定中/backlog#27] FairPolishの候補分類を`fairTarget`（生回数round(平均)）でなく
         *  `fairDevOfBucket`（正式評価・達成率モード）の黒箱観測へ揃える（3.588.0で実測した分類漏れの修正）。既定 OFF。 */
        val fairAchievementDirection: Boolean = false,
        /** [3.597.0/測定中/backlog#30] 日ごと厳密割当で「自分の現シフトを保つ」対角を常に有限にする。
         *  恒等割当が常に実行可能になり、置けない職員/スロットがある日も残りを研磨できる。既定 OFF。 */
        val dayAssignIdentityFallback: Boolean = false,
        /** 玉突きの設定（[EngineConfig.ejection] の写し）。 */
        val ejection: EjectionFlags = EjectionFlags(),
    )

    /** 巡ごとの乱数列を分けるためのパス別タグ（[roundSeed]）。値は 3.499.0 以前の手書き値と同じ＝乱数列不変。 */
    private object SeedTag {
        const val HF80 = 0x80L
        const val C1_WINDOW = 0x1C1L
        const val C1_INDEX = 0x1C1D2L
        const val C1_FLOW = 0xC1F10L
        const val C1_BEAM = 0xC1BEAL
        const val C3MN = 0xC3AL
        const val C3N = 0xC3EL
        const val RANGE = 0x8A9EL
        const val C3RUN = 0xC3A2L
        const val C3PATTERN = 0xC3B4L
        const val APT = 0xA97L
        const val FAIR = 0xFA12L
        const val CYCLIC_N = 0xC1C54L
        const val C3N_MARGIN = 0xC3E9L
        const val PRE_DESCENT = 0x9DE5L
    }

    /** SoftPolishVerify の「採用内訳」の並び（ログ文言の順序を固定する）。 */
    private val adoptionKeys = listOf(
        "循環", "c1", "c3", "c3回転", "c3mn玉突き", "c3n", "range玉突き", "c3run玉突き", "c3pattern玉突き",
        "アンカー窓交換", "希望島", "ブロック交換", "c2玉突き", "c41フロー", "c42フロー", "apt玉突き", "fair玉突き", "成分修復",
    )

    /** SoftPolishVerify で「対象」に数える族（3.278.0 で CyclicSwap の対象族、3.475.0 で c3n を追加）。 */
    private val softTargetFamilies = listOf(
        "c1", "c3", "c3m", "c3mn", "c3n", "low", "high", "apt", "fair", "c2", "c41", "c42", "c41s", "c42s", "covO",
    )

    /**
     * 後処理チェーンの作業域＝盤面・ログ・パス別所要・ピン帰属の合流点。
     * 各パスは必ず [adopt] を通す＝「pinBlocks の合流を書き忘れる」（3.350.0・3.409.9 で実際に起きた）を構造的に防ぐ。
     */
    /** visibility は internal＝`DeterministicPostChainTest` から直接駆動して検証する。
     *  [3.608.0/3.610.0] チェーン内の走行 keep-best: 畳み込むたびにチェーン内の最良と比べ、悪化していれば次のパスの前に巻き戻す
     *  （構造的 covU 床が 0 より大きい盤面では働かない）。P2d で常時。 */
    internal class PostChain(
        private val onPhase: (String) -> Unit,
        schedule: Array<IntArray>,
        private val state: MagiState,
        private val quantitativeRangeEval: Boolean,
        initialReport: ViolationReport? = null,
        private val rollbackCountsZero: Boolean = false,
        /** [aptFairSoftTolerance] ON のとき apt/fair 研磨の畳み込みだけ [AptFairPolish.toleratedBetter] で判定する（OFF＝従来どおり betterReport）。 */
        private val aptFairSoftTolerance: Boolean = false,
    ) {
        private val runningKeepBest: Boolean =
            V6SanityPort.structuralHardFloor(state, cachedProblem(state, quantitativeRangeEval)) == 0

        var work: Array<IntArray> = schedule.copy2D()
            private set
        val logs = ArrayList<MirrorLog>()
        val passMs = LinkedHashMap<String, Long>()
        val stageRecords = ArrayList<PostStageRecord>()
        private var lastKey = ""
        private var lastMs = 0L
        val pinBlocksAll = PinBlockAttribution()
        /** [Iteration 2] 巡の中で各パスが残した拒否候補。巡の末尾で違反連結成分修復へ渡して空にする。 */
        val rejectedPool = ArrayList<CombinatorialRepair.Candidate>()

        /** [走行 keep-best] チェーン内で到達した最良盤面・報告書（構造的 covU 床が正なら未使用のまま）。 */
        private var bestWork: Array<IntArray> = work.copy2D()
        private var bestReport: ViolationReport? = initialReport
        private var lastFoldRolledBack = false

        /** フェーズ名を UI へ通知し、所要 ms を [key] に累算しながら [block] を実行する。 */
        /** 巡回で前回と同じ盤面・採用 0 だった段（[memoTimed]）を、[timed] と同じキー・0ms で記録する。 */
        fun memoHit(phase: String, key: String) {
            onPhase(phase)
            lastKey = key
            lastMs = 0L
            passMs.merge(key, 0L) { a, b -> a + b }
        }

        fun <R> timed(phase: String, key: String, block: (Array<IntArray>) -> R): R {
            onPhase(phase)
            val t = EngineClock.nowMs()
            val r = block(work)
            lastKey = key
            lastMs = EngineClock.nowMs() - t
            passMs.merge(key, lastMs) { a, b -> a + b }
            return r
        }

        /**
         * [走行 keep-best] 直前に畳み込んだ [work] を、パス自身が既に評価済みの [report]
         * （なければ安価な再チェック）でチェーン最良と比較する。悪化していれば最良盤面へ巻き戻し、
         * [passLogs] は棄却マーカー付きで返す（ログは落とさない＝`annotateStaleLogsIfRegressed` と同じ方針）。
         * flag OFF のときは何もせず [passLogs] をそのまま返す＝挙動完全不変。
         */
        private fun runningKeepBestFold(report: ViolationReport?, passLogs: List<MirrorLog>, toleranceFamily: String? = null): List<MirrorLog> {
            lastFoldRolledBack = false
            if (!runningKeepBest) return passLogs
            val rep = report ?: UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
            val best = bestReport
            // 許容 ON の apt/fair はパス内と同じ基準で畳む（素の betterReport だとパスが容認した手を必ず巻き戻す）。
            //   予算の基準はパス開始時点＝この時点のチェーン最良。
            val tolerated = aptFairSoftTolerance && toleranceFamily != null && best != null &&
                AptFairPolish.toleratedBetter(rep, best, best, toleranceFamily, enabled = true, count = false)
            if (best == null || betterReport(rep, best) || tolerated) {
                bestReport = rep
                bestWork = work.copy2D()
                return passLogs
            }
            if (work.contentDeepEquals(bestWork)) return passLogs   // 無変更のパスは巻き戻していない＝印を付けない
            work = bestWork.copy2D()
            lastFoldRolledBack = true
            return passLogs.map { it.copy(message = "$ROLLBACK_MARKER${it.message}") }
        }

        /** 結果を盤面へ反映し、ピン帰属を合流させ、[keepLogs] のときだけログを積む。採用数を返す。 */
        fun adopt(r: CyclicSwapResult, keepLogs: Boolean = true, toleranceFamily: String? = null): Int {
            r.pinBlocks?.let { pinBlocksAll.merge(it) }
            rejectedPool.addAll(r.rejectedCandidates)
            work = r.newSchedule.copy2D()
            val folded = runningKeepBestFold(r.report, r.logs, toleranceFamily)
            if (keepLogs) logs.addAll(folded)
            record(r.applied, r.report)
            if (rollbackCountsZero && lastFoldRolledBack) return 0
            return r.applied
        }

        fun adopt(r: DayAssignmentPolish.DayAssignResult) {
            r.pinBlocks?.let { pinBlocksAll.merge(it) }
            work = r.newSchedule.copy2D()
            logs.addAll(runningKeepBestFold(r.report, r.logs))
            record(r.appliedDays, r.report)
        }

        private fun record(applied: Int, rep: ViolationReport?) {
            stageProbe?.invoke(lastKey, work)
            stageRecords += PostStageRecord(lastKey, applied, lastMs, rep?.hard, rep?.weightedScore, rep?.total, lastFoldRolledBack)
            lastKey = ""
            lastMs = 0L
        }

        fun replaceBoard(newSchedule: Array<IntArray>, passLogs: List<MirrorLog>, report: ViolationReport? = null, applied: Int = -1) {
            work = newSchedule.copy2D()
            logs.addAll(runningKeepBestFold(report, passLogs))
            record(applied, report)
        }

        companion object {
            /** 測定・テスト用: 段を畳むたびに (段のキー, 畳んだ後の盤面) を受け取る。本番では null。 */
            @Volatile internal var stageProbe: ((String, Array<IntArray>) -> Unit)? = null

            /** [走行 keep-best] チェーン内巻き戻しで不採用になった行の目印（`annotateStaleLogsIfRegressed`
             *  の "[棄却盤面の観測] " と同型・別文脈用）。 */
            const val ROLLBACK_MARKER = "[チェーン内巻き戻しで不採用] "
        }
    }

    /**
     * [review: budget] 後処理チェーン HF80 -> HF67 -> HF66 -> 厳密日割当 -> 巡回研磨クラスタ（最大 maxRounds 巡）->
     * 曜日/交互研磨 -> 共同 LNS 2 本 -> HF70。全パス keep-best（正式チェッカーの HARD→weighted→total）なので
     * 順序・巡回数・予算配分は「時間の使い方」だけを変え、退化はしない。
     * @param shouldStop true を返した時点で各パスの反復を打ち切る。全体予算(deadline)超過と coroutine キャンセルの両方を
     *        呼び出し側でこのラムダに束ねる。HF70(異常検知=安価)は診断のため常に実行する。
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
        params: PostOptimizationParams = PostOptimizationParams(),
    ): V6PostOptimizationResult {
        val report0 = UnifiedViolationChecker.check(state, schedule, quantitativeRangeEval = params.quantitativeRangeEval)
        val chain = PostChain(onPhase, schedule, state, params.quantitativeRangeEval, report0,
            rollbackCountsZero = params.postChainRollbackCountsZero, aptFairSoftTolerance = params.aptFairSoftTolerance)
        // 玉突きパイプラインが有効なら、従来の玉突き（どの置き場所も）は走らせない。
        val ej = params.ejection
        val pipelineFocus = ej.pipelineFocus
        val pipelineOn = pipelineFocus != EjectionChainPipeline.Focus.OFF
        fun runPipeline(work: Array<IntArray>): CyclicSwapResult = EjectionChainPipeline.apply(state, work,
            EjectionChainPipeline.Config(focus = pipelineFocus, deterministic = params.deterministic, deepEvaluations = params.c1LnsMaxEvaluations.toLong() * 4,
                deepMaxMillis = ej.maxMillis, hardLeg = ej.pipelineHardLeg,
                holeFocus = ej.holeFocus, holeSoftOnly = ej.holeSoftOnly),
            previousImproved = (chain.stageRecords.lastOrNull()?.applied ?: 0) > 0, deadlineMs = deadlineMs,
            shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
        val t0 = EngineClock.nowMs()

        // [測定中・既定 OFF] 探索が拾い残した 1〜2 セルの改善手を先に拾う（HF80 の振動より前＝机上測定と同じ置き場所）。
        if (PolishGate.prePostDescent && !shouldStop()) {
            val rd = chain.timed("後処理 前段の局所降下", "PrePostDescent") { work ->
                if (params.deterministic) PrePostDescent.apply(state, work, maxMillis = Long.MAX_VALUE, maxDraws = 20_000, maxEvaluations = 3_000, seed = seed xor SeedTag.PRE_DESCENT, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                else PrePostDescent.apply(state, work, maxMillis = (EngineClock.remainingMs(deadlineMs, t0) / 4).coerceAtMost(params.prePostDescentMaxMs), seed = seed xor SeedTag.PRE_DESCENT, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            }
            chain.replaceBoard(rd.newSchedule, rd.logs, rd.report, rd.improved + rd.sideways)
        }

        val r80 = chain.timed("後処理 HF80 戦略的振動", "HF80StrategicOscillation") { work ->
            applyHF80StrategicOscillation(state, work, maxCycles = params.hf80MaxCycles, seed = seed xor SeedTag.HF80, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r80.newSchedule, r80.logs, r80.report)

        val t67 = EngineClock.nowMs()
        val r67 = chain.timed("後処理 HF67 職員間スワップ", "HF67InterStaffSwap") { work ->
            val cap = (EngineClock.remainingMs(deadlineMs, t67) / 2).coerceAtMost(params.hf67CapMs)
            HfSwapPolish.applyHF67InterStaffSwap(state, work, maxSwaps = params.hf67MaxSwaps, shouldStop = shouldStop, deadlineMs = if (params.deterministic) Long.MAX_VALUE else t67 + cap, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r67.newSchedule, r67.logs, r67.report, r67.swapsApplied)

        val t66 = EngineClock.nowMs()
        val r66 = chain.timed("後処理 HF66 職員内再配分", "HF66IntraStaffRedistribution") { work ->
            // HF66 は手ごとに全候補をフル check する高コストパス＝残予算の半分（後段の研磨群へ残り半分）で打ち切る。
            val cap = (EngineClock.remainingMs(deadlineMs, t66) / 2).coerceAtMost(params.hf66CapMs)
            HfSwapPolish.applyHF66IntraStaffRedistribution(state, work, maxMoves = params.hf66MaxMoves, shouldStop = shouldStop, deadlineMs = if (params.deterministic) Long.MAX_VALUE else t66 + cap, quantitativeRangeEval = params.quantitativeRangeEval)
        }
        chain.replaceBoard(r66.newSchedule, r66.logs, r66.report, r66.movesApplied)
        val t66Done = EngineClock.nowMs()

        // 巡回研磨クラスタは自身の締切を持たないため、共同 LNS 2 本の取り分を先に確保して clusterStop に畳む（3.271.0）。
        val jointLnsReserve = if (deadlineMs == Long.MAX_VALUE || params.deterministic) 0L
            else ((deadlineMs - t66Done).coerceAtLeast(0L) / 2).coerceAtMost(params.jointLnsReserveMaxMs)
        val clusterDeadline = if (deadlineMs == Long.MAX_VALUE || params.deterministic) Long.MAX_VALUE else deadlineMs - jointLnsReserve
        val clusterStop: () -> Boolean = { shouldStop() || EngineClock.nowMs() >= clusterDeadline }

        chain.adopt(chain.timed("後処理 厳密日割当", "DayAssignmentPolish") { work ->
            DayAssignmentPolish.applyDayAssignmentPolish(state, work, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval, identityFallback = params.dayAssignIdentityFallback)
        })

        // ソフト研磨クラスタの前後を測る基準（SoftPolishVerify）。
        val preSoftRep = UnifiedViolationChecker.check(state, chain.work, quantitativeRangeEval = params.quantitativeRangeEval)
        val cluster = runPolishCluster(state, chain, params, seed, clusterStop, preSoftRep)

        // weekly は同日 2 者スワップでは動かない（曜日別の勤務/休が不変）→ 被覆保存の 2 職員×2 日 長方形交換。
        chain.adopt(chain.timed("後処理 曜日平準化(長方形交換)", "WeeklyRebalancePolish") { work ->
            CyclicSwapWeeklyPolish.applyWeeklyRebalancePolish(state, work, maxPasses = params.weeklyRebalancePasses, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
        })
        // 長方形交換（クロス日）が届かない同日内の割当先を Hungarian で再配置＝相補的なので両方走らせる。
        chain.adopt(chain.timed("後処理 交互最適化(日ブロック割当)", "AlternatingSoftPolish") { work ->
            DayAssignmentPolish.applyAlternatingSoftPolish(state, work, maxSweeps = params.alternatingSweeps, shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval, identityFallback = params.dayAssignIdentityFallback)
        })

        // 最終 LNS 2 本（高コストなので巡回ループでなく最終 1 回）。残予算は既定比 8:6 で按分（3.255.0）。
        val tC1Lns = EngineClock.nowMs()
        val lnsTotal = params.c1LnsMaxMs + params.personalLnsMaxMs
        chain.adopt(chain.timed("後処理 期間要件(c1)共同LNS", "C1共同LNS") { work ->
            val remaining = EngineClock.remainingMs(deadlineMs, tC1Lns).coerceAtMost(params.remainingClampMs)
            val cap = if (lnsTotal <= 0L) 0L else (remaining * params.c1LnsMaxMs / lnsTotal).coerceAtMost(params.c1LnsMaxMs)
            val cfg = if (params.deterministic) C1JointLnsPolish.Config(maxMillis = 60_000L, patienceMs = 0L, maxEvaluations = params.c1LnsMaxEvaluations)
                else C1JointLnsPolish.Config(maxMillis = cap)
            // [3.510.2/測定中] 短い試行で採用が無ければそこで止める（ログでは共同 LNS が後処理時間の大半を使って採用 0 が多い）。
            val first = if (params.deterministic) cfg.copy(maxEvaluations = params.c1LnsFirstEvaluations) else cfg.copy(maxMillis = minOf(cap, params.c1LnsFirstMs))
            val r1 = C1RepairOperators.jointLns(state, work, config = first, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            if (r1.applied == 0) r1
            else {
                val r2 = C1RepairOperators.jointLns(state, r1.newSchedule, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                r2.copy(beforeTotal = r1.beforeTotal, applied = r1.applied + r2.applied, logs = r1.logs + r2.logs)
            }
        })
        if (!pipelineOn && (ej.c1Chain || ej.allFamilyChain)) {
            val tEj = EngineClock.nowMs()
            val origin = if (ej.allFamilyChain) C1EjectionChainPolish.Origin.ALL else C1EjectionChainPolish.Origin.C1
            chain.adopt(chain.timed("後処理 期間要件(c1)玉突き連鎖", "C1玉突き連鎖") { work ->
                val cfg = if (params.deterministic) C1EjectionChainPolish.Config(origin = origin, holeFocus = ej.holeFocus, holeSoftOnly = ej.holeSoftOnly, maxEvaluations = params.c1LnsMaxEvaluations.toLong() * 4)
                    else C1EjectionChainPolish.Config(origin = origin, holeFocus = ej.holeFocus, holeSoftOnly = ej.holeSoftOnly, maxMillis = (EngineClock.remainingMs(deadlineMs, tEj) / 4).coerceIn(0L, ej.maxMillis))
                C1EjectionChainPolish.apply(state, work, cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            })
        }
        val tPersonalLns = EngineClock.nowMs()
        chain.adopt(chain.timed("後処理 個人回数/適切回数 共同LNS", "個人回数共同LNS") { work ->
            val cap = EngineClock.remainingMs(deadlineMs, tPersonalLns).coerceAtMost(params.personalLnsMaxMs)
            val cfg = if (params.deterministic) PersonalBalanceJointLnsPolish.Config(maxMillis = 60_000L, maxEvaluations = params.personalLnsMaxEvaluations)
                else PersonalBalanceJointLnsPolish.Config(maxMillis = cap)
            val first = if (params.deterministic) cfg.copy(maxEvaluations = params.personalLnsFirstEvaluations) else cfg.copy(maxMillis = minOf(cap, params.personalLnsFirstMs))
            val r1 = PersonalBalanceJointLnsPolish.apply(state, work, config = first, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
            if (r1.applied == 0) r1
            else {
                val r2 = PersonalBalanceJointLnsPolish.apply(state, r1.newSchedule, config = cfg, shouldStop = shouldStop, quantitativeRangeEval = params.quantitativeRangeEval)
                r2.copy(beforeTotal = r1.beforeTotal, applied = r1.applied + r2.applied, logs = r1.logs + r2.logs)
            }
        })

        val c3nMarginActive = params.c3nMarginLnsEnabled ||
            (params.c3nMarginLnsReactivate && targetFamiliesRemain(state, chain.work, params.quantitativeRangeEval, "c3n"))
        if (c3nMarginActive && !shouldStop()) {
            // [測定中] 共同 LNS の後・成分修復の前。c3n(禁止連続)のパターン日+前後余白を複数セル同時に
            //   destroy-rebuildして、1セル付け替え(C3nPolish)が構造的に届かない局面を拾う。
            val marginStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            chain.adopt(chain.timed("後処理 c3n禁止連続(前後余白込みLNS・最終)", "C3nMarginLNS") { work ->
                C3nMarginLnsPolish.apply(state, work, marginDays = params.c3nMarginLnsMarginDays, maxEvaluations = params.c3nMarginLnsEvaluations, shouldStop = marginStop, seed = seed xor SeedTag.C3N_MARGIN, quantitativeRangeEval = params.quantitativeRangeEval)
            })
        }

        if (!shouldStop()) {
            // [Iteration 5] 最終段の予算は残り時間に応じて拡張（2 秒以上残っていれば推定 4 倍・正式評価 2.5 倍）。締切は stop に畳む。
            val remainingFinal = EngineClock.remainingMs(deadlineMs)
            val base = params.componentRepair
            val timeWidened = if (params.deterministic || remainingFinal >= 2_000L) base.copy(maxEstimates = base.maxEstimates * 4, maxEvaluations = base.maxEvaluations * 5 / 2) else base
            val finalStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            chain.adopt(chain.timed("後処理 違反起点修復(最終)", "ComponentRepair") { work ->
                ViolationComponentRepair.repair(state, work, chain.rejectedPool.toList(), timeWidened, shouldStop = finalStop, quantitativeRangeEval = params.quantitativeRangeEval)
            })
            chain.rejectedPool.clear()
        }

        if (pipelineOn && !shouldStop()) {
            chain.adopt(chain.timed("後処理 玉突き連鎖(予察つき・修復の後)", "玉突きパイプライン") { work -> runPipeline(work) })
        }

        if (params.restZeroWindowLnsEnabled && !shouldStop()) {
            val lnsStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            val r = chain.timed("後処理 休0日の窓LNS(最終)", "RestZeroLNS") { work ->
                RestZeroWindowLns.apply(state, work, shouldStop = lnsStop, quantitativeRangeEval = params.quantitativeRangeEval)
            }
            chain.replaceBoard(r.newSchedule, r.logs, r.report)
        }

        if (!shouldStop()) {
            // 最後に置く＝後続パスが無いので keep-best の 1 セル手だけが盤面に足され、旧チェーンの結果より悪くならない。
            val reliefStop: () -> Boolean = if (params.deterministic) shouldStop else ({ shouldStop() || EngineClock.remainingMs(deadlineMs) <= 0L })
            val r = chain.timed("後処理 人員過剰の退避(最終)", "CovORelief") { work ->
                CovOReliefPolish.apply(state, work, shouldStop = reliefStop, quantitativeRangeEval = params.quantitativeRangeEval)
            }
            chain.replaceBoard(r.newSchedule, r.logs, r.report)
        }

        if (params.softCascadeEnabled && !shouldStop()) {
            // 退避より後＝最後の盤面変更。改善が無ければ入力の盤面と report をそのまま返す（後段に再実行は無い）。
            val cascade = chain.timed("後処理 全SOFT玉突き連鎖", "SoftCascadePolish") { work ->
                if (params.deterministic) SoftCascadePolish.apply(state, work, params.softCascade.copy(maxMillis = Long.MAX_VALUE), shouldStop, 0L, params.quantitativeRangeEval)
                else SoftCascadePolish.apply(state, work, params.softCascade, shouldStop, deadlineMs, params.quantitativeRangeEval)
            }
            chain.replaceBoard(cascade.newSchedule, cascade.logs, cascade.report, cascade.applied)
        }

        val tHf = EngineClock.nowMs()
        if (shouldStop()) {
            chain.logs.add(MirrorLog(level = "W", tag = "POST", message = "予算超過のため後処理は締切で短縮されました(各パスは内部で打ち切り済み・以降は最終検査のみ)"))
        }

        onPhase("後処理 HF70 異常検知")
        val work = chain.work
        val report = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = params.quantitativeRangeEval)
        val r70 = HfSwapPolish.detectHF70Anomalies(state, work, algoName, report, quantitativeRangeEval = params.quantitativeRangeEval)
        chain.logs.addAll(r70.logs)

        val tEnd = EngineClock.nowMs()
        chain.logs.add(MirrorLog(level = "I", tag = "POST",
            message = "後処理タイミング 総${tEnd - t0}ms: HF80=${t67 - t0}ms HF67=${t66 - t67}ms HF66=${t66Done - t66}ms" +
                " 巡回研磨(厳密日割当+c1/c3/range/apt/fair+曜日/交互)=${tC1Lns - t66Done}ms" +
                " C1共同LNS+玉突き連鎖=${tPersonalLns - tC1Lns}ms 個人共同LNS=${tHf - tPersonalLns}ms" +
                " 最終検査+HF70=${tEnd - tHf}ms"))
        // パスごとの内訳（多い順・上位 N）。「時間を食っているのに採用0」のパスが各パス自身の行と突き合わせられる（3.339.0）。
        if (chain.passMs.isNotEmpty()) {
            val sum = chain.passMs.values.sum().coerceAtLeast(1L)
            chain.logs.add(MirrorLog(level = "I", tag = "POST",
                message = "後処理パス別 計${sum}ms: " + chain.passMs.entries.sortedByDescending { it.value }
                    .take(params.passLogTopN).joinToString(" ") { "${it.key}=${it.value}ms(${it.value * 100 / sum}%)" }))
        }

        chain.logs.add(MirrorLog(level = "I", tag = "POST", message = "後処理 収支: " + ChangeSummary.familyLine(ChangeSummary.familyDeltas(report0, report))))

        val plateauOut = finalC1Plateau(state, work, report, cluster.c1Plateau, params.quantitativeRangeEval)
        val allLogs = ArrayList<MirrorLog>(chain.logs)
        allLogs.addAll(report.logs)
        return V6PostOptimizationResult(
            work, report.copy(logs = allLogs), r80, r67, r66, r70, chain.logs, plateauOut,
            chain.pinBlocksAll.attempts, chain.pinBlocksAll, chain.stageRecords.toList(),
        )
    }

    /** [E0B] 研磨なしの後処理＝検査と HF70 だけ（盤面は入力のまま）。 */
    fun minimalPost(state: MagiState, schedule: Array<IntArray>, algoName: String, quantitativeRangeEval: Boolean = false): V6PostOptimizationResult {
        val work = schedule.copy2D()
        val report = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        val r70 = HfSwapPolish.detectHF70Anomalies(state, work, algoName, report, quantitativeRangeEval)
        val note = MirrorLog(level = "I", tag = "POST", message = "希望衝突の床で頭打ち（E0B）: 後処理の研磨を省略（検査・HF70 のみ）")
        val r80 = HF80Result(work, report.hard, report.hard, report.weightedScore, report.weightedScore, 0, false, "E0B", emptyList(), report)
        val r67 = HF67Result(work, report.total, report.total, 0, 0, 0, 0, emptyList(), report)
        val r66 = HF66Result(work, report.total, report.total, 0, 0, 0, 0, emptyList(), report)
        val logs = listOf(note) + r70.logs
        return V6PostOptimizationResult(work, report.copy(logs = logs + report.logs), r80, r67, r66, r70, logs)
    }

    private class ClusterOutcome(val c1Plateau: C1PlateauDiagnosis?)

    /**
     * 巡回研磨クラスタ（循環交換〜fair 玉突き）を「1 巡で 1 手も採用されなくなるまで」最大 maxRounds 巡繰り返す。
     * 各パスは内部で自己収束するが、別パスの変更が他パスの改善を再び開く（例: c3 の組替えで新たな c1 充足余地が出る）。
     * 各パスの個別ログは巡 1 だけ積み（4 巡ぶんのスパム防止）、SoftPolishVerify の集約行は全巡合計を出す。
     */
    private fun runPolishCluster(
        state: MagiState,
        chain: PostChain,
        params: PostOptimizationParams,
        seed: Long,
        clusterStop: () -> Boolean,
        preSoftRep: ViolationReport,
    ): ClusterOutcome {
        val adopted = LinkedHashMap<String, Int>().also { m -> for (k in adoptionKeys) m[k] = 0 }
        val c3Anchor = setOf("vio-c3", "vio-c3m", "vio-c3mn")
        val pC1 = cachedProblem(state, params.quantitativeRangeEval)   // state の純関数＝巡回間で不変（C1DeltaPrefilter のゲート用）
        var c1Plateau: C1PlateauDiagnosis? = null
        val memo = HashMap<String, Pair<Array<IntArray>, CyclicSwapResult>>()
        fun memoTimed(phase: String, key: String, memoKey: String = key, block: (Array<IntArray>, () -> Boolean) -> CyclicSwapResult): CyclicSwapResult {
            val hit = memo[memoKey]
            if (hit != null && hit.first.contentDeepEquals(chain.work) && !clusterStop()) {
                chain.memoHit(phase, key)
                return hit.second
            }
            val input = chain.work.copy2D()
            var stopped = false
            val r = chain.timed(phase, key) { block(it) { clusterStop().also { s -> if (s) stopped = true } } }
            if (!stopped && r.applied == 0 && r.newSchedule.contentDeepEquals(input)) memo[memoKey] = input to r.copy(newSchedule = input.copy2D())
            else memo.remove(memoKey)
            return r
        }
        var round = 0
        while (round < params.maxRounds && !clusterStop()) {
            val first = round == 0
            val tag = " [巡${round + 1}]"
            var roundApplied = 0
            fun take(key: String, r: CyclicSwapResult, toleranceFamily: String? = null) {
                val n = chain.adopt(r, keepLogs = first, toleranceFamily = toleranceFamily)
                adopted[key] = (adopted[key] ?: 0) + n
                roundApplied += n
            }

            take("循環", chain.timed("後処理 循環交換(k=2,3)$tag", "CyclicSwapPolish") { work ->
                CyclicSwapWeeklyPolish.applyCyclicSwapPolish(
                    state, work, maxPasses = params.cyclicSwapPasses, maxK = params.cyclicSwapMaxK,
                    kTrialsPerDay = params.cyclicSwapKTrialsPerDay, seed = roundSeed(seed, SeedTag.CYCLIC_N, round),
                    shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })

            // c1 違反セルに厳密アンカーする 2 op は、不足窓が無ければ必ず no-op＝C1DeltaPrefilter で 1 回判定して飛ばす（3.275.0/3.276.0）。
            if (C1DeltaPrefilter.hasActionableC1(C1RepairIndex.build(pC1, chain.work))) {
                val rC1 = chain.timed("後処理 期間要件(c1)研磨$tag", "C1同日交換") { work ->
                    C1RepairOperators.selfRelocateAndSameDaySwap(state, work, maxPasses = params.c1WindowPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_WINDOW, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
                }
                take("c1", rC1)
                // 構造化診断は巡ごとに合算（3.331.0。最後の巡だけだと観測が減る）。末尾で最終盤面に対して再フィルタする。
                rC1.plateau?.let { fresh -> c1Plateau = c1Plateau?.mergedWith(fresh) ?: fresh }
                take("c1", chain.timed("後処理 期間要件(c1)index駆動修復$tag", "C1索引修復") { work ->
                    C1RepairOperators.indexChainRepair(state, work, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_INDEX, round), quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            // 時系列 DP＋同日ジョイント再割当（3.254.0 の ablation で一本化）。広域ビームより前に置く（逆順は golden で劣化を実測）。
            take("c1", chain.timed("後処理 期間要件(c1)時系列DP+ジョイント再割当研磨$tag", "C1時系列フロー") { work ->
                C1RepairOperators.temporalFlow(
                    state, work, maxPasses = params.c1FlowPasses, maxRelocations = params.c1FlowRelocations, trials = params.c1FlowTrials,
                    shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_FLOW, round), quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            take("c1", chain.timed("後処理 期間要件(c1)広域ビーム研磨$tag", "C1広域ビーム") { work ->
                C1RepairOperators.wideBeam(state, work, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C1_BEAM, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            // 別日で連動して初めて解ける多職員手を、窓スコープの被覆保存 permutation 厳密探索で拾う。
            val c1ComponentActive = params.c1ComponentRepair ||
                (params.c1ComponentRepairReactivate && targetFamiliesRemain(state, chain.work, params.quantitativeRangeEval, "c1"))
            take("c1", memoTimed("後処理 期間要件(c1)厳密窓修復$tag", "C1厳密窓", "C1厳密窓/$c1ComponentActive") { work, stop ->
                C1RepairOperators.exactWindow(state, work, shouldStop = stop, useComponents = c1ComponentActive, quantitativeRangeEval = params.quantitativeRangeEval)
            })

            val rC3 = memoTimed("後処理 連続規則(c3系)研磨$tag", "C3SequencePolish") { work, stop ->
                C3RotationPolish.applyC3SequencePolish(state, work, maxPasses = params.c3SequencePasses, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval)
            }
            take("c3", rC3)
            // 3 者回転は O(候補^3) で通常時の寄与ゼロ（3.300.0 ablation）＝主手が詰まった巡と最終巡だけの脱出手。
            if (rC3.applied == 0 || round == params.maxRounds - 1) {
                take("c3回転", memoTimed("後処理 連続規則(c3系)3者回転研磨$tag", "BlockRotationPolish") { work, stop ->
                    C3RotationPolish.applyBlockRotationPolish(state, work, c3Anchor, "C3Rotate", maxPasses = params.c3RotatePasses, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            take("c3mn玉突き", chain.timed("後処理 回避パターン(c3mn)玉突き研磨$tag", "C3mnPolish") { work ->
                C3FamilyPolish.applyC3mnPolish(state, work, maxPasses = params.c3mnPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3MN, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            take("c3n", chain.timed("後処理 禁止連続(c3n)研磨$tag", "C3nPolish") { work ->
                C3FamilyPolish.applyC3nPolish(state, work, maxPasses = params.c3nPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3N, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            take("range玉突き", chain.timed("後処理 個人回数(low/high)玉突き研磨$tag", "RangePolish") { work ->
                RangePolish.applyRangePolish(state, work, maxPasses = params.rangePasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.RANGE, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs)
            })
            take("c3run玉突き", chain.timed("後処理 連続規則(c3/c3m単一シフト連)玉突き研磨$tag", "C3RunPolish") { work ->
                C3FamilyPolish.applyC3RunPolish(state, work, maxPasses = params.c3RunPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3RUN, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            take("c3pattern玉突き", chain.timed("後処理 連続規則(c3/c3m複数シフトパターン)玉突き研磨$tag", "C3PatternPolish") { work ->
                C3FamilyPolish.applyC3PatternPolish(state, work, maxPasses = params.c3PatternPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.C3PATTERN, round), quantitativeRangeEval = params.quantitativeRangeEval)
            })
            // 違反アンカー型・可変長窓の一括交換（3.495.0、ユーザー提示の設計）。
            take("アンカー窓交換", memoTimed("後処理 違反アンカー窓交換$tag", "AnchoredWindowSwap") { work, stop ->
                AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
                    state, work, mode = WindowMode.STRICT_WHOLE_WINDOW, maxPasses = params.anchorWindowPasses,
                    maxEvaluations = params.anchorWindowEvaluations, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            // 希望島研磨（3.496.0、ユーザー提示の確定仕様）。
            take("希望島", memoTimed("後処理 希望島研磨$tag", "WishIslandPolish") { work, stop ->
                WishIslandPolish.applyWishIslandPolish(state, work, maxPasses = params.wishIslandPasses, maxEvaluations = params.wishIslandEvaluations, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval)
            })
            take("ブロック交換", memoTimed("後処理 長期ブロック丸ごと交換$tag", "AdaptiveBlockSwapPolish") { work, stop ->
                AdaptiveBlockSwapPolish.applyAdaptiveBlockSwapPolish(
                    state, work,
                    AdaptiveBlockSwapPolish.CyclicParams(
                        maxPasses = params.blockSwapPasses, candidatesPerLength = params.blockSwapCandidatesPerLength,
                        maxEvaluations = params.blockSwapEvaluations,
                    ),
                    shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval,
                )
            })
            if (params.c2PolishEnabled ||
                (params.c2PolishReactivate && targetFamiliesRemain(state, chain.work, params.quantitativeRangeEval, "c2"))) {
                take("c2玉突き", memoTimed("後処理 個人合計(c2)研磨$tag", "C2Polish") { work, stop ->
                    C2Polish.applyC2Polish(state, work, maxPasses = params.c2Passes, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            if (params.countChainEnabled ||
                (params.countChainReactivate && targetFamiliesRemain(state, chain.work, params.quantitativeRangeEval, "high", "apt"))) {
                take("回数連鎖", chain.timed("後処理 回数連鎖研磨$tag", "CountChainPolish") { work ->
                    CountChainPolish.applyCountChainPolish(state, work, config = if (params.deterministic) CountChainPolish.Config(maxMillis = 60_000L) else CountChainPolish.Config(),
                        shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.RANGE, round), quantitativeRangeEval = params.quantitativeRangeEval)
                        .also { TuningTelemetry.countChainApplied.addAndGet(it.applied) }
                })
            }
            if (params.c42FlowPolishEnabled ||
                (params.c42FlowPolishReactivate && targetFamiliesRemain(state, chain.work, params.quantitativeRangeEval, "c42", "c42s"))) {
                take("c42フロー", memoTimed("後処理 群ペア禁止(c42/c42s)フロー研磨$tag", "C42FlowPolish") { work, stop ->
                    C42FlowPolish.applyC42FlowPolish(state, work, maxPasses = params.c42FlowPasses, shouldStop = stop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }
            take("apt玉突き", chain.timed("後処理 適切回数(apt)研磨$tag", "AptPolish") { work ->
                AptFairPolish.applyAptPolish(state, work, maxPasses = params.aptPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.APT, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs, aptFairSoftTolerance = params.aptFairSoftTolerance)
            }, toleranceFamily = "apt")
            take("fair玉突き", chain.timed("後処理 グループ内公平化(fair)玉突き研磨$tag", "FairPolish") { work ->
                AptFairPolish.applyFairPolish(state, work, maxPasses = params.fairPasses, shouldStop = clusterStop, seed = roundSeed(seed, SeedTag.FAIR, round), quantitativeRangeEval = params.quantitativeRangeEval, combineExhaustPairs = params.combineExhaustPairs, aptFairSoftTolerance = params.aptFairSoftTolerance, fairAchievementDirection = params.fairAchievementDirection)
            }, toleranceFamily = "fair")
            // [Iteration 2] 巡の中で各パスが単独では不採用にした候補を、違反連結成分ごとにトランザクション結合する。
            val pool = chain.rejectedPool.toList(); chain.rejectedPool.clear()
            if (pool.size >= 2) {
                take("成分修復", chain.timed("後処理 違反連結成分修復$tag", "ComponentRepair") { work ->
                    ViolationComponentRepair.repair(state, work, pool, params.componentRepair.copy(generateFromAnchors = false), shouldStop = clusterStop, quantitativeRangeEval = params.quantitativeRangeEval)
                })
            }

            round++
            if (roundApplied == 0) break   // この巡で 1 手も採用なし＝joint 局所最適に到達
        }

        chain.logs.add(softPolishVerifyLog(state, chain.work, preSoftRep, round, adopted, params.quantitativeRangeEval))
        return ClusterOutcome(c1Plateau)
    }

    /** 研磨可否の検証ログ。採用 0 かつ対象 > 0 なら「頭打ち（正常）」、対象 0 なら「対象なし」と明示する。 */
    private fun softPolishVerifyLog(
        state: MagiState, work: Array<IntArray>, preSoftRep: ViolationReport, rounds: Int, adopted: Map<String, Int>,
        quantitativeRangeEval: Boolean = false,
    ): MirrorLog {
        val softAfter = UnifiedViolationChecker.check(state, work, quantitativeRangeEval = quantitativeRangeEval)
        fun bd(r: ViolationReport, k: String) = r.breakdown[k] ?: 0
        val adoptedTotal = adopted.values.sum()
        val targets = softTargetFamilies.sumOf { bd(preSoftRep, it) }
        val verdict = when {
            adoptedTotal > 0 -> "有効(採用${adoptedTotal}手)"
            targets == 0 -> "対象なし"
            else -> "頭打ち(採用0=現在の探索範囲では改善手なし)"
        }
        val hardNote = if (softAfter.hard == preSoftRep.hard) "不変" else "変化${preSoftRep.hard}->${softAfter.hard}!"
        return MirrorLog(tag = "SoftPolishVerify", message =
            "ソフトc1/c3系研磨 可否=$verdict (${rounds}巡・各パス行は巡1のみ表示/本行は全巡合計) | c1 ${bd(preSoftRep, "c1")}->${bd(softAfter, "c1")}" +
                " / c3 ${bd(preSoftRep, "c3")}->${bd(softAfter, "c3")}" +
                " / c3m ${bd(preSoftRep, "c3m")}->${bd(softAfter, "c3m")}" +
                " / c3mn ${bd(preSoftRep, "c3mn")}->${bd(softAfter, "c3mn")}" +
                " / low ${bd(preSoftRep, "low")}->${bd(softAfter, "low")}" +
                " / high ${bd(preSoftRep, "high")}->${bd(softAfter, "high")}" +
                " / apt ${bd(preSoftRep, "apt")}->${bd(softAfter, "apt")}" +
                " / fair ${bd(preSoftRep, "fair")}->${bd(softAfter, "fair")}" +
                " | HARD $hardNote / total ${preSoftRep.total}->${softAfter.total}" +
                " (採用内訳 " + adopted.entries.joinToString(" ") { "${it.key}:${it.value}" } + ")")
    }

    /**
     * C1 研磨の時点で作った構造化診断（3.322.0）を最終盤面に合わせ直す（共同 LNS 等が直した箇所を「直せなかった」と見せない）。
     * c1 が残っているなら観測が 1 件も無くても診断を返す＝UI が「原因未確定」と出す（3.325.0）。
     */
    private fun finalC1Plateau(
        state: MagiState, work: Array<IntArray>, report: ViolationReport, plateau: C1PlateauDiagnosis?,
        quantitativeRangeEval: Boolean = false,
    ): C1PlateauDiagnosis? {
        val c1Left = report.breakdown["c1"] ?: 0
        val refreshed = plateau?.let { d ->
            val pFin = cachedProblem(state, quantitativeRangeEval)
            d.refreshedAgainst(c1Left) { i, x, ri ->
                val c = pFin.cons1.getOrNull(ri)
                c != null && c.shiftIdx == x && c.day1 > 0 &&
                    (0..pFin.T - c.day1).any { j -> inDeficientC1Window(pFin, work, i, x, c.day1, c.day2, j) }
            }
        }
        return refreshed?.takeIf { it.hasEntries || it.causeUnknown }
            ?: (if (c1Left > 0) C1PlateauDiagnosis(c1Left, emptyList()) else null)
    }

    fun applyHF80StrategicOscillation(
        state: MagiState,
        schedule: Array<IntArray>,
        maxCycles: Int = 3,
        seed: Long = System.nanoTime(),
        shouldStop: () -> Boolean = { false },
        quantitativeRangeEval: Boolean = false,
    ): HF80Result {
        val p = Problem(state, quantitativeRangeEval)
        val ev = Evaluator(p)   // 内側探索用（サイクルごとに作り直さない）
        val rng = Random(seed)
        val before = UnifiedViolationChecker.check(state, schedule, quantitativeRangeEval = quantitativeRangeEval)
        var best = normalizeSchedule(schedule, p)
        val original = best   // [厳密ピン保護] 全サイクル共通の基準盤面（exactPinRegressionの比較元）
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
                        if (allowed.isNotEmpty()) {
                            val k = allowed[rng.nextInt(allowed.size)]
                            if (!p.extBanned(i, j, k)) cand[i][j] = k   // 拡張希望の禁止へは置かない（乱数は従来どおり消費）
                        }
                    }
                }
                t++
            }
            val polished = localBestImprovement(p, ev, cand, 250 + cycle * 120, rng, shouldStop)
            val rep = UnifiedViolationChecker.check(state, polished, quantitativeRangeEval = quantitativeRangeEval)
            usedCycles = cycle + 1
            // [厳密ピン保護/3.522.0] 摂動+再研磨は複数職員の回数を同時に変えうるため、他パス（RangePolish等）
            //   と同じ exactPinRegression ガードを追加。旧実装はこのパスだけ欠けており、SOFT重みの相対関係が
            //   十分ずれると（例: c1/covU等の重み引き上げ）weightedScore改善とのトレードで
            //   staffRange厳密ピン(lo==hi)が崩れうる不具合があった（PinInvariantTest, docs/history/3.4xx.md）。
            if (isBetter(rep, bestReport) && !exactPinRegression(p, original, polished)) {
                best = polished
                bestReport = rep
                applied = true
            }
            cycle++
        }
        val reason = if (applied) "strategic oscillation accepted" else "no improving oscillation"
        val logs = listOf(MirrorLog(tag = "HF80", message = "SO applied=$applied HARD ${before.hard}->${bestReport.hard} score ${before.weightedScore.toLong()}->${bestReport.weightedScore.toLong()} cycles=$usedCycles"))
        return HF80Result(best, before.hard, bestReport.hard, before.weightedScore, bestReport.weightedScore, usedCycles, applied, reason, logs, report = bestReport)
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
        /** [Iteration 2] このパスが単独では不採用にし、結合にも使わなかった候補（違反連結成分修復の材料）。 */
        val rejectedCandidates: List<CombinatorialRepair.Candidate> = emptyList(),
        /** [走行 keep-best] このパスが自身の keep-best ループで既に評価済みの `newSchedule` に対応する報告書。
         * 未設定(null)のパスは `PostChain` 側で安価な再チェックにフォールバックする。 */
        val report: ViolationReport? = null,
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
    private fun localBestImprovement(p: Problem, ev: Evaluator, schedule: Array<IntArray>, tries: Int, rng: Random, shouldStop: () -> Boolean = { false }): Array<IntArray> {
        // 1 セルをその場で書き換えて評価し、改善しなければ戻す（盤面のコピーは入口の 1 回だけ）。
        //   同じ値への書き換えは評価しても同点＝不採用なので飛ばす。乱数の消費順は変えない。
        val best = schedule.copy2D()
        var bestScore = ev.fullEval(best)
        var t = 0
        val maxTry = max(0, tries)
        while (t < maxTry) {
            if (shouldStop()) break
            if (p.S > 0 && p.T > 0) {
                val i = rng.nextInt(p.S)
                val j = rng.nextInt(p.T)
                if (!p.wishLocked(i, j)) {
                    val allowed = p.allowedShiftsForStaff(i)
                    if (allowed.isNotEmpty()) {
                        val nw = allowed[rng.nextInt(allowed.size)]
                        val old = best[i][j]
                        if (nw != old && !p.extBanned(i, j, nw)) {
                            best[i][j] = nw
                            val score = ev.fullEval(best)
                            if (score < bestScore) bestScore = score else best[i][j] = old
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

    /** [3.580.0/backlog#26] 既定OFFの専用修復腕を再活性化フラグ経由で呼ぶかどうかの判定材料。
     *  腕自身の自己申告カウンタ（TuningTelemetryの適用数など）でなく、正式チェッカーの breakdown 生値で
     *  「対象違反が今まだ残っているか」を見る。各xxxReactivateフラグがOFFなら一切呼ばれない＝挙動不変。 */
    internal fun targetFamiliesRemain(state: MagiState, work: Array<IntArray>, quantitativeRangeEval: Boolean, vararg families: String): Boolean {
        val rep = UnifiedViolationChecker.check(state, work, quantitativeRangeEval)
        return families.any { (rep.breakdown[it] ?: 0) > 0 }
    }

}
