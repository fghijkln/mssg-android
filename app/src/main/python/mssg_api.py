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
    except ValueError as e:
        return _fail(str(e))
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


def export_source_backup(site_dir: str) -> str:
    """打包站点源码（文章/配置/模板/数据），不含构建产物与 Token，返回 zip 路径。"""
    try:
        import datetime

        from mssg.backup import backup_site

        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        zip_path = Path(site_dir).parent / ("mssg-backup-%s.zip" % stamp)
        path = backup_site(site_dir, dest=zip_path)
        return _ok(path=path, name=Path(path).name)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def _human_size(n: int) -> str:
    f = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if f < 1024 or unit == "GB":
            return "%d%s" % (n, unit) if unit == "B" else "%.1f%s" % (f, unit)
        f /= 1024.0
    return "%.1fGB" % f


def clean_build(site_dir: str) -> str:
    """删除构建输出目录（public/），释放空间。文章/配置不受影响。"""
    try:
        import shutil

        public = _output_dir(site_dir)
        site = Path(site_dir).resolve()
        if public.resolve() == site:
            return _fail("拒绝清空站点根目录")
        shutil.rmtree(public, ignore_errors=True)
        return _ok()
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def get_build_info(site_dir: str) -> str:
    """构建产物信息：是否存在、文件数、体积。"""
    try:
        public = _output_dir(site_dir)
        if not public.is_dir():
            return _ok(exists=False)
        files = [f for f in public.rglob("*") if f.is_file()]
        size = sum(f.stat().st_size for f in files)
        return _ok(exists=True, files=len(files), size=size,
                   size_human=_human_size(size))
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def list_build_files(site_dir: str) -> str:
    """列出构建产物所有文件：相对路径、大小（按路径排序）。"""
    try:
        public = _output_dir(site_dir)
        if not public.is_dir():
            return _ok(exists=False, files=[], count=0)
        items = []
        for f in sorted(public.rglob("*")):
            if f.is_file():
                size = f.stat().st_size
                items.append({"path": f.relative_to(public).as_posix(),
                              "size": size, "size_human": _human_size(size)})
        total = sum(i["size"] for i in items)
        return _ok(exists=True, files=items, count=len(items),
                   total_human=_human_size(total))
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def delete_build_file(site_dir: str, rel: str) -> str:
    """删除构建产物中的单个文件（防 ../ 穿出），顺手清理空目录。"""
    try:
        public = _output_dir(site_dir).resolve()
        target = (public / rel).resolve()
        if target != public and public not in target.parents:
            return _fail("非法路径")
        if not target.is_file():
            return _fail("文件不存在")
        target.unlink()
        p = target.parent
        while p != public and p.is_dir() and not any(p.iterdir()):
            p.rmdir()
            p = p.parent
        return _ok()
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def import_bundle_image(site_dir: str, tmp_path: str, rel: str) -> str:
    """把外部图片导入文章的 page bundle 目录（与 md 同目录），返回文件名。

    图片过大时自动缩到 1600 宽（复用构建的图片管线）。
    """
    try:
        import time

        from mssg import images as _images

        site = Path(site_dir)
        content_root = (site / "content").resolve()
        target_dir = (content_root / Path(rel).parent).resolve()
        if target_dir != content_root and content_root not in target_dir.parents:
            return _fail("非法路径")
        tmp = Path(tmp_path)
        if not _images.is_image(tmp):
            return _fail("不是图片文件")
        target_dir.mkdir(parents=True, exist_ok=True)
        name = "img-%s%s" % (time.strftime("%Y%m%d-%H%M%S"), tmp.suffix.lower())
        _images.copy_static_file(tmp, target_dir / name, 1600, 82)
        return _ok(name=name)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


_RESTORE_ITEMS = ("mssg.toml", "content", "static", "data", "templates")


