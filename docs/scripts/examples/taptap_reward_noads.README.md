# taptap_reward_noads.js

TapTap **SCE / Tap制造** 激励视频：不看广告直接发奖（Mujde / Frida）。

## 作用范围

| 覆盖 | 不覆盖 |
|------|--------|
| `com.taptap:urhox_sce_runtime*` 内所有 SCE 游戏 | 独立安装的第三方 APK |
| Lua `sdk:ShowRewardVideoAd` → 引擎回调 `{success:true}` | 插屏 / Banner |

说明：`urhox_sce_runtime` 上 Frida **Java 桥不可用**，本脚本走 `libUrhoXRuntime.so` native hook。

## 安装

1. 脚本：`/data/data/com.rel.mujde/files/scripts/taptap_reward_noads.js`
2. Mujde → Apps → `com.taptap` 勾选本脚本并保存（LSPosed 作用域含 `com.taptap`）
3. KernelSU 允许 `com.rel.mujde` root
4. 强制停止 TapTap → 重新打开 → 进游戏点广告奖励

## 日志

```text
adb logcat -s TAPAD_NOADS:I
```

成功时应出现：

```text
[TAPAD_NOADS] ShowRewardVideoAd replaced
[TAPAD_NOADS] callback stored
[TAPAD_NOADS] CallReward success
```

## 原理

`SDKLuaAPI::ShowRewardVideoAd`（RVA `0x17AA868`）本会 JNI 调起  
`EmbedUrhoXGameLauncher.showRewardVideoAd` → 播广告 → `postRewardVideoResult`。  
脚本替换该函数：写入 `__SDK_REWARD_VIDEO_AD_CALLBACK__` 后直接  
`CallRewardVideoAdCallback(success=true)`。
