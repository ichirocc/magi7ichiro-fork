// [MAGI-003] ホストで JNI の .so を作り、実行時の照合（合計＋21 族）を本物の盤面で確かめる道具。使い方: g++ -shared -fPIC で magi_native.cpp を libmagi_native.so にし、-Djava.library.path で読み込む。
package probe.np

import com.magi.app.model.StateParser
import com.magi.app.v6.*
import java.io.File
import kotlin.random.Random

fun main(args: Array<String>) {
    println("native available=${NativeBridge.available}")
    var all = 0; var ok = 0
    for (m in ObjectiveMethod.entries) {
        ObjectiveWeights.method = m
        for (f in args.drop(1)) {
            val st = StateParser.parse(File(args[0], f).readText())!!
            val p = Problem(st)
            val rng = Random(3)
            val b = st.schedule.map { it.toIntArray() }.toTypedArray()
            for (step in 0 until 5) {
                val r = NativeEval.parityCheck(p, b)!!
                all++; if (r.match) ok++ else println("MISMATCH $m $f step=$step ${r.familyDiffs}")
                repeat(60) { val i = rng.nextInt(p.S); val j = rng.nextInt(p.T); b[i][j] = rng.nextInt(p.K) }
            }
        }
    }
    println("parity match $ok/$all")
}
