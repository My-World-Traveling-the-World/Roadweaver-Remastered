/* 文件职责：计算绑定世界生成、采样策略和寻路配置的路径指纹。 */
package net.shiroha233.roadweaver.planning.path;

import net.shiroha233.roadweaver.config.sub.PathfindingCostConfig;
import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.persistence.WorldgenFingerprint;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 待生成路径的配置身份计算服务。
 */
public final class PlannedPathFingerprintService {
    // 仅更新候选路径的缓存身份；旧结果未看巨树和水域价，不能当新规划复用。道路存档不变。
    private static final String DOMAIN = "roadweaver:planned_path:v5";

    private PlannedPathFingerprintService() {}

    public static String create(WorldgenFingerprint worldgen,
                                TerrainSamplingMode effectiveMode,
                                PathfindingCostConfig config) {
        Objects.requireNonNull(worldgen, "worldgen");
        Objects.requireNonNull(effectiveMode, "effectiveMode");
        Objects.requireNonNull(config, "config");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            putString(digest, DOMAIN);
            putString(digest, worldgen.namespace());
            putInt(digest, worldgen.schemaVersion());
            putInt(digest, worldgen.dataVersion());
            putString(digest, effectiveMode.name());
            putString(digest, config.pathfindingAlgorithm().name());
            putDouble(digest, config.orthoStepCost());
            putDouble(digest, config.diagStepCost());
            putInt(digest, config.elevationWeight());
            putInt(digest, config.biomeWeight());
            putInt(digest, config.stabilityWeight());
            putInt(digest, config.waterDepthWeight());
            putInt(digest, config.nearWaterCost());
            putInt(digest, config.waterProximityCost());
            putDouble(digest, config.heuristicWeight());
            // POTENTIAL_FIELD（势场法）晋升常量：改变路径形态，必须纳入指纹。
            putDouble(digest, config.contourDiscount());
            putDouble(digest, config.softGradeLimit());
            putDouble(digest, config.hardGradeLimit());
            putDouble(digest, config.softGradePenalty());
            putDouble(digest, config.hardGradePenalty());
            // 0.9.062（②）：坡罚换了量纲 ⇒ 旧尺子下算出来的缓存路径不能复用。
            putInt(digest, config.slopePenaltyByLength() ? 1 : 0);
            putDouble(digest, config.gradientAlignPenalty());
            putDouble(digest, config.pfWaterBasePenalty());
            // 蜿蜒/群系走向：改变路径形态（按群系避让/偏好 + 等高线跟随强度），必须纳入指纹。
            putDouble(digest, config.biomeCostWater());
            putDouble(digest, config.biomeCostPlains());
            putDouble(digest, config.biomeCostForest());
            putDouble(digest, config.biomeCostMountain());
            putDouble(digest, config.biomeCostSwamp());
            putDouble(digest, config.biomeCostDesert());
            putDouble(digest, config.biomeCostOther());
            putDouble(digest, config.contourFollowWeight());
            putDouble(digest, config.deviationWeight());
            putInt(digest, config.effectiveAStarStep());
            putInt(digest, config.aStarMaxSteps());
            // A path is also affected by road width, slope policy, bridge and
            // structure settings. Include the complete stable planning payload
            // so changing those settings cannot reuse an incompatible path.
            putString(digest, ConfigService.planningFingerprintPayload());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void putDouble(MessageDigest digest, double value) {
        digest.update(ByteBuffer.allocate(Double.BYTES).putDouble(value).array());
    }
}
