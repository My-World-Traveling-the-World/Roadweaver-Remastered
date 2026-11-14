# RoadWeaver 旧版「持久化与 API」功能域盘点

> 依据：`D:\云游四海-Mod源码\roadweaver`，HEAD `2c6547a683f6219a5b7c9c4d9f871a77cf3751e0`；只读静态源码盘点，**未启动游戏、未跑构建**。链接为相对工作区根的路径。
>
> 总链路：**规划产出 `RoadData` → `RoadFingerprint.compute` 得 `long` 主键 → 聚合根写成不可变 NBT（`roads/<fp>.g<gen>.nbt`）→ ChunkPos 倒排写成 `chunks/<cx>_<cz>.json` → `index.json` 作为提交点原子发布 → 内存换成新 `RoadSnapshot` 挂 `AtomicReference`**。查询只读快照不读盘；跨 mod 侧走独立部署的 `roadweaver_api` 契约 mod + `compat/` 桥。
>
> 出厂组合：道路存储 `SCHEMA_VERSION=2`、施工台账 `SCHEMA=3`、就绪索引 `READINESS_SCHEMA=2`、路径缓存 `SCHEMA=1`、粗地形瓦片 `SCHEMA_VERSION=2`、世界生成指纹 `CURRENT_SCHEMA_VERSION=1`、路线文件 `version=1`；`api` 模块 **25 文件 / 1819 行**、`API_VERSION=1`、部署 id `roadweaver_api` 版本 `1.0.0`；`compat/` **7 文件**。存档根 `<世界>/data/roadweaver/persistence/<category>/<维度>/`（[FileStoragePathResolver.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/files/FileStoragePathResolver.java) `:14`/`:24`）。
>
> **行号口径：本文行号来自对冻结仓库的静态扫描，可能有 ±2 行偏差（方法定义与调用点、常量与字段容易混）。定位请优先按方法名/符号名检索，行号仅作快速跳转参考。**

---

## 一、核心存档模型与 Codec 契约

### `RoadData`（聚合根）
[RoadData.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/core/model/RoadData.java)（70 行）是 record，9 字段，`CODEC` 在 `:38`–`:50`。**字段顺序即 `RecordCodecBuilder` 参数顺序，动一个就要同步改构造函数。**

| 磁盘键 | 类型 | 必填 | 缺省 | 备注 |
|---|---|---|---|---|
| `width` / `road_type` | int / int | **是** | — | 宽度（奇数）/ 0=人工 1=自然 |
| `materials` | BlockState 列表 | **是** | — | 主材，随机取一 |
| `slab_materials` | BlockState 列表 | 否 | 空 | `:43` |
| `placements` | Placement 列表 | **是** | — | **Java 字段叫 `roadSegmentList`，磁盘键叫 `placements`** |
| `spans` | RoadSpan 列表 | 否 | 空 | `:45` |
| `target_y` | int 列表 | 否 | 空 | `:46`，规划期唯一的施工剖面契约 |
| `owner_a_2d` / `owner_b_2d` | long | 否 | `Long.MIN_VALUE` | `:47`/`:48`；`NO_OWNER_2D` `:23` |

- **算法核心**：紧凑构造器（`:30`–`:36`）把 5 个列表统一 `List.copyOf(null → List.of())`，**反序列化出的对象永不出现 null 列表**；`hasOwnerPair` `:52`、`containsOwner` `:56`、`hasProtectedGap` `:67`。
- **⚠️ 唯一「键名 ≠ 字段名」的字段是 `placements`**：改它 → required 字段缺失 → 每条 road 解码失败 → 全部被隔离。

### `RoadSegmentPlacement` / `RoadSpan` / `SpanType`
- [RoadSegmentPlacement.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/core/model/RoadSegmentPlacement.java)（19 行）：`middle_pos`+`positions`，**都 required**（`:13`–`:18`）。
- [RoadSpan.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/core/model/RoadSpan.java)（21 行）：`start`/`end`（必填）+ `type`（STRING，`:16`–`:18`，`xmap(SpanType::valueOf, Enum::name)`）。
- **⚠️ `type` 是最脆的字段**：用 `SpanType::valueOf`（**非宽容**）——老存档出现已删枚举名或大小写不符 → `IllegalArgumentException` → 整条 road 解码失败 → `readRoadFile` 落 `catch (RuntimeException)` → **文件被 `quarantineCorrupt` 隔离**（对比 `RouteKind`/`RouteStatus` 都宽容解析，两套不一致）。

