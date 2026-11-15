# RoadWeaver 文档导航

接手先读[开发现状](STATUS.md)和[交接台账](HANDOFF.md)最后一轮，再按[验证手册](VALIDATION.md)跑基线。

| 要查什么 | 唯一入口 |
|---|---|
| 默认在跑什么、哪部分待真机 | [开发现状](STATUS.md) |
| 下一步做什么、按什么顺序验收 | [技术路线](ROADMAP.md) |
| 架构与开发规则 | [架构](../ARCHITECTURE.md) · [开发约定](../CONVENTIONS.md) |
| 怎么编译、回归、真机对照、打包 | [验证手册](VALIDATION.md) |
| 旧版能力有没有丢 | [功能对照](FEATURE-PARITY-CHECK.md) |
| 每轮改动、变异与未验证项 | [交接台账](HANDOFF.md) |
| 已发布的产物和回退方式 | [发布台账](RELEASES.md) |
| 历史审计、实验和原文 | [历史资料](archive/README.md) |

## 测试读取的台账

| 路径 | 读取者 |
|---|---|
| [根目录重制替换台账](../REFACTOR.md) | `LegacyLedgerTest` |
| [S5 方法映射](S5-METHOD-MAP.md) | `S5MethodMapTest` |
| [S5 文件台账](S5-POSTPROCESS-LEDGER.md) | `PostprocessLedgerTest` |
| [跨层依赖基线](../common/src/test/resources/architecture/deps-baseline.txt) | `ArchitectureGuardTest` |

机器台账保持原路径和格式。生产接线或默认值变动更新现状；下一步改路线；实验数据写交接或发布记录。历史资料中的「当时状态」不需要追着代码改。
