package com.hono.bgrecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 他アプリ（BGViewer）に録画データを見せるためのProvider。
 *
 * Manifestで readPermission / writePermission に signature 権限を指定しているため、
 * BGRecorderと同じ鍵で署名されたアプリ以外は絶対にこのProviderへアクセスできない
 * （OSレベルで自動的に拒否される。ダイアログも出ない＝勝手に権限を得る余地がない）。
 */
object RecordingsContract {
    const val AUTHORITY = "com.hono.bgrecorder.provider"
    val BASE_URI: Uri = Uri.parse("content://$AUTHORITY/recordings")

    const val COL_ID = "_id"
    const val COL_DISPLAY_NAME = "display_name"
    const val COL_SIZE = "size_bytes"
    const val COL_DATE_ADDED = "date_added"
    const val COL_DURATION_MS = "duration_ms"

    fun uriFor(fileName: String): Uri = BASE_URI.buildUpon().appendPath(fileName).build()
}

class RecordingsProvider : ContentProvider() {

    companion object {
        private const val CODE_LIST = 1
        private const val CODE_ITEM = 2
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(RecordingsContract.AUTHORITY, "recordings", CODE_LIST)
        addURI(RecordingsContract.AUTHORITY, "recordings/*", CODE_ITEM)
    }

    override fun onCreate(): Boolean = true

    private fun dir(): File = RecordingsStorage.dir(context!!)

    private fun fileForUri(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        val base = dir()
        val f = File(base, name)
        // パストラバーサル対策：解決後のパスが録画フォルダの直下であることを確認する
        return if (f.parentFile?.canonicalPath == base.canonicalPath && f.exists() && f.isFile) f else null
    }

    private fun getDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // 無視
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val columns = arrayOf(
            RecordingsContract.COL_ID,
            RecordingsContract.COL_DISPLAY_NAME,
            RecordingsContract.COL_SIZE,
            RecordingsContract.COL_DATE_ADDED,
            RecordingsContract.COL_DURATION_MS,
        )
        val cursor = MatrixCursor(columns)

        val files: List<File> = when (matcher.match(uri)) {
            CODE_LIST -> dir().listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            CODE_ITEM -> fileForUri(uri)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }

        files.forEachIndexed { index, file ->
            cursor.addRow(
                arrayOf(
                    index.toLong(),
                    file.name,
                    file.length(),
                    file.lastModified(),
                    getDurationMs(file),
                )
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "video/mp4"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // 録画の追加はRecordingServiceのみが行う。外部からの新規作成は許可しない。
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return when (matcher.match(uri)) {
            CODE_ITEM -> {
                val file = fileForUri(uri) ?: return 0
                if (file.delete()) 1 else 0
            }
            else -> 0
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        // 更新は不要（録画ファイルはイミュータブル）
        return 0
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileForUri(uri) ?: throw FileNotFoundException("recording not found: $uri")
        // 呼び出し元の指定モードに関わらず、常に読み取り専用で開く（録画データの改変を防ぐため）
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
