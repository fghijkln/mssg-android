package com.mssg.app;

import android.app.Activity;
import android.content.ContentValues;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Environment;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import com.chaquo.python.Python;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    /** 供 Python（Chaquopy）回调拿 WebView 通道 */
    public static ApiBridge instance;

    private final Activity activity;
    private final WebView wv;
    private final String siteDir;

    // WebView 网络通道：reqId -> 数据
    private final java.util.Map<String, byte[]> cfBodies = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.concurrent.CountDownLatch> cfLatches =
            new java.util.HashMap<>();
    private final java.util.Map<String, String> cfResults = new java.util.HashMap<>();

    // 应用内更新：下载任务 id（-1 表示空闲）
    private long updateDownloadId = -1;
    private BroadcastReceiver updateReceiver;

    // 相册选图
    private static final int REQ_PICK_IMAGE = 1001;
    private String pendingImageRel;

    public ApiBridge(Activity activity, WebView wv, String siteDir) {
        this.activity = activity;
        this.wv = wv;
        this.siteDir = siteDir;
        instance = this;
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
    public String cfStatus() {
        try {
            return api().callAttr("cf_status", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfConnect(String token) {
        try {
            return api().callAttr("cf_connect", siteDir, token).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfSetAccount(String accountId) {
        try {
            return api().callAttr("cf_set_account", siteDir, accountId).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfSetProject(String name) {
        try {
            return api().callAttr("cf_set_project", siteDir, name).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfProjects() {
        try {
            return api().callAttr("cf_projects", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfDeploy() {
        try {
            return api().callAttr("cf_deploy", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cfDisconnect() {
        try {
            return api().callAttr("cf_disconnect", siteDir).toString();
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
    public String backupSite() {
        try {
            return api().callAttr("export_source_backup", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String cleanBuild() {
        try {
            return api().callAttr("clean_build", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String getBuildInfo() {
        try {
            return api().callAttr("get_build_info", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public void previewSite() {
        Intent intent = new Intent(activity, PreviewActivity.class);
        intent.putExtra("siteDir", siteDir);
        activity.startActivity(intent);
    }

    @JavascriptInterface
    public String listBuildFiles() {
        try {
            return api().callAttr("list_build_files", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String deleteBuildFile(String rel) {
        try {
            return api().callAttr("delete_build_file", siteDir, rel).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public void previewBuildFile(String rel) {
        Intent intent = new Intent(activity, PreviewActivity.class);
        intent.putExtra("siteDir", siteDir);
        intent.putExtra("startPath", "/" + rel);
        activity.startActivity(intent);
    }

    @JavascriptInterface
    public String getAppVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /**
     * 通用异步文本抓取：网络在普通后台线程跑，不占 JS 桥线程；
     * 结果通过 onFetchText(tag, jsonStr) 回调到页面。
     * jsonStr 形如 {"status":200,"body":"..."} 或 {"error":"..."}。
     */
    @JavascriptInterface
    public void fetchText(String tag, String url) {
        new Thread(() -> {
            String raw;
            try {
                raw = cfFetchSync("GET", url, "{\"Accept\":\"*/*\"}", "", 15);
            } catch (Exception e) {
                raw = "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}";
            }
            final String result = raw;
            activity.runOnUiThread(() -> {
                try {
                    wv.evaluateJavascript("onFetchText(" + JSONObject.quote(tag) + ","
                            + JSONObject.quote(result) + ")", null);
                } catch (Exception ignored) {}
            });
        }).start();
    }

    private void toast(final String msg) {
        activity.runOnUiThread(() ->
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
    }

    /**
     * 应用内更新：DownloadManager 下载 APK，完成后自动调起系统安装。
     * Android 8+ 需要用户先在设置里允许本应用“安装未知应用”。
     */
    @JavascriptInterface
    public void downloadUpdate(String url) {
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception ignored) {}
            toast("请允许“安装未知应用”，然后再点下载更新");
            return;
        }
        if (updateDownloadId != -1) {
            toast("正在下载更新，请稍候…");
            return;
        }
        try {
            DownloadManager dm = (DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("mssg 更新下载");
            req.setDescription("正在下载新版本…");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, "mssg-update.apk");
            req.setMimeType("application/vnd.android.package-archive");
            updateDownloadId = dm.enqueue(req);
            if (updateReceiver == null) {
                updateReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        long id = intent.getLongExtra(
                                DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                        if (id == updateDownloadId) {
                            updateDownloadId = -1;
                            installUpdate(dm, id);
                        }
                    }
                };
                activity.registerReceiver(updateReceiver, new IntentFilter(
                        DownloadManager.ACTION_DOWNLOAD_COMPLETE));
            }
            toast("开始下载更新，完成后自动调起安装");
        } catch (Exception e) {
            updateDownloadId = -1;
            toast("下载失败：" + e.getMessage());
        }
    }

    private void installUpdate(DownloadManager dm, long id) {
        try {
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                toast("安装失败：找不到下载的文件");
                return;
            }
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Exception e) {
            toast("调起安装失败：" + e.getMessage());
        }
    }

    /**
     * 从相册选一张图片，导入到文章的 page bundle 目录，并在光标处插入引用。
     * 选择结果经 MainActivity.onActivityResult 回到 onImagePicked。
     */
    @JavascriptInterface
    public void pickImage(String rel) {
        if (rel == null || rel.trim().isEmpty()) {
            toast("请先填写文件名，再插入图片");
            return;
        }
        pendingImageRel = rel.trim();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            activity.startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Exception e) {
            toast("打不开相册：" + e.getMessage());
        }
    }

    /** 供 MainActivity.onActivityResult 调用；返回 true 表示已处理。 */
    public boolean onImagePicked(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_IMAGE) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        final String rel = pendingImageRel;
        new Thread(() -> {
            File tmp = null;
            try {
                String ext = ".jpg";
                try {
                    String mime = activity.getContentResolver().getType(uri);
                    if ("image/png".equals(mime)) ext = ".png";
                    else if ("image/webp".equals(mime)) ext = ".webp";
                    else if ("image/gif".equals(mime)) ext = ".gif";
                } catch (Exception ignored) {}
                tmp = File.createTempFile("pick", ext, activity.getCacheDir());
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String r = api().callAttr("import_bundle_image",
                        siteDir, tmp.getAbsolutePath(), rel).toString();
                JSONObject o = new JSONObject(r);
                if (o.optBoolean("ok", false)) {
                    final String js = "onImagePicked(" +
                            JSONObject.quote(o.optString("name", "")) + ")";
                    activity.runOnUiThread(() -> {
                        try { wv.evaluateJavascript(js, null); }
                        catch (Exception ignored) {}
                    });
                } else {
                    toast(o.optString("msg", "图片处理失败"));
                }
            } catch (Exception e) {
                toast("图片处理失败：" + e.getMessage());
            } finally {
                if (tmp != null) tmp.delete();
            }
        }).start();
        return true;
    }

    @JavascriptInterface
    public void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String exportPageHtml(String rel) {
        try {
            return api().callAttr("export_page_html", siteDir, rel).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String getCustomCss() {
        try {
            return api().callAttr("get_custom_css", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String saveCustomCss(String css) {
        try {
            return api().callAttr("save_custom_css", siteDir, css).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String clearCustomCss() {
        try {
            return api().callAttr("clear_custom_css", siteDir).toString();
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

    // ---------- WebView 网络通道（供 Python 备用传输） ----------

    /**
     * 经 WebView（Chromium 网络栈）发一次 HTTPS 请求，供 Python 的
     * cloudflare 备用传输调用。运行在 Chaquopy 后台线程。
     * 返回 JSON：{"status":200,"body":"..."} 或 {"error":"..."}。
     * JS 也可以直接调（检查更新用）。
     */
    @JavascriptInterface
    public String cfFetchSync(String method, String url, String headersJson,
                              String bodyB64, int timeoutSec) {
        final String reqId = java.util.UUID.randomUUID().toString();
        byte[] body = (bodyB64 == null || bodyB64.isEmpty())
                ? new byte[0] : Base64.decode(bodyB64, Base64.DEFAULT);
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        synchronized (cfLatches) {
            cfBodies.put(reqId, body);
            cfLatches.put(reqId, latch);
        }
        try {
            String js = "(async()=>{"
                    + "var reqId=" + JSONObject.quote(reqId) + ";"
                    + "try{"
                    + "var n=window.MssgApi.cfBodyChunks(reqId);"
                    + "var parts=[];"
                    + "for(var i=0;i<n;i++){"
                    + "var b=window.MssgApi.cfBodyChunk(reqId,i);"
                    + "var bin=atob(b);var u8=new Uint8Array(bin.length);"
                    + "for(var j=0;j<bin.length;j++)u8[j]=bin.charCodeAt(j);"
                    + "parts.push(u8);}"
                    + "var headers=JSON.parse(" + JSONObject.quote(headersJson) + ");"
                    + "var resp=await fetch(" + JSONObject.quote(url) + ",{"
                    + "method:" + JSONObject.quote(method) + ","
                    + "headers:headers,"
                    + (body.length > 0 ? "body:new Blob(parts)," : "")
                    + "});"
                    + "var text=await resp.text();"
                    + "window.MssgApi.cfFetchResult(reqId,"
                    + "JSON.stringify({status:resp.status,body:text}));"
                    + "}catch(e){window.MssgApi.cfFetchResult(reqId,"
                    + "JSON.stringify({error:String((e&&e.stack)||e)}));}"
                    + "})();";
            new Handler(Looper.getMainLooper()).post(() -> wv.evaluateJavascript(js, null));
            boolean done = latch.await(timeoutSec + 15,
                    java.util.concurrent.TimeUnit.SECONDS);
            synchronized (cfLatches) {
                String r = cfResults.remove(reqId);
                if (!done) return "{\"error\":\"timeout\"}";
                return r != null ? r : "{\"error\":\"no result\"}";
            }
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(e.toString()) + "}";
        } finally {
            synchronized (cfLatches) {
                cfBodies.remove(reqId);
                cfLatches.remove(reqId);
            }
        }
    }

    @JavascriptInterface
    public void cfFetchResult(String reqId, String json) {
        synchronized (cfLatches) {
            cfResults.put(reqId, json);
            java.util.concurrent.CountDownLatch l = cfLatches.get(reqId);
            if (l != null) l.countDown();
        }
    }

    @JavascriptInterface
    public int cfBodyChunks(String reqId) {
        byte[] b;
        synchronized (cfLatches) { b = cfBodies.get(reqId); }
        if (b == null) return 0;
        return (b.length + 65535) / 65536;
    }

    @JavascriptInterface
    public String cfBodyChunk(String reqId, int i) {
        byte[] b;
        synchronized (cfLatches) { b = cfBodies.get(reqId); }
        if (b == null) return "";
        int s = i * 65536;
        int e = Math.min(s + 65536, b.length);
        if (s >= b.length) return "";
        return Base64.encodeToString(b, s, e - s, Base64.NO_WRAP);
    }
}
