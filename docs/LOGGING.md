# 中文日志与源码说明

生产日志使用中文主说明，保留原有英文诊断标记、占位符及key=value字段，方便玩家阅读并兼容已有分析脚本。第45轮编译器扫描五模块701个生产Java文件，识别168个日志文件、842处调用，全部格式说明含中文。等级、节流、默认诊断开关与调用次数没有为翻译而放宽；没有新增逐方块INFO。

## 日志定位

| 标记或内容 | 用途 | 不能据此推断 |
|---|---|---|
| `[ROUTE-EVIDENCE]` | 关联粗候选、最终校验和区块处理结果 | 阶段齐全不等于整路可通行 |
| `[CHUNKY-READINESS-TIMING]` | READY收集、排序或插入、编码和文件提交 | READY不等于道路实体完工 |
| 施工台账计时 | 区分收集、排序、编码、写盘 | 临时文件性能不等于真实存档磁盘尾延迟 |
| `[PLANNING-HEIGHT-TILES]` | 实际高度瓦片请求、采样列与驱逐 | 三通道缓存字节不等于总堆峰值 |
| 已隔离损坏文件 / 隔离损坏文件失败 | 区分真正移走损坏文件与仍无法处理 | 警告级别本身不表示隔离失败 |

`INFO`保留阶段/结果汇总，`WARN`说明异常、拒绝或降级，`DEBUG`和`TRACE`用于现有细节诊断。不要为了看中文全局强开TRACE。应先保留完整日志，再按标记筛选，避免只截最后几行丢掉首次失败原因。控制台GBK乱码与日志本身UTF-8不同，工具输入须显式指定编码。

`-Droadweaver.buildabilityShadow=on`开启已有可建性影子观测及路线证据；`-Droadweaver.chunkyReadinessTimings=on`开启READY分阶段计时。开关默认关闭。`content=c1-<64位十六进制>`是诊断内容摘要，包含RoadData的站序、完整中心和铺装列坐标、分站边界、目标高度、跨度、材料状态、宽度/类型/所有者；不包含世界方块、配置和模板内容，不是道路持久身份或完成台账键。第47轮起，同一不可变RoadData实例首次或被驱逐后扫描整路，重复输出复用最多4096项的弱身份摘要；新实例独立编码，不跨实例继承校验。首次/并发冷计算和其他格式化仍有成本。

## 证据汇总

```powershell
python -B scripts/evidence/summarize_route_evidence.py --session new-save latest.log --encoding utf-8-sig --output summary.json
python -B -m unittest discover -s scripts/evidence -p 'test_*.py' -v
```

相同会话轮转文件按旧到新提供，不同存档/重开显式分会话。`versionsByContent`只关联同内容的校验和施工；无摘要旧记录放在`legacyVersionObservations`，不会借用旧校验替新内容背书。`chunksByFingerprint`仅保留身份维度旧汇总，不能用作内容版本证明。COMPLETED表示施工函数结案，ABANDONED不是完成，NOT_ATTEMPTED也不是已验证旧台账。全路完成始终不自动确认。

## 中文注释维护

全部701个生产Java文件已有中文职责头。第45轮处理635段原来仅英文的Javadoc、补23处仅参数标签的正文，并清掉52段拆分后挂错成员的残留。168个生产文件的注释外内容与改前一致。现编译器提取3262处成员Javadoc，正文均含中文；关键旧契约另结合实现核对。

注释解释约束与原因，不逐行翻译语法；已有英文公式、参数、标记可以保留。重点维护线程/锁归属、输入单位、持久成功条件、保护边界、取消语义和失败返回。record不自动保证嵌套集合深不可变，Future取消不自动强停计算，缓存清空也不能凭直觉标O(1)。历史采样必须写明历史，不作为当前版本结论。精确采样CPU-only与仍可用的粗GPU采样须区分。

`ChineseLogGuardTest`检查日志格式参数；`ChineseCommentGuardTest`用JDK编译器检查已附着成员的Javadoc正文，参数标签、文件头、字符串不能充数。它不强制每个getter都有独立Javadoc，也不能证明中文语义正确；普通行注释、所有历史注释以及脚本/资源不在“3262处正文”的统计内。语义过时仍须查实现与调用方，本轮不能声称全仓每句话都已经证明无误。


### 第46轮：区块耗时与摘要限样本

开启既有-Droadweaver.deferredChunkTimings=on时，新增[CHUNK-REPLAY-TIMING] chunk/outcome/replayUs；异常记exception并继续抛出，与NO_PLACEMENT不同。默认关闭逐块日志。回填每次仍最多8块，另在完整区块之间按4ms预算让出；单块、台账flush和候选选择可能超过4ms，该值不是MSPT保证。

大日志可用python scripts/evidence/summarize_route_evidence.py --session 首次 latest.log --mode summary --history-limit 16 --output report.json。摘要只截每阶段尾部历史、错误和无owner样本，保留总计数、latestFailuresByStage、failedObservationsByStage、最新校验、内容版本与区块结果历史；errorCount决定退出码。detail默认保留完整历史。道路/版本/区块仍全保留，不能称全输入恒定内存。


### 第48轮：分片内容验证

[SHARD-VERIFY]汇总files、checked、mismatches、malformed、ioFailures、workers、elapsedMs、matches。mismatches含字段/有序引用不一致、重复坐标及缺失；malformed是解析损坏，ioFailures是IO/任务/中断等无法完整读取。计数不是互斥分区，不能相加当文件数。最多8工作者；不一致/解析/IO细节各限前8次并保留汇总，无逐块INFO。目录读失败/预中断早返回可能只有专用错误或上层只读日志，不保证汇总行。

matches=true只证明本次所读分片与聚合根重建引用相符，不证明道路可通行或随后没有外部改写。IO明确“不按损坏隔离”；本会话只读，解锁后新实例重开完整验证才恢复。修复失败表示完整内存快照可查，等下次实际提交重试，成功才清除意图。取消后最多等5秒不等于磁盘或整个启动有5秒硬上限。
