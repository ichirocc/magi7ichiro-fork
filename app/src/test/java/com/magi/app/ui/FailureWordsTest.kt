package com.magi.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** [3.643.0] 失敗の一文は場面ごとに利用者の言葉で、例外のクラス名（英字）を含まない。 */
class FailureWordsTest {
    @Test fun eachBranchHasItsOwnWords() {
        assertEquals("書き出す内容がありませんでした", failureWords(null, FailureKind.SAVE))
        assertEquals("ファイルの中身を読めませんでした", failureWords(null, FailureKind.LOAD))
        assertEquals("メモリが足りませんでした", failureWords(OutOfMemoryError(), FailureKind.ENGINE))
        assertEquals("重大なエラー", failureWords(StackOverflowError(), FailureKind.ENGINE))
        assertEquals("アクセスが許可されていません", failureWords(SecurityException("denied"), FailureKind.LOAD))
        assertEquals("ファイルが見つからないか、アクセスが許可されていません", failureWords(java.io.FileNotFoundException("x"), FailureKind.LOAD))
        assertEquals("保存先の空き容量が足りません", failureWords(java.io.IOException("No space left on device"), FailureKind.SAVE))
        assertEquals("書き込みに失敗しました", failureWords(java.io.IOException("x"), FailureKind.SAVE))
        assertEquals("読み込みに失敗しました", failureWords(java.io.IOException("x"), FailureKind.LOAD))
        assertEquals("ファイルの形式が違うか、壊れています", failureWords(IllegalArgumentException("bad json"), FailureKind.LOAD))
        assertEquals("内部エラー", failureWords(IllegalStateException("x"), FailureKind.ENGINE))
    }

    @Test fun noClassNameOrAsciiLeaksToTheScreen() {
        val samples = listOf<Throwable?>(null, RuntimeException("NullPointerException at Foo.kt:12"), NullPointerException(), IllegalStateException(),
            java.io.IOException("Stream closed"), java.io.FileNotFoundException("/a/b.json"), SecurityException(), OutOfMemoryError(), StackOverflowError(),
            NumberFormatException("For input string: \"x\""), IndexOutOfBoundsException(), kotlinx.coroutines.CancellationException("cancel"))
        for (e in samples) for (k in FailureKind.values()) {
            val w = failureWords(e, k)
            assertFalse("英字を画面に出さない: ${e?.javaClass?.simpleName} $k → $w", Regex("[A-Za-z]").containsMatchIn(w))
        }
    }
}