---

## 二、道路存储：聚合根 + 索引 + ChunkPos 分片

**存储对象**：[RoadChunkStore.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/chunk/RoadChunkStore.java)（856 行），`SCHEMA_VERSION=2` `:52`、三个 schema 字面量 `:56`–`:58`。实例按**规范化世界路径**注册在 [RoadFileStorage.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/files/RoadFileStorage.java)（145 行）的 `STORES` `:29`（`categoryRoot(level,"roads")` `:139`）。

五个文件：`schema.json`（`ROOT_SCHEMA`+`version=2`+两个目录名）、`index.json`（`INDEX_SCHEMA`+`generation`+`roads:[{fingerprint,width,roadType,minX,minZ,maxX,maxZ,file}]`）、`roads/<fp>.g<gen>.nbt`（`{"road":<RoadData 的 NBT>}`）、`chunks/<cx>_<cz>.json`（`CHUNK_SCHEMA`+`chunkX/chunkZ/roads`）、`shard-verify.json` 缓存戳。DTO：`SchemaDocument` `:822`、`IndexDocument` `:830`、`IndexEntry` `:837`、`ChunkDocument` `:848`。

- **写入事务（顺序不可换）** `persistSnapshot` `:659`：`writeSchema` `:718` → 逐条 `writeRoadFile` `:780`（只写新增/变了的）→ 逐区块 `writeChunkShard` `:750` 或删空分片 → **`writeIndex` `:728` 是提交点，只引用前面已成功写入的不可变文件** → 最后删旧 road 文件。**算法核心**：写锁内**先完整落盘再一次性发布新快照**（`publish` `:345`）；查询方只读 `AtomicReference<RoadSnapshot>`，不持锁、不读盘、不解析（注释 `:44`–`:50`）。
- **⚠️ 只读降级（R-5）**：首载 `IOException` 置 `readOnlyDegraded`（`:72`）——查询照常读空快照不崩，**所有写路径 `throw IllegalStateException`**（`:365`–`:370`）。旧实现 warn 完用空快照继续，随后任何写入都把空快照写回 `index.json`，真索引被覆盖 ⇒ 全部道路「不可见」。读成功一次即自动解除（`:310`–`:313`）。`publish` 另查 `RoadLifecycle.allowsWrites()`（`:353`），停服瞬间的落盘中途任务不会把旧世界道路写进存档。

### 主键算法 `RoadFingerprint`
[RoadFingerprint.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/chunk/RoadFingerprint.java)（74 行），FNV-1a 64（`FNV_OFFSET=0xCBF29CE484222325` `:11`、`FNV_PRIME=0x100000001B3` `:12`）。**有 owner 对**（`:18`–`:22`）：`mix(mix(mix(FNV_OFFSET,0x524F41444F574E52), min(a,b)), max(a,b))` —— **只由两端 key 决定、与路径形状无关**，同端点对重算结果稳定可替换。**无 owner**（`:24`–`:37`）：`mix(width)`→`mix(roadType)`→逐站 `mix(middlePos.asLong())`，方向由首末站比较决定（`:27` 的 `forward`），保证 **A→B 与 B→A 同 fingerprint**；空列表返回 `0L`（`:17`）。**⚠️ 这个 `long` 是文件名的一部分**（`newEntry` `:380`–`:384`），改算法 = 所有老路对不上号。

---

## 三、磁盘分类与全部 SCHEMA

| category | 文件 / 格式 | SCHEMA |
|---|---|---|
| `roads` | §二 那套（`RoadFileStorage:28`） | **2** |
| `planned_paths` | 每端点对一文件，`MAGIC=0x52575050`（"RWPP"）、Deflate+CRC32、`MAX_PATH_POINTS=4_000_000`（[FilePlannedPathStore.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/planning/path/FilePlannedPathStore.java) `:33`–`:35`） | **1** `:34` |
| `planning-readiness` | `chunky-ready-regions-v2.txt`（[RoadPlanningReadinessIndex.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/planning/RoadPlanningReadinessIndex.java) `:55`，`REGION_CHUNKS=32` `:45`） | **2** `:48` |
| `construction-ledger` | `constructed-chunks-v3.txt`（§四） | **3** `:27` |
| `routes` | `routes.json`（§六） | **`version=1`** |
| `structures` 等另 5 个 | `state.json`（[StructureFileStorage.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/files/StructureFileStorage.java) 947 行 `:61`–`:62`）/ 断点 / 精确采样 / 路牌 / 路边内容 | 未逐个核对 |

