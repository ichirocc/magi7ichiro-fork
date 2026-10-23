package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-14（`docs/overhaul_design.md` §6.2）。VM の reportAutoSave・persistNow が適用する `SaveFailNotifier.report` を、
 *  失敗→失敗の継続→やり直し→復旧の順で通す。 */
class SaveRetryWiringTest {
    @Test fun failureRetryAndRecoveryFollowTheSameOutcomeTheViewModelApplies() {
        val n = SaveFailNotifier()

        val ok = n.report(ok = true, retried = false)
        assertEquals(SaveOutcome(null, setDialog = true, notice = null, retryLater = false), ok)

        val first = n.report(ok = false, retried = false)
        assertEquals(SaveOutcome(SaveFailDialog.FIRST, setDialog = true, notice = SaveNotice.FAILED, retryLater = true), first)

        // 1.2 秒ごとの自動保存が失敗し続ける間は、ダイアログも通知も触らず、書き直しだけ仕掛ける。
        val again = n.report(ok = false, retried = false)
        assertEquals(SaveOutcome(null, setDialog = false, notice = null, retryLater = true), again)

        // 利用者の「もう一度保存する」も失敗＝「まだ保存できていません」。通知は重ねない。
        val retryFailed = n.report(ok = false, retried = true)
        assertEquals(SaveFailDialog.AGAIN, retryFailed.dialog)
        assertTrue(retryFailed.setDialog)
        assertNull(retryFailed.notice)

        val recovered = n.report(ok = true, retried = true)
        assertEquals(SaveOutcome(null, setDialog = true, notice = SaveNotice.RECOVERED, retryLater = false), recovered)
        assertTrue(n.lastOk)
    }

    @Test fun successAfterSuccessNeitherNotifiesNorRetries() {
        val n = SaveFailNotifier()
        n.report(ok = true, retried = false)
        val r = n.report(ok = true, retried = false)
        assertNull(r.notice)
        assertFalse(r.retryLater)
    }
}
