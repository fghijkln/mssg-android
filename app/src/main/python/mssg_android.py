"""Android 启动桥：首次运行建站 + 启动 mssg admin 后台。

原生层（MainActivity）调用：
    ensure_site(site_dir) -> 建站（mssg.toml 不存在时）
    start_admin(site_dir, port) -> 启动后台线程，返回 token
"""
import os
import secrets
import threading


def ensure_site(site_dir: str) -> str:
    from mssg.scaffold import new_site

    if not os.path.exists(os.path.join(site_dir, "mssg.toml")):
        new_site(site_dir)
    return site_dir


def start_admin(site_dir: str, port: int = 8902) -> str:
    from mssg.admin import run

    token = secrets.token_urlsafe(16)
    t = threading.Thread(
        target=run,
        kwargs={"root": site_dir, "port": port, "token": token},
        daemon=True,
    )
    t.start()
    return token
