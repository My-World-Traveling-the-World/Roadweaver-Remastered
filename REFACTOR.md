# 重制替换台账

此处只保留 `LegacyLedgerTest` 读取的七批替换记录。`TODO` / `IN_PROGRESS` 是对应整包替换、退役和验收状态，不能用来判断一个生产能力是否存在。第 4–7 批实际保留原包并就地改造；生产默认和待验项以[开发现状](docs/STATUS.md)为准，下一步见[技术路线](docs/ROADMAP.md)。

字段：`LEDGER|批号|能力|旧包|计划替换包|状态|旧包行数`。旧包退役须先确认生产新入口、全量零外部引用和行为验证。几何细账见 [S5 方法映射](docs/S5-METHOD-MAP.md)和 [S5 文件台账](docs/S5-POSTPROCESS-LEDGER.md)。历史原稿可用 `git show 55184e4:REFACTOR.md` 查看。

```text
<!-- LEDGER:BEGIN -->
LEDGER|1|纵断面与坡度|pathlogic/grade|pathlogic/profile|DONE|3543
LEDGER|2|桥隧判定|pathlogic/tunnel|pathlogic/profile|IN_PROGRESS|2918
LEDGER|3|桥体与施工|pathlogic/bridge|pathlogic/construction|IN_PROGRESS|4085
LEDGER|4|路口与合并|pathlogic/junction|roadnet|TODO|1877
LEDGER|5|几何后处理|postprocess|geometry|TODO|5615
LEDGER|6|结构避让|pathlogic/structures|avoidance|TODO|6291
LEDGER|7|验证与收尾|pathlogic/validation|validate|TODO|1594
<!-- LEDGER:END -->
```
