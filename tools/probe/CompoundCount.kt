package probe.compound

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import org.json.JSONObject
import java.io.File

// シーソー仕様 §6「1 組の手を Range・Apt・Fair へ広げる」の §9 の 4（測定だけ）。最適化後の盤面で、同じ日の 2 人の入れ替えのうち
// 回数の族（low・high・apt・fair）の件数は減るのに betterReport で落ちる手を数え、そのうち c1 が増えた手について、
// 入れ替えた 2 人のどちらかと別の 1 人の同じ日の入れ替え（前後 6 日、GapPolish の埋め直しと同じ届く範囲）を 1 つ足すと採れる手を数える。
// args: boardDir fixtures(csv)
private val COUNT_FAMILIES = listOf("low", "high", "apt", "fair")
private const val REACH = 6

fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val dir = File(args[0])
    for (fx in args[1].split(',')) {
        val text = File(dir, "$fx.opt.json").readText()
        val st = StateParser.parse(text)!!
        val a = JSONObject(text).getJSONArray("schedule")
        val b0 = Array(a.length()) { i -> a.getJSONArray(i).let { r -> IntArray(r.length()) { r.getInt(it) } } }
        run(fx, st, b0)
    }
}

private fun run(fx: String, st: com.magi.app.model.MagiState, b0: Array<IntArray>) {
    val p = Problem(st)
    val b = normalizeSchedule(b0, p)
    val base = UnifiedViolationChecker.check(st, b)
    fun fam(r: ViolationReport) = COUNT_FAMILIES.sumOf { r.breakdown[it] ?: 0 }
    fun ok(i: Int, j: Int, k: Int) = !p.wishLocked(i, j) && p.mayPlaceAt(i, j, k)
    fun swap(i1: Int, i2: Int, j: Int) { val t = b[i1][j]; b[i1][j] = b[i2][j]; b[i2][j] = t }
    fun canSwap(i1: Int, i2: Int, j: Int) = b[i1][j] != b[i2][j] && ok(i1, j, b[i2][j]) && ok(i2, j, b[i1][j])
    var famDown = 0; var direct = 0; var rejected = 0; var c1Up = 0; var rescued = 0
    var bestW = base.weightedScore
    val t0 = System.currentTimeMillis()
    for (j in 0 until p.T) for (i1 in 0 until p.S) for (i2 in i1 + 1 until p.S) {
        if (!canSwap(i1, i2, j)) continue
        swap(i1, i2, j)
        val r = UnifiedViolationChecker.check(st, b)
        if (fam(r) < fam(base)) {
            famDown++
            if (betterReport(r, base)) { direct++; if (r.weightedScore < bestW) bestW = r.weightedScore }
            else {
                rejected++
                if ((r.breakdown["c1"] ?: 0) > (base.breakdown["c1"] ?: 0) && r.hard <= base.hard) {
                    c1Up++
                    var saved = false
                    loop@ for (who in intArrayOf(i1, i2)) for (d in (j - REACH).coerceAtLeast(0)..(j + REACH).coerceAtMost(p.T - 1)) for (q in 0 until p.S) {
                        if (q == i1 || q == i2 || !canSwap(who, q, d)) continue
                        swap(who, q, d)
                        val r2 = UnifiedViolationChecker.check(st, b)
                        swap(who, q, d)
                        if (betterReport(r2, base)) { saved = true; if (r2.weightedScore < bestW) bestW = r2.weightedScore; break@loop }
                    }
                    if (saved) rescued++
                }
            }
        }
        swap(i1, i2, j)
    }
    println("COMPOUND $fx 必須=${base.hard} 重み=${base.weightedScore.toLong()} 回数の族が減る入れ替え=$famDown うち単独で採れる=$direct 落ちる=$rejected" +
        " うちc1が増えた=$c1Up 埋め直しで採れる=$rescued 最良の重み=${bestW.toLong()}(${"%.2f".format(100.0 * (bestW - base.weightedScore) / base.weightedScore)}%) ${System.currentTimeMillis() - t0}ms")
}
