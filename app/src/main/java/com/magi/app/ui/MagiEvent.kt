package com.magi.app.ui

import com.magi.app.v6.FixSuggestion
import com.magi.app.v6.V6Algorithm
import com.magi.app.v6.SettingIssue

/**
 * 画面で起きた操作。Composable はこれを上へ渡すだけで、何が起きるかは知らない。
 * [kind]＝「実行中でも通してよいか」の分類で、判定は [MagiArbiter] の1箇所だけが行う。
 */
internal sealed interface MagiEvent {
    val kind: MagiEventKind

    /** 盤面セルの書き換え。 */
    sealed interface Board : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.BoardEdit

        data class SetCell(val staff: Int, val day: Int, val shift: Int) : Board
        data class SetCells(val cells: Collection<Pair<Int, Int>>, val shift: Int) : Board
        data class ApplyWishes(val includeOutOfScope: Boolean) : Board
        data class ApplyAlternative(val index: Int) : Board
        data class ApplyFixSuggestion(val suggestion: FixSuggestion) : Board
        data object Undo : Board
        data object Redo : Board
    }

    /** ws1（シフト・グループ・職員・スキル・期間）の構造変更。 */
    sealed interface Structure : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.StructureEdit

        // [3.603.0/backlog#24] isRest: このシフトを「休みとして扱う」(ShiftRole.Rest)トグル。単一選択＝
        //   trueにすると他の全シフトのRestは外れる（Ws1Ops側で担保）。
        data class EditShift(val shift: Int, val name: String, val kigou: String, val need1: String, val need2: String, val isRest: Boolean) : Structure
        data class SetShiftNeed(val shift: Int, val need1: String, val need2: String) : Structure
        data class AddShift(val name: String, val kigou: String, val need1: String, val need2: String, val isRest: Boolean = false) : Structure
        /** 一括追加（記号がそのまま名称）。1回の操作＝1回の「元に戻す」。 */
        data class BulkAddShift(val kigous: List<String>) : Structure
        data class RemoveShift(val shift: Int) : Structure
        data class MoveShift(val from: Int, val to: Int) : Structure
        data class EditGroup(val group: Int, val name: String, val kigou: String) : Structure
        data class AddGroup(val name: String, val kigou: String) : Structure
        data class RemoveGroup(val group: Int) : Structure
        data class MoveGroup(val from: Int, val to: Int) : Structure
        data class EditStaff(val staff: Int, val name: String, val groupIdx: Int) : Structure
        data class AddStaff(val name: String, val groupIdx: Int) : Structure
        data class BulkAddStaff(val names: List<String>, val groupIdx: Int) : Structure
        data class RemoveStaff(val staff: Int) : Structure
        data class MoveStaff(val from: Int, val to: Int) : Structure
        data class SetGroupShift(val group: Int, val shift: Int, val allowed: Boolean) : Structure
        data class SetGroupShiftRow(val group: Int, val allowed: Boolean) : Structure
        data class SetGroupShiftColumn(val shift: Int, val allowed: Boolean) : Structure
        data class SetGroupApt(val group: Int, val shift: Int, val value: String) : Structure
        data object ResetGroupApt : Structure
        data class SetUse2(val on: Boolean) : Structure
        data class ResizeDays(val days: Int) : Structure
        data class SetMonth(val year: Int, val month1to12: Int) : Structure
        data class ShiftMonth(val delta: Int) : Structure
        data object SetNextMonth : Structure
        data class AddSkillGroup(val name: String, val kigou: String) : Structure
        data class EditSkillGroup(val group: Int, val name: String, val kigou: String) : Structure
        data class RemoveSkillGroup(val group: Int) : Structure
        data class SetStaffSkill(val staff: Int, val skillIdx: Int) : Structure
    }

    /** 月別条件（人数・範囲・希望・表示色）。 */
    sealed interface Condition : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.StructureEdit

        data class SetNeedDay(val shift: Int, val day: Int, val p1: String, val p2: String) : Condition
        data class RemoveNeedDay(val shift: Int, val day: Int) : Condition
        data class SetNeedDaysForDays(val shift: Int, val days: List<Int>, val p1: String, val p2: String) : Condition
        data class ClearNeedDaysForDays(val shift: Int, val days: List<Int>) : Condition
        data class SetStaffRange(val staff: Int, val shift: Int, val lo: String, val hi: String) : Condition
        data class RelaxStaffRangePin(val staff: Int, val shift: Int, val loDelta: Int, val hiDelta: Int) : Condition
        data class RemoveStaffRange(val staff: Int, val shift: Int) : Condition
        data class SetGroupRange(val group: Int, val shift: Int, val lo: String, val hi: String) : Condition
        data class ClearGroupRange(val group: Int, val shift: Int, val lo: String, val hi: String) : Condition
        data class ClearGroupRangeAll(val group: Int, val shift: Int) : Condition
        data class ClearGroupRangeSection(val group: Int) : Condition
        data class SetWish(val staff: Int, val day: Int, val shift: Int) : Condition
        data class RemoveWish(val staff: Int, val day: Int) : Condition
        data class SetWishesForDays(val staff: Int?, val days: List<Int>, val shift: Int) : Condition
        data class ClearWishesForDays(val staff: Int?, val days: List<Int>) : Condition
        data object ClearAllWishes : Condition
        data object ClearOutOfScopeWishes : Condition
        data class SetShiftColor(val kigou: String, val hex: String) : Condition
        data class ResetShiftColor(val kigou: String) : Condition
        data class SetViolationColor(val hex: String) : Condition
        data object ResetViolationColor : Condition
        data class SetViolationSoftColor(val hex: String) : Condition
        data object ResetViolationSoftColor : Condition
        data class SetViolationFamilyColor(val family: String, val hex: String) : Condition
        data class ResetViolationFamilyColor(val family: String) : Condition
    }

    /** 制約（cons1/2/3系/41/42 とスキル変種）。`family` は保存データ互換の文字列キー。 */
    sealed interface Constraint : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.StructureEdit

        data class AddCons1(val day1: String, val shiftKigou: String, val day2: String) : Constraint
        data class AddCons2(val shiftKigou: String, val count: String) : Constraint
        data class AddCons3(val family: String, val pattern: List<String>) : Constraint
        data class AddCons3w(val wishKigou: String, val prevKigou: String) : Constraint
        data class AddCons41(val groupKigou: String, val shiftKigou: String, val l: String, val u: String) : Constraint
        data class AddCons41s(val groupKigou: String, val shiftKigou: String, val l: String, val u: String) : Constraint
        data class AddCons42(val g1: String, val g2: String, val s1: String, val s2: String) : Constraint
        data class AddCons42s(val g1: String, val g2: String, val s1: String, val s2: String) : Constraint
        data class Remove(val family: String, val index: Int) : Constraint
        data class Update(val family: String, val index: Int, val values: List<String>) : Constraint
        data class ApplySettingFix(val issue: SettingIssue) : Constraint
        data class RelaxForbiddenRule(val seqLabel: String) : Constraint
    }

    /** 盤面を差し替えるジョブの開始・中止。 */
    sealed interface Run : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.RunStart
        /** 拒否ログに出す操作名（旧 `runBlockedByInFlight(what)` の引数）。 */
        val what: String

        data object Optimize : Run { override val what get() = "勤務表づくり" }
        data object SoftPolish : Run { override val what get() = "仕上げ最適化" }
        data object SmartInitial : Run { override val what get() = "下書きづくり" }
        data object InBackground : Run { override val what get() = "バックグラウンド最適化" }

        /** 中止は実行中にこそ押される＝常に通す。 */
        data object Stop : MagiEvent {
            override val kind: MagiEventKind get() = MagiEventKind.Always
        }
    }

    /** 読み書き。盤面を差し替えるものだけ [MagiEventKind.RunStart]。 */
    sealed interface Io : MagiEvent {
        data class Load(val json: String, val note: String = "") : Io {
            override val kind: MagiEventKind get() = MagiEventKind.RunStart
        }
        data object InitBlankState : Io {
            override val kind: MagiEventKind get() = MagiEventKind.RunStart
        }
        data object RestorePrevious : Io {
            override val kind: MagiEventKind get() = MagiEventKind.RunStart
        }
        data class ImportCsvSmart(val rawText: String) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.RunStart
        }
        data class ImportRosterAs(val rawText: String, val asWishes: Boolean) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.RunStart
        }
        data class ImportStaffCsv(val rawText: String) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.StructureEdit
        }
        data class ImportWishesCsv(val rawText: String) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.StructureEdit
        }
        data class ImportConstraintsCsv(val rawText: String) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.StructureEdit
        }
        data class ImportShiftColorsCsv(val rawText: String) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.StructureEdit
        }
        /** 書き出しと保存は盤面を触らない＝実行中でも通す。 */
        data class Export(val kind2: ExportKind) : Io {
            override val kind: MagiEventKind get() = MagiEventKind.Always
        }
        data object SaveNow : Io {
            override val kind: MagiEventKind get() = MagiEventKind.Always
        }
    }

    /** 書き出しの種類（旧 `exportXxx()` 群）。 */
    enum class ExportKind { Json, Csv, StaffCsv, WishesCsv, ConstraintsCsv, ShiftColorsCsv, Logs, LogsJson }

    /** エンジン設定。盤面を触らないので実行中でも通す（次の実行から効く）。 */
    sealed interface Settings : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.Always

        data class SetWorkers(val n: Int) : Settings
        data class SetBudget(val sec: Int) : Settings
        data class SetSoftPolish(val on: Boolean) : Settings
        data class SetAlgorithm(val algorithm: V6Algorithm) : Settings
        data class SetNativeAccel(val on: Boolean) : Settings
        data class SetNativeParity(val on: Boolean) : Settings
        data class SetBlockSwapC3nFilter(val on: Boolean) : Settings
        data class SetWideC3nBreak(val on: Boolean) : Settings
        data class SetCombineExhaustPairs(val on: Boolean) : Settings
        data class SetLnsAdaptive(val on: Boolean) : Settings
        data class SetCountChainPolish(val on: Boolean) : Settings
        data class SetAptFairSoftTolerance(val on: Boolean) : Settings
    }

    /** 画面遷移・選択・問い合わせ。盤面を触らない。 */
    sealed interface Session : MagiEvent {
        override val kind: MagiEventKind get() = MagiEventKind.Always

        data class SelectTab(val tab: Int) : Session
        data class FocusCell(val staff: Int, val day: Int) : Session
        data class ToggleVioBucket(val bucket: String) : Session
        data class SetNameQuery(val query: String) : Session
        data object RefreshCheck : Session
        data class FindFixSuggestions(val focusStaff: Int?, val focusShift: Int?, val focusKey: String = "", val exceptStaff: Int? = null, val day: Int? = null) : Session
        /** シートを閉じたときの探索の取り消し（古い結果を書き戻さない）。 */
        data object CancelFixSearch : Session
        data class Notify(val text: String, val level: String = "I") : Session
        data class ClearMessage(val shown: String?) : Session
        data class AddReviewMemo(val text: String) : Session
        data class RemoveReviewMemo(val index: Int) : Session
        data object DismissInterrupted : Session
    }
}