**统一 IO 语义**（[FileStorageIO.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/files/FileStorageIO.java) 82 行）：`writeBytesAtomic` `:19` = 同目录 `.tmp` → `moveIntoPlace` `:41`（先试 `ATOMIC_MOVE`，失败降级 `REPLACE_EXISTING`）；`quarantineCorrupt` `:68` = 改名 `<name>.corrupt.<epochMillis>`，**永不抛异常**。**⚠️ 目录 key 陷阱**：`dimensionKey()` 把 `minecraft:overworld` 拼成 `minecraft_overworld`，**但所有读写门面硬判主世界**（`RoadFileStorage:142`、`RoadShardStorage:159`、`RoadConstructionLedger:137`），**下界/末地目录理论存在但代码永不写**。

---

## 四、施工台账与世界生成指纹

### `RoadConstructionLedger`
[RoadConstructionLedger.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/RoadConstructionLedger.java)（148 行）：记录「每个整区块里哪些道路 fingerprint 已施工完」。**文件是纯文本行协议**（`:66`–`:73`）、**不是 JSON**：`# RoadWeaver constructed road chunks` / `schema=3` / `entry=<chunkX>,<chunkZ>,<roadFingerprint>`。`load` `:96` 解析 `#` 注释、`schema=` 头、`entry=` 三段 `split(",",3)`；**`schema != 3` 整体忽略**（`:118`–`:121`），**不迁移**。
- **算法核心**：内存 `ConcurrentHashMap<Long chunkKey, Set<Long> fingerprint>>`（`:145`）；`markComplete` `:44` 用 `computeIfAbsent(newKeySet).add(...)` 的返回值决定置脏（`:47`–`:49`，先查 `allowsWrites()` `:45`）；`flush` `:52` 用 `dirty.compareAndSet(true,false)` 抢写权，落盘前**按双键排序**（`:63`）保证可复现。
- **⚠️ 换 `SCHEMA` 必须同时换文件名**，否则老文件被读到后整体判 stale。

### `WorldgenFingerprint`
[WorldgenFingerprint.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/WorldgenFingerprint.java)（19 行）是值对象 `(namespace, schemaVersion, dataVersion)`，构造器校验非空 / `schemaVersion>=1` / `dataVersion>=0`（`:8`–`:18`）。计算见 [WorldgenFingerprintService.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/WorldgenFingerprintService.java)（163 行）：`CURRENT_SCHEMA_VERSION=1` `:32`、`DOMAIN="roadweaver:worldgen_fingerprint"` `:33`。
- **算法核心**（`create` `:98`–`:131`）：SHA-256，**按固定顺序喂 8 段**——`DOMAIN`、`schemaVersion`(int)、`dataVersion`(int)、`dimension.toString()`、`seed`(long)、`generatorIdentity`、`settingsIdentityBytes`、`settingsPayloadBytes`。后两段分别是 `NoiseGeneratorSettings.CODEC`（identity，含 holder key）与 `DIRECT_CODEC`（payload，完整值）编成 NBT 再 `putBytes`；**每段先写长度前缀**（`putString` `:141`、`putBytes` `:147`）避免拼接歧义。`generatorIdentity` `:133` = `typeKey + "|" + generator.getClass().getName()`——**带实现类全限定名**，换实现类（哪怕 type key 相同）指纹就变。
- **⚠️ 必须缓存**：结果按 `(ServerLevel, schemaVersion)` 双层缓存（`:49`–`:50`、`forLevel` `:54`）。注释 `:35`–`:48`：它曾被主线程 `onEndTick → PlannedPathCache.find` 反复调用，而 `find` 在查内存缓存**之前**就无条件重算 ⇒ 每 tick 一次全量 Codec 编码 + SHA-256，生物冻结。**算法与 schemaVersion 不变，存量 `planned_paths` 缓存全部继续有效。**

