# Mujde 1.1.0（本机改造）

源码：`frida-modules-dl/mujde-app/`（基于 mon231/com.rel.mujde）

## 构建

```bat
cd frida-modules-dl\mujde-app
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`  
已复制：`frida-modules-dl\Mujde-v1.1.0-debug.apk`

Frida injectors 已 vendored 于 `app/src/main/jniLibs/*/libfrida-inject.so`（**17.16.4**）。升级版本时手动替换四架构 so，并更新 `jniLibs/.frida-version` 与 `gradle.properties` 的 `FRIDA_VERSION`。

## 安装

```bat
adb install -r frida-modules-dl\Mujde-v1.1.0-debug.apk
```

安装后：LSPosed 重新启用模块 → 打开一次 Mujde（前台服务）→ Apps 绑定脚本。
