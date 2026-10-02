package com.mssg.app;

import android.webkit.JavascriptInterface;

import com.chaquo.python.Python;

/**
 * WebView JS ↔ Python 的桥：页面（file:// 本地）直接调 mssg 引擎，
 * 不再经过 localhost HTTP 服务。
 *
 * 所有方法都在 WebView 的 JavaBridge 后台线程执行，可直接做耗时操作。
 */
public class ApiBridge {
    private final String siteDir;

    public ApiBridge(String siteDir) {
        this.siteDir = siteDir;
    }

    private com.chaquo.python.PyObject api() {
        return Python.getInstance().getModule("mssg_api");
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
            return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
        }
    }

    @JavascriptInterface
    public String getPage(String rel) {
        try {
            return api().callAttr("get_page", siteDir, rel).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
        }
    }

    @JavascriptInterface
    public String savePage(String rel, String title, String date, String tags,
                           String categories, boolean draft, String body) {
        try {
            return api().callAttr("save_page", siteDir, rel, title, date,
                    tags, categories, draft, body).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
        }
    }

    @JavascriptInterface
    public String deletePage(String rel) {
        try {
            return api().callAttr("delete_page", siteDir, rel).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
        }
    }

    @JavascriptInterface
    public String buildSite() {
        try {
            return api().callAttr("build_site", siteDir).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"" + esc(e.toString()) + "\"}";
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }
}
