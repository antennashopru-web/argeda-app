package ru.argeda.cabinet;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Сохранение файлов из кабинета в «Загрузки»: стикеры PDF, листы подбора, прайс Excel, отчёты.
 * Файлы с сервера качает DownloadManager с cookie кабинета (иначе сервер попросит войти заново).
 * Файлы, собранные прямо в браузере (blob:), приходят из JavaScript как base64.
 */
final class Downloads {
    private Downloads() {
    }

    static void fromUrl(Context c, String url, String userAgent, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .setTitle(name)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) r.addRequestHeader("Cookie", cookie);
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        if (dm == null) return;
        dm.enqueue(r);
        Toast.makeText(c, R.string.download_started, Toast.LENGTH_SHORT).show();
    }

    /** JavaScript, который скачивает blob: и отдаёт его в приложение через ArgedaSeller.saveBlob. */
    static String blobScript(String blobUrl) {
        return "(function(){fetch('" + blobUrl + "').then(function(r){return r.blob()}).then(function(b){"
                + "var fr=new FileReader();fr.onload=function(){"
                + "ArgedaSeller.saveBlob(fr.result.split(',')[1], b.type||'application/octet-stream', document.title||'');"
                + "};fr.readAsDataURL(b)}).catch(function(e){ArgedaSeller.saveBlob('','',String(e))})})()";
    }

    /** Сохраняет base64 в «Загрузки» через MediaStore: разрешение на память не нужно (Android 10+). */
    static boolean saveBase64(Context c, String base64, String mime, String hint) {
        if (base64 == null || base64.isEmpty()) return false;
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null) ext = mime != null && mime.contains("pdf") ? "pdf" : "bin";
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(new Date());
        String base = hint == null ? "" : hint.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (base.isEmpty()) base = "argeda";
        if (base.length() > 40) base = base.substring(0, 40);
        String name = base + "_" + stamp + "." + ext;

        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return false;
        try (OutputStream out = c.getContentResolver().openOutputStream(uri)) {
            if (out == null) return false;
            out.write(Base64.decode(base64, Base64.DEFAULT));
            return true;
        } catch (IOException | IllegalArgumentException e) {
            c.getContentResolver().delete(uri, null, null);
            return false;
        }
    }
}
