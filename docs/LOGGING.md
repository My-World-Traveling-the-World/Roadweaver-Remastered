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

`-Droadweaver.buildabilityShadow=on`开启已有可建性影子观测及路线证据；`-Droadweaver.chunkyReadinessTimings=on`开启READY分阶段计时。开关默认关闭。`content=c1-<64位十六进制>`是诊断内容摘要，包含RoadData的站序、完整中心和铺装列坐标、分站边界、目标高度、跨度、材料状态、宽度/类型/所有者；不包含世界方块、配置和模板内容，不是道路持久身份或完成台账键。每次输出摘要要扫描整路，不能当作零成本开关。

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
