package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 裁定（段階×種別の可否）と鎖（最初に名乗り出た輪だけが処理）を固定する。
 *  拒否の文言は既存3種のガードと1文字も変えない＝この移行で画面の言葉が変わらないことの担保。 */
class MagiMediatorTest {
    private var bgRunning = false
    private val machine = MagiPhaseMachine { bgRunning }

    private fun mediator(
        handlers: List<MagiEventHandler> = listOf(MagiEventHandler { true }),
        rejects: MutableList<Arbitration.Reject> = mutableListOf(),
        unhandled: MutableList<MagiEvent> = mutableListOf(),
    ) = MagiMediator({ machine.phase }, handlers, onReject = { _, r -> rejects.add(r) }, onUnhandled = { unhandled.add(it) })

    @Test
    fun idleAcceptsEveryKind() {
        val seen = mutableListOf<MagiEvent>()
        val m = mediator(listOf(MagiEventHandler { seen.add(it); true }))
        val events = listOf(
            MagiEvent.Board.SetCell(0, 0, 1),
            MagiEvent.Structure.AddShift("夜", "N", "1", "1"),
            MagiEvent.Run.Optimize,
            MagiEvent.Session.RefreshCheck,
        )
        events.forEach { m.dispatch(it) }
        assertEquals("待機中は全種別が通る", events, seen)
    }

    @Test
    fun busyPhaseBlocksEditsAndRunsButNeverStop() {
        val rejects = mutableListOf<Arbitration.Reject>()
        val seen = mutableListOf<MagiEvent>()
        val m = mediator(listOf(MagiEventHandler { seen.add(it); true }), rejects)
        machine.begin(MagiPhase.Optimizing)

        m.dispatch(MagiEvent.Board.SetCell(0, 0, 1))
        m.dispatch(MagiEvent.Structure.AddShift("夜", "N", "1", "1"))
        m.dispatch(MagiEvent.Run.SoftPolish)
        assertEquals("盤面編集・設定変更・別の実行は弾かれる", 3, rejects.size)
        assertTrue("ハンドラまで届かない", seen.isEmpty())

        // 中止は実行中にこそ押される＝閉じてはいけない唯一の実行系イベント。
        m.dispatch(MagiEvent.Run.Stop)
        m.dispatch(MagiEvent.Session.ClearMessage(null))
        assertEquals(listOf<MagiEvent>(MagiEvent.Run.Stop, MagiEvent.Session.ClearMessage(null)), seen)
        assertEquals(3, rejects.size)
    }

    @Test
    fun rejectMessagesMatchLegacyWording() {
        val rejects = mutableListOf<Arbitration.Reject>()
        val m = mediator(rejects = rejects)
        machine.begin(MagiPhase.Optimizing)

        m.dispatch(MagiEvent.Board.SetCell(0, 0, 1))
        assertEquals("勤務表づくりの実行中は編集できません（完了後にもう一度お試しください）", rejects[0].userMessage)
        assertEquals("盤面編集の拒否は記録しない（旧 editBlockedNow に logOp なし）", "", rejects[0].logLine)

        m.dispatch(MagiEvent.Structure.SetUse2(true))
        assertEquals("勤務表づくりの実行中は設定を変更できません。終わるか「やめる」を押してからにしてください。", rejects[1].userMessage)
        assertEquals("勤務表づくりの実行中のため設定変更を取り消しました（終わってから、または「やめる」の後にどうぞ）", rejects[1].logLine)

        m.dispatch(MagiEvent.Run.SmartInitial)
        assertEquals("勤務表づくりの実行中です。終わるか「やめる」を押してからにしてください。", rejects[2].userMessage)
        assertEquals("下書きづくり を取り消しました（勤務表づくりが実行中）", rejects[2].logLine)
    }

