package dev.codeservergo;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

/**
 * Sends editor downloads (exported files, generated artefacts) to /sdcard/Download/CodeServerGo.
 *
 * <p>DownloadManager is used instead of streaming in-process so the transfer survives a DeX
 * window being closed and shows a proper system notification when it finishes.
 */
final class Downloads {

    private static final String FOLDER = "CodeServerGo";

    private Downloads() {
    }

    static void start(final Context c, String url, String userAgent, String contentDisposition,
                      String mimeType, Server server) {
        if (url == null) return;

        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(c, "This download is generated in the page; open it with "
                    + "\u201cOpen in browser\u201d instead.", Toast.LENGTH_LONG).show();
            return;
        }

        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        if (name == null || name.trim().isEmpty()) name = "download.bin";
        name = name.replace('/', '_').replace('\\', '_');

        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) req.addRequestHeader("Cookie", cookies);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            if (mimeType != null) req.setMimeType(mimeType);
            req.setTitle(name);
            if (server != null) req.setDescription(server.name);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    FOLDER + "/" + name);
            req.allowScanningByMediaScanner();

            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("no DownloadManager");
            dm.enqueue(req);

            final String shown = FOLDER + "/" + name;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(c, "Downloading \u2192 Download/" + shown,
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(c, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
