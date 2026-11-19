# 2026-11-19历史审计探针

这里保存的是**缺陷修复前反例**，不是当前版本的验收程序。DeepAuditRoadProbe故意断言旧记录丢失、旧版本表增长；mutations.py故意记录两个“全绿”的守卫盲区。修复后锚点和内部结构已经改变，不要在当前工作树运行它们。

原生产基线：道路bdb7c84，长城2e74bf88；独立审计工具提交为道路eb0f2c4、长城eedb7286。原始结果保留于docs/evidence/deep-review-20261119.json，不能把旧“全绿”误当新守卫仍失效。

当前验收已转入正式回归并显式登记：

- RoadConstructionLedgerReadFaultTest：真实读锁/坏文件、拒绝覆盖、重读恢复。
- RoadPlanFailureRecoveryTest、LoadedChunkReplayTest、DeferredChunkQueueTest：真实编译故障及暂缓行为。
- ChunkPlanVersionsTest：有界元数据、在途认领、局部/全局晚到发布。

变异和最终全量结果见docs/HANDOFF.md第61—63轮及docs/evidence/deep-review-fixes-20261119.json。历史工具不加入正式测试数；不要改写其旧证据来迎合当前行为。
