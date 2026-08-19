package com.hono.bgrecorder

import android.content.Context
import java.io.File

/**
 * 録画ファイルの保存先。
 *
 * あえて内部ストレージ（filesDir配下）を使う。ここは自分のアプリだけがアクセスできる領域で、
 * Gallery・ファイル管理アプリ・他のどのアプリからもroot無しでは絶対に見えない
 * （MediaStoreにも登録しないので「アルバム」等の一覧にも一切出てこない）。
 * 唯一の公開経路は RecordingsProvider（signature権限必須）だけになる。
 */
object RecordingsStorage {
    private const val DIR_NAME = "recordings"

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }
}
