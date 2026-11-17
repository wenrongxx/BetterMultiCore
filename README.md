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

- 按生产环境 spark 画像继续压主线程：pathwarm 发现半径 `scan-distance`（默认 32），去掉空结果时的第二次大范围实体查询  
- 高负载时 pathwarm **隔轮**扫描（`high-load-pathwarm-interval-multiplier`，默认 2）；drain 仍每 tick，**不暂停**  
- 空闲时 `preload.idle-radius-bonus` 默认 2，多核多暖一圈区块  
- `Players.all` 仅在 online+bots 为空时才扫全部 world  
- 仍含 1.5 的 PlugMan 热卸载  

### 1.5.0

- particle Brigadier 补丁：安装前备份原版 `Command`，`uninstall()` 还原  
- `onDisable` 必卸 particle 补丁 → PlugMan `unload`/`load`/`reload` 安全  
- `/bsc reload` 按新配置重建 offload；status 增加 `originals=`  
- 实测：PlugManX 3 轮 unload+load，`restored 7 vanilla`，无 ClassNotFound  

### 1.4.0

- 反射替换 `/particle` 叶子命令：观众 ≥ 阈值时并行扇出发送（Netty 线程安全）  
- 参数、viewers、force、成功/失败文案与原版一致；`execute` 仍主线程  
- 真实协议客户端 4 人实测：`parallelBatches` 生效  

### 1.3.0

- 弃用 1.2「暂停模块」；改为 **spread**：高 MSPT 只缩主线程预算  
- pathwarm/preload **永不归零暂停**；worker 池持续运行  

### 1.2.0

- 针对高频命令数据包：adaptive yield（高 MSPT 暂停 pathwarm/preload）  
- **已弃用**：暂停会影响功能，后由 1.3 取代  

### 1.1.0

- **移除 AI 休眠**（1.0 会破坏刷怪塔）  
- 多核区块预热：worker 池异步规划/加载，主线程只 kick  
- 修复 Leaves 假人不在 `getOnlinePlayers()` 等问题  

### 1.0.0

- 首版：AI 休眠 + 区块预热 + MSPT 监控  
- **勿用于生产刷怪场景**（`setAware`/`setAI` 破坏农场）  

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
- 若 spark 显示大量 `@e` 扫描，需从**命令/资源写法**优化（tag、limit、合并入口），不是关 AI。  

## 回滚

在 [Releases](https://github.com/wenrongxx/BetterSingleCore/releases) 或 `versions/<semver>/` 换回对应 jar 即可。

## 许可

[MIT](LICENSE)
