/* 文件职责：结构索引服务。 */
package net.shiroha233.roadweaver.search;

import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.StructureInfo;
import net.shiroha233.roadweaver.map.MapPatchService;
import net.shiroha233.roadweaver.persistence.files.StructureFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 结构索引服务
 */
public final class StructureIndexService {

    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final boolean CACHE_DEBUG = Boolean.getBoolean("roadweaver.structureCacheDebug");

    /**
     * 「整批预测项全部通过验证」的批次数（2026-11-15 新增，纯读取证）。
     *
     * <p><b>为什么需要它：</b>{@code verifyPredictedStructures} 在拿不到
     * {@code ServerChunkCache} / {@code chunkScanner} 时会<b>原样返回全部预测项</b>，
     * 而真正验证通过的也是原样返回 —— <b>「无法验证」与「验证了且都对」完全同形</b>。
     * 生产环境里这个比例只在 {@code roadweaver.structureCacheDebug} 打开时才会打印，
     * 等于默认情况下没人能知道验证有没有在工作。</p>
     *
     * <p>本计数器只回答「有多少批是一个都没被过滤掉的」，<b>不参与任何决策</b>。
     * 它偏大不一定有问题（预测本来就准），但<b>偏大且伴随真机「路绕过根本没有的结构」</b>
     * 时，就是验证失效的信号。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong FULL_PASS_BATCHES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean FULL_PASS_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 「整批全部通过验证」的累计批次数（只读取证；见字段 javadoc）。 */
    public static long fullPassBatchCount() {
        return FULL_PASS_BATCHES.get();
    }

    /** 重置取证计数（测试用）。 */
    static void resetFullPassBatchCount() {
        FULL_PASS_BATCHES.set(0);
        FULL_PASS_WARNED.set(false);
    }


    private StructureIndexService() {
    }

    /**
     * 预测并验证围绕出生点的结构
     */
    public static List<StructureInfo> predictAndVerifyAroundSpawn(ServerLevel level) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return List.of();
        }
        ModConfig cfg = ConfigService.get();
        if (cfg == null || !cfg.structurePredictionEnabled()) {
            return List.of();
        }
        BlockPos spawn = level.getSharedSpawnPos();
        int radiusChunks = Math.max(1, cfg.predictRadiusChunks());
        int minBlockX = (spawn.getX() >> 4) - radiusChunks;
        int maxBlockX = (spawn.getX() >> 4) + radiusChunks;
        int minBlockZ = (spawn.getZ() >> 4) - radiusChunks;
        int maxBlockZ = (spawn.getZ() >> 4) + radiusChunks;
        return predictAndVerifyInRect(level, minBlockX * 16, minBlockZ * 16, maxBlockX * 16, maxBlockZ * 16);
    }

    /**
     * 在给定矩形范围内做结构预测和验证
     */
    public static List<StructureInfo> predictAndVerifyInRect(ServerLevel level,
                                                                     int minBlockX, int minBlockZ,
                                                                     int maxBlockX, int maxBlockZ) {
        if (level == null || !Level.OVERWORLD.equals(level.dimension())) {
            return List.of();
        }

        ModConfig cfg = ConfigService.get();
        if (cfg == null || !cfg.structurePredictionEnabled()) {
            return List.of();
        }
        StructureFileStorage.ensurePolicy(level, policyHash(cfg));

        final long tAll0 = CACHE_DEBUG ? System.nanoTime() : 0L;
        final int beforeCount = CACHE_DEBUG
                ? StructureFileStorage.queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, StructureFileStorage.SOURCE_PREDICTED).size()
                : 0;

        int cminx = Math.floorDiv(minBlockX, 16);
        int cminz = Math.floorDiv(minBlockZ, 16);
        int cmaxx = Math.floorDiv(maxBlockX, 16);
        int cmaxz = Math.floorDiv(maxBlockZ, 16);

        int windowsClaimed = 0;
        int predictedTotal = 0;
        int verifiedTotal = 0;

        if (StructureFileStorage.claimScanWindow(level, cminx, cminz, cmaxx, cmaxz)) {
            windowsClaimed++;
            try {
                List<StructureInfo> predicted = StructurePredictor.predictStructuresInRect(
                        level,
                        cminx, cminz, cmaxx, cmaxz,
                        cfg.biomePrefilter(),
                        cfg.structureWhitelist(),
                        cfg.structureBlacklist()
                );
                List<StructureInfo> verified = StructureVerificationService.verifyPredictedStructures(level, predicted);
                predictedTotal += predicted != null ? predicted.size() : 0;
                verifiedTotal += verified != null ? verified.size() : 0;
                if (verified != null && !verified.isEmpty()) {
                    // P0-3: fold the structure write and the window-done marker
                    // into one write-through instead of two full state saves.
                    StructureFileStorage.addStructuresAndMarkScanWindowDone(level, verified,
                            StructureFileStorage.SOURCE_PREDICTED, cminx, cminz, cmaxx, cmaxz);
                    MapPatchService.publishStructures(level, verified);
                } else {
                    StructureFileStorage.markScanWindowDone(level, cminx, cminz, cmaxx, cmaxz);
                }
            } catch (Throwable t) {
                StructureFileStorage.releaseScanWindow(level, cminx, cminz, cmaxx, cmaxz);
                LOGGER.warn("StructureIndexService: scan window failed rect=[{},{}..{},{}]", cminx, cminz, cmaxx, cmaxz, t);
            }
        }

        // 2026-11-15：无论 CACHE_DEBUG 开关如何，都把「整批一个都没被过滤」记下来并首次告警。
        // 验证失效与「预测本来就准」同形，日志里必须能看出端倪。
        if (predictedTotal > 0 && verifiedTotal >= predictedTotal) {
            long n = FULL_PASS_BATCHES.incrementAndGet();
            if (FULL_PASS_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("RoadWeaver structure verification let every predicted structure through"
                                + " (first of possibly many; predicted={} verified={})"
                                + " — this also happens when verification is unavailable,"
                                + " so treat it as a hint, not a verdict (counter={})",
                        predictedTotal, verifiedTotal, n);
            }
        }

        List<StructureInfo> out = StructureFileStorage.queryRect(level, minBlockX, minBlockZ, maxBlockX, maxBlockZ, StructureFileStorage.SOURCE_PREDICTED);
        if (CACHE_DEBUG) {
            int afterCount = out.size();
            long ms = (System.nanoTime() - tAll0) / 1_000_000L;
            LOGGER.info("StructureIndexService.predictAndVerifyInRect rect=[{},{}..{},{}] windowsClaimed={} predicted={} verified={} cachedBefore={} cachedAfter={} timeMs={}",
                    minBlockX, minBlockZ, maxBlockX, maxBlockZ,
                    windowsClaimed,
                    predictedTotal, verifiedTotal,
                    beforeCount, afterCount,
                    ms);
        }
        return out;
    }

    private static String policyHash(ModConfig cfg) {
        if (cfg == null) return "";
        List<String> wl = cfg.structureWhitelist() != null ? new ArrayList<>(cfg.structureWhitelist()) : new ArrayList<>();
        List<String> bl = cfg.structureBlacklist() != null ? new ArrayList<>(cfg.structureBlacklist()) : new ArrayList<>();
        Collections.sort(wl);
        Collections.sort(bl);
        String raw = "biomePrefilter=" + cfg.biomePrefilter()
                + "|whitelist=" + String.join(",", wl)
                + "|blacklist=" + String.join(",", bl);
        // Re-scan existing worlds after the multi-height biome prefilter fix;
        // predicted entries are rebuilt while manual entries remain intact.
        raw += "|scanWindowVersion=3";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }
}
