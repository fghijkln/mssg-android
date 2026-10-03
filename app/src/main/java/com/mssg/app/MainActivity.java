package com.mssg.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.widget.EditText;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

/**
 * mssg 手机版：WebView 加载本地 file:// 页面，
 * JS 经 ApiBridge 直调 Python 引擎（无 localhost HTTP 服务）。
 */
public class MainActivity extends Activity {

    private WebView wv;
    private ApiBridge bridge;

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
        // 允许本地页面跨域访问 API（Cloudflare 备用网络通道用）
        s.setAllowUniversalAccessFromFileURLs(true);
        bridge = new ApiBridge(this, wv, siteDir.getAbsolutePath());
        wv.addJavascriptInterface(bridge, "MssgApi");
        // 注册 WebView 网络通道为 Cloudflare 备用传输
        try {
            py.getModule("mssg_api").callAttr("cf_enable_webview_transport", bridge);
        } catch (Exception ignored) {}
        wv.setWebViewClient(new WebViewClient());
        // JS 的 confirm()/alert()/prompt() 用原生对话框：标题显示应用名而不是
        // 系统默认的 "网址为 file:// 的网页显示："，按钮跟随 App 语言
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("WebWeave")
                        .setMessage(message)
                        .setPositiveButton(bridge.tr("确定", "OK"),
                                (d, w) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("WebWeave")
                        .setMessage(message)
                        .setPositiveButton(bridge.tr("确定", "OK"),
                                (d, w) -> result.confirm())
                        .setNegativeButton(bridge.tr("取消", "Cancel"),
                                (d, w) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("WebWeave")
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(bridge.tr("确定", "OK"),
                                (d, w) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(bridge.tr("取消", "Cancel"),
                                (d, w) -> result.cancel())
                        .setCancelable(false)
                        .show();
                return true;
            }
        });
        wv.loadUrl("file:///android_asset/admin/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge == null) return;
        if (bridge.onImagePicked(requestCode, resultCode, data)) return;
        bridge.onBackupPicked(requestCode, resultCode, data);
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
