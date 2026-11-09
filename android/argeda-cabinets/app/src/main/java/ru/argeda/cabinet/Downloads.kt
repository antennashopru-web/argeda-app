package ru.argeda.cabinet

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохранение файлов из кабинета в «Загрузки»: стикеры PDF, листы подбора, прайс Excel, отчёты.
 * Файлы с сервера качает DownloadManager с cookie кабинета (иначе сервер попросит войти заново).
 * Файлы, собранные прямо в браузере (blob:), приходят из JavaScript как base64.
 */
internal object Downloads {

    fun fromUrl(c: Context, url: String, userAgent: String?, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val r = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .setTitle(name)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val cookie = CookieManager.getInstance().getCookie(url)
        if (cookie != null) r.addRequestHeader("Cookie", cookie)
        val dm = c.getSystemService(DownloadManager::class.java) ?: return
        dm.enqueue(r)
        Toast.makeText(c, R.string.download_started, Toast.LENGTH_SHORT).show()
    }

    /** JavaScript, который скачивает blob: и отдаёт его в приложение через ArgedaSeller.saveBlob. */
    fun blobScript(blobUrl: String): String =
        "(function(){fetch('" + blobUrl + "').then(function(r){return r.blob()}).then(function(b){" +
            "var fr=new FileReader();fr.onload=function(){" +
            "ArgedaSeller.saveBlob(fr.result.split(',')[1], b.type||'application/octet-stream', document.title||'');" +
            "};fr.readAsDataURL(b)}).catch(function(e){ArgedaSeller.saveBlob('','',String(e))})})()"

    /** Сохраняет base64 в «Загрузки» через MediaStore: разрешение на память не нужно (Android 10+). */
    fun saveBase64(c: Context, base64: String?, mime: String?, hint: String?): Boolean {
        if (base64.isNullOrEmpty()) return false
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (mime != null && mime.contains("pdf")) "pdf" else "bin"
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
        var base = hint?.replace(Regex("[^\\p{L}\\p{N} _-]"), "")?.trim() ?: ""
        if (base.isEmpty()) base = "argeda"
        if (base.length > 40) base = base.substring(0, 40)
        val name = base + "_" + stamp + "." + ext

        val v = ContentValues()
        v.put(MediaStore.Downloads.DISPLAY_NAME, name)
        v.put(MediaStore.Downloads.MIME_TYPE, if (mime.isNullOrEmpty()) "application/octet-stream" else mime)
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        return try {
            val out = c.contentResolver.openOutputStream(uri) ?: return false
            out.use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
            true
        } catch (e: IOException) {
            c.contentResolver.delete(uri, null, null)
            false
        } catch (e: IllegalArgumentException) {
            c.contentResolver.delete(uri, null, null)
            false
        }
    }
}
