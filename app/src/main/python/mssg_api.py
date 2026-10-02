"""给 App 内 WebView 用的 JSON API：文章增删改查 + 构建。

由 Java 的 ApiBridge 通过 @JavascriptInterface 直接调用，
不再经过 HTTP 服务（localhost 在 WebView 里不可靠）。
"""
import json
import os
import re
import traceback
import zipfile
from pathlib import Path


def _app(site_dir):
    from mssg.admin import AdminApp

    return AdminApp(site_dir)


def _ok(**kw):
    kw.setdefault("ok", True)
    # default=str：front matter 的 date 可能是 datetime.date 对象
    return json.dumps(kw, ensure_ascii=False, default=str)


def _fail(msg):
    return json.dumps({"ok": False, "msg": str(msg)}, ensure_ascii=False)


def list_pages(site_dir: str) -> str:
    try:
        return _ok(pages=_app(site_dir)._list_pages())
    except Exception as e:
        return _fail("读取失败：%s" % e)


def get_page(site_dir: str, rel: str) -> str:
    try:
        from mssg.admin import _split_fm

        app = _app(site_dir)
        path = app._safe_path(rel)
        meta, body = _split_fm(path.read_text(encoding="utf-8"))
        return _ok(meta=meta if isinstance(meta, dict) else {}, body=body)
    except Exception as e:
        return _fail("读取失败：%s" % e)


def save_page(
    site_dir: str,
    rel: str,
    title: str,
    date: str,
    tags: str,
    categories: str,
    draft: bool,
    body: str,
) -> str:
    try:
        app = _app(site_dir)
        form = {
            "rel": [rel],
            "title": [title],
            "date": [date],
            "tags": [tags],
            "categories": [categories],
            "body": [body],
        }
        if draft:
            form["draft"] = ["on"]
        saved_rel = app.save_page(form)
        msg = app._rebuild()
        return _ok(msg=msg, rel=saved_rel)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def delete_page(site_dir: str, rel: str) -> str:
    try:
        app = _app(site_dir)
        app._safe_path(rel).unlink(missing_ok=True)
        msg = app._rebuild()
        return _ok(msg=msg)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def build_site(site_dir: str) -> str:
    try:
        return _ok(msg=_app(site_dir)._rebuild())
    except Exception:
        return _fail(traceback.format_exc(limit=3))


_THEMES_ZH = {
    "company": "公司站（浅色）",
    "minimal": "极简风",
    "novacore": "深色科技",
}


def list_themes() -> str:
    try:
        from mssg.site import Site

        themes = Site.available_themes()
        return _ok(
            themes=[
                {"id": t, "name": _THEMES_ZH.get(t, t)} for t in themes
            ]
        )
    except Exception as e:
        return _fail("读取失败：%s" % e)


def get_theme(site_dir: str) -> str:
    try:
        from mssg.site import Site

        theme = Site(site_dir).cfg["site"].get("theme", "company")
        return _ok(theme=theme)
    except Exception as e:
        return _fail("读取失败：%s" % e)


def set_theme(site_dir: str, theme: str) -> str:
    try:
        from mssg.site import Site

        if theme not in Site.available_themes():
            return _fail("未知主题：%s" % theme)
        toml_path = os.path.join(site_dir, "mssg.toml")
        with open(toml_path, encoding="utf-8") as f:
            text = f.read()
        new_text, n = re.subn(
            r'^theme\s*=\s*"[^"]*"', 'theme = "%s"' % theme,
            text, count=1, flags=re.M,
        )
        if n == 0:
            return _fail("mssg.toml 里找不到 theme 配置项")
        with open(toml_path, "w", encoding="utf-8") as f:
            f.write(new_text)
        # 切换主题时清除自定义 CSS，避免旧样式串到新主题上
        custom = Path(site_dir) / "static" / "style.css"
        if custom.is_file():
            custom.unlink()
            msg = _app(site_dir)._rebuild()
            return _ok(msg=msg + "（已清除自定义 CSS）", theme=theme)
        msg = _app(site_dir)._rebuild()
        return _ok(msg=msg, theme=theme)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def _output_dir(site_dir: str) -> Path:
    from mssg.site import Site

    out = Site(site_dir).cfg["build"].get("output_dir", "public")
    return Path(site_dir) / out


def export_zip(site_dir: str) -> str:
    """构建整站并打包为 zip（用于上传到 Pages/Netlify 等），返回文件路径。"""
    try:
        app = _app(site_dir)
        msg = app._rebuild()
        public = _output_dir(site_dir)
        if not public.is_dir():
            return _fail("构建输出不存在")
        zip_path = Path(site_dir) / "mssg-site.zip"
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
            for f in sorted(public.rglob("*")):
                if f.is_file():
                    zf.write(f, f.relative_to(public).as_posix())
        return _ok(path=str(zip_path), msg=msg)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def export_page_html(site_dir: str, rel: str) -> str:
    """构建并返回单篇文章的 HTML 文件路径。"""
    try:
        from mssg.site import Site

        app = _app(site_dir)
        app._rebuild()
        site = Site(site_dir)
        lang, base_rel = site._split_lang(rel)
        default = site._default_lang()
        prefix = "" if lang == default else lang + "/"
        url = prefix + base_rel[:-3] + ".html"
        html_path = _output_dir(site_dir) / url
        if not html_path.is_file():
            return _fail("页面尚未生成：%s" % url)
        return _ok(path=str(html_path), name=html_path.name)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def get_custom_css(site_dir: str) -> str:
    """返回自定义 CSS：有覆盖则返回覆盖内容，否则返回当前主题的 CSS 供改写。"""
    try:
        from mssg.site import Site
        from mssg import themes as _themes

        site = Site(site_dir)
        override = Path(site_dir) / "static" / "style.css"
        if override.is_file():
            return _ok(
                css=override.read_text(encoding="utf-8"), is_custom=True
            )
        theme = site.cfg["site"].get("theme", "company")
        _, s_dir = _themes.theme_dirs(theme)
        base = Path(s_dir) / "style.css"
        css = base.read_text(encoding="utf-8") if base.is_file() else ""
        return _ok(css=css, is_custom=False, theme=theme)
    except Exception as e:
        return _fail("读取失败：%s" % e)


