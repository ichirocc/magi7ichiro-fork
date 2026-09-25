package com.magi.app.v6

import com.magi.app.model.Group
import com.magi.app.model.MagiState
import com.magi.app.model.Shift
import com.magi.app.model.Staff
import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [backlog#38] スキルグループ未指定の職員は `skillIdx = -1`（未所属）。既定の出どころ（型の既定値・`skillIdx` の無い JSON・
 * 職員追加・名簿取込）がすべて -1 になることと、[Ws1Ops.addSkillGroup] が 0 件から最初の 1 群を作るときだけ全員を
 * 未所属にする（群が 1 件以上なら触らない）ことを固定する。
 */
class Ws1OpsSkillGroupTest {

    /** `MagiViewModel.initBlankState` と同じ種（職員は `skillIdx` を持たない）。 */
    private val blankSeed = """
        {"startDate":"2026-01-01","endDate":"2026-01-03",
        "shifts":[{"name":"休み","kigou":"休","need1":"","need2":""}],
        "groups":[{"name":"グループA","kigou":"A"}],
        "staff":[{"name":"職員1","groupIdx":0}],
        "use2Patterns":true,
        "groupShift":[[1]],"groupShiftApt":[[""]],
        "cons1":[],"cons2":[],"cons3":[],"cons3n":[],"cons3m":[],"cons3mn":[],"cons41":[],"cons42":[],
        "wishes":{},"staffRange":{},"needDay1":{},"needDay2":{},
        "schedule":[[0,0,0]]}
    """.trimIndent()

    private fun state(staff: List<Staff>, skillGroups: List<Group>) = MagiState(
        startDate = "2026-07-01", endDate = "2026-07-02",
        shifts = listOf(Shift("休み", "休", "", "", com.magi.app.model.ShiftRole.Rest), Shift("A", "A", "1", "")),
        groups = listOf(Group("G", "G")),
        staff = staff,
        use2Patterns = false,
        groupShift = listOf(listOf(1, 1)),
        groupShiftApt = listOf(listOf("", "")),
        schedule = List(staff.size) { listOf(1, 0) },
        wishes = emptyMap(), staffRange = emptyMap(), needDay1 = emptyMap(), needDay2 = emptyMap(),
        cons1 = emptyList(), cons2 = emptyList(), cons3 = emptyList(), cons3n = emptyList(),
        cons3m = emptyList(), cons3mn = emptyList(), cons41 = emptyList(), cons42 = emptyList(),
        skillGroups = skillGroups,
    )

    private fun grid(st: MagiState) = st.schedule.map { it.toIntArray() }.toTypedArray()

    @Test fun staffWithoutSkillIdxKeyParsesAsUnassigned() {
        assertEquals("型の既定値", -1, Staff("x", 0).skillIdx)
        assertEquals("キーの無い JSON", listOf(-1), StateParser.parse(blankSeed).staff.map { it.skillIdx })
        val explicit = blankSeed.replace("\"groupIdx\":0}", "\"groupIdx\":0,\"skillIdx\":0}")
        assertEquals("明示の 0 はそのまま", listOf(0), StateParser.parse(explicit).staff.map { it.skillIdx })
    }

    @Test fun addStaffLeavesTheNewStaffUnassigned() {
        val st = state(listOf(Staff("s0", 0, 0)), listOf(Group("L", "L")))
        val r = Ws1Ops.addStaff(st, grid(st), "新人", 0)
        assertEquals(listOf(0, -1), r.state.staff.map { it.skillIdx })
    }

    @Test fun rosterCsvImportLeavesNewStaffUnassigned() {
        val template = listOf(
            "令和8年,,,7,月",
            "ユニット名：,,柳,,1,2,3",
            "№,,氏 名,,水,木,金",
            "1,リーダー,古泉 健一,予定,A4,,休",
            "2,,山本 昌幸,予定,A4,休,",
            ",,,,,,",
            ",記号,時刻,休憩時間,水,木,金",
            ",A4,6:00～15:00,1h,1,0,1",
            ",休,定休,,1,2,1",
        ).joinToString("\n")
        val flat = listOf(
            "ユニット,No,役職,氏名,1,2,3",
            "柳,1,,古泉 健一,A,,休",
            "柳,2,,山本 昌幸,B,A,",
        ).joinToString("\n")
        for ((label, st) in listOf("テンプレ" to RosterCsvImport.parse(template)!!, "一覧" to FlatRosterCsvImport.parse(flat)!!)) {
            assertEquals(label, listOf(-1, -1), st.staff.map { it.skillIdx })
        }
    }

    @Test fun blankStartThenFirstSkillGroupHasNoMembers() {
        val st = StateParser.parse(blankSeed)
        val added = Ws1Ops.addStaff(st, grid(st), "職員2", 0).state
        val after = Ws1Ops.addSkillGroup(added, "リーダー", "L")
        assertEquals(listOf(Group("リーダー", "L")), after.skillGroups)
        assertTrue("誰も所属しない", Problem(after).ssk.all { it == -1 })
    }

    @Test fun addingAnotherSkillGroupKeepsExistingAssignments() {
        val st = state(listOf(Staff("s0", 0, 0), Staff("s1", 0, 1), Staff("s2", 0, -1)), listOf(Group("L", "L"), Group("N", "N")))
        val after = Ws1Ops.addSkillGroup(st, "中堅", "M")
        assertEquals(listOf("L", "N", "M"), after.skillGroups.map { it.kigou })
        assertEquals("群が 1 件以上なら割当は触らない", listOf(0, 1, -1), after.staff.map { it.skillIdx })
    }

    @Test fun firstSkillGroupOnAnOldSaveWithExplicitZerosUnassignsEveryone() {
        // 既定が 0 だった頃の保存＝群が 0 件なのに全員が明示の 0。
        val st = state(listOf(Staff("s0", 0, 0), Staff("s1", 0, 0)), emptyList())
        val after = Ws1Ops.addSkillGroup(st, "リーダー", "L")
        assertEquals(listOf(-1, -1), after.staff.map { it.skillIdx })
        assertEquals("この時点では採点は変わらない",
            UnifiedViolationChecker.check(st, grid(st)).weightedScore, UnifiedViolationChecker.check(after, grid(after)).weightedScore, 0.0)
    }
}
