package com.mssg.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

public class MainActivity extends Activity {

    private static final int MAX_RETRIES = 8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python py = Python.getInstance();

        File siteDir = new File(getFilesDir(), "site");
        py.getModule("mssg_android")
                .callAttr("ensure_site", siteDir.getAbsolutePath());
        // start_admin 内部会等端口就绪才返回 token
        final String token = py.getModule("mssg_android")
                .callAttr("start_admin", siteDir.getAbsolutePath(), 8902)
                .toString();
        final String url = "http://127.0.0.1:8902/?token=" + token;

        final WebView wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        wv.setWebViewClient(new WebViewClient() {
            private int retries = 0;

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // 主页面加载失败时延迟重试，兜底一切瞬时问题
                if (request.isForMainFrame() && retries < MAX_RETRIES) {
                    retries++;
                    view.postDelayed(() -> view.loadUrl(url), 1200);
                }
            }
        });
        wv.loadUrl(url);
    }
}
