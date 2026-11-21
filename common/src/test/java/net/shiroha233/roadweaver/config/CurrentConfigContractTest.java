package net.shiroha233.roadweaver.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.shiroha233.roadweaver.config.meta.ConfigKeyTable;
import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.pathfinding.Pathfinder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 清理必须落到真实序列化、指纹和界面，不能只隐藏仍能修改缓存身份的旧控件。 */
final class CurrentConfigContractTest {
    private static final Gson GSON = new Gson();
    private static final List<String> RETIRED_COST = List.of(
            "waterProximityCost", "contourDiscount", "gradientAlignPenalty", "pfWaterBasePenalty",
            "biomeCostPlains", "biomeCostForest", "biomeCostMountain", "biomeCostSwamp", "biomeCostDesert",
            "contourFollowWeight", "elevationGentleMax", "elevationGentleWeight", "elevationSteepWeight",
            "elevationCliffThreshold", "elevationCliffWeight", "elevationSegmentedCost",
            "slopePenaltyByLength", "aStarMaxSteps", "pathfindingAlgorithm");
    private static final List<String> RETIRED_PLANNING = List.of(
            "knnK", "knnAlpha", "knnMinAngleDeg", "knnDegreeCap", "planningAlgorithm");
    private static final List<String> ECONOMIC_AND_WATER = List.of(
            "softGradeLimit", "hardGradeLimit", "softGradePenalty", "hardGradePenalty",
            "biomeCostWater", "biomeCostOther");
    private static final Map<String, List<String>> OTHER_RETIRED = Map.of(
            "bridge", List.of("keepLamps", "minLength"),
            "roadAppearance", List.of("placeWaypoints"),
            "terrainProfile", List.of("oceanBridgeMinDepth"),
            "performance", List.of("chunkyRoadThreadCap", "openclAccurateSamplingEnabled"));

    @Test
    void retiredKeysAreAbsentFromSerializationAndMetadata() {
        JsonObject root = GSON.toJsonTree(new ModConfig()).getAsJsonObject();
        assertAbsent(root.getAsJsonObject("pathfindingCost"), RETIRED_COST, "pathfindingCost");
        assertAbsent(root.getAsJsonObject("planning"), RETIRED_PLANNING, "planning");
        OTHER_RETIRED.forEach((section, keys) -> assertAbsent(root.getAsJsonObject(section), keys, section));
        assertFalse(root.getAsJsonObject("pathfindingCost").has("aStarStep"));
        assertTrue(root.getAsJsonObject("pathfindingCost").has("pathGridStep"));
        for (String key : ECONOMIC_AND_WATER) {
            assertTrue(root.getAsJsonObject("pathfindingCost").has(key), key);
            assertTrue(ConfigKeyTable.byJsonKey(key).isPresent(), key);
        }
    }

    @Test
    void unknownOldKeysNeitherPersistNorAffectRealFingerprintPayloads() throws Exception {
        ModConfig defaults = new ModConfig();
        JsonObject input = GSON.toJsonTree(defaults).getAsJsonObject();
        for (String key : RETIRED_COST) input.getAsJsonObject("pathfindingCost").addProperty(key, 98765);
        for (String key : RETIRED_PLANNING) input.getAsJsonObject("planning").addProperty(key, 54321);
        OTHER_RETIRED.forEach((section, keys) -> keys.forEach(
                key -> input.getAsJsonObject(section).addProperty(key, 12345)));
        input.getAsJsonObject("pathfindingCost").addProperty("aStarStep", 64);
        ModConfig loaded = GSON.fromJson(input, ModConfig.class);
        loaded.sanitize();
        defaults.sanitize();
        assertEquals(GSON.toJsonTree(defaults), GSON.toJsonTree(loaded));
        assertEquals(payloads(defaults), payloads(loaded));
    }

    @Test
    void newShapeControlsParticipateInBothCacheIdentities() throws Exception {
        for (String key : List.of("finslerGradeWeight", "finslerObstacleWeight", "elasticBandStiffness")) {
            var original = new ModConfig();
            var before = payloads(original);
            var option = net.shiroha233.roadweaver.config.meta.ConfigOption.bind(original).stream()
                    .filter(v -> v.id().equals("pathfindingCost." + key)).findFirst().orElseThrow();
            option.save(10.0D);
            original.sanitize();
            assertBothChanged(before, payloads(original));
        }
    }

