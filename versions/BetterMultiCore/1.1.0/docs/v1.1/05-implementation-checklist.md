# v1.1 Step 5 — 实现清单与验收

日期：2026-11-18  
状态：**完成 → 1.1.0**

## 变更列表

- [x] `PluginConfig`：`idle-mspt`、`idle-stretch-multiplier`、`particle-parallel.parallel-min-work`
- [x] `TickMonitor.isIdle()`：低 MSPT 且非 highLoad
- [x] `PathChunkWarmer`：空闲拉长 + 上轮 queued=0 才跳过 + status `idleSkips/idleStretch`
- [x] `ParallelChunkEngine`：空闲拉长（只跳过 plan，drain 保留）
- [x] `ParticleCommandOffload`：`viewers * count >= min-work` 才并行
- [x] `plugin.yml` / `config.yml` 版本 1.1.0
- [x] 构建 `BetterMultiCore-1.1.0.jar`
- [x] S0/S1 压测 → `05-benchmark-results.md`
- [ ] README 版本历史 + 性能节更新
- [ ] Release `v1.1.0`

## 验收标准

| ID | 标准 | 结果 |
|----|------|------|
| V1 | 轻载 B mean 不显著高于 A | **PASS**（B 1.42，干净 A 2.92） |
| V2 | 空闲 pathwarm/preload 频率下降，drain 仍运行 | **PASS**（idleSkips 36/69） |
| V3 | highLoad 仍 SPREAD，模块不停 | 代码路径保留，轻载未触发 |
| V4 | 大 particle parallelBatches 上升 | **PASS**（2228/2228） |
| V5 | 无 ClassNotFound / 无 AI 修改 | **PASS** |

## 工程文档

| 文件 | 内容 |
|------|------|
| `01-diagnosis.md` | 为何轻载「像没用」 |
| `02-idle-economy.md` | 空闲经济设计 |
| `03-particle-sequential-small.md` | 小粒子顺序路径 |
| `04-bench-plan.md` | S0/S1/S2 场景 |
| `05-benchmark-results.md` | 实测结果 |
| `05-implementation-checklist.md` | 本清单 |
