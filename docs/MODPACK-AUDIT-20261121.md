# WAYFARING安装目录核对（2026-11-21）

目标：`D:\minecraft\Plain Craft Launcher 2\.minecraft\versions\【云游四海】WAYFARING\mods`。
当前启用jar为533个；Loader选中786个模组，包含内嵌依赖与内置模组。
本轮未发现额外的声明级硬依赖或互斥阻断；没有启动游戏，不能据此断言全部运行兼容。
机器证据见[evidence/modpack-directory-audit-20261121.json](evidence/modpack-directory-audit-20261121.json)。

## 已执行的全量核对

| 检查 | 实际结果 |
| --- | --- |
| 顶层jar | 清理前536个，均能读取Fabric元数据，无混装的纯Forge包 |
| 内嵌发现 | 449个不同内容的内嵌候选，连同顶层及Loader共986条候选记录 |
| 实际依赖求解 | Loader0.19.3元数据解析器/SAT求解器通过，应用实例现有fastmove依赖覆盖 |
| Loader一致性 | 审计使用的缓存jar与游戏libraries中Loader完整SHA一致 |
| 环境 | Minecraft1.21.1；Java25模型由上次实际日志及PCL设置确认 |
| Mixin | 选中集合802份配置，按Gson规则解析，0重名、0解析错误 |
| 声明资源 | 0个缺失的Mixin类/插件/配置/AccessWidener |
| jar完整性 | 清理前536个顶层及449个不同内容内嵌jar的CRC全通过 |
| 清理后完整性 | 剩余533个顶层SHA逐一与CRC检查时相同，三个备份SHA通过 |
| 自有命名空间重类 | 选中类中local/plaincraft、roadweaver、streams三命名空间未发现重复类 |

未直接运行Minecraft，未应用Mixin到目标类，未验证注入方法签名、着色器、资源包或游玩行为。
元数据存在的未满足推荐依赖与真正的硬依赖失败分开记录。
72条refmap缺失声明没有被直接升级为致命错误；是否需要refmap取决于实际映射与注入，需启动日志确认。

审计是独立Java助手调用Loader原实现，不改Loader签名或游戏代码。
助手本身由JDK21运行，内置java候选显式按现场Java25建模；不是用开发编译JDK推断客户端环境。
初筛发现的三处严格JSON解析失败，在Gson下正常，未误报或修改C2ME/Voxy/女仆配置。
尾逗号产生的两个null列表项，另核实际MixinConfig.prepareMixins字节码会直接跳过，未当作缺失类。
层次日志还会展示server-only的polymer-autohost引用，核对时按客户端环境排除。

## 三组重复文件已备份清理

之前只按同ID报告重复，不代表Fabric必然拒绝；Loader会选择版本。
本轮先运行原集合，再在审计副本去掉以下旧包，逐项核对全部786个选中候选及版本完全相同，
然后才备份移出实际目录。清理没有切换实际启用版本。

| ID | 移出的未选中旧包 | 保留的已选中包 |
| --- | --- | --- |
| polytone | 20260927polytone-1.21-4.5.3-fabric.jar | 20260927polytone-1.21-5.3.8-fabric.jar |
| chestfill | chest-fill-level-1.0.0.jar | chestfill-plus-1.1.0+mc1.21.1.jar |
| punchy | punchy-2.8g-fabric-1.21.1.jar | punchy-3.2b-fabric-1.21.1.jar |

备份：`D:\云游四海-Mod源码\build\mods-audit-20261121\duplicate-backup`。
原118个.bak和1个.disabled保持原样；未更改配置、存档或跟随更新目录。
清理后再次运行实际求解器通过，结果与清理前完全一致。
对照用户上次实机日志，客户端集合仅少了前一轮移出的旧树叶剔除2.0.0，其他版本不变。

前两条实际启动阻断分别是旧MineMenu补丁与1.14.3不兼容、树叶剔除新旧分支Mixin重名；
处理与备份详见[实机清单](PLAYTEST-20261121.md)。

## 仍存在的非致命提示与待核项

| 项 | 核实结果 | 当前处理 |
| --- | --- | --- |
| nomeltsnowybuilds推荐McQoy | 推荐0.3.3+1.3.2，已装0.4.1+fabric-1.20；实际是recommends | 保持当前满足硬依赖的版本，未为消警告降级 |
| Sounds推荐rsls | rsls未安装，实际是recommends | 保持当前安装，不自动新增模组 |
| Resource Pack Options可选FREX入口 | 声明FrexCompat，但选中jar全集没有该类；当前无FREX/Canvas | 记录打包疑点，未证实本次启动会调用，不擅改第三方jar |
| 版本字符串及额外元数据键 | 多个非标准SemVer、credits等键触发WARN | 实际解析已通过，不当作本轮启动崩溃原因 |
| fastmove依赖覆盖 | 现有配置移除其minecraft/sodium声明限制，实际求解应用该覆盖 | 保留既有策略；这不证明其渲染/动作API实际兼容 |

该整合包的c2me-opts-accel-opencl、c2me-opts-natives-math及
plaincraft_integrated_gpu_opencl_compat明确要求Java>=25。
现场日志为java25、PCL范围为[25.0,)，当前匹配；游戏本体版本JSON中Java21最低要求不代表整个包可用21启动。

## 验证审计确实能拒绝坏输入

临时输入只写工作区审计副本，不改实际mods：

| 负对照 | 实际结果 |
| --- | --- |
| 从候选移走道路提供方，使内嵌roadweaver_api不可达 | 实际ModResolutionException，指出roadweaver_api依赖，非编译/启动助手失败 |
| 把已备份的旧MineMenu补丁重新加入候选 | 实际ModResolutionException，指出补丁的1.14.2依赖冲突 |

两组都确认无PASS摘要；正控与实际清理后集合均通过。
原始清单、SHA、解析日志、助手源码和负对照目录在工作区`build/mods-audit-20261121`。
未改生产源码，未重跑或虚增道路/长城/河流JUnit数量。

下一步重新启动整合包；若进入主菜单，再按[实机顺序](PLAYTEST-20261121.md)验证预热与世界产物。
启动后的新日志才用于判断Mixin目标绑定和实际运行问题。
