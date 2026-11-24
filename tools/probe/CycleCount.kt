package probe.cycle

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Random

// シーソー仕様 §9 の 4: 3 人 × 2 日の巡回（A の日 X の s を B が、B の日 Y の s を C が引き受ける）が、最適化後の盤面で
// 実際に何件採れるかを数える（測定だけ）。手: A[X]=s,B[X]=t,B[Y]=s,C[Y]=t → A[X]=t,B[X]=s,B[Y]=t,C[Y]=s（各日の人数・B の回数は不変）。
// 「巡回でしか取れない」＝2 つの同日の入れ替え（A↔B の X・B↔C の Y）のどちらも単独では betterReport で良くならないもの。
// args: resDir boardDir secs fixtures(csv) [sample=20000]
fun main(args: Array<String>) {
    ObjectiveWeights.method = ObjectiveMethod.PRIORITY
    val resDir = File(args[0]); val boardDir = File(args[1]).also { it.mkdirs() }
    val secs = args[2].toInt(); val fixtures = args[3].split(',')
    val sample = args.getOrNull(4)?.toInt() ?: 20_000
    for (fx in fixtures) {
        val text = File(resDir, "$fx.json").readText()
        val st = StateParser.parse(text)!!
        val bf = File(boardDir, "$fx.opt.json")
        val board: Array<IntArray> = if (bf.exists()) {
            val a = JSONObject(bf.readText()).getJSONArray("schedule")
            Array(a.length()) { i -> a.getJSONArray(i).let { r -> IntArray(r.length()) { r.getInt(it) } } }
        } else {
            val res = runBlocking { V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = secs, workers = 4, allowImpossible = true, softPolish = true, seed = 1001L) }
            val o = JSONObject(text); o.put("schedule", JSONArray(res.schedule.map { JSONArray(it.toList()) })); bf.writeText(o.toString())
            res.schedule
        }
        count(fx, st, board, sample)
    }
}

private fun count(fx: String, st: com.magi.app.model.MagiState, b0: Array<IntArray>, sample: Int) {
    val p = Problem(st)
    val b = normalizeSchedule(b0, p)
    val base = UnifiedViolationChecker.check(st, b)
    val S = p.S; val T = p.T; val K = p.K
    val cnt = Array(S) { IntArray(K) }
    for (i in 0 until S) for (j in 0 until T) { val k = b[i][j]; if (k in 0 until K) cnt[i][k]++ }
    fun ok(i: Int, j: Int, k: Int) = !p.wishLocked(i, j) && p.mayPlaceAt(i, j, k)
    // A は s を 1 つ減らしたい（上限超過・目安超過）、C は s を 1 つ増やしたい（下限割れ・目安不足）
    fun over(i: Int, k: Int) = cnt[i][k] > p.rangeHi[i][k] || (p.apt[i][k] >= 0 && cnt[i][k] > p.apt[i][k])
    fun under(i: Int, k: Int) = cnt[i][k] < p.rangeLo[i][k] || (p.apt[i][k] >= 0 && cnt[i][k] < p.apt[i][k])
    fun better(): ViolationReport? { val r = UnifiedViolationChecker.check(st, b); return r.takeIf { betterReport(it, base) } }
    fun swap(i1: Int, i2: Int, j: Int) { val t = b[i1][j]; b[i1][j] = b[i2][j]; b[i2][j] = t }

    var legal = 0L; var targeted = 0; var tImp = 0; var tOnly = 0
    var bestW = base.weightedScore
    val sampled = ArrayList<IntArray>()
    val rnd = Random(7)
    val t0 = System.currentTimeMillis()
    for (bb in 0 until S) for (x in 0 until T) for (y in 0 until T) {
        if (x == y) continue
        val t = b[bb][x]; val s = b[bb][y]
        if (t == s || t !in 0 until K || s !in 0 until K) continue
        if (!ok(bb, x, s) || !ok(bb, y, t)) continue
        for (a in 0 until S) {
            if (a == bb || b[a][x] != s || !ok(a, x, t)) continue
            for (c in 0 until S) {
                if (c == bb || c == a || b[c][y] != t || !ok(c, y, s)) continue
                legal++
                val tgt = over(a, s) && under(c, s)
                if (!tgt) {
                    if (sampled.size < sample) sampled.add(intArrayOf(a, bb, c, x, y))
                    else { val r = rnd.nextLong().mod(legal); if (r < sample) sampled[r.toInt()] = intArrayOf(a, bb, c, x, y) }
                    continue
                }
                targeted++
                swap(a, bb, x); swap(bb, c, y)
                val r = better()
                swap(bb, c, y); swap(a, bb, x)
                if (r == null) continue
                tImp++
                if (r.weightedScore < bestW) bestW = r.weightedScore
                swap(a, bb, x); val r1 = better(); swap(a, bb, x)
                swap(bb, c, y); val r2 = better(); swap(bb, c, y)
                if (r1 == null && r2 == null) tOnly++
            }
        }
    }
    var sImp = 0; var sOnly = 0
    for (m in sampled) {
        val (a, bb, c, x, y) = m.toList()
        swap(a, bb, x); swap(bb, c, y)
        val r = better()
        swap(bb, c, y); swap(a, bb, x)
        if (r == null) continue
        sImp++
        if (r.weightedScore < bestW) bestW = r.weightedScore
        swap(a, bb, x); val r1 = better(); swap(a, bb, x)
        swap(bb, c, y); val r2 = better(); swap(bb, c, y)
        if (r1 == null && r2 == null) sOnly++
    }
    println("CYCLE $fx 必須=${base.hard} 重み=${base.weightedScore.toLong()} 合法=$legal 狙い(A超過×C不足)=$targeted 改善=$tImp うち巡回でしか=$tOnly" +
        " 無作為${sampled.size}件の改善=$sImp うち巡回でしか=$sOnly 最良の重み=${bestW.toLong()}(${"%.2f".format(100.0 * (bestW - base.weightedScore) / base.weightedScore)}%) ${System.currentTimeMillis() - t0}ms")
}
