package com.magi.app.work

import org.json.JSONObject

/**
 * 実行中マーカー（`magi_run_marker.json`）の組み立てと、中断案内の文言。JVM で試せるよう VM の外に置く。
 * S5（希望の取り消しと、もう一度つくる）の実行だけ `s5` オブジェクトを載せ、再起動後の案内で取り消した希望を名指しする（s5_wish_trial.md §10）。
 */
internal object RunMarker {
    class S5(val staff: Int, val day: Int, val symbol: String, val name: String)

    fun format(startedAt: Long, mode: String, budgetSec: Int, workers: Int, algorithm: String, s5: S5? = null): String {
        val o = JSONObject()
        o.put("startedAt", startedAt)
        o.put("mode", mode) // "fg" | "bg"
        o.put("budgetSec", budgetSec)
        o.put("workers", workers)
        o.put("algorithm", algorithm)
        if (s5 != null) o.put("s5", JSONObject().put("staff", s5.staff).put("day", s5.day).put("symbol", s5.symbol).put("name", s5.name))
        return o.toString()
    }

    /** 旧マーカー（`s5` なし）・壊れた値は null。 */
    fun parseS5(marker: String): S5? = runCatching {
        val s = JSONObject(marker).optJSONObject("s5") ?: return null
        S5(s.getInt("staff"), s.getInt("day"), s.getString("symbol"), s.getString("name"))
    }.getOrNull()

    fun s5Suffix(marker: String): String {
        val s = parseS5(marker) ?: return ""
        return "（希望（${s.name} ${s.day + 1}日 ${s.symbol}）は取り消したまま保存されています）"
    }

    /** 途中結果なしで中断したときの案内。 */
    fun interruptedInfo(marker: String): String = runCatching {
        val modeJp = if (JSONObject(marker).optString("mode") == "bg") "バックグラウンド" else ""
        "前回の${modeJp}最適化は完了前に中断されました。入力は自動保存済みです。もう一度実行できます。"
    }.getOrNull()?.plus(s5Suffix(marker)) ?: "前回の最適化は完了前に中断されました。入力は自動保存済みです。"
}
