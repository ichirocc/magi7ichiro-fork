package com.magi.app.ui

/** 失敗の場面。LOAD＝ファイルの読込・取込、SAVE＝書き出し、ENGINE＝探索・チェック・下書き。 */
internal enum class FailureKind { LOAD, SAVE, ENGINE }

/** 失敗を利用者の言葉へ（3.147.0/3.191.0/3.400.0 の方針: 例外のクラス名と生の例外文を画面に出さない）。詳しい原因は呼び出し側が
 *  logOp へ残す。純関数＝ホストでテストする（Windows は `FailureWords.cs`）。 */
internal fun failureWords(e: Throwable?, kind: FailureKind): String = when {
    e == null -> when (kind) { FailureKind.SAVE -> "書き出す内容がありませんでした"; FailureKind.LOAD -> "ファイルの中身を読めませんでした"; FailureKind.ENGINE -> "原因不明" }
    e is OutOfMemoryError -> "メモリが足りませんでした"
    e is Error -> "重大なエラー"
    e is SecurityException -> "アクセスが許可されていません"
    e is java.io.FileNotFoundException -> "ファイルが見つからないか、アクセスが許可されていません"
    e.message?.contains("space", ignoreCase = true) == true -> "保存先の空き容量が足りません"
    e is java.io.IOException -> when (kind) { FailureKind.SAVE -> "書き込みに失敗しました"; FailureKind.LOAD -> "読み込みに失敗しました"; FailureKind.ENGINE -> "読み書きに失敗しました" }
    else -> when (kind) { FailureKind.SAVE -> "書き込みに失敗しました"; FailureKind.LOAD -> "ファイルの形式が違うか、壊れています"; FailureKind.ENGINE -> "内部エラー" }
}
