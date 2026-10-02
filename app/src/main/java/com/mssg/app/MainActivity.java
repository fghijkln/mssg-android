package com.mssg.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

/**
 * mssg 手机版：WebView 加载本地 file:// 页面，
 * JS 经 ApiBridge 直调 Python 引擎（无 localhost HTTP 服务）。
 */
public class MainActivity extends Activity {

    private WebView wv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        Python py = Python.getInstance();

        // 首次运行建站
        File siteDir = new File(getFilesDir(), "site");
        py.getModule("mssg_android")
                .callAttr("ensure_site", siteDir.getAbsolutePath());

        wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        wv.addJavascriptInterface(
                new ApiBridge(siteDir.getAbsolutePath()), "MssgApi");
        wv.setWebViewClient(new WebViewClient());
        wv.loadUrl("file:///android_asset/admin/index.html");
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) {
            wv.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
