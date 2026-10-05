package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
fun main(a: Array<String>) {
    val st = StateParser.parse(File(a[0]).readText())!!
    val p = Problem(st)
    val kig = st.shifts.map { it.kigou }
    fun k(x: String) = kig.indexOf(x)
    fun idx(n: String) = st.staff.indexOfFirst { it.name.contains(n) }
    val s0 = st.schedule.map { it.toIntArray() }.toTypedArray()
    val r0 = UnifiedViolationChecker.check(st, s0)
    fun c1Win(s: Array<IntArray>, i: Int): Int { var n = 0; for (j in 0..p.T - 7) if ((j until j + 7).count { s[i][it] == 0 } < 2) n++; return n }
    fun run(label: String, ch: List<Triple<String, Int, String>>) {
        val s = s0.map { it.copyOf() }.toTypedArray()
        for ((n, d, x) in ch) { val i = idx(n); println("   $n d$d ${kig[s[i][d - 1]]}->$x locked=${p.wishLocked(i, d - 1)} mayPlace=${p.mayPlace(i, k(x))}"); s[i][d - 1] = k(x) }
        val r = UnifiedViolationChecker.check(st, s)
        val diff = (r.breakdown.keys).mapNotNull { f -> val d = (r.breakdown[f] ?: 0) - (r0.breakdown[f] ?: 0); if (d != 0) "$f${if (d > 0) "+" else ""}$d" else null }
        println("$label: H ${r0.hard}->${r.hard} W ${r0.weightedScore}->${r.weightedScore} T ${r0.total}->${r.total} better=${betterReport(r, r0)} diff=$diff")
        for (n in ch.map { it.first }.distinct()) { val i = idx(n); println("   $n 休 ${s0[i].count { it == 0 }}->${s[i].count { it == 0 }} c1窓 ${c1Win(s0, i)}->${c1Win(s, i)}") }
    }
    val arif = listOf(Triple("アリフ", 7, "休"), Triple("アリフ", 8, "休"), Triple("佐藤", 7, "Dﾃ"), Triple("佐藤", 8, "Dﾃ"), Triple("佐藤", 9, "休"))
    val monica = listOf(Triple("モニカ", 11, "休"), Triple("モニカ", 1, "Aｱ"))
    run("アリフ", arif); run("モニカ(Aｱ)", monica)
    for (x in listOf("B4", "A4", "有")) run("モニカ($x)", listOf(Triple("モニカ", 11, "休"), Triple("モニカ", 1, x)))
    run("両方", arif + monica)
    // 片側希望の c3n の確認: アリフ d8-9 Dﾃ→A4 の禁止パターン
    println("cons3n=" + st.cons3n.toString().take(600))
}
