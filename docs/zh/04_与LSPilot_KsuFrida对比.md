# Mujde vs LSPilot vs KsuFrida

文档更新：2026-07-31

| 维度 | Mujde | LSPilot | KsuFrida / ZygiskFrida |
|------|-------|---------|-------------------------|
| 形态 | LSPosed 模块 + App | LSPosed 模块 + 插件目录 | Magisk/KSU Zygisk 模块 |
| 脚本 | **真 Frida JS** | Java / Rhino（非 Frida） | 真 Frida Gadget |
| 路径 | `/data/data/com.rel.mujde/files/scripts` | `.../LSPilot/Plugin/` | 模块配置目录 |
| 注入 | root 调 `frida-inject`，跟 Activity | Xposed Hook | Zygisk 早注入 |
| IL2CPP | 可（注意短函数/Java 坑） | **看不到** libil2cpp | 可 |
| 重复注入 | 每个 Activity.onCreate | 按插件生命周期 | 通常进程一次 |
| Root | **必须授权 Mujde su**（KSU） | 通常不需要 su 子进程 | 模块级 |

## 本机建议分工

- Java 层探针：LSPilot `MysteryForest-Probe`  
- Mujde 连通 / 学习：`mysteryforest_learning_skeleton.js`  
- IL2CPP 只读：Mujde readonly 示例或 PC `frida -U -l`  
- Mujde `su` 搞不定时：优先 KsuFrida  

**不要混写 API**（Frida ≠ Rhino）。
