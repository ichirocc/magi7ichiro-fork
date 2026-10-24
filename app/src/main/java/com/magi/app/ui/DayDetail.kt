package com.magi.app.ui

/** 日ヘッダのタップの読み上げ（どの日でも開く）。 */
internal const val DAY_DETAIL_OPEN = "この日の配置"

/** 日の詳細の 1 行の状態。必要人数の無いシフトは [NONE]（人数だけ出す）。 */
internal enum class DaySlotState { UNDER, OVER, OK, NONE }

/**
 * 日の詳細（日ヘッダのシート）のシフト 1 行: その日にそのシフトへ入っている職員と、必要人数との対比。
 * [need]・[fit] は必要人数と適正人数（`ConditionsView.needCellLimits` と同じ値。人員の印の判定と同じ源）。
 */
internal data class DaySlotRow(val shift: Int, val symbol: String, val staff: List<Int>, val need: Int?, val fit: Int?) {
    val got: Int get() = staff.size
    val state: DaySlotState get() = when {
        need == null -> DaySlotState.NONE
        got < need -> DaySlotState.UNDER
        fit != null && got > fit -> DaySlotState.OVER
        else -> DaySlotState.OK
    }

    /** 例「Pｼ　3/3人　充足」「Dﾃ　1/2人　1人不足」「Aｱ　3人（適正 2人）　1人多い」「休　4人」。 */
    val line: String get() = when (state) {
        DaySlotState.UNDER -> "$symbol　$got/${need}人　${need!! - got}人不足"
        DaySlotState.OVER -> "$symbol　${got}人（適正 ${fit}人）　${got - fit!!}人多い"
        DaySlotState.OK -> "$symbol　$got/${need}人　充足"
        DaySlotState.NONE -> "$symbol　${got}人"
    }
}

/** 日 [day] のシフトごとの行。誰か入っているか必要人数があるシフトだけを、シフトの並び順に出す。 */
internal fun daySlotRows(schedule: List<List<Int>>, symbols: List<String>, day: Int, limits: (Int, Int) -> Pair<Int, Int>?): List<DaySlotRow> =
    symbols.indices.mapNotNull { k ->
        val staff = schedule.indices.filter { schedule[it].getOrNull(day) == k }
        val lim = limits(k, day)
        if (staff.isEmpty() && lim == null) null
        else DaySlotRow(k, symbols[k], staff, lim?.first, lim?.second)
    }

/** シートの日送りの先（範囲外は null＝ボタンを出さない）。 */
internal fun dayStep(day: Int, delta: Int, days: Int): Int? = (day + delta).takeIf { it in 0 until days }