def _copy_data_only(src: "Path", dst: "Path") -> None:
    """递归复制文件/目录，只拷数据不保留权限时间。

    shutil.copy2/copytree 会调 copystat→chmod，在部分 Android
    文件系统上直接 Permission denied，这里完全避开。
    """
    import shutil as _shutil

    if src.is_dir() and not src.is_symlink():
        if dst.is_dir() and not dst.is_symlink():
            _shutil.rmtree(dst)
        elif dst.exists() or dst.is_symlink():
            dst.unlink()
        dst.mkdir(parents=True, exist_ok=True)
        for item in src.iterdir():
            _copy_data_only(item, dst / item.name)
    else:
        if dst.is_dir() and not dst.is_symlink():
            _shutil.rmtree(dst)
        elif dst.exists() or dst.is_symlink():
            dst.unlink()
        dst.parent.mkdir(parents=True, exist_ok=True)
        with open(src, "rb") as fsrc, open(dst, "wb") as fdst:
            _shutil.copyfileobj(fsrc, fdst, 1024 * 64)


def _check_backup_zip(zip_path: str) -> "Path":
    """校验备份 ZIP，返回其 Path；无效则抛 ValueError。"""
    import zipfile as _zf

    zp = Path(zip_path)
    if not zp.is_file():
        raise ValueError("找不到备份文件")
    if not _zf.is_zipfile(str(zp)):
        raise ValueError("不是有效的 ZIP 文件")
    with _zf.ZipFile(str(zp)) as zf:
        names = zf.namelist()
    if "mssg.toml" not in names:
        raise ValueError("该 ZIP 不是织网站点备份（缺少 mssg.toml）")
    return zp


def inspect_backup(zip_path: str) -> str:
    """查看备份基本信息（不恢复），供恢复前确认。"""
    try:
        zp = _check_backup_zip(zip_path)
        import zipfile as _zf

        with _zf.ZipFile(str(zp)) as zf:
            articles = sum(
                1 for n in zf.namelist()
                if n.startswith("content/") and n.endswith(".md")
            )
        size = zp.stat().st_size
        if size >= 1048576:
            human = "%.1f MB" % (size / 1048576)
        elif size >= 1024:
            human = "%.0f KB" % (size / 1024)
        else:
            human = "%d B" % size
        return _ok(articles=articles, name=zp.name, size_human=human)
    except Exception as e:
        return _fail(str(e))


def restore_backup(site_dir: str, zip_path: str) -> str:
    """从备份 ZIP 恢复站点：校验→解到临时目录→替换源码（保留
    .mssg_cf.json 等 App 配置）→清空构建产物。"""
    try:
        from mssg.backup import restore_site
        import shutil as _shutil
        import tempfile as _tf

        zp = _check_backup_zip(zip_path)
        site = Path(site_dir)
        site.mkdir(parents=True, exist_ok=True)
        tmp = Path(_tf.mkdtemp(prefix="restore-"))
        try:
            restore_site(str(zp), str(tmp))
            for name in _RESTORE_ITEMS:
                src = tmp / name
                if not src.exists():
                    continue
                _copy_data_only(src, site / name)
            # 构建产物清空，下次构建全新生成
            pub = site / "public"
            if pub.is_dir():
                _shutil.rmtree(pub)
            articles = sum(1 for _ in site.glob("content/**/*.md"))
        finally:
            _shutil.rmtree(tmp, ignore_errors=True)
        return _ok(articles=articles)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def _valid_plugin_name(name: str) -> bool:
    import re as _re

    return bool(_re.match(r"^[A-Za-z0-9_-]{1,64}$", name or ""))


