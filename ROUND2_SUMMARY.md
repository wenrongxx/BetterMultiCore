# BetterSingleCore — 第 2 轮总结（多核并行 · 已实测）

日期：2026-11-16  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 方向修正

| 问题 | 处理 |
|------|------|
| AI 休眠弄坏刷怪塔 | **v1.1 彻底移除** setAware/setAI |
| 目标是用上其他核心 | 多 worker 并行 offload 区块 IO/生成/路径邻域 |
| 每版可回滚 | `versions/<semver>/` 保留源码 + jar |

## 2. 本机实测（Leaves 1.21.11 test_server）

环境：`test_server/`，离线模式，flat 世界，12 逻辑 CPU。

| 检查项 | 结果 |
|--------|------|
| 插件 Loading/Enabling | 通过 |
| 线程池 | 12 workers, maxLoads=96 |
| `/bsc status/workers/preload/pathwarm/monitor/reload` | 全部正常 |
| Leaves 假人 `bot create Tester Steve minecraft:overworld 512 -50 512` | 加入成功 |
| Preload | **started=2592 done=2592 fail=0 batch=32** |
| PathWarm（附近牛/猪） | **mobs=160 queued/done 正常** |
| Exception/ERROR（插件相关） | **无** |
| MSPT 周期日志 | 正常（启动尖峰后 ~0.1ms） |

关键修复（实测中发现）：
1. **Leaves 假人不在 `getOnlinePlayers()`/`world.getPlayers()`** → 新增 `Players.all()` 走 `Server.getBotManager()`
2. 批量 `getChunksAtAsync` 在 `generate-if-missing=false` 时原先不触发 → 已改为矩形批量始终可用
3. 信号量获取失败时 `inFlight` 卡死 → 失败即释放 key

## 3. 版本目录

```
versions/
  1.0.0/  源码 + jar   （AI 休眠，刷怪塔勿用）
  1.1.0/  源码 + jar   （当前，已实测）
```

## 4. 交付物

- `versions/1.1.0/BetterSingleCore-1.1.0.jar` — 部署用
- `versions/1.1.0/BetterSingleCore/` — 完整源码
- `BetterSingleCore/test_with_bot.ps1` — 本机回归测试脚本
- `test_server/` — 可复跑的测试服

## 5. 部署

```text
copy versions\1.1.0\BetterSingleCore-1.1.0.jar → 服务端\plugins\
/bsc status
```

## 6. 边界（诚实说明）

- 能：区块读盘/生成在其他核并行，减少主线程等 IO  
- 不能（纯插件）：实体 tick/红石主循环多线程（需 Folia 类分叉）  
- 假人/真人在线后预热才有工作量；出生点已加载区域 `started` 可能为 0 属正常  

---
状态：**v1.1.0 本机实测通过，已归档 versions/1.1.0**
