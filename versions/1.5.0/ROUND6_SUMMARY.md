# BetterSingleCore — 第 6 轮总结（v1.5 · PlugMan 热加载/热卸载）

日期：2026-11-17  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 需求

> 接着做热加载热卸载支持，记得写好 md  
> （正式服用 PlugManX 热切换，不能留下 NMS 命令树死引用）

## 2. 1.5.0 改动

| 模块 | 行为 |
|------|------|
| **ParticleCommandOffload.uninstall()** | 遍历已保存的 `originals`，把 Brigadier `particle` 叶子 `command` 字段**还原为原版** |
| **install()** | 打补丁前先备份原版 Command；识别并跳过本插件旧 lambda（防把补丁当原版存） |
| **onDisable()** | `stopModules()` + **`particleOffload.uninstall()`** — PlugMan unload 的关键路径 |
| **reloadEverything()** | 先 uninstall 再按新配置重建 offload（threshold/enabled 热更安全） |
| **statusLine** | 增加 `originals=N`（已备份待还原的叶子数） |

### 为什么必须 uninstall

PlugMan unload 会丢掉插件 ClassLoader。若 Brigadier 节点仍指向旧 lambda：

- 再执行 `/particle` → `ClassNotFoundException` / 假成功  
- 旧 ClassLoader 无法 GC → 内存泄漏  

`onDisable` 还原原版后，节点不再引用插件类，热卸载才安全。

## 3. 实测（Leaves 1.21.11 + PlugManX 3.0.4）

脚本：`BetterSingleCore/test_hotreload.ps1`  
结果：`test_server/hotreload_result.txt` → **FAIL_COUNT=0，FINAL_OK=True，EXIT=0**

| 检查项 | 结果 |
|--------|------|
| 初始安装 | `fan-out installed` + `installed=true leaves=7 originals=7` |
| **unload ×3** | `has been unloaded` + **`restored 7 vanilla leaf node(s)`** ×3 |
| unload 后 `/bsc` | 未知命令（插件已卸） |
| unload 后 `/particle` | **无 ClassNotFound**（走原版） |
| **load ×3** | `loaded and enabled` + **`fan-out installed on 7 leaf`** ×3 |
| load 后 status | `installed=true leaves=7 originals=7` |
| load 后 particle | **`invocations` 0→1**（命令树上仍是可执行补丁） |
| ClassLoader 错误 | **0**（全程无 CNFE/NCDFE） |
| 插件列表 | PlugManX + BetterSingleCore 均在 |

关键日志（每轮重复 3 次）：

```text
[BetterSingleCore] Disabling BetterSingleCore v1.5.0
[BetterSingleCore] [BSC] ParticleCommand offload uninstalled; restored 7 vanilla leaf node(s)
[PlugMan] BetterSingleCore has been unloaded.
...
[PlugMan] BetterSingleCore has been loaded and enabled.
[BetterSingleCore] [BSC] ParticleCommand parallel fan-out installed on 7 leaf node(s)
[Particle] installed=true leaves=7 originals=7 invocations=1 ...
```

## 4. 正式服用法（PlugManX）

```text
# 放入 jar 后（可选热装）
plugman load BetterSingleCore

# 热卸载（会还原原版 particle）
plugman unload BetterSingleCore

# 热重载
plugman reload BetterSingleCore
# 等价于 unload + load
```

| 操作 | 建议 |
|------|------|
| 首次上正式服 | **仍建议重启**（最稳） |
| 后续换 jar / 开关 | **1.5.0 + PlugManX unload/load/reload 可用** |
| 只改 config.yml | `/bsc reload`（不必动 ClassLoader） |

## 5. 交付与回滚

```
versions/
  1.0.0 … 1.4.0  （见各 ROUND）
  1.5.0/  ← 本轮（源码 + jar + 测试 + 本文）
BetterSingleCore/
  dist/BetterSingleCore-1.5.0.jar
  test_hotreload.ps1
```

部署：

```text
copy BetterSingleCore\dist\BetterSingleCore-1.5.0.jar → 服务端\plugins\
# 需要 PlugManX（或同类 disablePlugin 管理器）
plugman load BetterSingleCore   # 或重启
/bsc particle                   # installed=true leaves=7 originals=7
```

回滚：`versions/1.4.0`（**无 uninstall，勿用 PlugMan 热卸**）。

## 6. 边界

- 依赖 **onDisable 被调用**（PlugMan 正常 unload 会调）；进程 `kill -9` 无法还原  
- 还原的是**安装前**的 Command；若上次异常退出未还原，本次 install 会跳过「本插件旧 lambda」当原版保存（不把补丁写回 originals）  
- 并行 particle 语义与 1.4.0 相同，未改  
- 仍不改 AI、不暂停模块、不挪 execute 世界逻辑  

## 7. 测试脚本

```powershell
powershell -File BetterSingleCore\test_hotreload.ps1 -Cycles 3
# 依赖：plugins/BetterSingleCore-1.5.0.jar + PlugManX-3.0.4.jar
```

---

状态：**v1.5.0 PlugMan 3 轮热卸/热装实测通过（fail=0），已归档 versions/1.5.0**
