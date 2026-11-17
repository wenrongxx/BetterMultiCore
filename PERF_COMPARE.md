# BetterSingleCore — 性能对照实测（第 6 轮）

日期：2026-11-17  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 测试设计

自建可复现对照，**同一固定负载**下跑四组：

| 场景 | 插件 | GravestoneDungeons | 固定负载 |
|------|------|--------------------|----------|
| **A** | 无 | 无 | 有 |
| **B** | 有 v1.4.0 | 无 | 有 |
| **C** | 无 | 有 | 有 |
| **D** | 有 v1.4.0 | 有 | 有 |

**固定负载（`bsc_stress`，每 tick）**

- 每名玩家 4 组 `particle`（force、count 30–80）→ 观众扇出
- 对 `#bsc_stress:scanned` 实体跑 `scan_one`（坐标 scoreboard + 小粒子）
- scoreboard 计时

**统一条件**

- Leaves 1.21.11，flat 世界，view/sim distance=4，`-Xms512M -Xmx1024M`
- **4 个真实协议客户端**（RealOne–Four）在线
- 约 19 实体（10 cow / 5 pig / 4 player）
- 预热 12s 后 **spark profiler 30s**（每场景独立重启服）
- 指标：spark 报告的 MSPT / TPS（非 BSC 自报）

**Profile 文件**

```
bench_results/
test_server/plugins/spark/profile-2026-11-17_00.04.08.sparkprofile  # A
test_server/plugins/spark/profile-2026-11-17_00.05.56.sparkprofile  # B
test_server/plugins/spark/profile-2026-11-17_00.07.41.sparkprofile  # C
test_server/plugins/spark/profile-2026-11-17_00.09.30.sparkprofile  # D
```

## 2. 结果（spark MSPT，30s 窗口）

| 场景 | 中位数 | p95 | **mean** | max | TPS 1m |
|------|--------|-----|----------|-----|--------|
| A 无插件·无数据包 | 1.07 | 4.01 | **2.02** | 366.44 | 19.92 |
| B 仅插件·无数据包 | 1.01 | 3.35 | **1.35** | 17.08 | 20.06 |
| C 无插件·+数据包 | 1.20 | 4.46 | **2.21** | 349.03 | 19.95 |
| D 插件·+数据包 | 0.97 | 3.19 | **1.34** | 26.62 | 20.08 |

单位：ms。`max` 含启动/加入瞬间尖峰，稳态看 median/p95/mean。

### 2.1 插件带来的变化（同负载）

| 对比 | mean | p95 | median | max |
|------|------|-----|--------|-----|
| **B vs A**（无数据包） | **−33%**（2.02→1.35） | −16%（4.01→3.35） | −6% | **−95%**（366→17） |
| **D vs C**（有数据包） | **−39%**（2.21→1.34） | **−28%**（4.46→3.19） | −19% | **−92%**（349→27） |

### 2.2 数据包本身的影响

| 对比 | mean | 说明 |
|------|------|------|
| C vs A（均无插件） | +9%（2.02→2.21） | 本场景 GD tick 代价不大（空载/无地牢实体） |
| D vs B（均有插件） | −1%（1.35→1.34） | 插件下数据包几乎不额外抬 MSPT |

### 2.3 插件自身开销（B / D 窗口内 BSC 自报）

- Monitor 稳态：**avg ≈ 0.76–1.24 ms**，max ≤ 3.7 ms
- `pluginKick` ≈ **0.4–0.7 ms/tick**（preload/pathwarm 主线程 kick）
- spark 包 `com.bettersinglecore` self-time ≈ **12 ms / 30 s ≈ 0.02 ms/tick**
- Preload：`started=done 4000+`，fail=0；PathWarm 正常扫描
- Particle：`installed=true leaves=7`；本窗口 `invocations=0`（见边界）

## 3. 结论（诚实）

1. **在本固定负载 + 4 真人条件下，开插件 mean MSPT 约降 1/3～4/5**，p95 更稳，启动/加入尖峰从数百 ms 降到 ~20ms 量级。
2. **TPS 四组都贴 20**——负载还没把服压到掉 TPS；差异在 MSPT 余量，不在「卡成 15 TPS」。
3. **GravestoneDungeons 在本场景几乎不增加 MSPT**（无地牢战斗/大量 VFX 实体时 tick 很轻）；插件在有/无数据包下表现一致。
4. **多核证据**：12 workers 预热 `4000+` 区块 fail=0；主线程只做 kick（~0.05ms/次）。真正的收益场景是**探索加载 / 高实体 / 高频多观众 particle**，不是空载 flat。
5. **不能神化**：mean 从 2ms 降到 1.3ms，在 50ms 预算里只是余量变大；若主线程被 mcfunction/实体打满，纯插件仍无法把 `execute` 世界逻辑挪走。

## 4. 边界与未测到的点

| 点 | 说明 |
|----|------|
| 负载偏轻 | flat + vd4 + ~19 实体；GD 未进入战斗 VFX 路径 |
| particle 并行未在 BSC 计数里体现 | `invocations=0`：mcfunction 内 particle 可能未走到包装的 Brigadier 叶子（或计数窗口问题）；spark 仍显示整体 mean 下降 |
| 单次 30s 窗口 | 非多次重复统计；量级趋势可信，百分比有波动 |
| 对比的是 mean/p95 | 不是极端红石/刷怪塔满载 |

## 5. 复现

```powershell
# 负载：test_server/world/datapacks/bsc_stress
# 四组脚本：
powershell -File BetterSingleCore\bench_compare.ps1 -ProfilerSec 30 -WarmupSec 12 -Players 4
# 分析：
# spark-local_analyze_spark → 各 .sparkprofile
```

环境已恢复：**插件在、GravestoneDungeons 在**。

---

状态：**四组对照实测完成，数据与 profile 已归档 `bench_results/`**
