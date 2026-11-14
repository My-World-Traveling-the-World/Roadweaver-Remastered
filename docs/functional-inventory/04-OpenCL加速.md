# RoadWeaver 旧版「OpenCL 加速」功能域盘点

> 依据：`D:\云游四海-Mod源码\roadweaver`，HEAD `2c6547a683f6219a5b7c9c4d9f871a77cf3751e0`（冻结 oracle）；只读静态盘点 + git 历史取证（`git log -S`、`git show <commit>:<path>`），未启动游戏、未构建、未改旧仓库。「是否被调用 / 是否死键」一律由全仓符号引用清单（`grep -rn <Symbol> --include=*.java`）加 git 历史判定，不采信注释；注释与 git 冲突处单独标注。
>
> 总链路：**`CoarseHeightBatchSamplers.create` → `OpenCLCoarseHeightBatchSampler.sampleHeights` → `OpenCLRuntime.submit` → `OpenCLSubmissionGate` → `OpenCLBridge.Session` → kernel `roadweaver_coarse_height_sample`**；精确采样没有 GPU 分支。
>
> 出厂组合：粗采开（`openclDevicePreference=AUTO`、`openclMinSamples=1024`、`openclValidateSamples=false`）、游玩期让路（`runtimeCoarseUseCpuInGameplay=true`、`runtimeCoarseMaxThreads=2`、`runtimeCoarseMinThreads=1`）、预算 `0.70`/`4096 MiB`、`coarseHeightBandCount=4`；死键 `openclAccurateSamplingEnabled=true`。定义于 [PerformanceConfig.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/config/sub/PerformanceConfig.java)（378 行）。
>
> 行号口径：静态扫描所得，可能 ±2 行偏差，定位优先按方法名。

## 〇、核心结论：精确采样有没有真的用过 GPU

**冻结版没有。** 精采一次 GPU 都不尝试，整条 GPU 精采链是「编译进 jar、零调用者」的死代码。

- **接线**：[AccurateHeightBackends.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/AccurateHeightBackends.java)（21 行）的 `create()` 在 12–20 行：13–18 行是 `4060 / 2046 MiB / clCreateBuffer -61` 英文注释，19 行只有 `return cpu;`。唯一入口是 [AccurateHeightSampler.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/AccurateHeightSampler.java)（1090 行）159 行，故精采 `backendName()` 恒为 `"CPU"`（`CpuAccurateHeightBackend.java:245–247`）。
- **死代码**：`OpenCLAccurateHeightBackend` 全仓 **0 个实例化点**，唯一构造入口 `tryCreate()`（53–70）**0 个调用者**，`OpenCLRuntime.tryCreateAccurateGpu()`（83–101）的唯一调用者也在这个 `tryCreate`（57）。同簇 9 个文件合计约 2311 行、只互相引用；外部只剩 `OpenCLAccurateProgramCache.clear(...)`（[CacheManager.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/runtime/CacheManager.java) 207）清一个永远空着的表。
- **死键**：`openclAccurateSamplingEnabled` 只被字段（`PerformanceConfig.java:79`）、`snapshot()`（256）、旧配置迁移（[ConfigService.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/config/ConfigService.java) 261）、界面（[ConfigScreenFactoryImpl.java](roadweaver/fabric/src/main/java/net/shiroha233/roadweaver/client/fabric/ConfigScreenFactoryImpl.java) 1403–1407）引用，**没有采样代码读它**；界面 1398–1401 自认「该开关全项目**无任何读取点**」。
- **git 时间线**：`76b6c82`（0.8.67，2026-08-19）工厂活的（先查死键再 `tryCreate`，失败才 CPU）；`7fbe1cc`（0.8.240，2026-09-02）删分支只留 `return cpu`；`2657962` 只是移包（R093）。**CPU-only 是 0.8.240 的既有事实，重构版只是继承**。**（主控核实更正）**：`AccurateHeightBackends` 的注释里**没有任何版本号**；`0.8.340` 全仓只出现在 `CoarseCorridorPlanningAdapter:80/234/247`（讲并发等待，与 OpenCL 无关）；`0.8.240` 在源码注释里也不存在（属提交侧事实）⇒ 时间线只认 `git log`/`git show`。
- **浪费路径存在过、被同一次提交掐掉**：0.8.67 的 `OpenCLAccurateBatchPlanner` 用 `max(latticePerChunk, aquiferPerChunk, 256)` 估每 chunk scratch（`git show 76b6c82:...` 26–28），把 `64 lane × 256 列 = 16384` 个 work-item **低估 64 倍**，实际单块 scratch 即估算的 64 倍，必撞 `CL_DEVICE_MAX_MEM_ALLOC_SIZE` → `-61` → 回退；0.8.240 改成 16384（现 `OpenCLAccurateBatchPlanner:39–44`）、加 `perBufferCap = 0.80 × maxAllocation`（60），同时关掉整条路。

