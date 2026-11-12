/* 文件职责：记录开局道路预热的阶段与进度，使崩溃/蓝屏后能从未完成处续接。 */
package net.shiroha233.roadweaver.persistence.files;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Lightweight startup checkpoint; expensive terrain data is stored separately.
 *
 * <p>0.8.355：由「两态标记」升级为「阶段 + 进度断点」。旧版只有
 * {@code state=ACTIVE|COMPLETED}，崩溃后重启只能判定「未完成」，于是
 * {@code InitialGenManager.begin()} 会从头重跑整个开局流程（真机：1024 区块下
 * 道路 10 小时起步，玩家崩溃即全部白做）。现在额外记录 {@code phase / done / total}，
 * 重启时可跳过已完成的阶段、只补未完成的连接。</p>
 *
 * <p><b>向后兼容</b>：{@code schema=1} 的旧文件仍可读，此时 {@link ResumePoint#phase()}
 * 为空，调用方按「从头开始」处理，行为与本版之前完全一致。</p>
 */
public final class InitialGenerationCheckpointStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final String FILE_NAME = "checkpoint.txt";
    private static final String ACTIVE = "state=ACTIVE";
    private static final String COMPLETED = "state=COMPLETED";
    /** 当前写入版本。schema=1 为旧版（仅两态），schema=2 起带 phase/done/total。 */
    private static final int SCHEMA_CURRENT = 2;

    private InitialGenerationCheckpointStore() {}

    /**
     * 断点信息。{@code phaseName} 为 {@code InitialGenerationStage} 的枚举名，
     * 缺失（旧 schema 或字段损坏）时为 {@code null}。
     *
     * @param phaseName 最后进入的阶段名（可能为 null）
     * @param done      该阶段内已完成的连接数（未知为 0）
     * @param total     该阶段内的连接总数（未知为 0）
     */
    public record ResumePoint(String phaseName, int done, int total) {
        /** 旧 schema 或无可信阶段信息时为 true —— 调用方应回退到「从头开始」。 */
        public boolean hasPhase() {
            return phaseName != null && !phaseName.isBlank();
        }
    }

    public static boolean isActive(ServerLevel level) {
        if (!isOverworld(level)) return false;
        return cachedState(level).active;
    }

    public static void begin(ServerLevel level) {
        if (!isOverworld(level)) return;
        write(level, lines(ACTIVE, null, 0, 0));
    }

    /**
     * 更新断点阶段与进度。由 {@code InitialGenManager} 在阶段切换与每条连接完成后调用。
     *
     * <p>写入是原子的（见 {@link FileStorageIO#writeStringAtomic}），因此崩溃最多只丢失
     * 「最后一次调用之后」的进度 —— 与用户「丢失几秒数据无所谓」的口径一致。</p>
     */
    public static void recordProgress(ServerLevel level, String phaseName, int done, int total) {
        if (!isOverworld(level)) return;
        write(level, lines(ACTIVE, phaseName, done, total));
    }

    public static boolean hasCompleted(ServerLevel level) {
        if (!isOverworld(level)) return false;
        return cachedState(level).completed;
    }

    public static void markCompleted(ServerLevel level) {
        if (!isOverworld(level)) return;
        write(level, lines(COMPLETED, null, 0, 0));
    }

    /**
     * 读取断点。文件不存在、不可读或缺少 state 行时返回 {@link Optional#empty()}，
     * 由调用方决定回退行为。
     */
    public static Optional<ResumePoint> readResumePoint(ServerLevel level) {
        if (!isOverworld(level)) return Optional.empty();
        return cachedState(level).resumePoint;
    }

    /**
     * 纯解析逻辑（便于单元测试，不依赖 ServerLevel）。可识别：
     * <ul>
     *   <li>{@code schema=2} 的新格式：带 {@code phase/done/total}；</li>
     *   <li>{@code schema=1} 的旧格式：只有 {@code state/updatedAtSeconds}，
     *       此时 {@link ResumePoint#hasPhase()} 为 false，调用方回退到「从头开始」，
     *       与本版之前的行为完全一致（向后兼容的关键）。</li>
     * </ul>
     */
    static Optional<ResumePoint> parseResumePoint(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Optional.empty();
        String phase = null;
        int done = 0;
        int total = 0;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.startsWith("phase=")) {
                String value = trimmed.substring("phase=".length()).trim();
                phase = value.isEmpty() ? null : value;
            } else if (trimmed.startsWith("done=")) {
                done = parseNonNegative(trimmed.substring("done=".length()));
            } else if (trimmed.startsWith("total=")) {
                total = parseNonNegative(trimmed.substring("total=".length()));
            }
        }
        return Optional.of(new ResumePoint(phase, done, total));
    }

    public static void clear(ServerLevel level) {
        if (!isOverworld(level)) return;
        try {
            Files.deleteIfExists(path(level));
        } catch (IOException failure) {
            LOGGER.warn("清理开局道路预热检查点失败: {}", path(level), failure);
        } finally {
            // 无论删除成功与否都失效缓存：即使删除失败，下一次读也应重新取磁盘真值。
            invalidate(level);
        }
    }

    // ═══════════════ 内存缓存（★ 0.8.401：消除每 tick 上万次读盘）═══════════════
    //
    // 【为什么必须缓存】
    // 真机 3× jstack 取证（2026-09-27，Chunky 预热期，游戏已跑 10 小时仍未完成）：
    // 三次采样服务端线程**全部**卡在同一处：
    //
    //   at Files.newBufferedReader / WindowsNativeDispatcher.CreateFile0   ← 磁盘 IO
    //     at InitialGenerationCheckpointStore.hasCompleted(:89) / isActive(:60)
    //     at InitialGenManager.shouldRunInitialGeneration(:210-211)
    //     at RoadPlanningReadinessIndex.adoptCompletedInitialCoverage(:374)
    //     at RoadPlanningReadinessIndex.requestRegion(:76)
    //     at ChunkyRoadAwareCoordinator.startWholeSelectionWhenReadyLocked(:219)
    //
    // 调用规模：Chunky 每 tick 遍历 3190 个 region，每个 region 走
    // requestRegion → adoptCompletedInitialCoverage → shouldRunInitialGeneration，
    // 而后者**连续读两次盘**（hasCompleted + isActive）。且
    // adoptCompletedInitialCoverage 的 `isActive() || shouldRunInitialGeneration()`
    // 短路顺序把读盘排在前面，于是**每个 region 都必然读盘**后才 return false。
    // 又因存在**两个独立 mixin** 注入同一 tick 钩子，整批扫描每 tick 跑两遍：
    //
    //   2754 region × 2 遍 × 2 次读盘 ≈ 1.1 万次文件打开/tick ≈ 22 万次/秒
    //
    // 这解释了全部现象：tick ≈ 0.8s、critical=true、lanes 被压到 1、
    // 「Can't keep up 230s」——而 lane 限速完全无效，因为瓶颈是 IO 不是 CPU。
    //
    // 【为什么可以安全缓存】
    // checkpoint.txt 只由本类的 4 个写入口修改（begin / recordProgress /
    // markCompleted / clear），没有任何外部写入者。因此「进程内在写入口失效缓存」
    // 与「每次读盘」语义等价，但把 IO 降到 0。
    //
    // 【与既有 IO 降级策略的关系】
    // 读失败时返回的默认值（active=false / completed=false / empty）与原实现一致，
    // 只是把「每次读都失败」变成「失效后读一次失败」。不影响
    // FileStorageIO 的只读降级语义。

    /** 一个 level 的检查点快照（包私有：供离线测试直接断言）。 */
    static final class State {
        final boolean active;
        final boolean completed;
        final Optional<ResumePoint> resumePoint;

        State(boolean active, boolean completed, Optional<ResumePoint> resumePoint) {
            this.active = active;
            this.completed = completed;
            this.resumePoint = resumePoint;
        }
    }

    /**
     * 按 level 缓存的检查点状态。
     *
     * <p>用 {@code WeakHashMap} 与工程既有风格一致（level 回收后分区自动消失），
     * 避免跨维度/跨存档串味 —— 虽然本类只对主世界生效，但存档切换会换 level 实例。</p>
     *
     * <p>键类型是 {@code Object} 而非 {@code ServerLevel}：生产路径传
     * {@code ServerLevel}（弱引用语义照旧），离线测试传一个普通对象即可
     * 完整覆盖缓存行为 —— 否则测试必须构造真实 {@code ServerLevel}，
     * 而那是做不到的（会让 T2-1 退化成源码文本断言）。</p>
     */
    private static final java.util.Map<Object, State> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 缓存命中/未命中计数（诊断用，便于真机确认读盘已归零）。 */
    private static final java.util.concurrent.atomic.AtomicLong HITS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISSES =
            new java.util.concurrent.atomic.AtomicLong();

    /** 读取（必要时加载）该 level 的检查点状态。 */
    private static State cachedState(ServerLevel level) {
        return cachedState(level, path(level));
    }

    /**
     * 缓存核心（测试可直接调用）。
     *
     * @param key  缓存键；生产传 {@link ServerLevel}，测试可传任意对象
     * @param path 检查点文件路径
     */
    static State cachedState(Object key, Path path) {
        State cached = CACHE.get(key);
        if (cached != null) {
            HITS.incrementAndGet();
            return cached;
        }
        State loaded = load(path);
        CACHE.put(key, loaded);
        long misses = MISSES.incrementAndGet();
        // 只在前几次未命中时打日志，避免刷屏；命中数由诊断 API 查询。
        if (misses <= 4 || misses % 1000L == 0L) {
            LOGGER.info("RoadWeaver checkpoint 状态已缓存（第 {} 次实际读盘）：active={} completed={}",
                    misses, loaded.active, loaded.completed);
        }
        return loaded;
    }

    /** 真正的磁盘读取。只在缓存未命中时执行。 */
    private static State load(ServerLevel level) {
        return load(path(level));
    }

    /** 真正的磁盘读取（测试可调用）。 */
    static State load(Path path) {
        if (!Files.isRegularFile(path)) {
            return new State(false, false, Optional.empty());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.warn("读取开局道路预热检查点失败: {}", path, failure);
            // 与原实现一致：读失败时按「未激活 / 未完成 / 无断点」处理。
            return new State(false, false, Optional.empty());
        }
        boolean active = lines.stream().anyMatch(ACTIVE::equals);
        boolean completed = lines.stream().anyMatch(COMPLETED::equals);
        return new State(active, completed, parseResumePoint(lines));
    }

    /** 写入口统一调用：丢弃缓存，使下一次读重新取磁盘真值。 */
    private static void invalidate(ServerLevel level) {
        if (level == null) return;
        CACHE.remove(level);
    }

    /** 仅供测试：按任意键失效缓存。 */
    static void invalidateForTest(Object key) {
        if (key != null) CACHE.remove(key);
    }

    /** 缓存统计（诊断 / 测试用）。 */
    public static String cacheStats() {
        return "checkpointCache hits=" + HITS.get() + " misses(实际读盘)=" + MISSES.get()
                + " cachedLevels=" + CACHE.size();
    }

    /** 仅供测试：清空缓存与计数。 */
    static void resetCacheForTest() {
        CACHE.clear();
        HITS.set(0L);
        MISSES.set(0L);
    }

    /** 仅供测试：当前实际读盘次数。 */
    static long diskReadCountForTest() {
        return MISSES.get();
    }

    /** 仅供测试：当前缓存命中次数。 */
    static long cacheHitCountForTest() {
        return HITS.get();
    }

    private static int parseNonNegative(String raw) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(0, parsed);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static List<String> lines(String state, String phaseName, int done, int total) {
        List<String> out = new ArrayList<>(6);
        out.add("schema=" + SCHEMA_CURRENT);
        out.add(state);
        if (phaseName != null && !phaseName.isBlank()) {
            out.add("phase=" + phaseName.trim().toUpperCase(Locale.ROOT));
        }
        if (done > 0 || total > 0) {
            out.add("done=" + Math.max(0, done));
            out.add("total=" + Math.max(0, total));
        }
        out.add("updatedAtSeconds=" + (System.currentTimeMillis() / 1000L));
        return out;
    }

    private static void write(ServerLevel level, List<String> lines) {
        try {
            FileStorageIO.writeStringAtomic(path(level), String.join("\n", lines) + "\n");
        } catch (IOException failure) {
            // Checkpoint failure must not abort startup; the warning exposes the gap.
            LOGGER.warn("写入开局道路预热检查点失败: {}", path(level), failure);
        } finally {
            // ★ 0.8.401：所有写入口（begin / recordProgress / markCompleted）都经此处，
            //   因此在这里统一失效缓存即可覆盖全部写入路径 —— 不会出现
            //   「已写入但缓存仍是旧值」的不一致。写在 finally 里是刻意的：
            //   即使写失败也要失效，让下一次读重新取磁盘真值。
            invalidate(level);
        }
    }

    private static Path path(ServerLevel level) {
        return FileStoragePathResolver.categoryRoot(level, "initial-generation").resolve(FILE_NAME);
    }

    private static boolean isOverworld(ServerLevel level) {
        return level != null && Level.OVERWORLD.equals(level.dimension());
    }
}