    @Test
    void currentDecoderIgnoresLegacyHintsWithoutRewritingExplicitNewBudgets() {
        var root = GSON.toJsonTree(new ModConfig()).getAsJsonObject();
        var performance = root.getAsJsonObject("performance");
        performance.addProperty("generationThreads", 4);
        performance.addProperty("sharedWorkerThreads", 16);
        performance.addProperty("initialGenerationThreads", 24);
        performance.addProperty("maxConcurrentGenerations", 12);
        root.getAsJsonObject("pathfindingCost").addProperty("softGradePenalty", 600);
        var loaded = ConfigService.decodeCurrent(root);
        assertEquals(16, loaded.performance().sharedWorkerThreads());
        assertEquals(24, loaded.performance().initialGenerationThreads());
        assertEquals(12, loaded.performance().maxConcurrentGenerations());
        assertEquals(600, loaded.pathfindingCost().softGradePenalty());
        assertFalse(GSON.toJsonTree(loaded).getAsJsonObject().getAsJsonObject("performance").has("generationThreads"));
    }

    @Test
    void terrainPolicyInvalidatesBothRealCachePayloadsButDisplayAndBudgetDoNot() throws Exception {
        ModConfig baseline = new ModConfig();
        List<String> before = payloads(baseline);
        var fields = baseline.terrainProfile().getClass().getDeclaredFields();
        assertEquals(10, fields.length, "地形字段增减后须重新审查缓存依赖");
        for (Field field : fields) {
            ModConfig changed = new ModConfig();
            field.setAccessible(true);
            Number value = (Number) field.get(changed.terrainProfile());
            if (field.getType() == int.class) field.setInt(changed.terrainProfile(), value.intValue() + 1);
            else field.setDouble(changed.terrainProfile(), value.doubleValue() + 0.1);
            changed.sanitize();
            assertBothChanged(before, payloads(changed));
        }
        baseline.client().setLoadingProgressEnabled(false);
        baseline.performance().setGameplayThreadCap(1);
        assertEquals(before, payloads(baseline), "显示或调度预算不能触发形态缓存失效");
    }

    @Test
    void currentGridStepSurvivesJsonSnapshotAndClamp() {
        PathfindingCostConfig config = GSON.fromJson("{\"pathGridStep\":16}", PathfindingCostConfig.class);
        config.sanitize();
        assertEquals(16, config.pathGridStep());
        assertEquals(16, config.snapshot().effectivePathGridStep());
        assertEquals(16, GSON.fromJson(GSON.toJson(config), PathfindingCostConfig.class).pathGridStep());
        assertEquals(16, config.withPathGridStep(1).snapshot().withPathGridStep(16).pathGridStep());
        assertEquals(16, config.pathGridStep(), "临时精修不能修改原配置");
        assertEquals(RoadConstants.PATH_GRID_STEP_MIN, config.withPathGridStep(-1).pathGridStep());
        assertEquals(RoadConstants.PATH_GRID_STEP_MAX, config.withPathGridStep(Integer.MAX_VALUE).pathGridStep());
        var meta = ConfigKeyTable.byJsonKey("pathGridStep").orElseThrow();
        assertEquals(RoadConstants.PATH_GRID_STEP_MIN, meta.min().intValue());
        assertEquals(RoadConstants.PATH_GRID_STEP_MAX, meta.max().intValue());
    }

    @Test
    void activeControlsStillChangeActualFingerprints() throws Exception {
        ModConfig config = new ModConfig();
        List<String> original = payloads(config);
        config.pathfindingCost().setPathGridStep(16);
        assertBothChanged(original, payloads(config));
        original = payloads(config);
        config.pathfindingCost().setSoftGradePenalty(123.0);
        assertBothChanged(original, payloads(config));
        original = payloads(config);
        config.pathfindingCost().setBiomeCostWater(999.0);
        assertBothChanged(original, payloads(config));
        original = payloads(config);
        config.planning().setComponentDegreeCap(7);
        assertNotEquals(original.get(0), payloads(config).get(0));
    }

