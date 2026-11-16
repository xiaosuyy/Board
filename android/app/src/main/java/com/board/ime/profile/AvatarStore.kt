package com.board.ime.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.board.ime.settings.BoardPreferences
import java.io.File
import java.io.FileOutputStream

/**
 * 本机头像。
 *
 * 只保存一张图：`files/profile/avatar.jpg`（正方形、最长边 [TARGET_PX]），
 * 更新时间写在 `BoardPreferences.avatarUpdatedAt`，WebDAV 同步按这个时间做「后写胜出」。
 * 图片本身不存数据库，也不缓存到内存之外的地方。
 */
object AvatarStore {

    /** 头像更新时间（SharedPreferences key）。值变化时工具栏据此刷新。 */
    const val KEY_AVATAR_UPDATED_AT = "avatar_updated_at"

    private const val DIR = "profile"
    private const val FILE_NAME = "avatar.jpg"
    private const val TARGET_PX = 256
    private const val JPEG_QUALITY = 88

    @Volatile
    private var cached: Bitmap? = null

    @Volatile
    private var cachedStamp = -1L

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    fun updatedAt(context: Context): Long = BoardPreferences(context).avatarUpdatedAt

    fun hasAvatar(context: Context): Boolean = updatedAt(context) > 0L && file(context).isFile

    /** 读头像；没有则返回 null。文件未变化时直接命中内存缓存。 */
    fun loadBitmap(context: Context): Bitmap? {
        val f = file(context)
        if (!f.isFile) {
            cached = null
            cachedStamp = -1L
            return null
        }
        val stamp = updatedAt(context).takeIf { it > 0L } ?: f.lastModified()
        cached?.takeIf { stamp == cachedStamp }?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return null
        cached = bitmap
        cachedStamp = stamp
        return bitmap
    }

    /** 从相册选择结果写头像；返回是否成功。会做居中方形裁剪并压成 [TARGET_PX]。 */
    fun saveFromUri(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds 的解码按设计返回 null，不能用返回值判断是否成功
        val boundsStream = resolver.openInputStream(uri) ?: return false
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return false
        write(context, centerSquare(decoded, TARGET_PX), System.currentTimeMillis())
    }.getOrDefault(false)

    /** 同步下来时使用：按远端时间戳落盘，不覆盖更新时间。 */
    fun saveFromBase64(context: Context, base64: String, updatedAt: Long): Boolean = runCatching {
        if (base64.isBlank()) return false
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.isEmpty()) return false
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        write(context, centerSquare(decoded, TARGET_PX), updatedAt)
    }.getOrDefault(false)

    /** 上传用：整张 JPEG 的 base64（无换行）。 */
    fun encodeBase64(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return null
        Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    fun clear(context: Context) {
        file(context).delete()
        // 更新时间必须保留（= 现在）：云端据此知道「头像被删除」，否则旧头像会在下次同步时回来
        BoardPreferences(context).avatarUpdatedAt = System.currentTimeMillis()
        invalidate()
    }

    private fun write(context: Context, bitmap: Bitmap, updatedAt: Long): Boolean {
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        // 先写临时文件再改名：同步 / 选择过程中被读取时不会看到半张图
        FileOutputStream(tmp).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
        }
        if (target.exists() && !target.delete()) return false
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        BoardPreferences(context).avatarUpdatedAt = updatedAt
        invalidate()
        return target.isFile && target.length() > 0L
    }

    private fun invalidate() {
        cached = null
        cachedStamp = -1L
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun centerSquare(source: Bitmap, target: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        return if (side == target) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, target, target, true)
        }
    }
}
