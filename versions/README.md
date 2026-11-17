# 版本归档

## BetterMultiCore（当前品牌）

| 版本 | 说明 |
|------|------|
| [1.0.0](BetterMultiCore/1.0.0/) | 新名首发；功能对应原 BetterSingleCore 1.6.x。Release 标签：`v1.0.0` |

## BetterSingleCore（历史品牌，已更名）

| 版本 | 说明 |
|------|------|
| [1.0.0](BetterSingleCore/1.0.0/) | AI 休眠（**勿用于刷怪塔**） |
| [1.1.0](BetterSingleCore/1.1.0/) | 移除 AI 休眠；多核区块预热 |
| [1.2.0](BetterSingleCore/1.2.0/) | adaptive yield（会暂停，**已弃用**） |
| [1.3.0](BetterSingleCore/1.3.0/) | spread：高负载不暂停 |
| [1.4.0](BetterSingleCore/1.4.0/) | particle 多观众并行扇出 |
| [1.5.0](BetterSingleCore/1.5.0/) | PlugMan 热卸载 |
| [1.6.0](BetterSingleCore/1.6.0/) | 更轻 pathwarm 扫描（更名前最后一版） |

每个版本目录内容：

- `*.jar` — 可部署插件  
- `src/` — 该版本源码  
- `build.ps1` — 构建脚本（如有）  
- `VERSION.md` — 版本说明  

对应 GitHub Release 见 [Releases](https://github.com/wenrongxx/BetterMultiCore/releases)（历史 tag 无 `v` 前缀，新品牌为 `v1.0.0`）。
