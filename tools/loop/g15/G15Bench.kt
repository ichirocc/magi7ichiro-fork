package probe
import com.magi.app.model.StateParser
import com.magi.app.v6.V6FinalPort
import kotlinx.coroutines.runBlocking
import java.io.File

// One handleOptimize run: args = fixturePath seed seconds workers; prints elapsedMs,hard,weighted,total,hash
fun main(args: Array<String>) {
    val st = StateParser.parse(File(args[0]).readText())!!
    val t0 = System.currentTimeMillis()
    val res = runBlocking {
        V6FinalPort.handleOptimize(st, st.schedule.map { it.toIntArray() }.toTypedArray(), secondsRaw = args[2].toInt(),
            workers = args[3].toInt(), allowImpossible = true, seed = 1000L + args[1].toLong())
    }
    val el = System.currentTimeMillis() - t0
    println("RESULT,$el,${res.report.hard},${res.report.weightedScore},${res.report.total},${res.schedule.contentDeepHashCode().toUInt().toString(16)}")
}
