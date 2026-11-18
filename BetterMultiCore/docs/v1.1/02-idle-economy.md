# v1.1 Step 2 — 空闲经济：低负载时拉长调度 + pathwarm 早退

日期：2026-11-18  
对应代码：`PathChunkWarmer`、`ParallelChunkEngine`、`PluginConfig`

## 1. 设计

### 1.1 何时算「空闲」

同时满足：

- `adaptive.enabled` 且 **非** highLoad（MSPT 未进 spread）  
- MSPT **&lt; `adaptive.idle-mspt`**（默认 **6.0**）  
- **上一轮「无实质工作」**：  
  - pathwarm：`lastQueued == 0`（没踢出任何区块加载）  
  - preload：上轮 `lastLoadsThisCycle == 0`（没 kick 任何 load）

### 1.2 拉长方式（不是暂停）

- 新配置：`adaptive.idle-stretch-multiplier`（默认 **4**，最小 1）  
- 空闲时：每 **N** 个调度周期才真正干活 1 次，其余周期只 `drainMain()`（区块 kick 队列仍每 tick 推进）  
- **highLoad 时 multiplier 视为 1**（高负载仍按原逻辑缩预算、不拉长到几乎不做）  
- 连续「有事可做」或离开空闲 → 立刻恢复满频

### 1.3 pathwarm 早退（G2）

在扫描前：

```text
if (idle-stretch-skip) { drain; return; }
```

避免每 40 tick 对 48/32 格半径做 `getNearbyEntities` 却 `queued=0`。

### 1.4 preload

同 idle 条件下，非干活周期跳过 `planAndEnqueue`，只 drain。

## 2. 配置增量

```yaml
adaptive:
  idle-mspt: 6.0
  idle-stretch-multiplier: 4   # 1 = 关闭空闲拉长
```

## 3. 不变量

| 行为 | 空闲 | 高负载 highLoad |
|------|------|-----------------|
| drain kick 队列 | 每 tick | 每 tick |
| pathwarm/preload 实扫 | 每 N 轮 1 次 | 每轮（预算可能更小） |
| 模块 stop | **从不** | **从不** |

## 4. 验收

- 轻载稳态：`PathWarm`/`Preload` 的 `cycles` 增速下降，`lastCycle`/`lastKick` 仍低  
- 高 MSPT 场景：仍进入 SPREAD，**不**因 idle 逻辑停扫  
- `/bmc status` 可见 `idleStretch` 状态  