---

## 五、空间索引与查询

### `RoadFootprint`
[RoadFootprint.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/chunk/RoadFootprint.java)（240 行）：把「这条路实际占哪些 XZ 方块」编译成 `Map<chunkKey, 256-bit 位图>` 供无盘查询。`WORD_COUNT=4`（4×64=256bit=16×16）`:25`、`MAX_WIDTH_RADIUS=256` `:26`、`EMPTY` 单例 `:27`。
- **算法核心**（`from` `:50`）：每站用 `middlePos` 画**宽度圆盘**（`addDisk` `:203`：半径 `ceil(max(0.5,width/2))`，判定 `dx²+dz² <= halfWidth²+1e-9`），再把 `positions` 每列逐个打点，最后把 `spans` 的**起终点也补画圆盘**（`:68`–`:76`：跨度端点有时来自桥梁后处理，补入避免桥头跨区块时漏索引）。
- **圆盘是兜底**：`middlePos` 圆盘覆盖**缺 `positions` 的旧/中间数据**，`positions` 才是生成器真正光栅化出的精确列（`:20`–`:22`）；查询先命中区块再用位图过滤，不会被整条路 AABB 误报。API：`chunkKeys()` `:84`、`touchesChunk` `:108`、`intersectsRect` `:112`（AABB 早退 → 逐区块 `ChunkMask.intersects`）、`containsLocal` `:150`（`bit=(localZ<<4)|localX`）。
- 查询载体 [RoadSnapshot.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/chunk/RoadSnapshot.java)（277 行）：`entries` + `chunkReferences` 倒排（`:36`–`:45`）；**只读，写入构造新实例经 `AtomicReference` 发布**（`:17`）。

### `RoadSpatialIndex` / `RoadPositionQuery`
- [RoadSpatialIndex.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/RoadSpatialIndex.java)（82 行）**不读 §二的磁盘索引，而复用世界生成期的区块计划**：`isNearRoad` `:37` 先判主世界 + `roadsEnabled()` + `preventTreesOnRoad()`（`:38`–`:43`），再 `RoadWorldgenPlanCache.get(...)` → `RoadDensityStamp`，最后 `isOccupied || fillLateral>0 || carveLateral>0`（`:50`–`:52`）；注释 `:22`–`:23` 说明它与 Beardifier **共享同一会话计划、不需要第二套 LRU**（植被阶段在道路 `TOP_LAYER` Feature 之前）。[RoadPositionQuery.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/RoadPositionQuery.java)（37 行）是纯门面，3 个 `isOnRoad` 重载全转它，**用途是阻止树木在路面上生成**；失效在写侧由 [RoadShardStorage.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/persistence/sharded/RoadShardStorage.java)（166 行）的 `RoadWorldgenPlanCache.invalidate(level, RoadFootprint.from(road).chunkKeys())` 负责（`:163`–`:165`）。

---

## 六、玩家手绘路线持久化（`route/` 包，29 文件 / 9874 行）

