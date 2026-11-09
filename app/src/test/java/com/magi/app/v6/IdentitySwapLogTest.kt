package com.magi.app.v6

import com.magi.app.model.StateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** [MAGI-021/022] 最終結果の「入力に無かった必須違反」の診断。採否は変えず、件数で見えない入れ替わりをログに出す。 */
class IdentitySwapLogTest {
    private val st = StateParser.parse(javaClass.classLoader!!.getResource("sample_state_v6.json")!!.readText())!!

    @Test fun noLogWhenNothingNewAppears() {
        val b = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r = UnifiedViolationChecker.check(st, b)
        assertTrue(V6FinalPort.identitySwapLog(st, r, r).isEmpty())
    }

    @Test fun countsTheNewHardViolationsByIdentity() {
        val p = Problem(st)
        val b0 = st.schedule.map { it.toIntArray() }.toTypedArray()
        val r0 = UnifiedViolationChecker.check(st, b0)
        val b1 = b0.copy2D(); val rng = Random(5)
        repeat(40) { b1[rng.nextInt(p.S)][rng.nextInt(p.T)] = rng.nextInt(p.K) }
        val r1 = UnifiedViolationChecker.check(st, b1)
        val born = ViolationIdentity.newHard(r0, r1)
        assertTrue("この乱しで必須が新しく生まれる前提", born.isNotEmpty())
        val log = V6FinalPort.identitySwapLog(st, r0, r1)
        assertEquals(1, log.size)
        assertTrue(log[0].message, log[0].message.startsWith("入力に無かった必須違反 ${born.size}件"))
        assertEquals("W", log[0].level)
    }
}