    @Test
    void currentUiKeepsRealControlsAndDropsRetiredPage() throws Exception {
        Path root = repoRoot();
        Path ui = root.resolve("fabric/src/main/java/net/shiroha233/roadweaver/client/fabric");
        assertFalse(Files.exists(ui.resolve("LegacyPathfindingSection.java")));
        assertFalse(Files.exists(ui.resolve("PlanningSection.java")), "不得保留第二套手写菜单");
        String screen = Files.readString(ui.resolve("MetadataConfigScreen.java"));
        assertTrue(screen.contains("ConfigOption.bind(draft)"));
        var options = net.shiroha233.roadweaver.config.meta.ConfigOption.bind(new ModConfig());
        for (String key : ECONOMIC_AND_WATER) {
            assertTrue(options.stream().anyMatch(v -> v.id().equals("pathfindingCost." + key)),
                    key + " 必须仍有真实保存绑定");
        }
    }

    @Test
    void bothLanguagesDescribeOnlyCurrentControls() throws Exception {
        for (String lang : List.of("zh_cn", "en_us")) {
            try (var stream = getClass().getResourceAsStream("/assets/roadweaver/lang/" + lang + ".json")) {
                assertNotNull(stream, lang);
                JsonObject text = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                assertFalse(text.has("config.roadweaver.category.legacy_pathfinding"));
                for (String key : List.of("knn_k", "knn_alpha", "knn_min_angle_deg", "knn_degree_cap",
                        "a_star_step", "a_star_max_steps", "contour_discount", "gradient_align_penalty",
                        "bridge_keep_lamps", "bridge_min_length", "place_waypoints",
                        "ocean_bridge_min_depth", "chunky_road_thread_cap")) {
                    assertFalse(text.has("config.roadweaver." + key), lang + ":" + key);
                    assertFalse(text.has("config.roadweaver." + key + ".tooltip"), lang + ":" + key);
                }
                for (String key : List.of("path_grid_step", "soft_grade_limit", "hard_grade_limit",
                        "soft_grade_penalty", "hard_grade_penalty", "biome_cost_water", "biome_cost_other")) {
                    assertFalse(text.get("config.roadweaver." + key).getAsString().isBlank(), lang + ":" + key);
                    String tooltip = text.get("config.roadweaver." + key + ".tooltip").getAsString();
                    assertFalse(tooltip.isBlank(), lang + ":" + key);
                    assertFalse(tooltip.contains("势场"), lang + ":" + key);
                }
                assertFalse(text.get("config.roadweaver.pathfinding_algorithm.fixed_band").getAsString().isBlank());
                assertFalse(text.get("config.roadweaver.planning_algorithm.fixed_pipeline").getAsString().isBlank());
            }
        }
    }

    @Test
    void pathfinderDoesNotAdvertiseAnIgnoredExpansionBudget() {
        // 编译约束之外再限定唯一公开契约，防止重新塞入不会执行的预算重载。
        var methods = List.of(Pathfinder.class.getDeclaredMethods());
        assertEquals(2, methods.size());
        assertEquals(7, methods.stream().filter(m -> m.getName().equals("findPath")).findFirst().orElseThrow()
                .getParameterCount());
        assertEquals(6, methods.stream().filter(m -> m.getName().equals("findRawPath")).findFirst().orElseThrow()
                .getParameterCount());
    }

    private static void assertAbsent(JsonObject json, List<String> keys, String section) {
        assertFalse(keys.isEmpty(), "空清单会让删除守卫空转");
        for (String key : keys) {
            assertFalse(json.has(key), section + ":" + key);
            assertTrue(ConfigKeyTable.forConfig(section).stream().noneMatch(meta -> meta.jsonKey().equals(key)), key);
        }
    }

    private static void assertBothChanged(List<String> before, List<String> after) {
        assertNotEquals(before.get(0), after.get(0), "规划指纹必须读取有效控制项");
        assertNotEquals(before.get(1), after.get(1), "预热算法身份必须读取有效控制项");
    }

    private static List<String> payloads(ModConfig config) throws Exception {
        // 暂换真实服务的引用并在finally恢复，验证生产载荷而非自行复制一套指纹公式。
        Field field = ConfigService.class.getDeclaredField("INSTANCE");
        field.setAccessible(true);
        Object saved = field.get(null);
        try {
            field.set(null, config);
            return List.of(ConfigService.planningFingerprintPayload(),
                    ConfigService.planningAlgorithmFingerprintPayload());
        } finally {
            field.set(null, saved);
        }
    }

    private static Path repoRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.exists(path.resolve("fabric/src/main/java"))) return path;
        }
        throw new AssertionError("找不到真实配置界面源码，不能跳过清理守卫");
    }
}
