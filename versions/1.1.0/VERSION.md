# Version 1.1.0 (tested on Leaves 1.21.11)

- 方向：多核并行 offload（不休眠 AI）
- 模块：ParallelChunkEngine、PathChunkWarmer、TickMonitor、Players（含 Leaves 假人）
- 实测（test_server + 假人 Tester）：
  - 插件加载/启用/命令/reload 正常，无 Exception
  - Preload: started=done≈2500+, fail=0, batch≈30, 12 workers
  - PathWarm: mobs 扫描 160+, queued/done 正常
  - MSPT 监控正常
- 保留：完整源码 + BetterSingleCore-1.1.0.jar
- 回滚：versions/1.0.0 的 jar（含 AI 休眠，勿用于刷怪塔）
