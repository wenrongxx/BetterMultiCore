# BetterSingleCore

Minecraft **Leaves / Paper 1.21.x** 插件：在**不修改原版语义、不关闭实体 AI** 的前提下，把可并行的工作摊到其他 CPU 核，降低主线程压力。

- **多核区块预热**（preload / pathwarm）— 规划与异步加载走 worker 线程池  
- **`/particle` 发送扇出** — 多观众时并行 `send`（参数、viewers、force、成败文案与原版一致）  
- **高负载 spread** — 只缩小主线程批量 / 拉长本插件扫描间隔，**不暂停模块**  
- **PlugMan 热卸载** — `onDisable` 还原原版 particle 命令树，避免 ClassLoader 泄漏  

> **不做的事**：不 `setAware`/`setAI` 休眠生物；不把 `execute` 世界状态逻辑挪出主线程（会破坏原版一致性）。

## 下载

各版本 jar 见 [Releases](https://github.com/wenrongxx/BetterSingleCore/releases)。  
源码归档见 [`versions/`](versions/)。

## 版本历史

### 1.6.0（当前）

- 按 spark 画像继续压主线程：pathwarm 发现半径 `scan-distance`（默认 32），去掉空结果时的第二次大范围实体查询  
- 高负载时 pathwarm **隔轮**扫描（`high-load-pathwarm-interval-multiplier`，默认 2）；drain 仍每 tick，**不暂停**  
- 空闲时 `preload.idle-radius-bonus` 默认 2，多核多暖一圈区块  
- `Players.all` 仅在 online+bots 为空时才扫全部 world  
- 仍含 1.5 的 PlugMan 热卸载  

### 1.5.0

- particle Brigadier 补丁：安装前备份原版 `Command`，`uninstall()` 还原  
- `onDisable` 必卸 particle 补丁 → PlugMan `unload`/`load`/`reload` 安全  
- `/bsc reload` 按新配置重建 offload；status 增加 `originals=`  
- 实测：PlugMan 多轮 unload+load，`restored N vanilla`，无 ClassNotFound  

### 1.4.0

- 反射替换 `/particle` 叶子命令：观众 ≥ 阈值时并行扇出发送（Netty 线程安全）  
- 参数、viewers、force、成功/失败文案与原版一致；`execute` 仍主线程  
- 真实协议客户端多人实测：`parallelBatches` 生效  

### 1.3.0

- 弃用 1.2「暂停模块」；改为 **spread**：高 MSPT 只缩主线程预算  
- pathwarm/preload **永不归零暂停**；worker 池持续运行  

### 1.2.0

- 高频命令负载下的 adaptive yield（高 MSPT 暂停 pathwarm/preload）  
- **已弃用**：暂停会影响功能，后由 1.3 取代  

### 1.1.0

- **移除 AI 休眠**（1.0 会破坏刷怪塔）  
- 多核区块预热：worker 池异步规划/加载，主线程只 kick  
- 修复 Leaves 假人不在 `getOnlinePlayers()` 等问题  

### 1.0.0

- 首版：AI 休眠 + 区块预热 + MSPT 监控  
- **勿用于生产刷怪场景**（`setAware`/`setAI` 破坏农场）  

## 性能对照（实测摘要）

在同一固定负载下做了四组对照（无插件 / 仅插件 × 无附加内容 / 有附加内容），条件示例：Leaves 1.21.x、view/sim distance=4、约 4 名真人客户端、约 19 实体；预热后 **spark profiler 30s**，指标为 spark 的 MSPT/TPS（非插件自报）。

### MSPT（ms，30s 窗口）

| 场景 | 中位数 | p95 | **mean** | max | TPS 1m |
|------|--------|-----|----------|-----|--------|
| 无插件 · 无附加 | 1.07 | 4.01 | **2.02** | 366 | 19.92 |
| 仅插件 · 无附加 | 1.01 | 3.35 | **1.35** | 17 | 20.06 |
| 无插件 · +附加 | 1.20 | 4.46 | **2.21** | 349 | 19.95 |
| 插件 · +附加 | 0.97 | 3.19 | **1.34** | 27 | 20.08 |

`max` 含启动/加入尖峰，稳态看 median / p95 / mean。

### 插件带来的变化（同负载）

| 对比 | mean | p95 | max |
|------|------|-----|-----|
| 开插件（无附加） | **−33%**（2.02→1.35） | −16% | **−95%**（366→17） |
| 开插件（+附加） | **−39%**（2.21→1.34） | **−28%** | **−92%**（349→27） |

### 插件自身开销

- Monitor 稳态 avg ≈ **0.8–1.2 ms**，max ≤ 3.7 ms  
- `pluginKick` ≈ **0.4–0.7 ms/tick**  
- spark 中 `com.bettersinglecore` self-time ≈ **0.02 ms/tick**  
- Preload：数千区块 `fail=0`；主线程只做 kick  

### 结论（诚实）

1. 该固定负载 + 多真人条件下，开插件 **mean MSPT 约降 1/3**，p95 更稳，加入尖峰从数百 ms 降到约 20ms 量级。  
2. 四组 TPS 都贴 20——差异在 **MSPT 余量**，不是「从 15 TPS 救回来」。  
3. 附加内容在无战斗/VFX 时几乎不抬 MSPT；有无附加时插件趋势一致。  
4. 真正收益场景是**探索加载 / 高实体 / 高频多观众 particle**，不是空载。  
5. 不能神化：2ms→1.3ms 只是余量变大；主线程被选择器/`execute` 打满时，纯插件仍无法把世界逻辑挪走。  

### 边界

| 点 | 说明 |
|----|------|
| 负载偏轻 | flat、vd=4、约 19 实体；未覆盖重战斗 VFX |
| 单次 30s 窗口 | 量级趋势可信，百分比会波动 |
| 对比 mean/p95 | 非极端红石/刷怪塔满载 |
| particle 并行计数 | 部分窗口 `invocations=0`，但整体 mean 仍下降 |

## 构建

无 Maven/Gradle，需 **JDK 21**：

```powershell
# 需要 leaves-api / 服务端 jar 与 brigadier 在约定路径（见 build.ps1）
powershell -File BetterSingleCore\build.ps1 -Version 1.6.0
# 输出: BetterSingleCore\dist\BetterSingleCore-1.6.0.jar
```

## 部署

1. 将 jar 放入服务端 `plugins/`  
2. 重启，或（已装 PlugMan 等）：`plugman load BetterSingleCore`  
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
- 若 spark 显示大量 `@e` 扫描，需从**命令/资源写法**优化（tag、limit、合并入口），不是关 AI。  

## 回滚

在 [Releases](https://github.com/wenrongxx/BetterSingleCore/releases) 或 `versions/<semver>/` 换回对应 jar 即可。

## 许可

[MIT](LICENSE)