## 一、上下文 / 设备 / 队列的生命周期

### 会话、队列、失效

- **做什么**：建一次 context/queue/program/8 kernel 并按设备偏好复用；native 调用串行，精采排队时压制粗采，设备坏了整体作废。
- **关键类**：[OpenCLRuntime.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLRuntime.java)（350 行）、`OpenCLBridge.java`（521 行）、[OpenCLSubmissionGate.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLSubmissionGate.java)（98 行）。
- **关键参数**：`RUNTIMES = LinkedHashMap<RuntimeKey, OpenCLRuntime>`（36）；粗采 lane 数 `round(总显存/4 GiB)` 夹 `1..4`（`OpenCLCoarseHeightBatchSampler:295–313`）；三档 `ACCURATE/COARSE/MAINTENANCE`、公平锁（Gate:14）。
- **算法核心**：`tryCreate` 查 `OpenCLAvailability` 后按 key 找 `isUsable()` 会话，否则 `open()`：`CL.create()` 幂等（46–64）→ `selectDevice` 三层循环「类型→平台→设备」取首个过 FP64 者（108–146）→ `clCreateContext → clCreateCommandQueue(PROFILING) → clCreateProgramWithSource → clBuildProgram → 8×clCreateKernel`（66–106），失败逆序释放（99–105）。提交经闸门再进 `operationLock`（212–224），闸内 `while (running || (priority==COARSE && waitingAccurate>0)) await()`（Gate:62–79）；`invalidate()` CAS 只关一次（291–301），`close()` 空实现（303–306），寿命归服务器级 `closeAll()`（312–327，由 `CacheManager.java:185` 调用）。

## 二、内核编译与缓存

### 单一 .cl 源、8 个 kernel 一次性编译

- **做什么**：整份源码编成一个 program 再按名取 8 个 kernel；编译前注入 Lithostitched 的 FastNoise 向量表；密度图按 RandomState 缓存复用。
- **关键类**：[OpenCLCoarseHeightKernel.cl](roadweaver/common/src/main/resources/assets/roadweaver/opencl/OpenCLCoarseHeightKernel.cl)（1671 行）、`LithostitchedKernelSupport.java`（66 行）、`OpenCLCoarseHeightBatchSampler.java`（700 行）。
- **关键参数**：源路径常量 `/assets/roadweaver/opencl/OpenCLCoarseHeightKernel.cl`（`OpenCLRuntime.java:34`）；编译选项空串、无 notify（`OpenCLBridge.java:87`）；占位符 `/*__LITHOSTITCHED_FAST_NOISE_TABLE__*/`、表长 1024 float（`LithostitchedKernelSupport.java:10、22–28`）；`PROGRAM_CACHE = IdentityHashMap<RandomState, CachedDensityProgram>`（`OpenCLCoarseHeightBatchSampler.java:55`）。**磁盘编译产物缓存：未找到**（无 `CL_PROGRAM_BINARIES`/`clGetProgramInfo`，每次建会话都重编）。
- **算法核心**：`clBuildProgram(program, {device}, "", null, 0L)`，非 0 时读 `CL_PROGRAM_BUILD_LOG` 拼进异常（87–90、196–200）；浮点用 `Float.toHexString` 保位精确，拿不到向量填 0 表并 warn，且 `DensityGraphCompiler.java:281–283` 直接判该密度图不支持。kernel 名见 `OpenCLRuntime.java:24–31`；`.cl` 行号 coarse 846、精采 7 个 1367–1595。**7 个精采 kernel 也照样参与编译并各建对象**——`.cl` 任一处编译错误都会让粗采会话起不来。密度图按 RandomState 身份查缓存，未命中才 `DensityGraphCompiler.compile(router.initialDensityWithoutJaggedness())`（`OpenCLCoarseHeightBatchSampler.java:139–171`）；编译结果三态 `supported/unsupported/retryable`（586–608）：后者只打一次日志、下轮再试，前者永久否决。

