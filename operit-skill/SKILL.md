---
name: Mujde Frida脚本开发助手
description: 编写/部署/调试 Mujde（com.rel.mujde）真 Frida 脚本。覆盖 KernelSU 授权、脚本路径、重入保护、短函数禁 patch、Unity 上禁止 Toast、liblog 打日志、注入链路排障。用户提到 Mujde、frida-inject、Activity.onCreate 注入、神秘元素消消乐 Frida 脚本时务必使用本 Skill。
---

# Mujde Frida 脚本开发 Skill

## 何时使用

- 用户要写 / 改 / 部署 **Mujde** 脚本（不是 LSPilot Rhino）
- 注入不生效、`su` Permission denied、注入后闪退
- Unity IL2CPP + Mujde 脚本开发 / hook 排障

## 运行时事实

| 项 | 值 |
|----|-----|
| 包名 | `com.rel.mujde` |
| 版本 | **1.1.0**（源码 `frida-modules-dl/mujde-app/`，APK `Mujde-v1.1.0-debug.apk`） |
| 引擎 | 真 Frida **17.16.4**（`su -c …/libfrida-inject.so -e -p PID -s script.js`） |
| 脚本目录 | `/data/data/com.rel.mujde/files/scripts/` |
| 日志目录 | `/data/data/com.rel.mujde/files/logs/`（应用内 Logs 页） |
| 暂存目录 | `/sdcard/Download/frida-modules/mujde/scripts/examples/` |
| 文档目录 | `frida-modules-dl/mujde/` |
| 绑定方式 | Mujde → Apps 勾选；保存时可自动写 LSPosed scope |
| 注入时机 | 目标 `Activity.onCreate`（默认每进程一次） |
| prefs | `/data/misc/apexdata/<GUID>/prefs/com.rel.mujde/mujde_prefs.xml` |

## 强制规范

### 1. 重入保护

```javascript
'use strict';
var FLAG = '__UNIQUE_SCRIPT_ID_V1__';
if (globalThis[FLAG]) {
  console.log('[TAG] skip re-inject');
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}
```

### 2. 等待 SO（用 setTimeout 递归）

```javascript
function waitModule(name, cb) {
  var m = Process.findModuleByName(name);
  if (m) { cb(m); return; }
  setTimeout(function () { waitModule(name, cb); }, 300);
}
```

### 3. 日志：优先 liblog

`console.log` 经 Mujde 经常进不了 logcat：

```javascript
function alog(msg) {
  var line = '[TAG] ' + msg;
  console.log(line);
  try {
    var fn = new NativeFunction(
      Module.findExportByName('liblog.so', '__android_log_write'),
      'int', ['int', 'pointer', 'pointer']
    );
    fn(4, Memory.allocUtf8String('TAG'), Memory.allocUtf8String(line));
  } catch (e) {}
}
```

排障同时看：`adb logcat -s "[Mujde]:D"`。

### 4. 短函数禁止 patch（已验证闪退）

函数体若只有 ~8 字节（如 `ldrb`+`ret`），**禁止** `Interceptor.replace`，也避免 `attach`。  
神秘元素消消乐：`get_detectCheat` RVA `0x6F65C0` 即此类 → `SIGSEGV` / `fault addr 0x11`。

先用 `xxd`/`IDA` 看长度，再决定能否 attach。

### 5. Unity 上禁止默认 Toast（已验证闪退）

在 `com.zcs.udpmysteryforest`：`Java.perform` + `Toast` 会 SIGSEGV。  
连通性脚本用 liblog，不要弹 Toast。其它 App 需单独验证。

### 6. 与 LSPilot 隔离

- Mujde = Frida API（`Process` / `Interceptor` …）
- LSPilot = Java / Rhino；`setInterval` 在 Rhino 里常未定义
- 禁止把 LSPilot 插件当 Mujde 脚本

## 部署流程

1. 写到暂存：`/sdcard/Download/frida-modules/mujde/scripts/examples/`
2. Root 拷到官方目录并 `chmod 644` + `chown` 为 Mujde uid
3. Mujde → Apps → 勾选 → **冷启动**目标 App
4. KernelSU：**超级用户允许 Mujde**（否则 `su` error=13）

```shell
su -c '
DST=/data/data/com.rel.mujde/files/scripts
UID=$(stat -c %u /data/data/com.rel.mujde)
cp /sdcard/Download/frida-modules/mujde/scripts/examples/YOUR.js "$DST/"
chmod 644 "$DST/YOUR.js"
chown $UID:$UID "$DST/YOUR.js"
'
```

## 推荐模板

优先复用已验证文件：

- `mysteryforest_learning_skeleton.js` — 无 hook、无 Java，已验证存活 + `MF_LEARN` 日志
- `mujde_hello.js` — 最小连通
- `mysteryforest_readonly_*.js` — 观测示例（按稳定性选用）

新建脚本时默认结构：

```javascript
'use strict';
var FLAG = '__SCRIPT_V1__';
var TAG = 'MF_TAG';
function alog(msg) { /* liblog 双写，见上 */ }
function waitModule(name, cb) { /* setTimeout 递归，见上 */ }
function main() {
  alog('start');
  waitModule('libil2cpp.so', function (il2cpp) {
    alog('base=' + il2cpp.base);
    // 确认函数足够长后，再 Interceptor.attach / 其它逻辑
  });
}
if (globalThis[FLAG]) { alog('skip'); } else { globalThis[FLAG] = true; setImmediate(main); }
```

## 排障速查

| 日志/现象 | 处理 |
|-----------|------|
| `Cannot run program "su": error=13` | KernelSU 授权 Mujde |
| 无 `about to frida-inject` | 作用域 / Apps 勾选 / 冷启动 |
| 有 inject + 进程立刻死 | 去掉短函数 patch / Toast 等危险写法；先跑 learning_skeleton 验证链路 |
| 进程活但无脚本日志 | 改用 liblog；确认脚本文件已更新到官方目录 |
| LSPilot `setInterval 未定义` | 那是 LSPilot 插件问题，与 Mujde 无关 |

取消注入恢复可玩：

```text
prefs 中 pref_app_scripts_map → {}
或 Mujde Apps 取消全部勾选后冷启动
```

## 文档位置（手机）

- `/sdcard/Download/frida-modules/mujde/00_快速开始.txt` 等 01–05
- 游戏镜像：`/sdcard/Android/media/com.zcs.udpmysteryforest/Analysis/mujde/`
- 本 Skill：`/sdcard/Download/Operit/skills/mujde_frida_script/SKILL.md`