def list_installed_plugins(site_dir: str) -> str:
    """列出已安装的 shortcode 插件（templates/shortcodes/*.html）。"""
    try:
        d = Path(site_dir) / "templates" / "shortcodes"
        plugins = []
        if d.is_dir():
            for f in sorted(d.glob("*.html")):
                plugins.append({"name": f.stem, "size": f.stat().st_size})
        return _ok(plugins=plugins)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def install_plugin_file(site_dir: str, name: str, content: str) -> str:
    """安装插件文件。content 由 App 经 WebView 下载得到；目标路径只
    用校验过的 name 构造，不信任远端的 install_to（防路径穿越）。"""
    try:
        if not _valid_plugin_name(name):
            return _fail("非法插件名")
        if not content or not content.strip():
            return _fail("插件文件为空")
        d = Path(site_dir) / "templates" / "shortcodes"
        d.mkdir(parents=True, exist_ok=True)
        (d / (name + ".html")).write_text(content, encoding="utf-8")
        return _ok(name=name)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def delete_plugin(site_dir: str, name: str) -> str:
    """删除已安装的插件。"""
    try:
        if not _valid_plugin_name(name):
            return _fail("非法插件名")
        p = Path(site_dir) / "templates" / "shortcodes" / (name + ".html")
        if p.is_file():
            p.unlink()
        return _ok(name=name)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def ai_create_site(parent_dir: str, spec_text: str) -> str:
    """AI 建站：按分隔符格式在 <parent_dir>/site-ai/ 生成并构建，不碰当前站点。

    格式：
        @@@SITE@@@
        title: 标题
        description: 描述
        theme: minimal
        @@@PAGE@@@
        path: content/_index.md
        title: 首页
        body:
        Markdown 正文（多行，无需转义）
        @@@PAGE@@@
        ...
        @@@END@@@
    """
    import shutil as _shutil

    try:
        site = {"title": "我的网站", "description": "", "theme": "minimal"}
        pages = []
        cur = None
        body_lines: list = []
        in_body = False
        section = None
        for line in (spec_text or "").split("\n"):
            s = line.strip()
            if s == "@@@SITE@@@":
                section = "site"
                continue
            if s == "@@@PAGE@@@":
                if cur is not None:
                    cur["body"] = "\n".join(body_lines).strip()
                    if cur.get("path") and cur["body"]:
                        pages.append(cur)
                cur = {"path": "", "title": "", "body": ""}
                body_lines = []
                in_body = False
                section = "page"
                continue
            if s == "@@@END@@@":
                if cur is not None:
                    cur["body"] = "\n".join(body_lines).strip()
                    if cur.get("path") and cur["body"]:
                        pages.append(cur)
                break
            if section == "site":
                if ":" in line:
                    k, _, v = line.partition(":")
                    k = k.strip()
                    if k in ("title", "description", "theme"):
                        site[k] = v.strip()
            elif section == "page" and cur is not None:
                if not in_body:
                    if line.startswith("body:"):
                        in_body = True
                        rest = line[5:].strip()
                        if rest:
                            body_lines.append(rest)
                    elif ":" in line:
                        k, _, v = line.partition(":")
                        k = k.strip()
                        if k in ("path", "title"):
                            cur[k] = v.strip()
                else:
                    body_lines.append(line)
        if not pages:
            return _fail("AI 没有返回有效页面")

        target = Path(parent_dir) / "site-ai"
        if target.exists():
            _shutil.rmtree(target)
        from mssg.scaffold import new_site
        new_site(str(target))

        theme = site.get("theme", "minimal").strip()
        if theme not in ("minimal", "company"):
            theme = "minimal"
        title = site.get("title", "").strip() or "我的网站"
        desc = site.get("description", "").strip()

        def _q(s: str) -> str:
            return '"%s"' % s.replace("\\", "\\\\").replace('"', '\\"')

        toml = (
            "[site]\n"
            "title = %s\n"
            'description = %s\n'
            'theme = "%s"\n'
            'language = "zh"\n'
            % (_q(title), _q(desc), theme)
        )
        (target / "mssg.toml").write_text(toml, encoding="utf-8")

        content_dir = target / "content"
        if content_dir.exists():
            _shutil.rmtree(content_dir)
        content_dir.mkdir(parents=True)

        count = 0
        for pg in pages:
            rel = pg["path"]
            if not rel.startswith("content/") or ".." in rel:
                continue
            if not rel.endswith(".md"):
                rel += ".md"
            body = pg["body"]
            if not body.strip():
                continue
            fp = target / rel
            fp.parent.mkdir(parents=True, exist_ok=True)
            ptitle = pg.get("title", "").strip() or fp.stem
            fp.write_text("---\ntitle: %s\n---\n\n" % _q(ptitle) + body,
                          encoding="utf-8")
            count += 1
        if count == 0:
            return _fail("AI 返回的页面都无效")

        from mssg.site import Site
        Site(str(target)).build()
        return _ok(pages=count, title=title, theme=theme)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def ai_promote_site(parent_dir: str) -> str:
    """把 site-ai/ 设为当前站点（覆盖 site/）。调用前 JS 已让用户确认。"""
    try:
        src = Path(parent_dir) / "site-ai"
        dst = Path(parent_dir) / "site"
        if not src.is_dir() or not (src / "mssg.toml").is_file():
            return _fail("AI 站点不存在，请先生成")
        # 只拷数据不保留权限（copytree 的 copystat 在手机上 Permission denied）
        _copy_data_only(src, dst)
        return _ok()
    except Exception:
        return _fail(traceback.format_exc(limit=3))