核心类：[RouteStorage.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/RouteStorage.java) 311、[PlayerRoute.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/PlayerRoute.java) 194、[RouteInjector.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/RouteInjector.java) 230（`plan` `:61`、`primeCache` `:114`）、[RouteKind.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/RouteKind.java) 72、[RoutePoint.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/RoutePoint.java) 54、[RouteStatus.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/RouteStatus.java) 47。
- **Codec 契约**：`PlayerRoute.CODEC` `:38`–`:50` = `id`（STRING↔UUID，必填）+ `name`（可选 `"route"`）+ `points`（必填）+ `status`（可选 `draft`）+ `created_at`/`updated_at`（可选 `0`）；`RoutePoint.CODEC` `:24`–`:31` = `pos`（必填）+ `kind`（可选 `"road"`）。**`RouteKind` 的 3 值存 `serializedName` 而非 `name()`**（`:23`–`:29`：`"road"`/`"bridge"`/`"tunnel"` + 编辑器 RGB `0x7CD44F`/`0x4FA8FF`/`0xFF9A3C`），理由 `:39`「小写、稳定、不随枚举改名而变」；`fromSerializedName` `:55` **宽容解析，认不出退回 `ROAD`**；`RouteStatus` 5 值（`draft`/`ready`/`building`/`done`/`cancelled`）同法，`:36` 退回 `DRAFT`。
- **文件**：`routes/<维度>/routes.json`，外层 `{"version":1,"routes":[…]}`。**不分片**的理由：路线是玩家手画级别（个位到几十条），单文件读写简单、原子替换容易做、出问题便于人肉查看（注释 `:31`–`:34`）。
- **四道数据保护（全是审计修复）**：① **顶层解析失败 → 隔离为 `routes.json.corrupt-<epochMillis>`**（`quarantineCorrupt` `:121` 附近）再返回空表，否则下次编辑就把空表写回、路线永久丢失；② **单条失败只跳过该条**（`decodeList` 用 `resultOrPartial`）；③ **点数上限 `MAX_POINTS_PER_ROUTE=4096`** `:141`（平滑/注入按 N 展开，超限会卡死主线程）；④ **写前备份 `routes.json.bak`**（`backupPrevious` `:259`）。
- **写串行化 + 返回值**：`saveAll` 用 `synchronized (writeLock(level))`，锁表是 `synchronizedMap(new WeakHashMap<>())`（`:243`–`:245`）——客户端 tick 与服务端 `submitForBuild` 写**同一个 `.tmp`**，Windows 下并发写多半 `AccessDeniedException`；返回 `boolean`，调用方 [RouteEditorSession.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/route/editor/RouteEditorSession.java) 只有**真写盘成功才清 `dirty`**（`:864`–`:866`，`AUTOSAVE_INTERVAL_MS=15_000L` `:904`）。

---

## 七、API 模块契约（`api` / `api-fabric`）

**为什么物理分离**：`roadweaver-api` 是**独立部署**小 mod（id `roadweaver_api`，版本 `1.0.0`），只含契约零实现。历史教训在 [RoadWeaverApi.java](roadweaver/api/src/main/java/net/shiroha233/roadweaver/api/RoadWeaverApi.java) `:13`–`:28`：原 `…api.interop` 契约与实现混在一起，0.8.406 删实现时把契约一起删了，**外部五个 mod 全部静默失效**，且它们的测试吃同一个 compile-only 幽灵 jar ⇒ 测试一直绿、运行时才炸。

**三条隔离**：① `api` 模块**不 import 任何非 api 包**，由 `ApiModulePurityTest` 钉住；② 实现经 [RoadWeaverBackend.java](roadweaver/api/src/main/java/net/shiroha233/roadweaver/api/RoadWeaverBackend.java)（76 行，**22 个方法**）注入，`api` 对实现零引用 ⇒ RoadWeaver 不装时仍能加载、每方法安全降级；③ 消费方 `fabric.mod.json` 写 `depends: { "roadweaver_api": ">=1" }` ⇒ 缺失是**加载期明确报错**，不是运行期 `NoClassDefFoundError`。

- **契约面**：`RoadWeaverApi`（122 行）`API_VERSION=1` `:44`（**只有破坏性改动才递增**，新增能力走 `hasCapability`）、4 个能力常量 `CAP_STRUCTURE_RESERVATION`/`CAP_PLANNING_LIFECYCLE`/`CAP_PLANNING_READINESS`/`CAP_LOAD_SIGNALS`（`:47`–`:53`）、`installBackend` `:67`（只给实现方调；注入时补注册后端未就绪期间排队的 lifecycle 监听器 `:74`）、`isAvailable` `:89`、`capabilities` `:95`、`hasCapability` `:105`、`describe` `:115`（一行 `api=1 available=true caps=[…]`，用来区分「没装」与「名字对不上」）。
- [RoadNetworkApi.java](roadweaver/api/src/main/java/net/shiroha233/roadweaver/api/RoadNetworkApi.java)（83 行）：`registerStructureEndpoint`（3 重载）、`ensureConnection`、`planRegion`；**每方法都 `backend == null` 直接 return + `catch (RuntimeException | LinkageError)` 静默吞**（`:43`–`:47`）。
- **双方向**：`api/incoming/`（10 文件，别人调我们）与 `api/outgoing/`（12 文件，双向协调：`RoadLoadSignals`+`Registry`+`Snapshot`+`LoadKind`、`StreamsThreadControl`、`GreatWallReadiness`、`RiverReadinessBroadcast`、`ChunkyRuntime`、`CruiseSignals` 各带注册表）。

