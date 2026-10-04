package com.mssg.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 构建产物预览页。
 *
 * 不走 file:// 跳转（WebView 里不可靠），也不起 localhost HTTP 服务：
 * 拦截 https://mssg.preview/ 的请求，直接从 <siteDir>/public/ 读文件返回。
 */
public class PreviewActivity extends Activity {

    private static final String HOST = "mssg.preview";

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("css", "text/css");
        MIME.put("js", "application/javascript");
        MIME.put("mjs", "application/javascript");
        MIME.put("json", "application/json");
        MIME.put("xml", "application/xml");
        MIME.put("rss", "application/rss+xml");
        MIME.put("txt", "text/plain");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("gif", "image/gif");
        MIME.put("svg", "image/svg+xml");
        MIME.put("webp", "image/webp");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("pdf", "application/pdf");
        MIME.put("mp4", "video/mp4");
        MIME.put("webm", "video/webm");
    }

    private WebView wv;
    private File publicDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String publicDirExtra = getIntent().getStringExtra("publicDir");
        if (publicDirExtra != null) {
            publicDir = new File(publicDirExtra);
        } else {
            String siteDir = getIntent().getStringExtra("siteDir");
            publicDir = new File(siteDir, "public");
        }

        wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                if (!HOST.equals(request.getUrl().getHost())) {
                    return null;
                }
                String path = request.getUrl().getPath();
                if (path == null || path.isEmpty() || path.equals("/")) {
                    path = "/index.html";
                }
                // 去掉开头的 /，防 ../ 穿出 public/
                File f = new File(publicDir, path.replaceFirst("^/+", ""));
                try {
                    String canonBase = publicDir.getCanonicalPath();
                    String canonFile = f.getCanonicalPath();
                    if (!canonFile.equals(canonBase) && !canonFile.startsWith(canonBase + File.separator)) {
                        return null;
                    }
                } catch (Exception e) {
                    return null;
                }
                if (f.isDirectory()) {
                    f = new File(f, "index.html");
                }
                if (!f.isFile()) {
                    return null;
                }
                String mime = mimeOf(f.getName());
                try {
                    // 文本类给 utf-8，二进制不给编码
                    String encoding = mime.startsWith("text/") || mime.contains("javascript")
                            || mime.contains("json") || mime.contains("xml")
                            ? "utf-8" : null;
                    return new WebResourceResponse(mime, encoding, new FileInputStream(f));
                } catch (Exception e) {
                    return null;
                }
            }
        });
        String startPath = getIntent().getStringExtra("startPath");
        if (startPath == null || startPath.isEmpty()) startPath = "/";
        wv.loadUrl("https://" + HOST + startPath);
    }

    private static String mimeOf(String name) {
        int i = name.lastIndexOf('.');
        if (i >= 0) {
            String ext = name.substring(i + 1).toLowerCase();
            String m = MIME.get(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wv != null) wv.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wv != null) wv.onResume();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE && wv != null) {
            wv.clearCache(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (wv != null) {
            wv.stopLoading();
            wv.loadUrl("about:blank");
            android.view.ViewParent p = wv.getParent();
            if (p instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) p).removeView(wv);
            }
            wv.removeAllViews();
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }
}
