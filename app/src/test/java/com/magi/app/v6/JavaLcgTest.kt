package com.magi.app.v6

import org.junit.Assert.assertEquals
import org.junit.Test

/** [JavaLcg] が `java.util.Random` と同じ列を出すこと（膜の細胞 LNS の C++ 経路 `JavaLcgN` と乱数を受け渡す前提）。 */
class JavaLcgTest {
    @Test fun sameSequenceAsJavaUtilRandom() {
        for (seed in listOf(0L, 1L, 42L, -7L, Long.MAX_VALUE, 0x5DEECE66DL)) {
            val ref = java.util.Random(seed)
            val lcg = JavaLcg(seed)
            val bounds = intArrayOf(1, 2, 3, 4, 7, 10, 31, 64, 100, 1000, Int.MAX_VALUE, 1 shl 30, (1 shl 30) + 1)
            repeat(20_000) { n ->
                if (n % 3 == 2) assertEquals(ref.nextDouble(), lcg.nextDouble(), 0.0)
                else { val b = bounds[n % bounds.size]; assertEquals("seed=$seed n=$n b=$b", ref.nextInt(b), lcg.nextInt(b)) }
            }
        }
    }

    @Test fun stateRoundTripContinuesTheSequence() {
        val a = JavaLcg(9L); repeat(100) { a.nextInt(17) }
        val b = JavaLcg(0L); b.state = a.state
        repeat(100) { assertEquals(a.nextInt(1000), b.nextInt(1000)) }
    }

    /** C++ `JavaLcgN` の照合用の既知の値（`host_parity_bench` の runJavaLcgTest と同じ列）。 */
    @Test fun knownValuesForTheNativePort() {
        val r = java.util.Random(12345L)
        assertEquals(listOf(51, 80, 41, 28, 55), List(5) { r.nextInt(100) })
        assertEquals(listOf(0, 1, 0, 0, 0), List(5) { r.nextInt(4) })
        assertEquals(4599960723077926490L, java.lang.Double.doubleToRawLongBits(r.nextDouble()))
        assertEquals(962239390, r.nextInt(1000000007))
    }
}
