# BetterSingleCore

Minecraft **Leaves / Paper 1.21.x** 插件：在**不修改原版语义、不关闭实体 AI** 的前提下，把可并行的工作摊到其他 CPU 核，降低主线程压力。

- **多核区块预热**（preload / pathwarm）— 规划与异步加载走 worker 线程池  
- **`/particle` 发送扇出** — 多观众时并行 `send`（参数、viewers、force、成败文案与原版一致）  
- **高负载 spread** — 只缩小主线程批量 / 拉长本插件扫描间隔，**不暂停模块**  
- **PlugMan 热卸载** — `onDisable` 还原原版 particle 命令树，避免 ClassLoader 泄漏  

> **不做的事**：不 `setAware`/`setAI` 休眠生物；不把 `execute` 世界状态逻辑挪出主线程（会破坏原版一致性）。

## 版本

| 版本 | 说明 |
|------|------|
| 1.0.0 | AI 休眠（**会破坏刷怪塔，勿用**） |
| 1.1.0 | 多核区块预热（无 AI 休眠） |
| 1.2.0 | 高 MSPT 让路（会暂停模块，**已弃用**） |
| 1.3.0 | spread：永不暂停，只缩预算 |
| 1.4.0 | + particle 多观众并行扇出 |
| 1.5.0 | + PlugMan 热加载/热卸载（还原 Brigadier 补丁） |
| **1.6.0** | 更轻 pathwarm 扫描、高负载隔轮扫、空闲加大预热半径 |

完整源码与 jar 见仓库 [`versions/`](versions/)；每轮说明见根目录 `ROUND*_SUMMARY.md`。

## 构建

无 Maven/Gradle，需 **JDK 21**：

```powershell
# 需要 leaves-api / 服务端 jar 与 brigadier 在约定路径（见 build.ps1）
powershell -File BetterSingleCore\build.ps1 -Version 1.6.0
# 输出: BetterSingleCore\dist\BetterSingleCore-1.6.0.jar
```

## 部署

1. 将 jar 放入服务端 `plugins/`  
2. 重启，或（已装 PlugManX 等）：`plugman load BetterSingleCore`  
3. 游戏内：`/bsc status`、`/bsc particle`、`/bsc pathwarm`  

### 配置要点（`config.yml`）

```yaml
particle-parallel:
  enabled: true
  parallel-viewers-threshold: 4   # 观众 ≥ N 才并行发送

pathwarm:
  scan-distance: 32               # 发现生物的 AABB 半径（实体多时可 24）
  player-distance: 48

preload:
  idle-radius-bonus: 2            # 空闲时多暖一圈区块

adaptive:
  high-load-mspt: 25.0
  high-load-pathwarm-mobs: 16     # 高负载时每轮扫描上限（>0，不暂停）
  high-load-pathwarm-interval-multiplier: 2
```

### 常用命令

| 命令 | 作用 |
|------|------|
| `/bsc status` | 总览 |
| `/bsc reload` | 重载配置（同实例，安全） |
| `/bsc enable` \| `disable` | 开关本插件模块 |
| `/bsc particle` | particle 补丁状态 |
| `/bsc preload` \| `pathwarm` \| `monitor` | 各模块统计 |

PlugMan：

```text
plugman unload BetterSingleCore
plugman load BetterSingleCore
plugman reload BetterSingleCore
```

## 边界（请阅读）

- **实体选择器 / `execute as @e` 等世界逻辑必须留在主线程**；本插件无法在不改原版语义的前提下把它们拆到其他核。  
- 收益主要在：**区块 IO 并行、particle 发送扇出、减少插件自身主线程占用**。  
- 若 spark 显示大量 `@e` 扫描，需从**数据包 / 命令写法**优化（tag、limit、合并入口），不是关 AI。  

## 回滚

各版本 jar 与源码均在 `versions/<semver>/`，直接换回对应 jar 即可。

## 许可

按仓库所有者声明使用；第三方服务端与数据包版权归其各自作者。