## 三、粗采样：唯一真正走 GPU 的一类

### 六道闸门

- **做什么**：不划算或会抢玩家 GPU 的请求提前转 CPU，并把原因写到进度条。
- **关键类**：[CoarseHeightBatchSamplers.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/CoarseHeightBatchSamplers.java)（95 行）。
- **关键参数**：`openclCoarseSamplingEnabled=true`、`openclMinSamples=1024`、仅 `Level.OVERWORLD`、`runtimeCoarseUseCpuInGameplay=true`。
- **算法核心**：①配置关（47）②request 空（53）③非主世界（56）④游玩活跃（`isForegroundPressureActive`＝巡航/交互/玩家活动，`RuntimeLoadPolicy.java:265–269`）且非开局/Chunky（63–71）⑤`sampleCount()<openclMinSamples`（73–79）⑥才 `tryCreate`（88–90）。不满足即 `setBackend("CPU","CPU",reason)`（37）。

### 数据形态与边界

- **做什么**：每个地形列在 GPU 上独立做 FP64 密度二分；其余阶段全在 CPU。
- **关键类**：`OpenCLCoarseHeightBatchSampler`（700 行）、[OpenCLDensityProgramBuffers.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLDensityProgramBuffers.java)（64 行）、`OpenCLCoarseWorkspace`（71）。
- **关键参数**：17 个 kernel 参数＝1 params＋14 张静态表＋scratch＋heights（418–424）；`#pragma OPENCL EXTENSION cl_khr_fp64` 与 `FP_CONTRACT OFF`（`.cl` 2–3）。
- **算法核心**：坐标先 `align_to_cpu_grid(min+k*step)`，样本索引决定 x/z（`.cl` 846–875）；「网格对齐＋逐列独立」是分带（`coarseHeightBandCount`）不改结果的前提（`PerformanceConfig.java:165–168`）。群系/水体、瓦片缓存、寻路、修坡、纵断面全为 CPU。

### 提交节奏

- **做什么**：开局开满，玩家操作/Chunky 时收敛或暂停。
- **关键类**：[RuntimeCoarseGpuBudget.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/RuntimeCoarseGpuBudget.java)（372 行）、[CoarseTerrainTileSampler.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/terrain/region/CoarseTerrainTileSampler.java)（340 行）。
- **关键参数**：`runtimeCoarse*`/`pauseCoarseGpu*` 默认值见 §八；硬边界 `MIN_BUDGET=1`、`HARD_MAX_BUDGET=16`、刷新 1 s／恢复 3 s／日志 5 s（31–35）；`coarseHeightBandCount=4`。
- **算法核心**：硬件上限 `max(1, min(16, (总显存/2)/256 MiB))`（311–323）；`refresh()` 按四态（STARTUP、CHUNKY_PREFLIGHT 开满；CHUNKY_RUNNING、GAMEPLAY_ACTIVE 压到 min；GAMEPLAY_IDLE 用 max）逐级 ±1 收敛（201–270）；`acquire()` 在 Chunky 独占（`RuntimeLoadPolicy.java:196–198`）或地图/任意 GUI 打开时 park（60–97）。**额度不够是排队等，不是转 CPU**（356–360）。

