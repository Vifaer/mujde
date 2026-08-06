# Mujde

LSPosed 模块形态的 **真 Frida 注入管理器**（包名 `com.rel.mujde`）。

维护本地 Frida JS 脚本库 → 按 App 勾选绑定 → 目标 `Activity.onCreate` 时以 root 调用 `libfrida-inject.so` 注入。

> 上游：https://github.com/mon231/com.rel.mujde  
> 本仓库：本地改造 fork（v1.1.2：作用域 / logcat / 脚本选择 UI 等修复）  
> LSPosed 模块页：https://modules.lsposed.org/module/com.rel.mujde/  
> Frida 版本钉死：**17.16.4**

脚本语法是 **标准 Frida GumJS**，不是 LSPilot 的 Rhino / Java 插件。

---

## 目录

- [前置依赖](#前置依赖)
- [安装与配置](#安装与配置)
- [日常使用](#日常使用)
- [脚本路径](#脚本路径)
- [与 LSPilot / KsuFrida 对比](#与-lspilot--ksufrida-对比)
- [脚本开发规范](#脚本开发规范)
- [给 AI 的开发提示词](#给-ai-的开发提示词)
- [故障排查](#故障排查)
- [编译](#编译)
- [更多文档](#更多文档)

---

## 前置依赖

按顺序装齐，缺一环就会「没注入」或 `su` 失败。

| 组件 | 要求 | 说明 |
|------|------|------|
| Root | **KernelSU** / Magisk | 本仓库以 KernelSU 实测为主 |
| Zygisk | ZygiskNext / NeoZygisk | LSPosed Zygisk 版依赖 |
| LSPosed | Zygisk 版 | 模块必须激活并勾选作用域 |
| Mujde APK | 本仓库构建产物 / Releases | 包名 `com.rel.mujde` |
| Root 授权 | **必须允许 Mujde** | KernelSU → 超级用户 → 永久允许 |

### KernelSU（关键）

注入命令本质是：

```text
su -c 'libfrida-inject.so -e -p <pid> -s <script.js>'
```

未授权时：Status 页显示 `Root (su): DENIED`，Logs 出现 `Cannot run program "su"` / `error=13`。

**处理：** KernelSU → 超级用户 → 允许 `com.rel.mujde`（永久）→ 冷启动目标 App。

### LSPosed

1. 模块 → Mujde **打开**
2. 作用域勾选目标包（例：`com.zcs.udpmysteryforest`）  
   - v1.1.x 在 Apps 勾选脚本并保存时，可自动 root 写入 scope（Status 开关「Auto-apply LSPosed scope」）
3. 建议打开一次 Mujde，确认通知 **Injection listener active**

---

## 安装与配置

### 1. 安装 APK

```bash
# 自行编译后
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 或使用你本地打包好的版本
adb install -r Mujde-v1.1.2-debug.apk
```

### 2. 一键检查清单

- [ ] KernelSU 已允许 Mujde
- [ ] LSPosed 已启用 Mujde + 目标包在作用域内
- [ ] Mujde 打开过至少一次（保活通知）
- [ ] Repository / scripts 目录里有 `.js`
- [ ] Apps 里已勾选脚本并保存
- [ ] **强制停止**目标 App 后冷启动（不要只是切后台）

### 3. 确认注入成功

```bash
adb logcat -s "[Mujde]:D"
# 或 Mujde → Logs → Pull logcat
adb shell "pidof <目标包名>"
```

成功标志：出现 `about to frida-inject ...` 且 `inject exit=0`，进程数秒后仍存活。

> `console.log` 经 Mujde/`frida-inject` **经常进不了 logcat**。脚本里请用 `liblog`（见下文）。

---

## 日常使用

1. **Repository / Scripts**：新建、导入、下拉刷新脚本  
2. **Apps**：已绑定包置顶 → 勾选脚本并保存（可触发作用域写入）  
3. **Logs**：查看注入 exit code / 拉 logcat  
4. **Status**：Frida 版本、su、脚本数、上次注入摘要  

注入时机：`Activity.onCreate`；默认 **每个进程只注入一次**（Status 可关）。

---

## 脚本路径

### 真正被注入的位置（官方运行目录）

```text
/data/data/com.rel.mujde/files/scripts/
```

- 通常需 Root 才能直接读写  
- **只丢文件不会绑定**，必须在 Apps 勾选  
- 扩展名 `.js`，文件名建议 ASCII  

### Root 拷贝示例

```sh
SRC=/sdcard/Download/frida-modules/mujde/scripts/examples
DST=/data/data/com.rel.mujde/files/scripts
MUJDE_UID=$(stat -c %u /data/data/com.rel.mujde)

mkdir -p "$DST"
cp "$SRC/mysteryforest_learning_skeleton.js" "$DST/"
chmod 644 "$DST"/*.js
chown "$MUJDE_UID:$MUJDE_UID" "$DST"/*.js
```

### 仓库内示例脚本

见 [`docs/scripts/examples/`](docs/scripts/examples/)：

| 脚本 | 用途 |
|------|------|
| `mysteryforest_learning_skeleton.js` | **推荐首跑**：无 hook / 无 Java，验证存活 + `MF_LEARN` |
| `mujde_hello.js` | 最小连通 |
| `mysteryforest_readonly_*.js` | 只读观测（注意稳定性） |

### 偏好设置（一般用 UI，无需手改）

```text
/data/misc/apexdata/<GUID>/prefs/com.rel.mujde/mujde_prefs.xml
```

键 `pref_app_scripts_map` 示例：

```json
{"com.zcs.udpmysteryforest":["mysteryforest_learning_skeleton.js"]}
```

---

## 与 LSPilot / KsuFrida 对比

| 维度 | **Mujde** | LSPilot | KsuFrida / ZygiskFrida |
|------|-----------|---------|-------------------------|
| 形态 | LSPosed 模块 + App | LSPosed 模块 + 插件目录 | Magisk/KSU Zygisk 模块 |
| 脚本 | **真 Frida JS** | Java / Rhino（**不是** Frida） | 真 Frida Gadget |
| 路径 | `/data/data/com.rel.mujde/files/scripts` | `.../LSPilot/Plugin/` | 模块配置目录 |
| 注入 | root 调 `frida-inject`，跟 Activity | Xposed Hook | Zygisk **早注入** |
| IL2CPP | 可以（注意短函数 / Java 坑） | **看不到** `libil2cpp` | 可以 |
| 重复注入 | 默认每进程一次（可关） | 按插件生命周期 | 通常进程一次 |
| Root | **必须授权 Mujde su** | 通常不需要 su 子进程 | 模块级 |

### 建议分工

- **Java 层探针** → LSPilot  
- **IL2CPP / 真 Frida** → Mujde 或 PC `frida -U -l`  
- **Mujde `su` 搞不定、要更早注入** → 优先 **KsuFrida / ZygiskFrida**  
- **禁止混写 API**（Frida ≠ Rhino；`setInterval` 在 LSPilot Rhino 里常未定义）

---

## 脚本开发规范

### 1. 必须可重入

每次 `Activity.onCreate` 可能再注入：

```javascript
'use strict';
var FLAG = '__MY_SCRIPT_V1__';
if (globalThis[FLAG]) {
  console.log('[skip] already installed');
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}
```

### 2. 等待动态库（用 setTimeout 递归）

```javascript
function waitModule(name, cb) {
  var m = Process.findModuleByName(name);
  if (m) { cb(m); return; }
  setTimeout(function () { waitModule(name, cb); }, 300);
}
```

### 3. 日志：优先 liblog

```javascript
function alog(msg) {
  var line = '[MYTAG] ' + msg;
  console.log(line);
  try {
    var fn = new NativeFunction(
      Module.findExportByName('liblog.so', '__android_log_write'),
      'int', ['int', 'pointer', 'pointer']
    );
    fn(4, Memory.allocUtf8String('MYTAG'), Memory.allocUtf8String(line));
  } catch (e) {}
}
```

过滤：`adb logcat -s MYTAG:I` 以及 `adb logcat -s "[Mujde]:D"`。

### 4. 短函数禁止 patch（会闪退）

函数体若只有约 8 字节（如 `ldrb` + `ret`），**禁止** `Interceptor.replace`，也尽量不要 `attach`——Frida 写跳转会踩坏相邻函数 → `SIGSEGV`。

先用 IDA/`xxd` 看长度，再决定能否 attach。

### 5. Unity 上默认不要 Toast

在部分 Unity IL2CPP 包上，`Java.perform` + `Toast` 会 **SIGSEGV**。连通性脚本用 liblog，不要弹 Toast。其它 App 需单独验证。

### 6. IL2CPP 建议流程

1. Il2CppDumper 取 arm64 RVA  
2. `waitModule('libil2cpp.so')`  
3. 优先 `Interceptor.attach`；确认函数足够长再 patch  
4. 实例方法 `args[0]` = this；`long` 返回值用 `retval` / `toInt64()`  

### 7. 不要做的事

- 把 LSPilot `main.java` / Rhino 逻辑原样粘到 Mujde  
- 对极短函数 `replace` / `attach`  
- 用 Toast 当「加载成功」信号（Unity 实测危险）  
- 无重入保护地重复 attach  
- 指望 `//Mujde:{packages:...}` 文件头自动绑定（官方不认）

---

## 给 AI 的开发提示词

写 / 改 Mujde 脚本时，可把下面整段交给 Cursor / Claude / ChatGPT（或直接使用仓库内 [`operit-skill/SKILL.md`](operit-skill/SKILL.md)）：

```text
你在为 Mujde（com.rel.mujde）编写真 Frida GumJS 脚本，不是 LSPilot Rhino。

运行时事实：
- 引擎：Frida 17.16.4，经 su -c libfrida-inject.so -e -p PID -s script.js
- 脚本目录：/data/data/com.rel.mujde/files/scripts/
- 绑定：Mujde Apps 勾选；保存时可自动写 LSPosed scope
- 注入时机：目标 Activity.onCreate（默认每进程一次）
- KernelSU 必须授权 Mujde，否则 su error=13

强制规范：
1. 必须 globalThis 重入保护 + setImmediate(main)
2. 等 SO 用 setTimeout 递归，不要假设 setInterval 一定存在
3. 日志优先 __android_log_write（liblog），console.log 常进不了 logcat
4. 短函数（约 8 字节）禁止 Interceptor.replace/attach
5. Unity IL2CPP 上默认禁止 Java.perform + Toast
6. 禁止混用 LSPilot Rhino API

部署：写到 scripts 目录 → Apps 勾选 → 冷启动目标 App。
排障：无 about to frida-inject → 查作用域/勾选；su DENIED → KSU 授权；
注入后闪退 → 先跑 learning_skeleton，去掉短函数 patch/Toast。
若 Mujde su 链路搞不定，可建议改用 KsuFrida/ZygiskFrida 做早注入。
```

---

## 故障排查

| 现象 | 处理 |
|------|------|
| 无 `about to frida-inject` | 作用域 / Apps 勾选 / 打开 Mujde 保活 / **冷启动** |
| `Cannot run program "su": error=13` | KernelSU **授权 Mujde** |
| `inject exit!=0` | 看 Logs 页输出；检查脚本路径与 frida-inject |
| 注入后进程立刻死 | 去掉短函数 patch / Toast；先跑 `learning_skeleton` |
| 进程活但无脚本日志 | 改用 liblog；确认官方 scripts 目录文件已更新 |
| 脚本列表看不到新文件 | `chown` 为 Mujde uid，`chmod 644`，强制停止 Mujde 再开 |
| LSPilot `setInterval 未定义` | 那是 LSPilot 问题，与 Mujde 无关 |

取消注入恢复可玩：Apps 取消全部勾选，或 prefs 中 `pref_app_scripts_map` → `{}`，再冷启动。

---

## 编译

### 环境

- JDK（建议 17/21）+ Android SDK  
- `local.properties` 配置 `sdk.dir=...`（勿提交）  
- 联网以下载 Frida `frida-inject` 二进制  

### 构建

```bash
git clone https://github.com/Vifaer/mujde.git
cd mujde
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release 可参考 [`BUILD.md`](BUILD.md)。

> GitHub Actions：见 [`.github/workflows/build.yml`](.github/workflows/build.yml)。

---

## 更多文档

| 文档 | 内容 |
|------|------|
| [`docs/zh/`](docs/zh/) | 完整中文手册（快速开始 / 路径 / 规范 / 对比 / 排障） |
| [`docs/scripts/examples/`](docs/scripts/examples/) | 示例脚本 |
| [`operit-skill/SKILL.md`](operit-skill/SKILL.md) | Operit / Agent 用的脚本开发 Skill |
| [`BUILD.md`](BUILD.md) | 编译细节 |
| [`SOURCE_URL`](SOURCE_URL) | 上游地址 |

---

## License / 致谢

基于 [mon231/com.rel.mujde](https://github.com/mon231/com.rel.mujde) 改造。请遵循上游许可；仅用于合法授权的安全研究与调试。
