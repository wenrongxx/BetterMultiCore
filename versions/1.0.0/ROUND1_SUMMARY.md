# BetterSingleCore — 单核性能优化插件 · 第 1 轮总结

日期：2026-11-16  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 现场结论

- 服务端：`leaves-1.21.11.jar`（Leaves build 157，MC 1.21.11，Java 21，Paper API 兼容）
- 用户附的 CPU 图：**单核打满、其余核空闲** —— 经典 Minecraft 主线程（game loop）瓶颈
- 本机不是服务器；插件面向服务端部署

## 2. 可优化点（纯 Bukkit/Paper 插件能碰的）

| 方向 | 手段 | 预期收益 |
|------|------|----------|
| 实体 AI | 远离玩家的 Mob `setAware(false)` + `setAI(false)`，打断寻路/随机游走/索敌 | **最大**：主线程不再为不可见生物付费 |
| 区块尖峰 | `World.getChunkAtAsync` 预热玩家周边未加载区块 | 探索时 MSPT 毛刺下降 |
| 过载自适应 | MSPT 连续超标 → aggressive：扫描间隔加倍、预热半径收缩 | 防止优化本身在过载时再加压 |
| 可观测 | `ServerTickStart/End` 量 MSPT，`/bsc status` 查看 | 便于验证效果、调参 |

说明：真正的热路径（区块 tick、红石、网络编码）在 NMS/服务端内部；插件层通过**减少主线程必须做的工作**来降单核占用。

## 3. 交付物

路径：`BetterSingleCore/dist/BetterSingleCore-1.0.0.jar`

### 模块

1. **TickMonitor** — MSPT last/avg/max、overloaded 连击、周期日志  
2. **AiDormancyService** — 时间预算 + 实体数预算的轮转扫描；滞回唤醒（`wake-margin`）防抖  
3. **ChunkPreloaderService** — 异步预热，kick 路径限时；in-flight 去重  
4. **BscCommand** — `/bsc status|reload|enable|disable|dormancy|preload|monitor`  
5. **Aggressive watcher** — 过载进 AGGRESSIVE，恢复回 NORMAL  

### 配置要点（`config.yml`）

- `dormancy.distant-distance` 默认 48 格；`wake-margin` 8  
- `dormancy.max-entities-per-scan` 400；`budget.max-scan-ms` 2.5ms  
- `preload.radius-chunks` 1；`max-loads-per-cycle` 24  
- `monitor.overloaded-mspt` 50  

## 4. 构建

- 无 Maven/Gradle：`javac --release 21` + `jar`  
- classpath：`leaves-api` + 服务端自带 adventure/bungee 等 libraries  
- 产物 22KB，含 `plugin.yml` / `config.yml`  

## 5. 部署与验证建议

1. 将 jar 放入服务端 `plugins/`  
2. 启动后 `/bsc status`  
3. 观察控制台 `[BSC] MSPT ...` 与 CPU 图：主核占用应下降  
4. 过高实体农场/刷怪塔场景收益最明显  

## 6. 后续可做（下一轮）

- [ ] 真机/容器里跑一轮 before/after MSPT  
- [ ] 可选：按实体类型分级休眠（村民交易、铁傀儡守卫不休眠）  
- [ ] 可选：与 spark profile 对齐，针对真实热点再加模块  
- [ ] README / 中文使用说明  

---
本轮状态：**实现 + 编译打包完成**，待服务端实测。
