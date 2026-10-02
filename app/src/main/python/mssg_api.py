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
