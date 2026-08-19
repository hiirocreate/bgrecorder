package com.hono.bgrecorder

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * ログを見る手段（PC・adb）が無い状態でも、クラッシュの原因をユーザー自身が
 * コピーして送れるようにするための、超簡易クラッシュロガー。
 *
 * アプリのどこかで例外が拾われずに落ちたとき、その内容をアプリ内部ストレージに保存しておき、
 * 次回起動時にMainActivityがそれを読み込んでダイアログで表示する（コピー可能）。
 */
object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                File(appContext.filesDir, FILE_NAME).writeText(sw.toString())
            } catch (e: Exception) {
                // ログの保存に失敗しても、クラッシュ処理自体は継続させる
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 前回のクラッシュログがあれば読み込んで返す（読んだら消す＝1回だけ表示） */
    fun readAndClear(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            null
        }
        file.delete()
        return text
    }
}