## 四、精确采样：代码在、接线断

### 设计上的 7 kernel 流水线

- **做什么**：把格点、初步地表、含水层与三类高度图搬上 GPU，一次算一个 chunk。
- **关键类**：[OpenCLAccurateProgram.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLAccurateProgram.java)（920 行）、`OpenCLAccurateWorkspace`（161）、`OpenCLAccurateLayout`（56）、`AquiferPositionPlanner`（141）、`SparseLatticePlanner`（98）。
- **关键参数**：`HEIGHT_PARALLEL_LANES=64`（42）、`MAX_ADAPTIVE_SPLITS=10`（46）、`MIN_RECOVERABLE_BATCH=4`（50）、`WATCHDOG_SAFE_MAX_CHUNKS=128` 与 `MAX_COLUMNS_PER_CHUNK=256`（`Planner:17、21`）。
- **算法核心**：`sample()` 算 `maxChunks` 后按方块 tile 切批（`tileSize=floor(sqrt(maxChunks))`，`Planner:89`），单次 `ACCURATE` 提交串完全部子批（133–167）；稀疏列路径对 `-61` 只重试失败子批，且按 **chunk** 而非按列拆（207–249）。

### 缓冲规划、单缓冲上限、自适应拆分

- **做什么**：在设备单缓冲上限与整批总量间算出能真正分配成功的子批。
- **关键类**：[OpenCLAccurateBatchPlanner.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLAccurateBatchPlanner.java)（111 行）。
- **关键参数**：`MIN_DEVICE_BUDGET=16 MiB`；`perBufferCap = max(16 MiB, 0.80 × maxAllocation)`（60）；`globalBudget = max(16 MiB, 探针预算/4)`（65–69）；`maxChunks` 再夹 128（78）。
- **算法核心**：`scratchPerChunk = max(lattice, aquifer, 64×256) × nodeCount × 8`（39–44），连同 `latticePerChunk/aquiferPerChunk/outputPerChunk`（45–51）取三商最小（70–73）；`maxChunks<=0` 抛 `IllegalStateException("OpenCL device memory cannot fit one accurate chunk batch")`（`OpenCLAccurateProgram.java:150–152、185–187`）。**按公式推算**（非实测）：`nodes ≥ ⌈0.8×2046 MiB/(16384×8)⌉ ≈ 13100` 时 `byScratch=0`，走不到 `clCreateBuffer`；nodes 更低时单块 scratch ≤ 0.8×maxAllocation，通常也不再 `-61`。

### 校验、回退与连带影响

- **做什么**：容忍 ±1 格浮点差、不容忍算法错；不让一次分配失败毒化会话。
- **关键类**：`OpenCLAccurateHeightBackend`（590 行）、[FullRegionPlanningAdapter.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/planning/terrain/FullRegionPlanningAdapter.java)（110 行）、`RoadTerrainPlanningPipeline`（87）。
- **关键参数**：`VALIDATION_TOLERANCE=1`（`Backend:39`）；每批校验复用 `openclValidateSamples`（373–379）；接口默认 `supportsAcceleratedSampling()==false`（`AccurateHeightBackend.java:87–89`）。
- **算法核心**：GPU 与 CPU 逐点比，不符则 `markUnsupported`＋整批回 CPU（107–124）；`-61 / CL_INVALID_BUFFER_SIZE / CL_INVALID_MEM_OBJECT` 判可恢复、不 `invalidate`（126–138、260–272）。连带：非圆形 `FULL_REGION` 在 `FullRegionPlanningAdapter.java:36–38` 抛 `FullRegionUnavailableException("FP64 OpenCL GPU is unavailable")`，`RoadTerrainPlanningPipeline.java:64–68` `session.downgrade(...)` 切粗走廊——**选 FULL_REGION 的净效果就是降级回 COARSE_CORRIDOR**；圆形局部精采不抛（40–52），继续用 CPU。

