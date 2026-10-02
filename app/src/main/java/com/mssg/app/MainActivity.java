package com.mssg.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;

public class MainActivity extends Activity {

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
        String token = py.getModule("mssg_android")
                .callAttr("start_admin", siteDir.getAbsolutePath(), 8902)
                .toString();

        WebView wv = findViewById(R.id.webview);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        wv.setWebViewClient(new WebViewClient());
        wv.loadUrl("http://127.0.0.1:8902/?token=" + token);
    }
}