---

## 八、跨 mod 桥接（`compat/` 包）

7 个文件：[GreatWallPrewarmCompat.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/compat/GreatWallPrewarmCompat.java) 312（长城预热门）、[GreatWallRoadGuard.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/compat/GreatWallRoadGuard.java) 186（长城只读守卫）、[StreamsReflowingCompat.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/compat/StreamsReflowingCompat.java) 519（Streams 河流计划）、[WallRoadAdapter.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/compat/WallRoadAdapter.java) 52（**唯一直接引用长城 api 的类**），另 3 个 Mixin 接口 `RoadBeardifierAccess` 13 / `SinglePoolElementTemplateAccess` 8 / `StructureTemplatePaletteAccess` 8。

- **长城只读守卫**：0.8.460 起**不用反射**——长城把接口编译进只含 `api` 包的极小 jar，道路侧 `compileOnly` 依赖它，于是**签名一变编译就失败**；没装时类加载风险全关在 `WallRoadAdapter`（`present()` `:17` 用 `Class.forName(...,false,cl)` 探一次并缓存，调方必须先问）。通道枚举 `Ch.` 含 `SURFACE`/`CLEAR`/`BRIDGE`/`BUOY`/`DECO`/`DENSITY`/`CUT`，`CUT`（挖方）是后加的——注释 `:19`–`:25` 记着它是「把墙挖成通高竖缝的真凶」：长城永远高于地表，高度图把墙顶当「地面」，施工期就把整面墙当十几格深的土方削平。
- **长城预热门**：道路在长城预热完前**不开始初始生成**，让「河流→长城→道路」严格串行（注释 `:12`–`:20` 的实测：某局道路 15:01:01 就开工而长城细采样未完，两者抢核）。全异步非阻塞：探针调长城零超时 `awaitPrewarm(0, NANOSECONDS)`，daemon scheduler 每 `POLL_MILLIS=250` 轮询；`DEFAULT_TIMEOUT_MILLIS=0L`，靠「长城在所有退出路径都开门 + `STALL_WINDOW_MILLIS` 状态不变自动放行 + `ABSOLUTE_CAP_MILLIS`」三重界定。
- **Streams**：反射读其公开河道计划，两条独立超时——`STREAMS_PREWARM_TIMEOUT_MS=60_000L`（原是无参 `join()`，实测卡 ≥2 分钟）、`STREAMS_QUERY_TIMEOUT_MS=15_000L`（实测卡过 186 秒）；超时后降级为「只用原版河道识别」=等于没装，**路会扭但不卡死不崩**。**反向桥** [RoadWeaverPathFeature.java](roadweaver/common/src/main/java/dev/streamsreflowing/compat/roadweaver/RoadWeaverPathFeature.java)（16 行）是**故意写进 Streams 包名**的 `PathFeature` 子类，让道路顶层施工 feature 不被 Streams 的通用植被否决。
- **Mixin 接口（`roadweaver$` 前缀）**：`RoadBeardifierAccess` 挂区块道路密度计划到原版 Beardifier；另两个取结构模板/palette，供结构避让与路边结构预计算。

- **Twilight —— 未找到**：`grep -rni "twilight"` 在 `common/src`、`fabric/src`、`api/src` 下**零命中**。`compat/` 只有长城 + Streams 两个外部 mod 桥，**没有 Twilight Forest（暮色森林）相关代码**。

---

## 九、存档兼容与迁移路径

### 老道路读取：`normalizeLegacyRoad`
[RoadDataValidator.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathlogic/validation/RoadDataValidator.java)（544 行）的 `normalizeLegacyRoad(RoadData)` `:448`，在 `RoadChunkStore.readSnapshot` `:426` 每读一条路都调一次。做四件事，**只为「结构上还能用」的老数据放行**：① **`roadSegmentList` 少于 5 站 → 返回 null**（`:449`）；② 每站必须 `middlePos != null` 且 `positions` 非空（`:452`–`:455`），这解释了 `positions` 为何是 required；③ **补 `target_y`**（`:458`–`:464`）——老版本无此字段，逐站取 `sourceY[i]`，缺失**回落 `middlePos.getY()`**，这是「pre-target_y 存档还能施工」的唯一机制；④ **清洗 `spans`**（`:466`–`:484`）——端点必须能在各站 XZ 集合 `centers` 里找到否则丢弃，`start==end` 且类型不是 `PROTECTED_GAP`/`JUNCTION_ANCHOR` 的丢弃，同 `(type,minXZ,maxXZ)` 去重。**分界**：新路与替换路仍必须过 `isValidFinalRoad` `:35`（由 `RoadChunkStore.isValidRoad` `:818` 调）——`normalizeLegacyRoad` 是**只读兼容层，不是放宽校验**（注释 `:443`–`:447`）。

