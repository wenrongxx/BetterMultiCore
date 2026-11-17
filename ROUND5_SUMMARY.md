# BetterSingleCore — 第 5 轮总结（v1.4 · particle 扇出并行）

日期：2026-11-16  
工作区：`O:\Xiaomi\workspace\mc_better_single_core`

## 1. 你的要求

> 尝试把 mcfunction 的 execute/particle 挪到其他核；不能改变原版特性

## 2. 能挪什么 / 不能挪什么（诚实边界）

| | 结论 |
|--|------|
| **execute 世界逻辑**（方块、实体状态、计分板） | **不能**挪到其他核——会破坏原版一致性 |
| **particle 多观众发送** | **可以**：解析仍在主线程，按观众扇出的 `connection.send` 走 Netty，可并行 |
| **语义保持** | 参数、viewers（`realPlayers` 或 `@a`）、force/距离规则、成功/失败文案与原版一致 |

## 3. 实现方式

反射替换 Brigadier `particle` 树上 **7 个叶子命令** 的 `Command` 字段：

1. 主线程：与原版相同的参数解析（`ParticleArgument` / `Vec3` / float / int）
2. 观众数 ≥ `parallel-viewers-threshold`（默认 4）→ `Arrays.parallel().forEach` 调 `ServerLevel.sendParticles`
3. 否则顺序循环（小开销）
4. 成功/失败翻译键与原版相同

`execute` 本身仍在主线程（必须）；`execute as @a run particle` 里每次 particle 调用会走并行扇出。

## 4. 实测（4 个真实协议客户端 + Leaves 1.21.11）

| 检查项 | 结果 |
|--------|------|
| 4 玩家连入 | RealOne–RealFour 全部 `joined the game` |
| `list` | **4/5 online** |
| particle 安装 | `installed=true leaves=7` |
| 命令执行 | `Displaying particle ...` ×2（**无异常**） |
| **并行扇出** | **`invocations=2 parallelBatches=2`**（两次都走 parallel） |
| `/bsc particle` | 正常输出统计 |

测试中修过的客户端协议问题（与插件无关，为连真实玩家）：  
压缩帧、config 阶段 `select_known_packs`、play keepalive ID（cb 43 / sb 27）。

## 5. 交付与回滚

```
versions/
  1.0.0/  AI 休眠（刷怪塔勿用）
  1.1.0/  多核预热
  1.2.0/  + adaptive（会暂停，已弃）
  1.3.0/  不暂停 spread
  1.4.0/  + particle 扇出并行  ← 本轮
```

部署：

```text
copy versions\1.4.0\BetterSingleCore-1.4.0.jar → 服务端\plugins\
/bsc status
/bsc particle
```

配置：

```yaml
particle-parallel:
  enabled: true
  parallel-viewers-threshold: 4   # 观众 ≥ N 才并行
```

## 6. 边界

- **execute** 的世界状态逻辑不能拆核（原版约束）  
- 并行只作用于 **已解析后的 per-player 发送**（Netty 写线程安全）  
- 不改 AI、不改粒子参数、不改成功/失败条件  

---
状态：**v1.4.0 四真实玩家实测通过（parallelBatches=2），已归档 versions/1.4.0**
