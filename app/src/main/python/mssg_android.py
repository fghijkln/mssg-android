"""Android 启动桥：首次运行建站 + 启动 mssg admin 后台。

原生层（MainActivity）调用：
    ensure_site(site_dir) -> 建站（mssg.toml 不存在时）
    start_admin(site_dir, port) -> 启动后台线程，等端口就绪后返回 token
"""
import os
import secrets
import socket
import threading
import time

_threads = []  # 持有线程引用，防止被回收


def ensure_site(site_dir: str) -> str:
    from mssg.scaffold import new_site

    if not os.path.exists(os.path.join(site_dir, "mssg.toml")):
        new_site(site_dir)
    return site_dir


def _wait_for_port(port: int, timeout: float = 20.0) -> bool:
    """等服务端 socket 真正 bind 并 accept，否则 WebView 会抢跑。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return True
        except OSError:
            time.sleep(0.2)
    return False


def start_admin(site_dir: str, port: int = 8902) -> str:
    from mssg.admin import run

    token = secrets.token_urlsafe(16)
    t = threading.Thread(
        target=run,
        kwargs={"root": site_dir, "port": port, "token": token},
        daemon=True,
    )
    t.start()
    _threads.append(t)
    _wait_for_port(port)
    return token