def save_custom_css(site_dir: str, css: str) -> str:
    """保存自定义 CSS（覆盖主题的 style.css），并重建。"""
    try:
        override = Path(site_dir) / "static" / "style.css"
        override.parent.mkdir(parents=True, exist_ok=True)
        override.write_text(css, encoding="utf-8")
        msg = _app(site_dir)._rebuild()
        return _ok(msg=msg)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def clear_custom_css(site_dir: str) -> str:
    """删除自定义 CSS 覆盖，恢复主题默认，并重建。"""
    try:
        override = Path(site_dir) / "static" / "style.css"
        if override.is_file():
            override.unlink()
        msg = _app(site_dir)._rebuild()
        return _ok(msg=msg)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


# ---------- Cloudflare Pages 一键部署（可选） ----------

def _cf_conf_path(site_dir: str) -> Path:
    return Path(site_dir).parent / ".mssg_cf.json"


def _cf_load(site_dir: str) -> dict:
    p = _cf_conf_path(site_dir)
    if p.is_file():
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def _cf_save(site_dir: str, conf: dict):
    p = _cf_conf_path(site_dir)
    p.write_text(json.dumps(conf), encoding="utf-8")
    try:
        os.chmod(p, 0o600)
    except Exception:
        pass


def cf_status(site_dir: str) -> str:
    """是否已连接 Cloudflare。"""
    try:
        c = _cf_load(site_dir)
        return _ok(
            connected=bool(c.get("token") and c.get("account_id")),
            account_name=c.get("account_name", ""),
            project=c.get("project", ""),
        )
    except Exception as e:
        return _fail("读取失败：%s" % e)


def cf_connect(site_dir: str, token: str) -> str:
    """用 API Token 连接：校验并保存账号。"""
    try:
        from mssg import cloudflare as cf

        token = (token or "").strip()
        if not token:
            return _fail("Token 不能为空")
        accounts = cf.list_accounts(token)
        if not accounts:
            return _fail("该 Token 下没有可用账号")
        acc = accounts[0]
        c = _cf_load(site_dir)
        c.update(
            token=token, account_id=acc["id"], account_name=acc["name"]
        )
        _cf_save(site_dir, c)
        return _ok(accounts=accounts, account_name=acc["name"])
    except Exception as e:
        return _fail(str(e))


def cf_set_project(site_dir: str, name: str) -> str:
    """设置/创建 Pages 项目。"""
    try:
        from mssg import cloudflare as cf

        c = _cf_load(site_dir)
        if not c.get("token"):
            return _fail("请先连接 Cloudflare")
        name = cf.sanitize_project_name(name)
        proj, created = cf.get_or_create_project(
            c["token"], c["account_id"], name
        )
        c["project"] = proj.get("name", name)
        _cf_save(site_dir, c)
        return _ok(project=c["project"], created=created,
                   url="https://%s.pages.dev" % c["project"])
    except Exception as e:
        return _fail(str(e))


def cf_deploy(site_dir: str) -> str:
    """构建并一键部署到 Cloudflare Pages。"""
    try:
        from mssg import cloudflare as cf

        c = _cf_load(site_dir)
        if not c.get("token"):
            return _fail("请先连接 Cloudflare")
        if not c.get("project"):
            return _fail("请先设置 Pages 项目")
        app = _app(site_dir)
        app._rebuild()
        public = _output_dir(site_dir)
        out = cf.deploy_directory(
            c["token"], c["account_id"], c["project"], public
        )
        return _ok(url=out["url"], project_url=out["project_url"])
    except Exception as e:
        return _fail(str(e))


def cf_disconnect(site_dir: str) -> str:
    """断开 Cloudflare 连接（删除本地保存的 Token）。"""
    try:
        p = _cf_conf_path(site_dir)
        if p.is_file():
            p.unlink()
        return _ok()
    except Exception as e:
        return _fail("断开失败：%s" % e)


# ---------- WebView 网络通道（Cloudflare 备用传输） ----------

def cf_enable_webview_transport(bridge):
    """Java 在启动时调用：把 WebView 通道注册为 cloudflare 备用传输。

    系统 DNS/socket 全坏的手机上，部署请求经 WebView（Chromium 网络栈）发出。
    注册失败不影响启动（只是少一条兜底）。
    """
    try:
        from mssg import cloudflare as cf

        def _wv_fetch(method, url, headers, body, timeout):
            import base64
            import json as _json

            b64 = base64.b64encode(body).decode("ascii") if body else ""
            raw = str(
                bridge.cfFetchSync(
                    method, url, _json.dumps(headers or {}), b64, int(timeout)
                )
            )
            res = _json.loads(raw)
            if "error" in res:
                raise OSError(res["error"])
            return int(res.get("status", 0)), str(res.get("body", "")).encode("utf-8")

        cf.set_transport(_wv_fetch)
        return _ok()
    except Exception as e:
        return _fail("注册 WebView 通道失败：%s" % e)
