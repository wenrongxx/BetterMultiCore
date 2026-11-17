# BetterSingleCore — 第 7 轮总结（v1.6 · 按正式服 Spark 继续压主线程 + 多核）

日期：2026-11-17  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`  
输入：用户 CPU 图（i7-4790K 单核偏满）+ Spark `https://spark.lucko.me/jUnhP35Cci`

## 1. 正式服 Spark 结论（61s，1 玩家，Leaves 1.21.11）

| 指标 | 值 |
|------|-----|
| TPS | 19.88 / 19.92 / 19.97 |
| **MSPT** | **median 44.3 · p95 55.9 · mean 45.2**（贴 50ms 上限） |
| 实体 | **1273**（sheep 220、villager 126、enderman 96…） |
| 配置 | view-distance **12** · simulation-distance **10** · monsters 70 |
| BetterSingleCore 自身 | **~0.1%** 主线程（36ms / 61s） |

### 热点（主线程）

| 占比 | 内容 | 能否用纯插件挪核 |
|------|------|------------------|
| **~22%** | **实体选择器** `@e`/`@a`：`EntitySelector` / `EntityLookup.getEntityStatus` / `ExecuteCommand` / `scores=` 过滤 | **不能**（改语义=破坏原版） |
| ~3–4% | 村民 Brain AI、randomTick、NaturalSpawner、TrackedEntity | **不能**（你禁止动 AI） |
| ~0.1% | BetterSingleCore | 已可忽略 |

**结论**：正式服瓶颈是 **数据包/插件每 tick 的 `execute as @e[…]` 扫描**，不是本插件自己。  
热加载本身不降 MSPT——要降的是主线程选择器与实体 tick 成本。

### 数据包侧（GravestoneDungeons + 同类）

| 项 | 数量 |
|----|------|
| mcfunction | 1285 |
| `particle` 行 | **~30160** |
| `execute` 行 | **~13326** |
| 含 `@e`/`@a` 选择器的行 | **~6756**（粗计） |

spark 里 `ExecuteCommand` + selector 栈与之吻合：**每 tick 全实体过滤** 才是 22% 的来源。

## 2. v1.6 改动（多核 + 减主线程）

| 模块 | 改动 | 目的 |
|------|------|------|
| **pathwarm 扫描** | 新增 `pathwarm.scan-distance`（默认 **32**，小于 player-distance 48） | 缩小 AABB，降低 `getNearbyEntities` / EntityLookup 成本 |
| **去掉双重扫描** | 空结果时**不再**再做一次 48 格 `world.getNearbyEntities` | 原来空结果会扫两遍大范围 |
| **高负载拉长扫描** | `adaptive.high-load-pathwarm-interval-multiplier`（默认 **2**） | 高 MSPT 时实体扫描隔轮执行；**drain 仍每 tick 跑，绝不停模块** |
| **空闲多核预热** | `preload.idle-radius-bonus` 默认 **1→2** | 空闲时 worker 多暖一圈区块（vd=12 正式服更有用） |
| **Players.all** | 仅在 online+bots 为空时才扫全部 world | 避免每 cycle 全世界 `getPlayers()` |
| **状态输出** | PathWarm 显示 `scanR=32` | 便于 `/bsc pathwarm` 核对 |

仍保留：**不改 AI、不暂停模块、particle 扇出、PlugMan 热卸载（1.5 能力）**。

## 3. 实测（test_server · 1.6.0）

| 检查项 | 结果 |
|--------|------|
| 加载/启用 | `BetterSingleCore 1.6.0` OK |
| particle 安装 | `installed on 7 leaf` · `originals=7` |
| PathWarm | `scanR=32` · cycles 正常 |
| Preload | workers=12 · fail=0 |
| PlugMan unload/load | `restored 7 vanilla` → 重装 `fan-out installed` · **无 ClassNotFound** |
| 插件异常 | **无**（数据包 `bsc_stress` 曾有 BOM 导致 function 解析失败，已去 BOM） |

## 4. 对「22% 选择器」的诚实边界

| 能做 | 不能做（纯插件） |
|------|------------------|
| 减少**本插件**实体扫描 | 把 `execute as @e` 拆到其他核 |
| 空闲时多核预加载块 | 缓存选择器结果跨 tick（实体在动，会错） |
| particle 多观众发送并行 | 关村民 AI / 休眠实体（你已禁止） |
| 高负载时 pathwarm 隔轮扫 | 改写数据包语义 |

**数据包侧建议（服务端配置/数据包 OPT，非本插件）：**

1. 把每 tick 全量 `@e[type=…]` 改成 **tag + limit/sort=nearest**，或合并到较少入口函数  
2. `scores=` 过滤尽量少用；能用 tag 就用 tag  
3. 无战斗时的 VFX `particle` 合并/降频（3 万行 particle 是发送与解析双负担）  
4. `simulation-distance` 10 + 1273 实体：非玩家区可考虑降到 6–8（配置层面）  
5. monsters spawn-limit 70 偏高，结合 NaturalSpawner 热点评估  

## 5. 多核分工（1.6 现状）

```text
主线程          → tick / 实体 AI / execute·选择器（原版，不可挪）
BSC-Worker ×N   → 区块规划与异步加载（preload + pathwarm 邻域）
PathWarm 半池   → 生物邻域区块 kick
Netty / parallel→ /particle 多观众 send（≥ threshold）
```

CPU 图上「一核高、其余低」与 spark 一致：**选择器在主线程**；worker 主要吃在区块 IO，选择器负载重时 worker 会显得闲——这是原版约束，不是插件没开多核。

## 6. 交付与回滚

```
versions/1.6.0/
  BetterSingleCore-1.6.0.jar
  BetterSingleCore/          # 源码
  ROUND7_SUMMARY.md
  VERSION.md
  test_hotreload.ps1
```

部署：

```text
copy BetterSingleCore\dist\BetterSingleCore-1.6.0.jar → plugins\
/bsc status
/bsc pathwarm    # 应见 scanR=32
```

配置增量（可选，写进正式服 config.yml）：

```yaml
pathwarm:
  scan-distance: 24          # 实体很多时可再降到 24
preload:
  idle-radius-bonus: 2
adaptive:
  high-load-pathwarm-interval-multiplier: 2
```

回滚：`versions/1.5.0`（热卸载仍在，无 scan-distance）。

## 7. 测试

```powershell
powershell -File BetterSingleCore\build.ps1 -Version 1.6.0
powershell -File BetterSingleCore\test_hotreload.ps1 -Cycles 2
```

---

状态：**v1.6.0 实测通过（含 PlugMan 热切换）；针对 Spark 22% 选择器给出数据包 OPT 边界说明，已归档 versions/1.6.0**
