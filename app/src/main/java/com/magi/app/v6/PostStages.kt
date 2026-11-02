package com.magi.app.v6

/**
 * 後処理の段の名前（全面改修 P4）。label は進捗に出す語（巡回の段は呼び出し側で巡の印を足す）、key は記録とログのタグ。
 * 段の名前の定義はここだけ（並びはソースの順。実行の順は `V6HotfixPasses.runPostOptimization` が決め、
 * 実行の順と採用数は `PostStageOrderTest`（T-10）が基準版と照合する）。
 */
internal object PostStages {
    data class Stage(val label: String, val key: String)

    val HF80 = Stage("後処理 HF80 戦略的振動", "HF80StrategicOscillation")
    val HF67 = Stage("後処理 HF67 職員間スワップ", "HF67InterStaffSwap")
    val HF66 = Stage("後処理 HF66 職員内再配分", "HF66IntraStaffRedistribution")
    val DAY_ASSIGN = Stage("後処理 厳密日割当", "DayAssignmentPolish")
    val WEEKLY_REBALANCE = Stage("後処理 曜日平準化(長方形交換)", "WeeklyRebalancePolish")
    val ALTERNATING = Stage("後処理 交互最適化(日ブロック割当)", "AlternatingSoftPolish")
    val C1_JOINT_LNS = Stage("後処理 期間要件(c1)共同LNS", "C1共同LNS")
    val C1_EJECTION = Stage("後処理 期間要件(c1)玉突き連鎖", "C1玉突き連鎖")
    val PERSONAL_LNS = Stage("後処理 個人回数/適切回数 共同LNS", "個人回数共同LNS")
    val COMPONENT_REPAIR_FINAL = Stage("後処理 違反起点修復(最終)", "ComponentRepair")
    val EJECTION_PIPELINE = Stage("後処理 玉突き連鎖(予察つき・修復の後)", "玉突きパイプライン")
    val MEMBRANE_CELL = Stage("後処理 膜の細胞LNS(最終・既定OFF)", "MembraneCellLns")
    val COVO_RELIEF = Stage("後処理 人員過剰の退避(最終)", "CovORelief")
    val CYCLIC = Stage("後処理 循環交換(k=2,3)", "CyclicSwapPolish")   // 巡回: label に巡の印が付く
    val C1_SAME_DAY = Stage("後処理 期間要件(c1)研磨", "C1同日交換")   // 巡回: label に巡の印が付く
    val C1_INDEX = Stage("後処理 期間要件(c1)index駆動修復", "C1索引修復")   // 巡回: label に巡の印が付く
    val C1_TEMPORAL = Stage("後処理 期間要件(c1)時系列DP+ジョイント再割当研磨", "C1時系列フロー")   // 巡回: label に巡の印が付く
    val C1_BEAM = Stage("後処理 期間要件(c1)広域ビーム研磨", "C1広域ビーム")   // 巡回: label に巡の印が付く
    val C1_EXACT = Stage("後処理 期間要件(c1)厳密窓修復", "C1厳密窓")   // 巡回: label に巡の印が付く
    val C3_SEQUENCE = Stage("後処理 連続規則(c3系)研磨", "C3SequencePolish")   // 巡回: label に巡の印が付く
    val C3_ROTATE = Stage("後処理 連続規則(c3系)3者回転研磨", "BlockRotationPolish")   // 巡回: label に巡の印が付く
    val C3MN = Stage("後処理 回避パターン(c3mn)玉突き研磨", "C3mnPolish")   // 巡回: label に巡の印が付く
    val C3N = Stage("後処理 禁止連続(c3n)研磨", "C3nPolish")   // 巡回: label に巡の印が付く
    val RANGE = Stage("後処理 個人回数(low/high)玉突き研磨", "RangePolish")   // 巡回: label に巡の印が付く
    val C3_RUN = Stage("後処理 連続規則(c3/c3m単一シフト連)玉突き研磨", "C3RunPolish")   // 巡回: label に巡の印が付く
    val C3_PATTERN = Stage("後処理 連続規則(c3/c3m複数シフトパターン)玉突き研磨", "C3PatternPolish")   // 巡回: label に巡の印が付く
    val ANCHORED_WINDOW = Stage("後処理 違反アンカー窓交換", "AnchoredWindowSwap")   // 巡回: label に巡の印が付く
    val WISH_ISLAND = Stage("後処理 希望島研磨", "WishIslandPolish")   // 巡回: label に巡の印が付く
    val BLOCK_SWAP = Stage("後処理 長期ブロック丸ごと交換", "AdaptiveBlockSwapPolish")   // 巡回: label に巡の印が付く
    val COUNT_CHAIN = Stage("後処理 回数連鎖研磨", "CountChainPolish")   // 巡回: label に巡の印が付く
    val APT = Stage("後処理 適切回数(apt)研磨", "AptPolish")   // 巡回: label に巡の印が付く
    val FAIR = Stage("後処理 グループ内公平化(fair)玉突き研磨", "FairPolish")   // 巡回: label に巡の印が付く
    val COMPONENT_REPAIR = Stage("後処理 違反連結成分修復", "ComponentRepair")   // 巡回: label に巡の印が付く

    /** 定義した段の一覧。 */
    val ALL: List<Stage> = listOf(HF80, HF67, HF66, DAY_ASSIGN, WEEKLY_REBALANCE, ALTERNATING, C1_JOINT_LNS, C1_EJECTION, PERSONAL_LNS, COMPONENT_REPAIR_FINAL, EJECTION_PIPELINE, MEMBRANE_CELL, COVO_RELIEF, CYCLIC, C1_SAME_DAY, C1_INDEX, C1_TEMPORAL, C1_BEAM, C1_EXACT, C3_SEQUENCE, C3_ROTATE, C3MN, C3N, RANGE, C3_RUN, C3_PATTERN, ANCHORED_WINDOW, WISH_ISLAND, BLOCK_SWAP, COUNT_CHAIN, APT, FAIR, COMPONENT_REPAIR)
}
