# BetterSingleCore — 第 3 轮总结（v1.2 · 针对高频命令数据包）

日期：2026-11-16  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`  
输入：`GravestoneDungeonsDATA_PACK_1.7.zip`

## 1. 数据包分析

| 项 | 结果 |
|----|------|
| mcfunction 文件 | **1285** 个 |
| 非注释命令行 | **约 46506** |
| 每 tick 入口 | `gd_main:exe` + `gd_enchantment:exe` + `gd_boss:exe` |
| 热点命令 | **particle ~30160**、execute ~13326、scoreboard ~917 |
| 模式 | 大量 `execute as @e[...]` 全实体扫描 + VFX 粒子 |

**结论**：mcfunction **只能在主线程执行**，插件无法把命令本身并行到其他核。  
可优化的是：

1. **命令风暴时让出主线程**（本插件自己的重活暂停）  
2. **空闲时用其他核做区块 IO/预热**（地牢结构加载不卡主线程）  
3. **尽量降低插件自身主线程占用**

## 2. v1.2 改动

| 模块 | 行为 |
|------|------|
| **Adaptive yield** | MSPT ≥ `yield-mspt` 连续 N tick → **让路**：暂停 pathwarm、暂停/降频 preload |
| **恢复** | MSPT ≤ `resume-mspt` 连续 M tick → **满速多核预热** |
| **ParallelChunkEngine** | 空闲时 radius + `idle-radius-bonus`；让路时可完全不 kick |
| **PathChunkWarmer** | 让路时整段跳过（`yieldSkip` 计数） |
| **TickMonitor** | 记录 `pluginKick` 主线程耗时 + yield 状态 |
| **不改 AI** | 依旧无 setAware/setAI |

配置要点：

```yaml
adaptive:
  enabled: true
  yield-mspt: 25.0        # 命令重载阈值
  yield-after-ticks: 8
  resume-mspt: 12.0
  resume-after-ticks: 40
  pause-pathwarm: true
  preload-while-yielding: false
```

## 3. 实测（test_server + GravestoneDungeons + bsc_stress）

环境：Leaves 1.21.11，12 逻辑 CPU，离线 flat 世界。

| 检查项 | 结果 |
|--------|------|
| 插件 1.2.0 加载/启用 | 通过 |
| GravestoneDungeons 数据包 | `Found new data pack file/GravestoneDungeons` 并启用 |
| 多核预热 | **started=done≈3600+，fail=0，batch 多次** |
| 假人加入 | 成功 |
| **yield ON** | `Command-load yield ON (MSPT …)` → Preload `yield=true started=0`，PathWarm `yieldSkip↑` |
| **yield OFF** | `Command-load yield OFF` → Preload 恢复 kick，PathWarm 恢复扫描 |
| 插件 Exception | **无**（数据包无关的测试函数语法错误已排除） |

说明：正式阈值 25ms 需真实地牢/大量粒子场景；测试用较低阈值验证了状态机 ON/OFF 全路径。

## 4. 交付与回滚

```
versions/
  1.0.0/  AI 休眠（勿用于刷怪塔）
  1.1.0/  多核并行（无 adaptive）
  1.2.0/  多核 + 命令风暴让路 ← 本轮
```

部署：

```text
copy versions\1.2.0\BetterSingleCore-1.2.0.jar → 服务端\plugins\
/bsc status
/bsc adaptive
```

## 5. 边界（诚实）

- **能**：命令重载时插件让路，减少与 mcfunction 抢主线程；空闲时其他核预热区块  
- **不能**：把 `execute/particle` 本身拆到多核（需服务端分叉如 Folia）  
- 数据包侧仍建议保留其已有 `[OPT]`（合并 unless、去掉每 tick data get）  

## 6. 测试脚本

- `BetterSingleCore/test_command_storm.ps1` — 数据包 + 风暴  
- `BetterSingleCore/test_yield.ps1` — yield ON/OFF  

---
状态：**v1.2.0 实测通过，已归档 versions/1.2.0**
