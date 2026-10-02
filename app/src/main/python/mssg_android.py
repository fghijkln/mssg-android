"""Android 启动桥：首次运行建站。

重写后的设计里，WebView 直接加载 file:// 本地页面，
经 ApiBridge 直调 mssg_api，不再需要 HTTP 服务。
"""
import os


def ensure_site(site_dir: str) -> str:
    from mssg.scaffold import new_site

    if not os.path.exists(os.path.join(site_dir, "mssg.toml")):
        new_site(site_dir)
    return site_dir
