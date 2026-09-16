package com.magi.app.ui

/**
 * イベント → ViewModel の対応表。鎖の輪ごとに 1 族を受け持ち、担当外は false で次の輪へ送る。
 *
 * ここには**分岐も判定も置かない**（可否は [MagiArbiter] が済ませている）＝この表が肥大しても
 * 壊れようがないことが、ロジックを [MagiViewState] と [MagiMediator] へ寄せた見返り。
 */
internal fun magiHandlers(vm: MagiViewModel, onExport: (MagiEvent.ExportKind) -> Unit): List<MagiEventHandler> = listOf(
    MagiEventHandler { e ->
        if (e !is MagiEvent.Board) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Board.SetCell -> vm.setCell(e.staff, e.day, e.shift)
            is MagiEvent.Board.SetCells -> vm.setCells(e.cells, e.shift)
            is MagiEvent.Board.ApplyWishes -> vm.applyWishes(e.includeOutOfScope)
            is MagiEvent.Board.ApplyAlternative -> vm.applyAlternative(e.index)
            is MagiEvent.Board.ApplyFixSuggestion -> vm.applyFixSuggestion(e.suggestion)
            MagiEvent.Board.Undo -> vm.undo()
            MagiEvent.Board.Redo -> vm.redo()
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Structure) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Structure.EditShift -> vm.ws1EditShift(e.shift, e.name, e.kigou, e.need1, e.need2)
            is MagiEvent.Structure.SetShiftNeed -> vm.setShiftNeed(e.shift, e.need1, e.need2)
            is MagiEvent.Structure.AddShift -> vm.ws1AddShift(e.name, e.kigou, e.need1, e.need2)
            is MagiEvent.Structure.RemoveShift -> vm.ws1RemoveShift(e.shift)
            is MagiEvent.Structure.MoveShift -> vm.ws1MoveShiftTo(e.from, e.to)
            is MagiEvent.Structure.EditGroup -> vm.ws1EditGroup(e.group, e.name, e.kigou)
            is MagiEvent.Structure.AddGroup -> vm.ws1AddGroup(e.name, e.kigou)
            is MagiEvent.Structure.RemoveGroup -> vm.ws1RemoveGroup(e.group)
            is MagiEvent.Structure.MoveGroup -> vm.ws1MoveGroupTo(e.from, e.to)
            is MagiEvent.Structure.EditStaff -> vm.ws1EditStaff(e.staff, e.name, e.groupIdx)
            is MagiEvent.Structure.AddStaff -> vm.ws1AddStaff(e.name, e.groupIdx)
            is MagiEvent.Structure.RemoveStaff -> vm.ws1RemoveStaff(e.staff)
            is MagiEvent.Structure.MoveStaff -> vm.ws1MoveStaffTo(e.from, e.to)
            is MagiEvent.Structure.SetGroupShift -> vm.ws1SetGroupShift(e.group, e.shift, e.allowed)
            is MagiEvent.Structure.SetGroupShiftRow -> vm.ws1SetGroupShiftRow(e.group, e.allowed)
            is MagiEvent.Structure.SetGroupShiftColumn -> vm.ws1SetGroupShiftColumn(e.shift, e.allowed)
            is MagiEvent.Structure.SetGroupApt -> vm.ws1SetGroupApt(e.group, e.shift, e.value)
            MagiEvent.Structure.ResetGroupApt -> vm.ws1ResetGroupApt()
            is MagiEvent.Structure.SetUse2 -> vm.ws1SetUse2(e.on)
            is MagiEvent.Structure.ResizeDays -> vm.ws1ResizeDays(e.days)
            is MagiEvent.Structure.SetMonth -> vm.setMonth(e.year, e.month1to12)
            is MagiEvent.Structure.ShiftMonth -> vm.shiftMonth(e.delta)
            MagiEvent.Structure.SetNextMonth -> vm.setNextMonth()
            is MagiEvent.Structure.AddSkillGroup -> vm.addSkillGroup(e.name, e.kigou)
            is MagiEvent.Structure.EditSkillGroup -> vm.editSkillGroup(e.group, e.name, e.kigou)
            is MagiEvent.Structure.RemoveSkillGroup -> vm.removeSkillGroup(e.group)
            is MagiEvent.Structure.SetStaffSkill -> vm.setStaffSkill(e.staff, e.skillIdx)
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Condition) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Condition.SetNeedDay -> vm.setNeedDay(e.shift, e.day, e.p1, e.p2)
            is MagiEvent.Condition.RemoveNeedDay -> vm.removeNeedDay(e.shift, e.day)
            is MagiEvent.Condition.SetStaffRange -> vm.setStaffRange(e.staff, e.shift, e.lo, e.hi)
            is MagiEvent.Condition.RelaxStaffRangePin -> vm.relaxStaffRangePin(e.staff, e.shift, e.loDelta, e.hiDelta)
            is MagiEvent.Condition.RemoveStaffRange -> vm.removeStaffRange(e.staff, e.shift)
            is MagiEvent.Condition.SetGroupRange -> vm.setGroupRange(e.group, e.shift, e.lo, e.hi)
            is MagiEvent.Condition.ClearGroupRange -> vm.clearGroupRange(e.group, e.shift, e.lo, e.hi)
            is MagiEvent.Condition.ClearGroupRangeAll -> vm.clearGroupRangeAll(e.group, e.shift)
            is MagiEvent.Condition.ClearGroupRangeSection -> vm.clearGroupRangeSection(e.group)
            is MagiEvent.Condition.SetWish -> vm.setWish(e.staff, e.day, e.shift)
            is MagiEvent.Condition.RemoveWish -> vm.removeWish(e.staff, e.day)
            is MagiEvent.Condition.SetWishesForDays -> vm.setWishesForDays(e.staff, e.days, e.shift)
            is MagiEvent.Condition.ClearWishesForDays -> vm.clearWishesForDays(e.staff, e.days)
            MagiEvent.Condition.ClearAllWishes -> vm.clearAllWishes()
            MagiEvent.Condition.ClearOutOfScopeWishes -> vm.clearOutOfScopeWishes()
            is MagiEvent.Condition.SetShiftColor -> vm.setShiftColor(e.kigou, e.hex)
            is MagiEvent.Condition.ResetShiftColor -> vm.resetShiftColor(e.kigou)
            is MagiEvent.Condition.SetViolationColor -> vm.setViolationColor(e.hex)
            MagiEvent.Condition.ResetViolationColor -> vm.resetViolationColor()
            is MagiEvent.Condition.SetViolationSoftColor -> vm.setViolationSoftColor(e.hex)
            MagiEvent.Condition.ResetViolationSoftColor -> vm.resetViolationSoftColor()
            is MagiEvent.Condition.SetViolationFamilyColor -> vm.setViolationFamilyColor(e.family, e.hex)
            is MagiEvent.Condition.ResetViolationFamilyColor -> vm.resetViolationFamilyColor(e.family)
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Constraint) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Constraint.AddCons1 -> vm.addCons1(e.day1, e.shiftKigou, e.day2)
            is MagiEvent.Constraint.AddCons2 -> vm.addCons2(e.shiftKigou, e.count)
            is MagiEvent.Constraint.AddCons3 -> vm.addCons3(e.family, e.pattern)
            is MagiEvent.Constraint.AddCons3w -> vm.addCons3w(e.wishKigou, e.prevKigou)
            is MagiEvent.Constraint.AddCons41 -> vm.addCons41(e.groupKigou, e.shiftKigou, e.l, e.u)
            is MagiEvent.Constraint.AddCons41s -> vm.addCons41s(e.groupKigou, e.shiftKigou, e.l, e.u)
            is MagiEvent.Constraint.AddCons42 -> vm.addCons42(e.g1, e.g2, e.s1, e.s2)
            is MagiEvent.Constraint.AddCons42s -> vm.addCons42s(e.g1, e.g2, e.s1, e.s2)
            is MagiEvent.Constraint.Remove -> vm.removeConstraint(e.family, e.index)
            is MagiEvent.Constraint.Update -> vm.updateConstraint(e.family, e.index, e.values)
            is MagiEvent.Constraint.ApplySettingFix -> vm.applySettingFix(e.issue)
            is MagiEvent.Constraint.RelaxForbiddenRule -> vm.relaxForbiddenRule(e.seqLabel)
        }
        true
    },
    MagiEventHandler { e ->
        when (e) {
            MagiEvent.Run.Optimize -> vm.runV6FullOptimize()
            MagiEvent.Run.SoftPolish -> vm.runSoftPolish()
            MagiEvent.Run.SmartInitial -> vm.generateSmartInitial()
            MagiEvent.Run.InBackground -> vm.runInBackground()
            MagiEvent.Run.Stop -> vm.stop()
            else -> return@MagiEventHandler false
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Io) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Io.Load -> vm.load(e.json, e.note)
            MagiEvent.Io.InitBlankState -> vm.initBlankState()
            MagiEvent.Io.RestorePrevious -> vm.restorePreviousData()
            is MagiEvent.Io.ImportCsvSmart -> vm.importCsvSmart(e.rawText)
            is MagiEvent.Io.ImportRosterAs -> vm.importRosterAs(e.rawText, e.asWishes)
            is MagiEvent.Io.ImportStaffCsv -> vm.importStaffCsv(e.rawText)
            is MagiEvent.Io.ImportWishesCsv -> vm.importWishesCsv(e.rawText)
            is MagiEvent.Io.ImportConstraintsCsv -> vm.importConstraintsCsv(e.rawText)
            is MagiEvent.Io.ImportShiftColorsCsv -> vm.importShiftColorsCsv(e.rawText)
            // 書き出しは結果の文字列を端末へ渡す必要があるので、保存先を持つ Root が受ける。
            is MagiEvent.Io.Export -> onExport(e.kind2)
            MagiEvent.Io.SaveNow -> vm.saveNow()
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Settings) return@MagiEventHandler false
        when (e) {
            is MagiEvent.Settings.SetWorkers -> vm.setWorkers(e.n)
            is MagiEvent.Settings.SetBudget -> vm.setBudget(e.sec)
            is MagiEvent.Settings.SetSoftPolish -> vm.setSoftPolish(e.on)
            is MagiEvent.Settings.SetAlgorithm -> vm.setV6Algorithm(e.algorithm)
            is MagiEvent.Settings.SetNativeAccel -> vm.setNativeAccel(e.on)
            is MagiEvent.Settings.SetNativeParity -> vm.setNativeParity(e.on)
            is MagiEvent.Settings.SetBlockSwapC3nFilter -> vm.setBlockSwapC3nFilter(e.on)
            is MagiEvent.Settings.SetWideC3nBreak -> vm.setWideC3nBreak(e.on)
            is MagiEvent.Settings.SetCombineExhaustPairs -> vm.setCombineExhaustPairs(e.on)
            is MagiEvent.Settings.SetLnsAdaptive -> vm.setLnsAdaptive(e.on)
            is MagiEvent.Settings.SetCountChainPolish -> vm.setCountChainPolish(e.on)
            is MagiEvent.Settings.SetAptFairSoftTolerance -> vm.setAptFairSoftTolerance(e.on)
        }
        true
    },
    MagiEventHandler { e ->
        if (e !is MagiEvent.Session) return@MagiEventHandler false
        when (e) {
            MagiEvent.Session.RefreshCheck -> vm.refreshCheck()
            is MagiEvent.Session.FindFixSuggestions -> vm.findFixSuggestions(e.focusStaff, e.focusShift)
            is MagiEvent.Session.Notify -> vm.notify(e.text, e.level)
            is MagiEvent.Session.ClearMessage -> vm.clearMessage(e.shown)
            is MagiEvent.Session.AddReviewMemo -> vm.addReviewMemo(e.text)
            is MagiEvent.Session.RemoveReviewMemo -> vm.removeReviewMemo(e.index)
            MagiEvent.Session.DismissInterrupted -> vm.dismissInterrupted()
            // 画面遷移・選択は Root が持つ（盤面にもドメインにも触らない）。
            is MagiEvent.Session.SelectTab, is MagiEvent.Session.FocusCell,
            is MagiEvent.Session.ToggleVioBucket, is MagiEvent.Session.SetNameQuery -> return@MagiEventHandler false
        }
        true
    },
)
