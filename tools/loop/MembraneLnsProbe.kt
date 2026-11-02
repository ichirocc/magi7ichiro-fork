// [2026-11-02] 細胞 LNS（MembraneCellLns）を頭打ち盤面に当てて、正式評価の前後を書く（M1 の前段の測定）。
package probe.membranelns

import com.magi.app.model.StateParser
import com.magi.app.v6.MembraneCellLns
import com.magi.app.v6.UnifiedViolationChecker
import java.io.File

fun main(args: Array<String>) {
    val ms = args[1].toLong(); val size = args.getOrNull(2)?.toInt() ?: 5
    val iters = args.getOrNull(3)?.toInt() ?: 60_000; val t0 = args.getOrNull(4)?.toDouble() ?: 40.0
    for (f in args[0].split(',')) {
        val st = StateParser.parse(File(f).readText())!!
        val b0 = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r0 = UnifiedViolationChecker.check(st, b0)
        val r = MembraneCellLns.apply(st, b0, MembraneCellLns.Params(maxMillis = ms, cellStaff = size, maxCells = 10_000, itersPerCell = iters, tempStart = t0))
        val r1 = UnifiedViolationChecker.check(st, r.newSchedule)
        r.logs.forEach { System.err.println(it.message) }
        println("${File(f).nameWithoutExtension} 必須 ${r0.hard}->${r1.hard} 合計 ${r0.total}->${r1.total} 重み ${r0.weightedScore.toLong()}->${r1.weightedScore.toLong()} 採用 ${r.applied} 細胞 ${r.cellsTried}")
    }
}