### 损坏隔离（所有路径都不抛异常）
`index.json` 解析失败或缺 `roads` → `quarantineCorrupt` + 空快照 + **进入只读降级拒写**（`RoadChunkStore:388`/`:395`/`:320`）；road 文件空/缺 `road` 根/解码失败/`RuntimeException` → `quarantineCorrupt` 后跳过该条（`readRoadFile:793` 四分支）；分片 JSON 坏 → 隔离 + `valid=false` 触发元数据重写（`chunkShardsMatch:545`）；待生成路径文件坏 → 隔离后抛 `IOException`（`FilePlannedPathStore.load`）；`routes.json` 顶层坏 → 改名 `.corrupt-<ts>` 保留后返回空表（`RouteStorage:121`）；台账 `schema != 3` → **整体忽略**（`RoadConstructionLedger:118`）。

### 跨版本升级现状
- **道路数据无版本迁移，只有「结构兼容 + 隔离」**：`hasCurrentRootSchema()` `:461` 发现 `schema.json` 版本不符就把 `rewriteMetadata` 置真，下次修改时用当前内存快照**整体重写全部元数据**（`persistSnapshot(..., forceMetadata=true)` `:659`）。即**能读就重写元数据、读不了就隔离**，没有「v1→v2 逐条转换」。
- `shard-verify.json` 是可重建缓存，删掉只让下次走慢速通道，不丢数据。
- **指纹变了 ⇒ `planned_paths` 全部失效重算**（设计行为，由 `PlannedPathCache.PATH_WORLDGEN_SCHEMA=1` 与 `WorldgenFingerprintService.CURRENT_SCHEMA_VERSION=1` 共同决定）。
- **台账换 schema = 全量重施工**；**路线文件 `version` 只写不读**（`saveAll` 写 `version:1`，`loadAll` 不校验，升级只需保证 Codec 的 `optionalFieldOf` 默认值能兜住老键）。

### ⛔ 不能动的存档 schema（一动就让老存档失效）

| 契约（位置） | 一动就怎样 |
|---|---|
| **`RoadData` 9 个 Codec 键名与顺序**（`:38`–`:50`），尤其 **`placements`（≠ 字段名 `roadSegmentList`）**、`materials`、`positions` 三个 **required** 键 | required 键改名/删除 → 每条 road 解码失败 → **全部被隔离**，玩家的路一夜消失 |
| **`RoadSpan.type` 用 `SpanType::valueOf`（严格）+ `SpanType` 8 个常量名**（`RoadSpan:16`–`:18`、`SpanType:6`–`:20`） | 删/改任一枚举名 → 含该 span 的路解码抛异常 → 整条路被隔离 |
| **三个 schema 字面量 `roadweaver.roads`/`.index`/`.chunk` 与 `version=2`**（`RoadChunkStore:52`、`:56`–`:58`） | `hasCurrentRootSchema` 判假 → 全量元数据重写；同时改 `ChunkDocument` 结构则 `chunkShardsMatch` 判假 → 反复重写 |
| **`WorldgenFingerprintService` 的 `DOMAIN` + 8 段喂入顺序 + `CURRENT_SCHEMA_VERSION=1`**（`:32`–`:33`、`:121`–`:129`）；**`routes.json` 外层 + `PlayerRoute`/`RoutePoint` 键名**（`PlayerRoute:38`、`RoutePoint:24`）；**存档根 `data/roadweaver/persistence` + `dimensionKey`**（`FileStoragePathResolver:14`/`:24`–`:27`） | 指纹全变 ⇒ 预热与路径缓存全部失效重算（不丢存档但等于让所有玩家重跑一次）；外层键变 → 空表 → 手绘路线丢失（`.bak` 可救）；目录变 → 读不到任何旧数据 |

