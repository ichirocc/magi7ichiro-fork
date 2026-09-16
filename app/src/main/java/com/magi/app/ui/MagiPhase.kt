package com.magi.app.ui

/** 盤面を丸ごと差し替えるジョブの段階。`Idle` 以外は編集・実行のガードが閉じる。
 *  再チェック(`checkJob`)と修正候補探索(`fixJob`)はここに入らない＝`optimizeInFlight()` と同じ境界。
 *  表示名は画面の文言なので変えない。 */
internal enum class MagiPhase(val busyLabel: String?) {
    Idle(null),
    Loading("読み込み"),
    Importing("CSV取込"),
    Drafting("下書きづくり"),
    Optimizing("勤務表づくり"),
    Polishing("仕上げ最適化"),
    Background("バックグラウンド最適化"),
    ;

    val isBusy: Boolean get() = this != Idle

    /** 画面を消灯させないのはこの 3 つだけ（前景で探索が走っている段階）。
     *  読み込み・CSV取込は短く、背景実行は前景サービスが持つので画面は要らない。 */
    val keepsScreenOn: Boolean get() = this == Drafting || this == Optimizing || this == Polishing
}

/** イベントが要求する権限の種類。どの段階で通すかは [MagiArbiter] が決める。 */
internal enum class MagiEventKind {
    /** 盤面セルの書き換え（割当・希望反映・undo/redo・候補適用）。 */
    BoardEdit,
    /** 設定・構造・制約の変更（ws1／月別条件／cons*）。 */
    StructureEdit,
    /** 盤面を差し替えるジョブの開始（最適化・研磨・下書き・読み込み・取込）。 */
    RunStart,
    /** 実行中でも常に通すもの（中止・画面遷移・表示設定・書き出し・メッセージ）。 */
    Always,
}

/** [MagiArbiter] の裁定。`Reject` は旧実装と同じ文言を持つ（`logLine` が空なら記録しない）。 */
internal sealed interface Arbitration {
    data object Accept : Arbitration
    data class Reject(val userMessage: String, val logLine: String) : Arbitration
}

/** 段階×種別だけで可否を決める唯一の裁定者。文言は既存3種のガードと同一（経緯: history 3.558.0）。 */
internal object MagiArbiter {
    fun arbitrate(phase: MagiPhase, kind: MagiEventKind, what: String = ""): Arbitration {
        if (!phase.isBusy || kind == MagiEventKind.Always) return Arbitration.Accept
        val busy = phase.busyLabel ?: MagiPhase.Background.busyLabel!!
        return when (kind) {
            MagiEventKind.BoardEdit -> Arbitration.Reject(
                "${busy}の実行中は編集できません（完了後にもう一度お試しください）", "",
            )
            MagiEventKind.StructureEdit -> Arbitration.Reject(
                "${busy}の実行中は設定を変更できません。終わるか「やめる」を押してからにしてください。",
                "${busy}の実行中のため設定変更を取り消しました（終わってから、または「やめる」の後にどうぞ）",
            )
            MagiEventKind.RunStart -> Arbitration.Reject(
                "${busy}の実行中です。終わるか「やめる」を押してからにしてください。",
                "$what を取り消しました（${busy}が実行中）",
            )
            MagiEventKind.Always -> Arbitration.Accept
        }
    }
}
