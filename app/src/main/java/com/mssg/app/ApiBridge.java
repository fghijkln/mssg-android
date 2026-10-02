package com.mssg.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.chaquo.python.Python;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * WebView JS ↔ Python 的桥：页面（file:// 本地）直接调 mssg 引擎，
 * 不再经过 localhost HTTP 服务。
 *
 * 所有方法都在 WebView 的 JavaBridge 后台线程执行，可直接做耗时操作；
 * 需要 UI 的操作（分享/Toast）切回主线程。
 */
public class ApiBridge {
    private final Activity activity;
    private final String siteDir;

    public ApiBridge(Activity activity, String siteDir) {
        this.activity = activity;
        this.siteDir = siteDir;
    }

    private com.chaquo.python.PyObject api() {
        return Python.getInstance().getModule("mssg_api");
    }

    private String fail(Exception e) {
        return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
    }

    @JavascriptInterface
    public String getSiteDir() {
        return siteDir;
    }

    @JavascriptInterface
    public String listPages() {
        try {
            return api().callAttr("list_pages", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String getPage(String rel) {
        try {
            return api().callAttr("get_page", siteDir, rel).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String savePage(String rel, String title, String date, String tags,
                           String categories, boolean draft, String body) {
        try {
            return api().callAttr("save_page", siteDir, rel, title, date,
                    tags, categories, draft, body).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String deletePage(String rel) {
        try {
            return api().callAttr("delete_page", siteDir, rel).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String buildSite() {
        try {
            return api().callAttr("build_site", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String listThemes() {
        try {
            return api().callAttr("list_themes").toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String getTheme() {
        try {
            return api().callAttr("get_theme", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String setTheme(String theme) {
        try {
            return api().callAttr("set_theme", siteDir, theme).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String exportZip() {
        try {
            return api().callAttr("export_zip", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String exportPageHtml(String rel) {
        try {
            return api().callAttr("export_page_html", siteDir, rel).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * 把导出的文件存到系统"下载"目录。
     * Android 10+ 走 MediaStore，无需任何权限；更早版本存到应用外部目录。
     */
    @JavascriptInterface
    public String saveToDownload(String srcPath, String fileName, String mimeType) {
        try {
            File src = new File(srcPath);
            if (!src.exists()) return "{\"ok\":false,\"msg\":\"文件不存在\"}";
            String where;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
                Uri uri = activity.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "{\"ok\":false,\"msg\":\"无法写入下载目录\"}";
                try (OutputStream out = activity.getContentResolver()
                        .openOutputStream(uri);
                     InputStream in = new FileInputStream(src)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                where = "下载/" + fileName;
            } else {
                File dir = activity.getExternalFilesDir(
                        Environment.DIRECTORY_DOWNLOADS);
                File dst = new File(dir, fileName);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                where = dst.getAbsolutePath();
            }
            return "{\"ok\":true,\"msg\":\"" + esc(where) + "\"}";
        } catch (Exception e) {
            return fail(e);
        }
    }

    /**
     * 调起系统分享（文件先经 MediaStore 落到下载目录，拿到 content URI 分享，
     * 不需要 FileProvider）。
     */
    @JavascriptInterface
    public void shareFile(String srcPath, String fileName, String mimeType) {
        activity.runOnUiThread(() -> {
            try {
                Uri uri;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    v.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                    v.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                    uri = activity.getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new Exception("无法写入下载目录");
                    try (OutputStream out = activity.getContentResolver()
                            .openOutputStream(uri);
                         InputStream in = new FileInputStream(srcPath)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                } else {
                    File dst = new File(activity.getExternalFilesDir(
                            Environment.DIRECTORY_DOWNLOADS), fileName);
                    try (InputStream in = new FileInputStream(srcPath);
                         OutputStream out = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    uri = Uri.fromFile(dst);
                }
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeType);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(intent, "分享文件"));
            } catch (Exception e) {
                Toast.makeText(activity, "分享失败：" + e,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
