# v1.1 Step 4 — 压测场景设计（证明「有用的场合」）

日期：2026-11-18  
背景：轻载 flat 测不出收益；需要 **多核可分摊** 的负载。

## 1. 场景 S1 — 多观众高频 particle（测扇出）

| 项 | 值 |
|----|-----|
| 玩家 | 4 真实客户端 |
| 负载 | 每 tick 对 `@a` 发送 `count=200` 的 `particle` × 8 组 |
| 对照 | 插件 threshold=2/min-work=64 vs 关闭 particle-parallel |
| 指标 | spark mean/p95 MSPT；`/bmc particle` 的 `parallelBatches` |

预期：并行扇出时主线程 **send 循环** 变短（发送在 worker/Netty）。

## 2. 场景 S2 — 连续区块加载（测 preload 多核）

| 项 | 值 |
|----|-----|
| 手段 | 控制台反复 `tp` 玩家到未加载坐标（或 4 人分散 tp） |
| 对照 | preload on vs off（`/bmc disable`） |
| 指标 | 探索期间 MSPT 毛刺、chunk load 尖峰 |

预期：预热把 IO 摊到 workers，主线程 kick 更平滑（非空载 mean）。

## 3. 场景 S0 — 既有轻载（回归）

保持 45s+30s 预热四组，确认 **空闲经济后 B 不劣于 A**（目标：Δmean ≤ 0.05ms 或更小）。

## 4. 执行顺序

1. 实现 Step 2–3，构建 **1.1.0**  
2. 跑 S0 回归  
3. 跑 S1、S2  
4. 写 `05-benchmark-results.md` 并更新 README  
