package net.shiroha233.roadweaver.config.sub;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.config.meta.PlanningKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.420 / 0.8.421：路网拓扑旋钮的可配置化守护测试。
 *
 * <p>这组参数决定「哪些结构互相连路」，全部位于 {@link PlanningConfig} 内，
 * 而 {@code ConfigService.planningFingerprintPayload()} 是把整个 planning 段
 * 交给 Gson 序列化后进指纹的 ⇒ <b>字段存在即自动进指纹</b>。因此这里用
 * 「Gson 序列化后 JSON 里有没有这个键」来证明指纹覆盖，而不依赖 Minecraft 运行时。
 */
final class PlanningConfigTest {

    private static final Gson GSON = new Gson();

    private static JsonObject serialize(PlanningConfig config) {
        return GSON.toJsonTree(config).getAsJsonObject();
    }

    @ParameterizedTest
    @CsvSource({"2048,2048", "4096,4096", "4097,4096", "2147483647,4096"})
    void initialRadiusAccepts4096AndClampsOnlyAboveItsOwnLimit(int configured, int expected) {
        // 用独立字面量验边界，不能让测试与错误常量一起退回 2048 而仍全绿。
        PlanningConfig config = new PlanningConfig();
        config.setInitialPlanRadiusChunks(configured);
        config.sanitize();
        assertEquals(expected, config.initialPlanRadiusChunks());
    }

    @Test
    void initialRadiusUiAllows4096WithoutIncreasingDynamicRadiusRange() {
        var initial = PlanningKeys.ALL.stream().filter(k -> k.jsonKey().equals("initialPlanRadiusChunks"))
                .findFirst().orElseThrow();
        var dynamic = PlanningKeys.ALL.stream().filter(k -> k.jsonKey().equals("dynamicPlanRadiusChunks"))
                .findFirst().orElseThrow();
        assertEquals(4096, initial.max().intValue());
        assertEquals(2048, dynamic.max().intValue());
        PlanningConfig config = new PlanningConfig();
        config.setInitialPlanRadiusChunks(4096);
        config.setDynamicPlanRadiusChunks(4096);
        config.sanitize();
        // 放大开局范围不能改变游玩时的动态窗口预算。
        assertEquals(4096, config.initialPlanRadiusChunks());
        assertEquals(2048, config.dynamicPlanRadiusChunks());
    }

    @Test
    void initialRadius4096SurvivesExistingJsonKeyAndSnapshot() {
        PlanningConfig config = new PlanningConfig();
        config.setInitialPlanRadiusChunks(4096);
        JsonObject json = serialize(config);
        assertEquals(4096, json.get("initialPlanRadiusChunks").getAsInt());
        // 真正经过旧键名的读回和归一化，防止只验证 setter 而漏掉加载时被夹回。
        PlanningConfig loaded = GSON.fromJson(json, PlanningConfig.class);
        loaded.sanitize();
        assertEquals(4096, loaded.initialPlanRadiusChunks());
        assertEquals(4096, loaded.snapshot().initialPlanRadiusChunks());
    }

    @Test
    void initialRadiusDefaultsAndInvalidValuesKeepPreviousBehavior() {
        PlanningConfig config = new PlanningConfig();
        config.sanitize();
        assertEquals(128, config.initialPlanRadiusChunks());
        config.setInitialPlanRadiusChunks(0);
        config.sanitize();
        assertEquals(64, config.initialPlanRadiusChunks());
        config.setInitialPlanRadiusChunks(-1);
        config.sanitize();
        assertEquals(64, config.initialPlanRadiusChunks());
    }

    // ---------- 0.8.421：主连边长度上限 / 跨接缺口上限 ----------

    @Test
    void edgeLimitsDefaultToTheFormerHardcodedConstants() {
        PlanningConfig config = new PlanningConfig();

        // 默认值必须与晋升前的 RoadConstants 常量逐位相同，否则等于悄悄改了所有存档的规划结果。
        assertEquals(RoadConstants.DEFAULT_PLAN_MAX_EDGE_LEN_BLOCKS, config.planMaxEdgeLenBlocks());
        assertEquals(RoadConstants.DEFAULT_BRIDGE_JOIN_LEN_BLOCKS, config.bridgeJoinLenBlocks());
        assertEquals(2048, config.planMaxEdgeLenBlocks());
        assertEquals(1536, config.bridgeJoinLenBlocks());
    }