    @Test
    fun endJobOnlyClearsOwnToken() {
        val first = machine.begin(MagiPhase.Loading)
        val second = machine.begin(MagiPhase.Optimizing)
        machine.end(first)
        assertEquals("先に終わった側が後発の旗を下ろさない", MagiPhase.Optimizing, machine.phase)
        machine.end(second)
        assertEquals(MagiPhase.Idle, machine.phase)
    }

    @Test
    fun backgroundRunningIsBusyWithoutJob() {
        val rejects = mutableListOf<Arbitration.Reject>()
        val m = mediator(rejects = rejects)
        bgRunning = true
        assertEquals(MagiPhase.Background, machine.phase)
        m.dispatch(MagiEvent.Board.SetCell(0, 0, 1))
        assertEquals("バックグラウンド最適化の実行中は編集できません（完了後にもう一度お試しください）", rejects[0].userMessage)
        bgRunning = false
        assertEquals(MagiPhase.Idle, machine.phase)
    }

    @Test
    fun jobPhaseWinsOverBackgroundFlag() {
        bgRunning = true
        machine.begin(MagiPhase.Importing)
        assertEquals("前面のジョブ名が優先（画面の文言がそちらを指す）", MagiPhase.Importing, machine.phase)
    }

    @Test
    fun chainStopsAtFirstHandlerThatClaims() {
        val order = mutableListOf<String>()
        val m = MagiMediator(
            { machine.phase },
            listOf(
                MagiEventHandler { order.add("a"); false },
                MagiEventHandler { order.add("b"); true },
                MagiEventHandler { order.add("c"); true },
            ),
            onReject = { _, _ -> },
        )
        m.dispatch(MagiEvent.Session.RefreshCheck)
        assertEquals("名乗り出た輪より先へは流れない", listOf("a", "b"), order)
    }

    @Test
    fun unclaimedEventReachesUnhandled() {
        val unhandled = mutableListOf<MagiEvent>()
        val m = mediator(listOf(MagiEventHandler { false }), unhandled = unhandled)
        m.dispatch(MagiEvent.Session.RefreshCheck)
        assertEquals(listOf<MagiEvent>(MagiEvent.Session.RefreshCheck), unhandled)
    }

    @Test
    fun phaseForMapsBoardReplacingEvents() {
        assertEquals(MagiPhase.Optimizing, phaseFor(MagiEvent.Run.Optimize))
        assertEquals(MagiPhase.Polishing, phaseFor(MagiEvent.Run.SoftPolish))
        assertEquals(MagiPhase.Drafting, phaseFor(MagiEvent.Run.SmartInitial))
        assertEquals(MagiPhase.Loading, phaseFor(MagiEvent.Io.Load("{}")))
        assertEquals(MagiPhase.Loading, phaseFor(MagiEvent.Io.RestorePrevious))
        assertEquals(MagiPhase.Importing, phaseFor(MagiEvent.Io.ImportCsvSmart("")))
        assertEquals(MagiPhase.Importing, phaseFor(MagiEvent.Io.ImportRosterAs("", false)))
        assertEquals("盤面を差し替えないものは段階を作らない", null, phaseFor(MagiEvent.Board.SetCell(0, 0, 1)))
        assertEquals(null, phaseFor(MagiEvent.Run.Stop))
        assertEquals(null, phaseFor(MagiEvent.Io.Export(MagiEvent.ExportKind.Json)))
    }

    @Test
    fun exportAndSettingsPassDuringRun() {
        val seen = mutableListOf<MagiEvent>()
        val m = mediator(listOf(MagiEventHandler { seen.add(it); true }))
        machine.begin(MagiPhase.Polishing)
        m.dispatch(MagiEvent.Io.Export(MagiEvent.ExportKind.Logs))
        m.dispatch(MagiEvent.Io.SaveNow)
        m.dispatch(MagiEvent.Settings.SetBudget(60))
        assertEquals("盤面を触らない操作は実行中でも通る", 3, seen.size)
    }
}
