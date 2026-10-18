package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [3.718.0] 問題パネル: パネルの状態（閉じる／セル／一覧）、高さの上限＝画面の 1/3、問題一覧の行（必須→要調整、行→セル）、
 * 「一覧へ」の語（C# `Review3718Test` と同じ文字列）。
 */
class Review3718Test {
    @Test fun panelModeTransitions() {
        // 一覧を開く → 行を押しても一覧のまま → セルを押すと編集（閉じない）→「一覧へ」で一覧 → 閉じる。
        var m = nextPanelMode(PanelMode.CLOSED, PanelAction.OPEN_LIST); assertEquals(PanelMode.LIST, m)
        m = nextPanelMode(m, PanelAction.ROW_TAP); assertEquals(PanelMode.LIST, m)
        m = nextPanelMode(m, PanelAction.CELL_TAP); assertEquals(PanelMode.CELL, m)
        m = nextPanelMode(m, PanelAction.TO_LIST); assertEquals(PanelMode.LIST, m)
        m = nextPanelMode(m, PanelAction.CLOSE); assertEquals(PanelMode.CLOSED, m)
        assertEquals(PanelMode.CLOSED, nextPanelMode(PanelMode.CLOSED, PanelAction.ROW_TAP))
        assertEquals(PanelMode.CELL, nextPanelMode(PanelMode.CLOSED, PanelAction.CELL_TAP))
        // 画面の状態から: セルを開いていれば編集が優先。
        assertEquals(PanelMode.CELL, panelModeOf(listOpen = true, cellOpen = true))
        assertEquals(PanelMode.LIST, panelModeOf(listOpen = true, cellOpen = false))
        assertEquals(PanelMode.CLOSED, panelModeOf(listOpen = false, cellOpen = false))
    }

    @Test fun panelHeightIsOneThirdOfScreen() {
        assertEquals(305, panelMaxHeightDp(915))
        assertEquals(246, panelMaxHeightDp(740))
        assertEquals("一覧へ", PANEL_LIST_BUTTON)
        assertEquals("要調整 3件", softRowsHeading(3))
        assertNull(softRowsHeading(0))
    }

    @Test fun problemRowsPutHardFirstThenSoftByDay() {
        val items = listOf(
            TourItem("pref", 1, listOf(3), "希望 A ・ 10/4", syms = listOf("B"), dayMd = "10/4"),
            TourItem("c3n", 0, listOf(5, 6), "禁止の並び 夜→夜 ・ 10/6〜10/7", syms = listOf("夜", "夜"), dayMd = "10/6"),
        )
        val hardRows = hardViolationRows(items, listOf("福澤", "金沢"), emptySet())
        val fams = mapOf("1,3" to listOf("vio-pref"), "0,1" to listOf("vio-c3", "vio-c3"), "0,0" to listOf("vio-c1"), "9,9" to listOf("vio-c1"), "0,5" to listOf("vio-c3n"))
        val sched = listOf(listOf(0, 1, 1, 1, 1, 2, 2), listOf(0, 0, 0, 1, 0, 0, 0))
        val rows = problemRows(hardRows, items, fams, listOf("福澤", "金沢"), listOf("A", "B", "夜"), sched, "2026-10-01")
        // 必須は巡回の順（tourAt）、要調整は日→職員→族。必須の族（c3n）は要調整に重ねない。範囲外のキーは捨てる。
        assertEquals(listOf(true, true, false, false), rows.map { it.hard })
        assertEquals(listOf(0, 1), rows.filter { it.hard }.map { it.tourAt })
        assertEquals(1 to 3, rows[0].cell)
        assertEquals(0 to 5, rows[1].cell)
        assertEquals(listOf("c1", "c3"), rows.filter { !it.hard }.map { it.family })
        assertEquals(0 to 0, rows[2].cell)
        assertEquals("福澤 10/2 守るとよい並び", rows[3].line)   // 要調整の行は problemLine の型（名前 日 族の名）
        assertTrue(rows.all { it.line.length <= PROBLEM_LINE_MAX })
        assertNull(rows[2].tourAt)
    }
}
