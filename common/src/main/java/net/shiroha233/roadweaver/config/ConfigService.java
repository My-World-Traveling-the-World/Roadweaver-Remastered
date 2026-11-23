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

        // 新版只按当前字段加载；旧键由GSON忽略，不再通过历史迁移重写玩家的新预算。
        ModConfig loaded = new ModConfig();
        boolean missing = !Files.exists(file);
        if (!missing) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = decodeCurrent(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("配置文件读取失败，使用默认值: {}", file, e);
            }
        }
        loaded.sanitize();
        INSTANCE = loaded;
        if (missing) save();
        LOGGER.info("配置已加载 (predictRadius={}, predictionEnabled={})",
                INSTANCE.structurePrediction().predictRadiusChunks(),
                INSTANCE.structurePrediction().enabled());
    }

    /** 加载与离线回归共用当前版本解码入口；成功校验之前不发布半成品。 */
    static ModConfig decodeCurrent(JsonObject root) {
        ModConfig loaded = GSON.fromJson(root, ModConfig.class);
        if (loaded == null) loaded = new ModConfig();
        loaded.sanitize();
        return loaded;
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

    /** 菜单编辑独立快照，点击取消不能提前污染后台任务；保存时一次发布完整配置。 */
    public static synchronized void applyAndSave(ModConfig edited) {
        edited.sanitize();
        INSTANCE = edited.snapshot();
        save();
    }

    /** 生成持久规划覆盖的指纹载荷，使相关配置变化能使旧覆盖失效。 */
    public static synchronized String planningFingerprintPayload() {
        JsonObject root = new JsonObject();
        root.add("structurePrediction", GSON.toJsonTree(INSTANCE.structurePrediction()));
        root.add("planning", GSON.toJsonTree(INSTANCE.planning()));
        // 绕行倍率改变候选路径，挖填与海底策略改变最终剖面；两类缓存均不能复用旧身份。
        root.add("terrainProfile", GSON.toJsonTree(INSTANCE.terrainProfile()));
        // 冲突重试的缓存键已写入实际网格步长；调这档不应使首轮缓存或开局覆盖重算。
        JsonObject pathfinding = GSON.toJsonTree(INSTANCE.pathfindingCost()).getAsJsonObject();
        pathfinding.remove("localRepairGridStep");
        root.add("pathfindingCost", pathfinding);
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
        // 新度量和平滑参数参与真实几何，修改后不能沿用原配置的完成覆盖。
        root.addProperty("finslerGradeWeight", INSTANCE.pathfindingCost().finslerGradeWeight());
        root.addProperty("finslerObstacleWeight", INSTANCE.pathfindingCost().finslerObstacleWeight());
        root.addProperty("elasticBandStiffness", INSTANCE.pathfindingCost().elasticBandStiffness());
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

}
