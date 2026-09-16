package com.magi.app.ui

/** 鎖の輪。担当なら実行して true＝そこで鎖は止まる。担当外は false で次の輪へ。 */
internal fun interface MagiEventHandler {
    fun handle(event: MagiEvent): Boolean
}

/**
 * 段階を持つ機械。[end] は**自分が立てた旗のときだけ**下ろす＝先に終わった側が後発の旗を下ろして
 * ロックを早く解く事故を防ぐ。段階の表現はアプリ全体でこの 1 つだけ（`MagiViewModel` が所有し、
 * [MagiMediator] は読むだけ）。
 */
internal class MagiPhaseMachine(private val backgroundRunning: () -> Boolean = { false }) {
    @Volatile private var jobPhase: MagiPhase? = null
    private var token = 0

    val phase: MagiPhase
        get() = jobPhase ?: if (backgroundRunning()) MagiPhase.Background else MagiPhase.Idle

    /** 前面ジョブが走っているか（背景は含まない）。 */
    val hasJob: Boolean get() = jobPhase != null

    fun begin(next: MagiPhase): Int {
        jobPhase = next
        return ++token
    }

    /** 自分の旗だったら下ろして true。呼び出し側が「自分の後片付けをしてよいか」の判断にも使う。 */
    fun end(jobToken: Int): Boolean {
        if (jobToken != token) return false
        jobPhase = null
        return true
    }
}

/**
 * イベントの唯一の裁定者。[phaseOf] が答える段階で [MagiArbiter] が可否を決め、
 * 通ったものだけをハンドラの鎖へ流す。段階そのものは持たない（[MagiPhaseMachine] が持つ）。
 */
internal class MagiMediator(
    private val phaseOf: () -> MagiPhase,
    private val handlers: List<MagiEventHandler>,
    private val onReject: (MagiEvent, Arbitration.Reject) -> Unit,
    private val onUnhandled: (MagiEvent) -> Unit = {},
) {
    fun dispatch(event: MagiEvent) {
        val what = (event as? MagiEvent.Run)?.what ?: ""
        when (val verdict = MagiArbiter.arbitrate(phaseOf(), event.kind, what)) {
            is Arbitration.Reject -> { onReject(event, verdict); return }
            Arbitration.Accept -> Unit
        }
        for (handler in handlers) if (handler.handle(event)) return
        onUnhandled(event)
    }
}

/** このイベントが始める段階（盤面を差し替えないイベントは null）。 */
internal fun phaseFor(event: MagiEvent): MagiPhase? = when (event) {
    MagiEvent.Run.Optimize -> MagiPhase.Optimizing
    MagiEvent.Run.SoftPolish -> MagiPhase.Polishing
    MagiEvent.Run.SmartInitial -> MagiPhase.Drafting
    is MagiEvent.Io.Load, MagiEvent.Io.InitBlankState, MagiEvent.Io.RestorePrevious -> MagiPhase.Loading
    is MagiEvent.Io.ImportCsvSmart, is MagiEvent.Io.ImportRosterAs -> MagiPhase.Importing
    else -> null
}