## 五、显存 / 缓冲区分配策略

### 缓冲区：没有预检、两层预算、单批闸

- **做什么**：读设备总显存与单缓冲上限；按真实空闲显存划额度、给单批需求设闸、连续批次复用大缓冲。
- **关键类**：`OpenCLBridge`（521 行）、[GpuMemoryProbe.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/GpuMemoryProbe.java)（504 行）。
- **关键参数**：`CL_DEVICE_GLOBAL_MEM_SIZE`、`CL_DEVICE_MAX_MEM_ALLOC_SIZE`、`CL_DEVICE_MAX_WORK_GROUP_SIZE`（`OpenCLBridge.java:162–164`），`DeviceInfo` 六字段（264–269）；`gpuBudgetFreeFraction=0.70`、`gpuBudgetHardCapMiB=4096`；三层探测 `PDH_LIVE/CONSERVATIVE_RATIO/UNAVAILABLE`（`GpuMemoryProbe.java:93–100`）。
- **算法核心**：`Session.allocate()` 直接 `clCreateBuffer(context, flags, max(1,bytes))`，错误码转 `IllegalStateException("clCreateBuffer failed: N")`（308–318、226–229）——**Java 侧没有「先比 maxAllocation 再申请」的前置检查**；唯一特殊处理是 `OpenCLRuntime.allocate` 打一条 -61 warn（254–265），每次失败都打、无去重。预算 `configBudgetBytes = min(free×0.70, 4096 MiB)`，free 优先 Windows PDH 实时「总显存−全卡已用」（`GpuMemoryProbe.java:111–138`），退「总显存×0.70」（126–135），再退 0（137）。粗采单批闸把 `scratch = samples×nodes×8`（`OpenCLCoarseHeightBatchSampler:693–696`）与预算比，超了本批转 CPU 并标 `gpu_budget_exceeded`（377–411），但注释自承默认包下基本不触发（385–398）。

## 六、CPU/GPU 结果一致性校验

- **做什么**：首场会话验一次；配置打开则逐批全量对拍，防止驱动 bug 产出错地形。
- **关键类**：`OpenCLCoarseHeightBatchSampler`（700 行）、[OpenCLWorldSupport.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLWorldSupport.java)（28 行）。
- **关键参数**：`ValidationMode = NONE/SESSION/CONFIG`（526–534）；SESSION 最多 `32` 个均匀点（507）。
- **算法核心**：`validationMode()` 用 `SESSION_VALIDATION_LOCK` 保证每会话只做一次、其余线程 `wait()`（448–467）；抽检用 `FastHeightSampler` 走 CPU 取期望值逐点比（503–524），CONFIG 则整批 CPU 重算 `Arrays.equals`（487–496）。不一致 → `markUnsupported` 永久否决＋本批 CPU 重算＋进度条显示原因（217–225）。

## 七、平台检测与降级

### 探测顺序与失败归类

- **做什么**：无运行时/平台/设备/FP64 时安静退 CPU 并说明原因。
- **关键类**：`OpenCLBridge`（521 行）、[OpenCLAvailability.java](roadweaver/common/src/main/java/net/shiroha233/roadweaver/pathfinding/sampling/opencl/OpenCLAvailability.java)（58 行）。
- **关键参数**：类型顺序 `AUTO={GPU,CPU}`、`GPU={GPU}`、`CPU={CPU}`（188–193）；FP64 先看 `CL_DEVICE_DOUBLE_FP_CONFIG != 0`，再看扩展串含 `cl_khr_fp64`（148–158）。
- **算法核心**：`clGetPlatformIDs` 非 SUCCESS 或数量 0 直接 `return null`（115–118）→「未发现支持 FP64 的匹配 OpenCL 设备」＋`disable`（`OpenCLRuntime.java:64–69`）；**只取第一台通过的 FP64 设备，不比显存、不跑基准**（136–141）。原因归四类（编译/创建失败、运行库缺失、初始化失败，178–194）。

