# v1.1 Step 3 — Particle 小负载走顺序路径

日期：2026-11-18  
对应代码：`ParticleCommandOffload`

## 1. 问题

`Arrays.parallel().forEach` 对 **长度 2–4 的数组** 往往比顺序循环更慢（ ForkJoin 调度开销）。  
轻载 4 玩家、count 不大时，并行可能 **净亏**，与「B 略差」方向一致（particle 仅在命令触发时）。

## 2. 设计

并行条件改为 **同时** 满足：

1. 观众数 ≥ `parallel-viewers-threshold`（已有，默认 4，测试可用 2）  
2. 观众数 × 每人发送「工作量」≥ 阈值  

工作量用 **count** 近似（`sendParticles` 主要成本随 count 与观众数走）：

```text
parallel  ⇔  viewers >= threshold
         && viewers * max(count, 1) >= parallel-min-work   # 新配置，默认 64
```

否则顺序循环（与原版一致）。

## 3. 配置

```yaml
particle-parallel:
  parallel-viewers-threshold: 4
  parallel-min-work: 64          # viewers * count 低于此值不并行
```

## 4. 验收

- 小 count + 少观众：`parallelBatches` 不增加  
- 大 count 或多观众：仍 `parallelBatches` 上升  
- 语义/文案不变  
