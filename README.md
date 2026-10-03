# WebWeave (mssg Android)

手机上的静态站点生成器。写文章、换主题、构建、预览、部署，全在手机上完成——Hugo 做不到的事。

核心引擎是 [mssg](https://github.com/fghijkln/mssg)（自研 Python 静态站点生成器）。

## 功能

- ✍️ 文章管理：新建 / 编辑 / 删除 Markdown 文章（标题、日期、标签、分类、草稿）
- 🎨 三套主题：company（公司站）/ minimal（极简）/ novacore（深色科技风），支持自定义 CSS
- 🔨 一键构建 + 本地预览
- 📦 导出：整站 ZIP（保存到下载 / 分享）、单篇文章 HTML 分享
- ☁️ Cloudflare Pages 一键部署（可选）：API Token 连接后建项目、上传、轮询一次搞定；支持项目列表切换与部署状态查看
- 🗄️ 站点源码备份：一键打包分享到微信 / 云盘 / 邮箱，换机不丢站

## 安装

到 [Releases](../../releases) 下载最新版 `app-debug.apk` 安装（Android 7.0+）。

Token 只存在手机本地，不上传任何服务器。

## 架构

- WebView 加载 APK 内置的移动端管理界面（`app/src/main/assets/admin/`）
- Java `ApiBridge`（`@JavascriptInterface`）直调 Chaquopy Python，没有 localhost HTTP 服务
- Python 侧（`app/src/main/python/`）复用 mssg 核心：构建、主题、部署、备份
- 网络走系统 WebView 网络栈：DNS 被污染时自动切 DoH，极端情况下用 WebView fetch 兜底——v0.13.x 在真机上验证过

## 构建

```bash
./gradlew assembleDebug
```

APK 二进制不进仓库；打 tag 后 GitHub Actions 自动构建并发布到 Release。

## 许可

MIT
