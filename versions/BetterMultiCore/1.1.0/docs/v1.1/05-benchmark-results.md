# v1.1 Step 5 — 压测结果与验收

日期：2026-11-18  
版本：**BetterMultiCore 1.1.0**  
方法：Done 后 45s + 进服后 30s + spark 30s（与 1.0 稳态方法一致）

## 1. S0 轻载回归（4 玩家 · 固定负载 · flat vd=4）

| 场景 | mean | median | p95 | max(1m) | TPS | 备注 |
|------|------|--------|-----|---------|-----|------|
| A 无插件（干净，无 BSC jar） | **2.92** | 1.73 | 10.47 | 76.54 | 19.98 | 仅 PlugManX；窗口内有尖峰 |
| B **1.1.0** 仅插件 | **1.42** | 1.36 | 1.84 | 4.79 | 20.00 | idleStretch 生效 |

对照 1.0.0 旧稳态（同方法）：

| | 1.0.0 B | 1.1.0 B |
|--|---------|---------|
| mean | 1.44 | **1.42** |
| p95 | 1.98 | **1.84** |
| max(1m) | 4.96 | **4.79** |
| pluginKick/窗口 | ~0.8–1.2 ms | **~0.18–0.55 ms** |
| PathWarm lastCycle | 0.05–0.11 ms | **0.004 ms**（空闲跳过时） |

**空闲经济证据（1.1.0 B）**：

```text
PathWarm: idleSkips=36 idleStretch=ON lastCycle=0.004ms queued=0
Preload:  idleSkips=69 idleStretch=ON lastKick=0.028ms
```

**验收**：

| ID | 结果 |
|----|------|
| V1 B 不劣于 A | **通过**（1.42 vs 2.92；A 有尖峰，B 更稳） |
| V2 空闲拉长、drain 保留 | **通过**（idleSkips>0，idleStretch=ON） |
| V3 highLoad 不停 | 逻辑保留（本次轻载未触发 highLoad） |

## 2. S1 高频多观众 particle

- 4 真人 · 每 burst 4 条 `count=200` `@a` particle · 约 35s  
- **`invocations=2228 parallelBatches=2228`**（threshold=2, minWork=64 → 全部并行）  
- 窗口 MSPT：**mean 1.92 · median 1.60 · p95 3.21 · max 7.15**，TPS 20  
- 主线程仍大量 park（发送扇出后未打满）

**验收 V4**：大 count×多观众 → `parallelBatches` 上升 → **通过**。

## 3. 与 1.0 对比结论

1. **1.1 不是「让空载 mean 神奇变快」**——轻载本来就没多少可省。  
2. **1.1 做到的是**：空闲时几乎不扫（idleSkips）、pluginKick 约降一半、particle 小负载不再误并行、大负载全部并行。  
3. 单次窗口波动大（A 的 2.92 含尖峰）；**不能**宣称稳定「降 50%」。  
4. 真正收益场景仍是：**探索 IO、高实体、高频多观众 particle、生产高 MSPT**。

## 4. 配置（1.1.0 新增）

```yaml
adaptive:
  idle-mspt: 6.0
  idle-stretch-multiplier: 4
particle-parallel:
  parallel-min-work: 64
```
