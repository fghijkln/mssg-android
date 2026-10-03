# WebWeave for Android

[中文](README_CN.md) | English

A static site generator that runs entirely on your phone. Write articles, switch themes, build, preview, and deploy — all without a desktop. Things Hugo can't do on mobile.

The core engine is [mssg](https://github.com/fghijkln/mssg), a self-built Python static site generator.

## Features

- ✍️ Article management: create / edit / delete Markdown articles (title, date, tags, categories, drafts)
- 🎨 Three built-in themes: company / minimal / novacore, with custom CSS support
- 🔨 One-tap build + local preview
- 📦 Export: full-site ZIP (save to Downloads / share), single-article HTML sharing
- ☁️ Cloudflare Pages one-click deploy (optional): connect with an API Token, then create project, upload, and poll in one go; project list switching and deployment status included
- 🗄️ Site source backup: one-tap archive shared to WeChat / cloud storage / email — switch phones without losing your site

## Install

Download the latest `app-debug.apk` from [Releases](../../releases) and install (Android 7.0+).

API tokens are stored on the device only. Nothing is uploaded to any server.

## Architecture

- A WebView loads the built-in mobile admin UI (`app/src/main/assets/admin/`)
- Java `ApiBridge` (`@JavascriptInterface`) calls Chaquopy Python directly — no localhost HTTP service
- The Python side (`app/src/main/python/`) reuses the mssg core: build, themes, deploy, backup
- Networking goes through the system WebView stack: automatically switches to DoH when DNS is poisoned, with a WebView fetch fallback for edge cases — verified on real devices in v0.13.x

## Build

```bash
./gradlew assembleDebug
```

APK binaries are not committed to the repo; pushing a tag triggers GitHub Actions to build and publish to Releases.

## License

[MIT](LICENSE) — free software, do what you want with it.