### 一票否决的作用域与中断语义

- **做什么**：一次失败就整场不再重试；但玩家取消不算故障。
- **关键类**：`OpenCLAvailability`（58 行）、`CacheManager`（250 行）。
- **关键参数**：`DISABLED_REASON`＋`LOGGED_DISABLED` 两个原子量（15–16）；`reset()` 只被 `CacheManager.onServerStarted()` 调用（`CacheManager.java:55`）。
- **算法核心**：`disable()` 用 `compareAndSet(null, reason)`——首个原因定终身，之后 `isAvailable()` 直接短路（20–31、38–48）；范围是「本次服务器会话」：`clearAll()` 只关会话与世界级否决（185–186）、**不重置设备级否决**。`CancellationException` 原样穿透、不 disable（`OpenCLCoarseHeightBatchSampler.java:246–262`）；`invalidate()` 只关一次（`OpenCLRuntime.java:291–301`）。

## 八、配置开关

| 键 | 默认 | 实际读取点 | 是否生效 |
| --- | --- | --- | --- |
| `openclCoarseSamplingEnabled` | `true` | `CoarseHeightBatchSamplers.java:47` | **生效**，粗采总闸 |
| `openclAccurateSamplingEnabled` | `true` | 无采样读取点 | **死键**（§〇） |
| `openclDevicePreference` | `AUTO` | 同文件 84、90 | 生效（只影响粗采） |
| `openclMinSamples` | `1024` | 同文件 73 | 生效（夹 `0..COARSE_REGION_MAX_SAMPLES`） |
| `openclValidateSamples` | `false` | 粗采 479；精采 375（死） | 生效（仅粗采） |
| `openclCoarseGpuConcurrencyCap` | `0` | `RuntimeCoarseGpuBudget.java:293–298` | **默认被遮蔽**：仅 `runtimeCoarseMaxThreads<=0` 时生效（273–282） |
| `runtimeCoarseUseCpuInGameplay` | `true` | `CoarseHeightBatchSamplers.java:63` | 生效（活跃期 CPU） |
| `runtimeCoarseMaxThreads` | `2` | `RuntimeCoarseGpuBudget.java:273–282` | 生效（挂机上限） |
| `runtimeCoarseMinThreads` | `1` | 同文件 284–291 | 生效（压力/Chunky 下限） |
| `gpuBudgetFreeFraction` | `0.70` | `GpuMemoryProbe.java:150–158` | 生效（越界回 0.70，`PerformanceConfig.java:363–366`） |
| `gpuBudgetHardCapMiB` | `4096` | 同文件 150–158 | 生效（`0`＝不封顶，`PerformanceConfig.java:340–343`） |
| `coarseHeightBandCount` | `4` | `CoarseTerrainTileSampler.java:149–152` | 生效（夹 `1..64`；开局/Chunky 不分带，107–109） |

补充：①死键只有 `openclAccurateSamplingEnabled`，界面保留但注释标注不生效（`ConfigScreenFactoryImpl.java:1398–1408`）；②`openclCoarseGpuConcurrencyCap` 是「半死」，默认组合下不被查询、仅作日志标签；③其余 GPU 邻接键默认 `1500/1500`、`true/true`（146–185）。

**只读复核**：开始与结束 HEAD 均为 `2c6547a…`，`git status --short`、`git diff --stat` 均无输出；旧仓库未改文件，本域只新增本盘点文档。