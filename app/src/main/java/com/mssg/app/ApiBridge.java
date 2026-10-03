package com.mssg.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
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

    // 相册选图
    private static final int REQ_PICK_IMAGE = 1001;
    private String pendingImageRel;
    // 备份恢复选文件
    private static final int REQ_PICK_BACKUP = 1002;

    // 应用内更新（走 WebView/Chromium 网络栈：系统 DNS 坏了也能下）
    private OutputStream dlOut;
    private Uri dlUri;
    private String dlUrl;
    private long dlReceived, dlTotal;
    private int dlLastPct;
    private boolean dlActive;

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
    public String listInstalledPlugins() {
        try {
            return api().callAttr("list_installed_plugins", siteDir).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String installPluginFile(String name, String content) {
        try {
            return api().callAttr("install_plugin_file", siteDir, name, content).toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String deletePlugin(String name) {
        try {
            return api().callAttr("delete_plugin", siteDir, name).toString();
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

    /* AI 写作助手 */
    @JavascriptInterface
    public String getAiSettings() {
        try {
            android.content.SharedPreferences sp =
                    activity.getSharedPreferences("mssg", android.content.Context.MODE_PRIVATE);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("endpoint", sp.getString("ai_endpoint", ""));
            o.put("key", sp.getString("ai_key", ""));
            o.put("model", sp.getString("ai_model", ""));
            return o.toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String setAiSettings(String endpoint, String key, String model) {
        try {
            android.content.SharedPreferences sp =
                    activity.getSharedPreferences("mssg", android.content.Context.MODE_PRIVATE);
            sp.edit()
                    .putString("ai_endpoint", endpoint == null ? "" : endpoint.trim())
                    .putString("ai_key", key == null ? "" : key.trim())
                    .putString("ai_model", model == null ? "" : model.trim())
                    .apply();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fail(e);
        }
    }

    /** AI 接口 POST（OpenAI 兼容），经 WebView Chromium 网络栈，走 onFetchText(tag) 回调。 */
    @JavascriptInterface
    public void fetchPostText(String tag, String url, String headersJson, String bodyJson) {
        new Thread(() -> {
            String raw;
            try {
                String bodyB64 = Base64.encodeToString(
                        bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
                raw = cfFetchSync("POST", url, headersJson, bodyB64, 90);
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

    private java.io.File aiPluginDir() {
        return new java.io.File(activity.getFilesDir(), "ai_plugins");
    }

    private boolean validAiName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
    }

    @JavascriptInterface
    public String listAiPlugins() {
        try {
            JSONArray arr = new JSONArray();
            java.io.File dir = aiPluginDir();
            if (dir.isDirectory()) {
                java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
                if (files != null) {
                    for (java.io.File f : files) {
                        try {
                            String c = new String(
                                    java.nio.file.Files.readAllBytes(f.toPath()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            JSONObject j = new JSONObject(c);
                            JSONObject o = new JSONObject();
                            o.put("name", j.optString("name",
                                    f.getName().replace(".json", "")));
                            o.put("description", j.optString("description", ""));
                            o.put("version", j.optString("version", ""));
                            JSONArray acts = j.optJSONArray("actions");
                            o.put("actionCount", acts != null ? acts.length() : 0);
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                }
            }
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("plugins", arr);
            return r.toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String getAiPlugin(String name) {
        try {
            if (!validAiName(name)) return fail(new Exception("非法插件名"));
            java.io.File f = new java.io.File(aiPluginDir(), name + ".json");
            if (!f.isFile()) return fail(new Exception("插件不存在"));
            String c = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            new JSONObject(c); // 校验
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("content", c);
            return r.toString();
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String installAiPlugin(String name, String content) {
        try {
            if (!validAiName(name)) return fail(new Exception("非法插件名"));
            if (content == null || content.trim().isEmpty())
                return fail(new Exception("插件文件为空"));
            new JSONObject(content); // 校验
            java.io.File dir = aiPluginDir();
            dir.mkdirs();
            java.nio.file.Files.write(
                    new java.io.File(dir, name + ".json").toPath(),
                    content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fail(e);
        }
    }

    @JavascriptInterface
    public String deleteAiPlugin(String name) {
        try {
            if (!validAiName(name)) return fail(new Exception("非法插件名"));
            java.io.File f = new java.io.File(aiPluginDir(), name + ".json");
            if (f.isFile()) f.delete();
            return "{\"ok\":true}";
        } catch (Exception e) {
            return fail(e);
        }
    }

    private Toast lastToast;

    private void toast(final String msg) {
        activity.runOnUiThread(() -> {
            try {
                if (lastToast != null) lastToast.cancel();
            } catch (Exception ignored) {}
            lastToast = Toast.makeText(activity, msg, Toast.LENGTH_LONG);
            lastToast.show();
        });
    }

    /**
     * 应用内更新：用主 WebView（Chromium 网络栈）fetch APK 二进制，
     * base64 分片传回 Java 写文件，完成后调起系统安装。
     * 不走 DownloadManager——后者用系统网络栈，在 DNS 被污染的手机上不可用。
     */
    @JavascriptInterface
    public void downloadUpdate(String url) {
        try {
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
        } catch (Exception e) {
            toast("检查安装权限失败：" + e.getMessage());
            return;
        }
        synchronized (this) {
            if (dlActive) {
                toast("正在下载更新，请稍候…");
                return;
            }
            dlActive = true;
        }
        dlUrl = url;
        activity.runOnUiThread(() -> startWebDownload(url));
    }

    private void startWebDownload(String url) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, "mssg-update.apk");
            v.put(MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.android.package-archive");
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS);
            }
            dlUri = activity.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (dlUri == null) throw new Exception("无法写入下载目录");
            dlOut = activity.getContentResolver().openOutputStream(dlUri);
            if (dlOut == null) throw new Exception("无法打开输出流");
            dlReceived = 0;
            dlTotal = -1;
            dlLastPct = -1;
            toast("开始下载更新…");
            String js = "(async()=>{try{"
                    + "const r=await fetch(" + JSONObject.quote(url) + ");"
                    + "if(!r.ok) throw new Error('HTTP '+r.status);"
                    + "const len=r.headers.get('content-length');"
                    + "if(len) window.MssgApi.dlMeta(parseInt(len));"
                    + "const buf=await r.arrayBuffer();"
                    + "const u8=new Uint8Array(buf);"
                    + "const CH=65536;"
                    + "for(let i=0;i<u8.length;i+=CH){"
                    + "  const sub=u8.subarray(i,Math.min(i+CH,u8.length));"
                    + "  window.MssgApi.dlChunk(btoa(String.fromCharCode.apply(null,sub)));"
                    + "}"
                    + "window.MssgApi.dlDone();"
                    + "}catch(e){window.MssgApi.dlError(String((e&&e.message)||e));}"
                    + "})();";
            wv.evaluateJavascript(js, null);
        } catch (Exception e) {
            finishDownload("下载失败：" + e.getMessage(), true);
        }
    }

    /** 以下 dl* 方法供下载 JS 回调（@JavascriptInterface）。 */
    @JavascriptInterface
    public void dlMeta(long total) {
        dlTotal = total;
    }

    @JavascriptInterface
    public void dlChunk(String b64) {
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            synchronized (this) {
                if (dlOut != null) {
                    dlOut.write(b);
                    dlReceived += b.length;
                } else {
                    return;
                }
            }
            if (dlTotal > 0) {
                int pct = (int) (dlReceived * 100 / dlTotal);
                if (pct >= dlLastPct + 25) {
                    dlLastPct = pct;
                    toast("下载中 " + pct + "%…");
                }
            }
        } catch (Exception e) {
            finishDownload("写入失败：" + e.getMessage(), true);
        }
    }

    @JavascriptInterface
    public void dlDone() {
        finishDownload(null, false);
    }

    @JavascriptInterface
    public void dlError(String err) {
        finishDownload(err, true);
    }

    private void finishDownload(String err, boolean fallbackBrowser) {
        synchronized (this) {
            try {
                if (dlOut != null) dlOut.close();
            } catch (Exception ignored) {}
            dlOut = null;
            dlActive = false;
        }
        final String error = err;
        activity.runOnUiThread(() -> {
            if (error != null) {
                toast(error + (fallbackBrowser ? "，改用浏览器下载" : ""));
                if (fallbackBrowser && dlUrl != null) {
                    try {
                        openUrl(dlUrl);
                    } catch (Exception ignored) {}
                }
                return;
            }
            try {
                if (dlUri == null) {
                    toast("安装失败：找不到文件");
                    return;
                }
                toast("下载完成，正在调起安装…");
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(dlUri,
                        "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(install);
            } catch (Exception e) {
                toast("调起安装失败：" + e.getMessage());
            }
        });
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

    /** 打开系统文件选择器，选站点备份 ZIP。结果经 onBackupPicked 回调。 */
    @JavascriptInterface
    public void pickBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        try {
            activity.startActivityForResult(intent, REQ_PICK_BACKUP);
        } catch (Exception e) {
            toast("打不开文件选择：" + e.getMessage());
        }
    }

    /** 供 MainActivity.onActivityResult 调用；返回 true 表示已处理。 */
    public boolean onBackupPicked(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_BACKUP) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            File tmp = null;
            try {
                tmp = File.createTempFile("backup", ".zip", activity.getCacheDir());
                try (InputStream in = activity.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String r = api().callAttr("inspect_backup",
                        tmp.getAbsolutePath()).toString();
                JSONObject o = new JSONObject(r);
                if (o.optBoolean("ok", false)) {
                    final String js = "onBackupPicked(" +
                            JSONObject.quote(tmp.getAbsolutePath()) + "," +
                            o.optInt("articles", 0) + "," +
                            JSONObject.quote(o.optString("size_human", "")) + ")";
                    activity.runOnUiThread(() -> {
                        try { wv.evaluateJavascript(js, null); }
                        catch (Exception ignored) {}
                    });
                } else {
                    tmp.delete();
                    toast(o.optString("msg", "备份校验失败"));
                }
            } catch (Exception e) {
                if (tmp != null) tmp.delete();
                toast("读取备份失败：" + e.getMessage());
            }
        }).start();
        return true;
    }

    /** 执行恢复（Python 已校验）。返回 JSON。 */
    @JavascriptInterface
    public String restoreBackup(String zipPath) {
        try {
            String r = api().callAttr("restore_backup",
                    siteDir, zipPath).toString();
            new File(zipPath).delete();
            return r;
        } catch (Exception e) {
            return "{\"ok\": false, \"msg\": \"" +
                    esc(String.valueOf(e.getMessage())) + "\"}";
        }
    }

    /** 用户取消恢复时清理缓存的备份文件。 */
    @JavascriptInterface
    public void discardBackup(String zipPath) {
        try {
            if (zipPath != null) new File(zipPath).delete();
        } catch (Exception ignored) {}
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
