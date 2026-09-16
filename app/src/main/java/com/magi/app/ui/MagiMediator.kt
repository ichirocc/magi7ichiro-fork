package com.magi.app.ui

/** 鎖の輪。担当なら実行して true＝そこで鎖は止まる。担当外は false で次の輪へ。 */
internal fun interface MagiEventHandler {
    fun handle(event: MagiEvent): Boolean
}

/**
 * イベントの唯一の裁定者。段階を持ち、[MagiArbiter] が通したものだけを鎖へ流す。
 * [endJob] は**自分が立てた旗のときだけ**下ろす＝先に終わった側が後発の旗を下ろしてロックを早く解く事故を防ぐ。
 */
internal class MagiMediator(
    private val handlers: List<MagiEventHandler>,
    private val onReject: (MagiEvent, Arbitration.Reject) -> Unit,
    private val onUnhandled: (MagiEvent) -> Unit = {},
) {
    private var jobPhase: MagiPhase? = null
    private var token = 0

    /** WorkManager 側の実行中フラグ。旧 `optimizeInFlight()` の第2項にあたる。 */
    var backgroundRunning: Boolean = false

    val phase: MagiPhase
        get() = jobPhase ?: if (backgroundRunning) MagiPhase.Background else MagiPhase.Idle

    fun beginJob(next: MagiPhase): Int {
        jobPhase = next
        return ++token
    }

    fun endJob(jobToken: Int) {
        if (jobToken == token) jobPhase = null
    }

    /** このイベントが始める段階（盤面を差し替えないイベントは null）。 */
    fun phaseFor(event: MagiEvent): MagiPhase? = when (event) {
        MagiEvent.Run.Optimize -> MagiPhase.Optimizing
        MagiEvent.Run.SoftPolish -> MagiPhase.Polishing
        MagiEvent.Run.SmartInitial -> MagiPhase.Drafting
        is MagiEvent.Io.Load, MagiEvent.Io.InitBlankState, MagiEvent.Io.RestorePrevious -> MagiPhase.Loading
        is MagiEvent.Io.ImportCsvSmart, is MagiEvent.Io.ImportRosterAs -> MagiPhase.Importing
        else -> null
    }

    fun dispatch(event: MagiEvent) {
        val what = (event as? MagiEvent.Run)?.what ?: ""
        when (val verdict = MagiArbiter.arbitrate(phase, event.kind, what)) {
            is Arbitration.Reject -> { onReject(event, verdict); return }
            Arbitration.Accept -> Unit
        }
        for (handler in handlers) if (handler.handle(event)) return
        onUnhandled(event)
    }
}
