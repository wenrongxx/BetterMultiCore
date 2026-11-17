# BetterMultiCore

Minecraft **Leaves / Paper 1.21.x** 插件：在**不修改原版语义、不关闭实体 AI** 的前提下，把可并行的工作摊到其他 CPU 核，降低主线程压力。

- **多核区块预热**（preload / pathwarm）— 规划与异步加载走 worker 线程池
- **`/particle` 发送扇出** — 多观众时并行 `send`（参数、viewers、force、成败文案与原版一致）
- **高负载 spread** — 只缩小主线程批量 / 拉长本插件扫描间隔，**不暂停模块**
- **PlugMan 热卸载** — `onDisable` 还原原版 particle 命令树，避免 ClassLoader 泄漏

> **不做的事**：不 `setAware`/`setAI` 休眠生物；不把 `execute` 世界状态逻辑挪出主线程（会破坏原版一致性）。

> **更名说明**：本项目原名 BetterSingleCore，已更名为 **BetterMultiCore**。历史 jar 仍可在 [Releases](https://github.com/wenrongxx/BetterMultiCore/releases) 按旧 tag 查看；当前品牌从 **1.0.0** 起算。

## 下载

见 [Releases](https://github.com/wenrongxx/BetterMultiCore/releases)。  
源码归档见 [`versions/`](versions/)（含 [版本索引](versions/README.md)）。

## 版本历史（BetterMultiCore）

### 1.0.0（当前 · 新名首发）

功能继承原 BetterSingleCore 1.6.x，完成品牌与包名迁移：

- 多核 preload / pathwarm，高负载 spread（不暂停）
- `/particle` 多观众并行扇出（原版语义）
- PlugMan 热卸载（还原 Brigadier particle 补丁）
- pathwarm `scan-distance`、高负载隔轮扫描、空闲加大预热半径
- 包名 `com.bettermulticore` · 命令 **`/bmc`** · 权限 `bettermulticore.admin` · 线程前缀 `BMC-`

### 历史（原 BetterSingleCore，tag 保留）

| 旧 tag | 要点 |
|--------|------|
| 1.0.0–1.6.0 | 见各 tag 的 release notes；1.0.0 AI 休眠勿用，1.2.0 暂停策略已弃用，1.3+ 为 spread |

## 构建

无 Maven/Gradle，需 **JDK 21**：

```powershell
# 需要 leaves-api / 服务端 jar 与 brigadier 在约定路径（见 build.ps1）
powershell -File BetterMultiCore\build.ps1 -Version 1.0.0
# 输出: BetterMultiCore\dist\BetterMultiCore-1.0.0.jar
```

## 部署

1. 将 jar 放入服务端 `plugins/`
2. 重启，或（已装 PlugMan 等）：`plugman load BetterMultiCore`
3. 游戏内：`/bmc status`、`/bmc particle`、`/bmc pathwarm`

配置目录为 `plugins/BetterMultiCore/`（与旧名目录不通用，需复制或改 `config.yml`）。

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
| `/bmc status` | 总览 |
| `/bmc reload` | 重载配置（同实例，安全） |
| `/bmc enable` \| `disable` | 开关本插件模块 |
| `/bmc particle` | particle 补丁状态 |
| `/bmc preload` \| `pathwarm` \| `monitor` | 各模块统计 |

别名：`/multicore`

PlugMan：

```text
plugman unload BetterMultiCore
plugman load BetterMultiCore
plugman reload BetterMultiCore
```

## 性能对照（实测摘要）

在同一固定负载下做了四组对照（无插件 / 仅插件 × 无附加内容 / 有附加内容），条件示例：Leaves 1.21.x、view/sim distance=4、约 4 名真人客户端、约 19 实体；预热后 **spark profiler 30s**，指标为 spark 的 MSPT/TPS（非插件自报）。

### MSPT（ms，30s 窗口）

| 场景 | 中位数 | p95 | **mean** | max | TPS 1m |
|------|--------|-----|----------|-----|--------|
| 无插件 · 无附加 | 1.07 | 4.01 | **2.02** | 366 | 19.92 |
| 仅插件 · 无附加 | 1.01 | 3.35 | **1.35** | 17 | 20.06 |
| 无插件 · +附加 | 1.20 | 4.46 | **2.21** | 349 | 19.95 |
| 插件 · +附加 | 0.97 | 3.19 | **1.34** | 27 | 20.08 |

### 插件带来的变化（同负载）

| 对比 | mean | p95 | max |
|------|------|-----|-----|
| 开插件（无附加） | **−33%**（2.02→1.35） | −16% | **−95%**（366→17） |
| 开插件（+附加） | **−39%**（2.21→1.34） | **−28%** | **−92%**（349→27） |

### 结论（诚实）

1. 该固定负载 + 多真人条件下，开插件 **mean MSPT 约降 1/3**，p95 更稳，加入尖峰从数百 ms 降到约 20ms 量级。
2. 四组 TPS 都贴 20——差异在 **MSPT 余量**，不是「从 15 TPS 救回来」。
3. 真正收益场景是**探索加载 / 高实体 / 高频多观众 particle**，不是空载。
4. 主线程被选择器/`execute` 打满时，纯插件仍无法把世界逻辑挪走。

## 边界（请阅读）

- **实体选择器 / `execute as @e` 等世界逻辑必须留在主线程**。
- 收益主要在：**区块 IO 并行、particle 发送扇出、减少插件自身主线程占用**。
- 若 spark 显示大量 `@e` 扫描，需从**命令/资源写法**优化（tag、limit、合并入口），不是关 AI。

## 回滚

在 [Releases](https://github.com/wenrongxx/BetterMultiCore/releases) 或 `versions/<semver>/` 换回对应 jar 即可。

## 许可

[MIT](LICENSE)
