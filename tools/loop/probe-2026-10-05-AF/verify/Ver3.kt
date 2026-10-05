package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
fun main(a: Array<String>) {
    val st0 = StateParser.parse(File(a[0]).readText())!!
    val kig = st0.shifts.map { it.kigou }
    fun k(x: String) = kig.indexOf(x)
    fun idx(n: String) = st0.staff.indexOfFirst { it.name.contains(n) }
    val s0 = st0.schedule.map { it.toIntArray() }.toTypedArray()
    val p0 = Problem(st0)
    val r0 = UnifiedViolationChecker.check(st0, s0)
    fun c1Win(s: Array<IntArray>, i: Int): Int { var n = 0; for (j in 0..p0.T - 7) if ((j until j + 7).count { s[i][it] == 0 } < 2) n++; return n }
    println("== 職員別（現盤面）")
    for (i in 0 until p0.S) {
        val hard = r0.cellFamilies.filter { it.key.startsWith("$i,") && it.value.any { c -> c in listOf("vio-c3n", "vio-c3w", "vio-pref", "vio-covU", "vio-groupViol") } }.keys.map { "d" + (it.split(",")[1].toInt() + 1) }
        val cnt = r0.countFamilies.filter { it.key.startsWith("$i,") }.map { (key, v) -> kig[key.split(",")[1].toInt()] + ":" + v.joinToString("/") { it.removePrefix("vio-") } }
        println("${st0.staff[i].name}: HARDセル=$hard c1窓=${c1Win(s0, i)} 回数=$cnt")
    }
    println("covU 日別=" + r0.needFamilies.filter { it.value.contains("vio-covU") }.keys.map { val (kk, j) = it.split(",").map { x -> x.toInt() }; "${kig[kk]}@d${j + 1}" })
    // Ideal-B
    val cancel = listOf("福澤" to 2, "古泉" to 26, "佐藤" to 24, "大島" to 11)
    val keys = cancel.map { (n, d) -> "${idx(n)},${d - 1}" }
    println("取消する希望: " + keys.map { it + "=" + (st0.wishes[it]?.let { w -> kig[w] } ?: "なし") })
    val st = st0.copy(wishes = st0.wishes - keys.toSet())
    val p = Problem(st)
    val s = s0.map { it.copyOf() }.toTypedArray()
    val set = listOf(Triple("古泉", 26, "Cｵ"), Triple("佐藤", 24, "Cｱ"), Triple("大島", 11, "Pｼ"),
        Triple("アリフ", 7, "休"), Triple("アリフ", 8, "休"), Triple("佐藤", 7, "Dﾃ"), Triple("佐藤", 8, "Dﾃ"), Triple("佐藤", 9, "休"),
        Triple("モニカ", 11, "休"), Triple("モニカ", 1, "Aｱ"))
    for ((n, d, x) in set) { val i = idx(n); println("  $n d$d ${kig[s[i][d - 1]]}->$x mayPlace=${p.mayPlace(i, k(x))} canDo=${p.canDo(i, k(x))} locked=${p.wishLocked(i, d - 1)}"); s[i][d - 1] = k(x) }
    // 福澤 d2: 担当可の出勤で最良
    val fi = idx("福澤")
    val best = p.allowedShiftsForStaff(fi).filter { it != 0 }.minByOrNull { x -> val w = s.map { it.copyOf() }.toTypedArray(); w[fi][1] = x; UnifiedViolationChecker.check(st, w).weightedScore }!!
    println("  福澤 d2 ${kig[s[fi][1]]}->${kig[best]}"); s[fi][1] = best
    val r = UnifiedViolationChecker.check(st, s)
    println("IdealB 中核適用: H ${r0.hard}->${r.hard} W ${r0.weightedScore}->${r.weightedScore} bd=${r.breakdown.filterValues { it > 0 }}")
    println("  covU 日別=" + r.needFamilies.filter { it.value.contains("vio-covU") }.keys.map { val (kk, j) = it.split(",").map { x -> x.toInt() }; "${kig[kk]}@d${j + 1}" })
    // 希望取消だけ（盤面そのまま）の床
    println("取消後の希望床=${V6SanityPort.wishConflictHardFloor(p)} 取消前=${V6SanityPort.wishConflictHardFloor(p0)}")
    // 中核の後に連鎖（全族起点）で需要を埋められるか
    val stt = C1EjectionChainPolish.Stats()
    val rr = C1EjectionChainPolish.apply(st, s.map { it.copyOf() }.toTypedArray(), C1EjectionChainPolish.Config(origin = C1EjectionChainPolish.Origin.ALL, maxEvaluations = 2_000_000L), stats = stt)
    val r2 = UnifiedViolationChecker.check(st, rr.newSchedule)
    println("  +連鎖(ALL): H=${r2.hard} W=${r2.weightedScore} bd=${r2.breakdown.filterValues { it > 0 }}")
}
