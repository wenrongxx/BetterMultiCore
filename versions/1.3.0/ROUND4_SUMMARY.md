# BetterSingleCore — 第 4 轮总结（v1.3 · 不再暂停，持续多核分摊）

日期：2026-11-16  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 你的反馈

> 暂停命令可能会影响功能，尽量试试分配到别的核心

v1.2 的 adaptive **yield 会暂停** pathwarm / preload → 可能导致预热断档、功能受影响。  
**v1.3 改为：模块永不暂停，只缩小主线程单次预算，工作继续分到其他核。**

## 2. v1.3 行为对比

| | v1.2 yield（已弃用） | **v1.3 spread（当前）** |
|--|----------------------|-------------------------|
| 高 MSPT | **暂停** pathwarm / preload | **继续跑**，只改预算 |
| 主线程 drain | 可到 0 | 正常 48 → 高负载 **10**（仍 >0） |
| pathwarm | 可整轮跳过 | 预算 48 → 高负载 **16**（仍 >0） |
| 规划/IO | 可能停 | **worker 池始终运行** |
| AI | 不改 | 不改 |

配置（`adaptive`）：

```yaml
high-load-mspt: 25.0
high-load-main-drain: 10      # 高负载时主线程每 tick 最多 kick 数
high-load-pathwarm-mobs: 16   # 高负载时每轮最多处理生物（绝不为 0）
normal-main-drain: 48
```

## 3. 实测（test_server + 1.3.0 + 假人 + 风暴）

| 检查项 | 结果 |
|--------|------|
| 插件 1.3.0 加载 | 通过（`never pauses modules`） |
| **High-load SPREAD ON** | `shrink main-thread batches, keep workers running` |
| highLoad 期间 | Preload/PathWarm **计数持续增长**（未停） |
| Preload | started=done **2707+**，fail=0，全程未断 |
| PathWarm | mobs **127+**，`highLoadCycles=2`（高负载轮次仍执行） |
| SPREAD OFF | 正常恢复 `drain=48` |
| 插件 Exception | **无** |

关键证据：

```text
High-load SPREAD mode ON ... keep workers running
Preload: started=601 → 682 → 1573 → 2626 → 2707   # 从未归零暂停
PathWarm: highLoadCycles=2 mobs=29 → 127            # 高负载仍在扫
```

## 4. 交付

```
versions/1.3.0/
  BetterSingleCore-1.3.0.jar   ← 可部署
  BetterSingleCore/            ← 完整源码
  test_spread.ps1              ← 回归脚本
  ROUND4_SUMMARY.md
```

回滚：`versions/1.2.0`（有暂停逻辑）或 `1.1.0` / `1.0.0`。

部署：

```text
copy versions\1.3.0\BetterSingleCore-1.3.0.jar → 服务端\plugins\
/bsc status
/bsc adaptive
```

## 5. 边界

- **能**：高负载时减少主线程单次占用，同时保持多核预热不断档  
- **不能**：把 mcfunction 的 execute/particle 本身挪到其他核（需 Folia 类分叉）  
- 命令风暴过猛时主线程仍会满，但本插件不再与「暂停预热」叠加恶化  

---
状态：**v1.3.0 实测通过（不暂停 + highLoad 分摊），已归档 versions/1.3.0**
