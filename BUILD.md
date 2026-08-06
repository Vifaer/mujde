# Mujde 构建说明（v1.2.0）

源码：`frida-modules-dl/mujde-app/`（基于 mon231/com.rel.mujde）

## 本地构建

```bat
cd frida-modules-dl\mujde-app
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat assembleDebug
```

`preBuild` 会跑 `fetchFridaInjectors`：若 `app/src/main/jniLibs/*/libfrida-inject.so` 缺失，则从 GitHub 下载对应 `frida-inject-*.xz`。

- Linux/CI：需 `xz`（`apt install xz-utils`）
- Windows：已有 vendored so 时可跳过下载；否则请安装 xz/7z，或手动放入四架构 so

产物：`app\build\outputs\apk\debug\app-debug.apk`

Frida 版本由 `gradle.properties` 的 `FRIDA_VERSION`（默认 **17.16.4**）控制。

## CI / Releases

推送 `main`：构建 APK 并上传 Artifact。  
打 tag `v*`（如 `v1.2.0`）：额外创建 GitHub Release 并挂 APK。

## 安装

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

安装后：LSPosed 启用模块 → 打开一次 Mujde → 首页可做健康检查 → Apps 绑定脚本。