_PROJECT_NAME_RE = None

def _valid_project_name(name: str) -> bool:
    global _PROJECT_NAME_RE
    import re as _re
    if _PROJECT_NAME_RE is None:
        _PROJECT_NAME_RE = _re.compile(r"^[A-Za-z0-9_\-\u4e00-\u9fa5]{1,50}$")
    return bool(_PROJECT_NAME_RE.match(name or ""))


def _projects_dir(parent_dir: str) -> "Path":
    return Path(parent_dir) / "projects"


def list_projects(parent_dir: str) -> str:
    """列出所有构建项目（按修改时间倒序）。"""
    try:
        pdir = _projects_dir(parent_dir)
        projects = []
        if pdir.is_dir():
            for d in pdir.iterdir():
                if not d.is_dir():
                    continue
                files = [f for f in d.rglob("*") if f.is_file()]
                total = sum(f.stat().st_size for f in files)
                mtime = max([f.stat().st_mtime for f in files] + [d.stat().st_mtime])
                projects.append({
                    "name": d.name,
                    "count": len(files),
                    "total_human": _human_size(total),
                    "mtime": mtime,
                })
        projects.sort(key=lambda x: x["mtime"], reverse=True)
        return _ok(projects=projects)
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def build_project(site_dir: str, parent_dir: str, name: str) -> str:
    """构建站点并保存为项目（覆盖同名需 JS 先确认）。"""
    try:
        if not _valid_project_name(name):
            return _fail("项目名非法（可用中文、字母、数字、下划线、连字符，最长 50）")
        from mssg.site import Site
        Site(site_dir).build()
        src = _output_dir(site_dir)
        if not src.is_dir():
            return _fail("构建没有产物")
        dst = _projects_dir(parent_dir) / name
        _copy_data_only(src, dst)
        files = [f for f in dst.rglob("*") if f.is_file()]
        return _ok(name=name, count=len(files))
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def list_project_files(parent_dir: str, name: str) -> str:
    """列出项目下的文件。"""
    try:
        if not _valid_project_name(name):
            return _fail("项目名非法")
        pdir = _projects_dir(parent_dir) / name
        if not pdir.is_dir():
            return _ok(exists=False, files=[], count=0)
        items = []
        for f in sorted(pdir.rglob("*")):
            if f.is_file():
                size = f.stat().st_size
                items.append({"path": f.relative_to(pdir).as_posix(),
                              "size": size, "size_human": _human_size(size)})
        total = sum(i["size"] for i in items)
        return _ok(exists=True, files=items, count=len(items),
                   total_human=_human_size(total))
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def delete_project(parent_dir: str, name: str) -> str:
    """删除整个项目。"""
    import shutil as _shutil
    try:
        if not _valid_project_name(name):
            return _fail("项目名非法")
        pdir = _projects_dir(parent_dir) / name
        if pdir.is_dir():
            _shutil.rmtree(pdir)
        return _ok()
    except Exception:
        return _fail(traceback.format_exc(limit=3))