    @Test
    void edgeLimitsClampIntoTheirValidRange() {
        PlanningConfig config = new PlanningConfig();

        config.setPlanMaxEdgeLenBlocks(0);
        config.setBridgeJoinLenBlocks(0);
        config.sanitize();
        assertEquals(128, config.planMaxEdgeLenBlocks());
        assertEquals(64, config.bridgeJoinLenBlocks());

        config.setPlanMaxEdgeLenBlocks(Integer.MAX_VALUE);
        config.setBridgeJoinLenBlocks(Integer.MAX_VALUE);
        config.sanitize();
        assertEquals(16384, config.planMaxEdgeLenBlocks());
        assertEquals(16384, config.bridgeJoinLenBlocks());
    }

    @Test
    void edgeLimitsSurviveTheSnapshotCopy() {
        PlanningConfig config = new PlanningConfig();
        config.setPlanMaxEdgeLenBlocks(4096);
        config.setBridgeJoinLenBlocks(4096);

        PlanningConfig copy = config.snapshot();

        assertEquals(4096, copy.planMaxEdgeLenBlocks());
        assertEquals(4096, copy.bridgeJoinLenBlocks());
    }

    @Test
    void edgeLimitsArePartOfThePlanningFingerprintPayload() {
        JsonObject json = serialize(new PlanningConfig());

        // ConfigService.planningFingerprintPayload() 走同一套 Gson 序列化 ⇒ 键在 = 进指纹。
        assertTrue(json.has("planMaxEdgeLenBlocks"),
                "planMaxEdgeLenBlocks must be serialized into the planning fingerprint payload");
        assertTrue(json.has("bridgeJoinLenBlocks"),
                "bridgeJoinLenBlocks must be serialized into the planning fingerprint payload");
    }

    // ---------- 0.8.420：KNN 路网拓扑六项 ----------

    @Test
    void knnTopologyDefaultsMatchTheFormerConstants() {
        PlanningConfig config = new PlanningConfig();

        assertEquals(RoadConstants.DEFAULT_KNN_K, config.knnK());
        assertEquals(RoadConstants.DEFAULT_KNN_ALPHA, config.knnAlpha());
        assertEquals(RoadConstants.DEFAULT_KNN_MIN_ANGLE_DEG, config.knnMinAngleDeg());
        assertEquals(RoadConstants.DEFAULT_KNN_DEGREE_CAP, config.knnDegreeCap());
        assertEquals(RoadConstants.DEFAULT_COMPONENT_MIN_ANGLE_DEG, config.componentMinAngleDeg());
        assertEquals(RoadConstants.DEFAULT_COMPONENT_DEGREE_CAP, config.componentDegreeCap());
    }

    @Test
    void knnTopologyKnobsClampIntoTheirValidRange() {
        PlanningConfig config = new PlanningConfig();

        config.setKnnK(0);
        config.setKnnAlpha(99.0D);
        config.setKnnMinAngleDeg(-10.0D);
        config.setKnnDegreeCap(99);
        config.setComponentMinAngleDeg(120.0D);
        config.setComponentDegreeCap(0);
        config.sanitize();

        assertEquals(1, config.knnK());
        assertEquals(5.0D, config.knnAlpha());
        assertEquals(0.0D, config.knnMinAngleDeg());
        assertEquals(8, config.knnDegreeCap());
        assertEquals(89.0D, config.componentMinAngleDeg());
        assertEquals(1, config.componentDegreeCap());
    }

    @Test
    void knnTopologyKnobsArePartOfThePlanningFingerprintPayload() {
        JsonObject json = serialize(new PlanningConfig());

        for (String key : new String[]{
                "knnK", "knnAlpha", "knnMinAngleDeg", "knnDegreeCap",
                "componentMinAngleDeg", "componentDegreeCap"}) {
            assertTrue(json.has(key), key + " must be serialized into the planning fingerprint payload");
        }
    }
}
