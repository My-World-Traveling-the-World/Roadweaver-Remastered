/* 文件职责：配置的加载 / 保存 / 缓存与默认值合并 —— 配置键名即 Java 字段名（GSON 直存），改名等于迁移。 */
package net.shiroha233.roadweaver.config;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import net.shiroha233.roadweaver.config.sub.PerformanceConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.persistence.files.FileStorageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置文件加载、保存、校验服务
 */
public final class ConfigService {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BASE_DIR = "roadweaver";
    private static final String FILE_NAME = "roadweaver.json";

    /** 热循环通过volatile读取配置引用，避免每次取配置都竞争类锁；这不构成字段深度不可变快照。 */
    private static volatile ModConfig INSTANCE = new ModConfig();

    private ConfigService() {}

    public static synchronized void load() {
        Path baseDir = Platform.getConfigFolder().resolve(BASE_DIR);
        Path file = baseDir.resolve(FILE_NAME);
        ensureDirectory(baseDir);

        boolean migratedLegacyPerformance = false;
        boolean migratedGradePenaltyScale = false;
        if (Files.exists(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed != null && parsed.isJsonObject()) {
                    JsonObject root = parsed.getAsJsonObject();
                    ModConfig loaded = GSON.fromJson(root, ModConfig.class);
                    if (loaded != null) {
                        INSTANCE = loaded;
                        migratedLegacyPerformance = migrateLegacyPerformance(root, loaded);
                        migratedGradePenaltyScale = migrateLegacyGradePenaltyScale(root, loaded);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("配置文件读取失败，使用默认值: {}", file, e);
            }
        } else {
            save();
        }

        try {
            INSTANCE.sanitize();
        } catch (Throwable t) {
            LOGGER.warn("配置校验失败，继续使用原始值", t);
        }
        if (migratedLegacyPerformance) {
            LOGGER.info("【旧性能配置迁移完成，采用受限启动预算】 " + "Migrated legacy RoadWeaver performance settings with safe initial limits; accurate sampling remains CPU-only.");
        }
        if (migratedGradePenaltyScale) {
            LOGGER.info("[GRADE-PENALTY] 配置迁移：老配置里的坡罚刻度 {}/{} 是 0.9.061 的旧出厂值，"
                            + "已升到 0.9.063 标定值 {}/{}（只认「正好等于旧出厂值」这一种情况，"
                            + "玩家自己改过的单价一律不动）",
                    RoadConstants.LEGACY_SOFT_GRADE_PENALTY, RoadConstants.LEGACY_HARD_GRADE_PENALTY,
                    RoadConstants.DEFAULT_SOFT_GRADE_PENALTY, RoadConstants.DEFAULT_HARD_GRADE_PENALTY);
        }
        LOGGER.info("配置已加载 (predictRadius={}, predictionEnabled={})",
                INSTANCE.structurePrediction().predictRadiusChunks(),
                INSTANCE.structurePrediction().enabled());
    }

    public static synchronized void save() {
        Path baseDir = Platform.getConfigFolder().resolve(BASE_DIR);
        Path file = baseDir.resolve(FILE_NAME);
        ensureDirectory(baseDir);

        try {
            INSTANCE.sanitize();
            FileStorageIO.writeStringAtomic(file, GSON.toJson(INSTANCE));
        } catch (Exception e) {
            LOGGER.warn("配置文件写入失败: {}", file, e);
        }
    }

    public static ModConfig get() {
        return INSTANCE;
    }

    /** 生成持久规划覆盖的指纹载荷，使相关配置变化能使旧覆盖失效。 */
    public static synchronized String planningFingerprintPayload() {
        JsonObject root = new JsonObject();
        root.add("structurePrediction", GSON.toJsonTree(INSTANCE.structurePrediction()));
        root.add("planning", GSON.toJsonTree(INSTANCE.planning()));
        // 绕行倍率改变候选路径，挖填与海底策略改变最终剖面；两类缓存均不能复用旧身份。
        root.add("terrainProfile", GSON.toJsonTree(INSTANCE.terrainProfile()));
        root.add("pathfindingCost", GSON.toJsonTree(INSTANCE.pathfindingCost()));
        root.add("roadAppearance", GSON.toJsonTree(INSTANCE.roadAppearance()));
        root.add("bridge", GSON.toJsonTree(INSTANCE.bridge()));
        root.add("roadsideStructure", GSON.toJsonTree(INSTANCE.roadsideStructure()));
        root.add("roadsideVillage", GSON.toJsonTree(INSTANCE.roadsideVillage()));
        return GSON.toJson(root);
    }

    /**
     * 只含「决定道路形态」的算法标识，供 Chunky 预热复用判定使用。
     *
     * <p>背景（2026-09-14）：原来的 readiness 指纹把 7 段配置的完整 JSON 与
     * 全部已注册 STRUCTURE id 一起纳入，导致任何运行类参数改动（例如
     * {@code planning.initialPlanRadiusChunks} —— 它只影响预热覆盖范围，不改变
     * 道路形态）或一次 mod 升级都会让已完成的千级区块预热整份作废，玩家被迫
     * 从头重跑。</p>
     *
     * <p>现在改为只纳入<b>算法本身</b>：规划算法、寻路算法、寻路代价、采样方式与
     * 地形剖面模式。这些一旦变化，旧规划确实不再适用（＝废弃重算，符合预期）；
     * 而它们都是玩家几乎不会改动的固定项。世界种子/维度/生成器/噪声设置由
     * {@code WorldgenFingerprintService} 单独纳入，不在此重复。</p>
     */
    public static synchronized String planningAlgorithmFingerprintPayload() {
        JsonObject root = new JsonObject();
        root.addProperty("planningAlgorithm", INSTANCE.planning().planningAlgorithm().name());
        root.addProperty("terrainSamplingMode", INSTANCE.planning().terrainSamplingMode().name());
        root.addProperty("terrainProfileMode", INSTANCE.planning().terrainProfileMode().name());
        // 已完成覆盖也依赖地形策略；性能和界面配置仍不参与，避免无关调参重跑预热。
        root.add("terrainProfile", GSON.toJsonTree(INSTANCE.terrainProfile()));
        root.addProperty("pathfindingAlgorithm", INSTANCE.pathfindingCost().pathfindingAlgorithm().name());
        root.addProperty("pathGridStep", INSTANCE.pathfindingCost().pathGridStep());
        root.addProperty("orthoStepCost", INSTANCE.pathfindingCost().orthoStepCost());
        root.addProperty("diagStepCost", INSTANCE.pathfindingCost().diagStepCost());
        root.addProperty("heuristicWeight", INSTANCE.pathfindingCost().heuristicWeight());
        root.addProperty("softGradeLimit", INSTANCE.pathfindingCost().softGradeLimit());
        root.addProperty("hardGradeLimit", INSTANCE.pathfindingCost().hardGradeLimit());
        root.addProperty("softGradePenalty", INSTANCE.pathfindingCost().softGradePenalty());
        root.addProperty("hardGradePenalty", INSTANCE.pathfindingCost().hardGradePenalty());
        root.addProperty("biomeCostWater", INSTANCE.pathfindingCost().biomeCostWater());
        root.addProperty("biomeCostOther", INSTANCE.pathfindingCost().biomeCostOther());
        // 局部修复仍读取高度与偏离权重；变化后必须使预热复用标识失效。
        root.addProperty("elevationWeight", INSTANCE.pathfindingCost().elevationWeight());
        root.addProperty("deviationWeight", INSTANCE.pathfindingCost().deviationWeight());
        return GSON.toJson(root);
    }

    /**
     * 修改配置后自动保存
     */
    public static synchronized void markDirty() {
        save();
    }

    private static void ensureDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("创建配置目录失败: {}", dir, e);
        }
    }

    /**
     * 0.9.063（任务一）：坡罚刻度重标定（soft 600→780 / hard 30000→39000）的配置迁移。
     *
     * <p><b>为什么必须有这一步。</b>Gson 只在键存在时覆盖字段，而 {@code ConfigService.save()}
     * 会把整份配置写盘 —— 所以任何一个装过 0.9.062 及更早版本的实例，配置里都<b>显式写着</b>
     * {@code softGradePenalty=600 / hardGradePenalty=30000}。只改默认常量的话，升级后玩家手上
     * 仍是旧刻度，标定在线上等于没做（坡罚还是便宜的那个数）。</p>
     *
     * <p><b>只认一种情况</b>：数值正好等于 0.9.061 的旧出厂值。玩家把它改成别的数（哪怕 601）
     * 一律视为显式选择，不动。迁移动的是内存里的 {@link ModConfig}，下次
     * {@code save()} / {@code markDirty()} 时会落盘；迁移本身幂等。</p>
     */
    private static boolean migrateLegacyGradePenaltyScale(JsonObject root, ModConfig loaded) {
        JsonElement costElement = root.get("pathfindingCost");
        if (costElement == null || !costElement.isJsonObject()) {
            return false;
        }
        JsonObject cost = costElement.getAsJsonObject();
        boolean migrated = false;
        Double soft = readNumber(cost, "softGradePenalty");
        if (soft != null && Math.abs(soft - RoadConstants.LEGACY_SOFT_GRADE_PENALTY) < 1.0E-9D) {
            loaded.pathfindingCost().setSoftGradePenalty(RoadConstants.DEFAULT_SOFT_GRADE_PENALTY);
            migrated = true;
        }
        Double hard = readNumber(cost, "hardGradePenalty");
        if (hard != null && Math.abs(hard - RoadConstants.LEGACY_HARD_GRADE_PENALTY) < 1.0E-9D) {
            loaded.pathfindingCost().setHardGradePenalty(RoadConstants.DEFAULT_HARD_GRADE_PENALTY);
            migrated = true;
        }
        return migrated;
    }

    /** 迁移时容忍单个可选数值类型错误，避免整个旧配置被一并丢弃。 */
    private static Double readNumber(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 迁移旧性能字段以保留玩家预算；精确采样保持CPU-only，不因旧配置开启废弃GPU路径。 */
    private static boolean migrateLegacyPerformance(JsonObject root, ModConfig loaded) {
        JsonElement performanceElement = root.get("performance");
        if (performanceElement == null || !performanceElement.isJsonObject()) {
            return false;
        }
        JsonObject legacy = performanceElement.getAsJsonObject();
        if (!legacy.has("generationThreads")) {
            return false;
        }

        PerformanceConfig performance = loaded.performance();
        int legacyWorkers = readInt(legacy, "generationThreads", 4);
        int legacyInitialWorkers = readInt(legacy, "initialGenerationThreads", legacyWorkers);
        int legacyConcurrent = readInt(legacy, "maxConcurrentGenerations", 1);
        performance.setSharedWorkerThreads(Math.max(1, Math.min(4, legacyWorkers)));
        performance.setInitialGenerationThreads(Math.max(1, Math.min(4, legacyInitialWorkers)));
        performance.setMaxConcurrentGenerations(Math.max(1, Math.min(2, legacyConcurrent)));
        performance.setThreadDutyCycle(readInt(legacy, "threadDutyCycle", 50));
        performance.setIdleGenerationEnabled(false);
        performance.setIdleThreadDutyCycle(readInt(legacy, "idleThreadDutyCycle", 20));
        performance.setOpenclCoarseSamplingEnabled(true);
        performance.setOpenclAccurateSamplingEnabled(true);
        return true;
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