def delete_project_file(parent_dir: str, name: str, rel: str) -> str:
    """删除项目中的单个文件（防 ../ 穿出）。"""
    import shutil as _shutil
    try:
        if not _valid_project_name(name):
            return _fail("项目名非法")
        pdir = _projects_dir(parent_dir) / name
        target = (pdir / rel).resolve()
        if pdir.resolve() not in target.parents and target != pdir.resolve():
            return _fail("非法路径")
        if target.is_file():
            target.unlink()
        elif target.is_dir():
            _shutil.rmtree(target)
        # 清理空目录
        for parent in target.parents:
            if parent == pdir.resolve():
                break
            try:
                parent.rmdir()
            except OSError:
                break
        return _ok()
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
            connected=bool(c.get("token")),
            account_id=c.get("account_id", ""),
            account_name=c.get("account_name", ""),
            project=c.get("project", ""),
        )
    except Exception as e:
        return _fail("读取失败：%s" % e)


def cf_connect(site_dir: str, token: str) -> str:
    """用 API Token 连接：先验活，再尽量自动取账号。

    有些 Token 调 /accounts 返回空列表，此时先保存 Token，
    让用户手动填 Account ID（cf_set_account）。
    """
    try:
        from mssg import cloudflare as cf

        token = (token or "").strip()
        if not token:
            return _fail("Token 不能为空")
        info = cf.verify_token(token)
        if info.get("status") != "active":
            return _fail("Token 状态异常：%s" % info.get("status"))
        accounts = cf.list_accounts(token)
        c = _cf_load(site_dir)
        c.update(token=token, account_id="", account_name="")
        if accounts:
            acc = accounts[0]
            c.update(account_id=acc["id"], account_name=acc["name"])
        _cf_save(site_dir, c)
        return _ok(
            accounts=accounts,
            need_account_id=not accounts,
            account_name=c.get("account_name", ""),
        )
    except Exception as e:
        return _fail(str(e))


def cf_set_account(site_dir: str, account_id: str) -> str:
    """手动设置 Account ID（/accounts 返回空时用）。"""
    try:
        from mssg import cloudflare as cf

        c = _cf_load(site_dir)
        if not c.get("token"):
            return _fail("请先粘贴 API Token 并连接")
        account_id = (account_id or "").strip()
        if not account_id:
            return _fail("Account ID 不能为空")
        # 验证 Token 对该账号可用：列出 Pages 项目
        cf.list_projects(c["token"], account_id)
        c.update(account_id=account_id)
        _cf_save(site_dir, c)
        return _ok(account_id=account_id)
    except Exception as e:
        return _fail("Account ID 无效或 Token 无权访问：%s" % e)


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


def cf_projects(site_dir: str) -> str:
    """列出账号下的 Pages 项目，每个附带最新一次部署状态。"""
    try:
        from mssg import cloudflare as cf

        c = _cf_load(site_dir)
        if not c.get("token") or not c.get("account_id"):
            return _fail("请先连接 Cloudflare")
        projs = cf.list_projects(c["token"], c["account_id"])
        out = []
        for pr in projs:
            name = pr.get("name", "")
            latest = None
            try:
                deps = cf.list_deployments(
                    c["token"], c["account_id"], name, per_page=1
                )
                latest = deps[0] if deps else None
            except Exception:
                latest = None
            out.append(
                {
                    "name": name,
                    "url": "https://%s.pages.dev" % name,
                    "latest": latest,
                }
            )
        return _ok(projects=out, current=c.get("project", ""))
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
