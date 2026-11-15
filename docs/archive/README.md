# 历史资料

这些资料保留当时的设计、证据和争议，**不描述现行默认**。回到[文档导航](../README.md)查看现状、路线与验证手册。

| 查什么 | 历史资料 |
|---|---|
| 两个 P0 的复现、修复与负控 | [P0 修复](P0-FIX-20261115.md) |
| 桥隧跨度试验与涵洞阈值 | [跨度回写设计](DESIGN-SPAN-WRITEBACK.md) · [涵洞阈值分析](CULVERT-THRESHOLD-ANALYSIS-20261115.md) |
| 守卫自审与客户端依赖 | [守卫有效性](GUARD-EFFECTIVENESS-AUDIT-20261115.md) · [客户端耦合审计](UI-COUPLING-AUDIT-20261115.md) |
| 新内核切换过程与坡度前置定义 | [S7 切换清单](S7-SWITCH-CHECKLIST.md) |
| 2026-11-13 至 14 首轮审计 | [总审计](AUDIT-20261113.md) · [分域报告](../audit-20261113/) · [后续交叉核查](审计结论-20261114.md) |
| 更早的旧版证据 | [2026-09-02 审查](audit-report-20260902.md) · [修复记录](fix-changes-20260902.md) · [0.8.406 性能和质量](RoadWeaver-性能与质量深度分析报告-0.8.406基线.md) |
| 冻结的旧版功能核查基线 | [八个功能域](../functional-inventory/README.md) |

本次合并前的完整文档在提交 `55184e4`。用 `git show 55184e4:REFACTOR.md`、`git show 55184e4:docs/重制总纲.md` 或 `git ls-tree --name-only 55184e4:docs` 找原文。旧 STATUS、HANDOVER、阶段审计草稿与扫描产物留在 Git 历史，无需作为日常开发入口。
