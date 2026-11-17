/* 文件职责：落盘前的涉水校验：路面不得泡在水里、桥下必须真的有水。 */
package net.shiroha233.roadweaver.pathlogic.validation;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.compat.StreamsReflowingCompat;
import net.shiroha233.roadweaver.config.ConfigService;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSample;
import net.shiroha233.roadweaver.pathfinding.sampling.AccurateHeightSampler;
import net.shiroha233.roadweaver.pathfinding.sampling.TerrainSamplingCache;
import net.shiroha233.roadweaver.pathfinding.terrain.SurfaceWaterClassifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.shiroha233.roadweaver.pathlogic.bridge.CulvertPlanner;
import net.shiroha233.roadweaver.pathlogic.core.Road;
import net.shiroha233.roadweaver.postprocess.JunctionTrimOps;

/** 最终水校验阻止普通路面落在表面水中，跨越仍由独立物理跨度处理。 */
public final class FinalRoadWaterValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private FinalRoadWaterValidator() {
    }

    /**
     * 0.9.013：全量水面验收。外面包一层计时，把耗时记进
     * {@code DetailedRoadGenerationStats} 的 {@code waterValidationMs}。
     *
     * <p>为什么在这里计时而不是在调用点：{@code Road.java} 的 planningRepair 段一次修复里
     * 最多调本方法 5 遍，而那段 50,059 ms 一直拆不开（已计量合计只有 122,665 / wallMs 233,558，
     * 111 秒没名字）。在方法内部加一次 nanoTime 是唯一不需要改 6 个调用点、也不受
     * 「lambda 捕获了被重新赋值的 rd」限制的做法。</p>
     */
    public static ValidationResult validate(ServerLevel level,
                                            RoadData road,
                                            TerrainSamplingCache cache,
                                            StreamsReflowingCompat.SamplingSession streams) {
        long startedAt = System.nanoTime();
        try {
            return validateImpl(level, road, cache, streams);
        } finally {
            net.shiroha233.roadweaver.generation.DetailedRoadGenerationStats
                    .recordWaterValidation(System.nanoTime() - startedAt);
        }
    }

    private static ValidationResult validateImpl(ServerLevel level,
                                            RoadData road,
                                            TerrainSamplingCache cache,
                                            StreamsReflowingCompat.SamplingSession streams) {
        if (level == null || cache == null) return ValidationResult.failure(
                "missing_context", new Collision(-1, null, Integer.MIN_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, null, "missing_context"));
        if (road == null || road.roadSegmentList() == null || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()) {
            return ValidationResult.failure("invalid_road", new Collision(
                    -1, null, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, null,
                    "invalid_road"));
        }

        LinkedHashMap<Long, BlockPos> positions = new LinkedHashMap<>();
        for (RoadSegmentPlacement segment : road.roadSegmentList()) {
            if (segment == null || segment.positions() == null) continue;
            for (BlockPos position : segment.positions()) {
                if (position != null) positions.putIfAbsent(
                        AccurateHeightSample.key(position.getX(), position.getZ()), position);
            }
        }
        AccurateHeightSampler accurate = cache.getAccurateSampler(level);
        // Task 2: reuse the sampler's shared chunk/column cache first and only
        // fetch the genuinely missing columns. Every later validate() call for
        // the same road (rerouted, gapped, fallback variants) then hits the
        // shared cache instead of resampling the whole footprint.
        Set<Long> known = accurate.cachedSamples(positions.values()).keySet();
        List<BlockPos> missing = new ArrayList<>();
        for (BlockPos position : positions.values()) {
            if (!known.contains(AccurateHeightSample.key(position.getX(), position.getZ()))) {
                missing.add(position);
            }
        }
        // Logging-only diagnostics: sampling-count summary per validate call.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("【水域校验采样缓存和缺失列统计】 " + "RoadWeaver water validation sampling connection={}->{} totalColumns={} cachedColumns={} missingColumns={} backend={} refilled={}",
                    road.ownerA2dKey(), road.ownerB2dKey(),
                    positions.size(), known.size(), missing.size(),
                    accurate.backendName(), !missing.isEmpty());
        }
        if (!missing.isEmpty()) {
            accurate.samplePositions(missing);
        }
        Map<Long, AccurateHeightSample> samples = accurate.cachedSamples(positions.values());
        StreamsReflowingCompat.SamplingSession riverSession = streams == null
                ? StreamsReflowingCompat.openSession(level) : streams;
        // Streams is an optional enhancement. If its reflective bridge is
        // unavailable or failed, validate against the already sampled terrain
        // heightmaps instead of converting every column into an unavailable
        // hard failure. A single bad Streams query gets the same fallback.
        boolean streamsUsable = riverSession != null && riverSession.isAvailable();
        int seaLevel = level.getSeaLevel();
        return validate(road, ConfigService.get().tunnelClearHeight(), (x, z) -> {
            if (streamsUsable) {
                StreamsReflowingCompat.LakeColumn lake = riverSession.sampleLake(x, z);
                if (lake.present()) {
                    return WaterColumn.wet(lake.surfaceY(), lake.bedY(), "streams_lake");
                }
                StreamsReflowingCompat.RiverColumn river = riverSession.sample(x, z);
                if (river.inChannel()) {
                    return WaterColumn.wet(river.waterSurfaceY(), river.bedY(), "streams_channel");
                }
            }
            AccurateHeightSample sample = samples.get(AccurateHeightSample.key(x, z));
            if (sample == null) return WaterColumn.unavailable();
            boolean waterBiome = cache.isWaterLike(level, x, z);
            boolean water = SurfaceWaterClassifier.isWaterColumn(
                    sample.worldSurfaceWg(), sample.oceanFloorWg(), waterBiome, seaLevel);
            if (!water) return WaterColumn.dry();
            return WaterColumn.wet(
                    SurfaceWaterClassifier.waterSurfaceY(
                            sample.worldSurfaceWg(), sample.oceanFloorWg(),
                            waterBiome, seaLevel, true),
                    sample.oceanFloorWg(), "terrain_heightmap");
        });
    }

    /** 涵洞查询仅读取水面和海床高度图，避免为轻量水深判断触发精确采样。 */
    public static CulvertPlanner.Probe probe(ServerLevel level, TerrainSamplingCache cache,
                                             StreamsReflowingCompat.SamplingSession streams) {
        if (level == null) {
            return (x, z) -> CulvertPlanner.Water.unavailable();
        }
        StreamsReflowingCompat.SamplingSession session = streams == null
                ? StreamsReflowingCompat.openSession(level) : streams;
        boolean streamsUsable = session != null && session.isAvailable();
        int seaLevel = level.getSeaLevel();
        return (x, z) -> {
            if (streamsUsable) {
                StreamsReflowingCompat.LakeColumn lake = session.sampleLake(x, z);
                if (lake.present()) return CulvertPlanner.Water.wet(lake.surfaceY(), lake.bedY());
                StreamsReflowingCompat.RiverColumn river = session.sample(x, z);
                if (river.inChannel()) return CulvertPlanner.Water.wet(river.waterSurfaceY(), river.bedY());
            }
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x, z);
            int floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            boolean waterBiome = cache != null && cache.isWaterLike(level, x, z);
            if (!SurfaceWaterClassifier.isWaterColumn(surface, floor, waterBiome, seaLevel)) {
                return CulvertPlanner.Water.dry();
            }
            return CulvertPlanner.Water.wet(
                    SurfaceWaterClassifier.waterSurfaceY(surface, floor, waterBiome, seaLevel, true), floor);
        };
    }

    static ValidationResult validate(RoadData road, int tunnelClearHeight, ColumnSource columns) {
        if (road == null || columns == null || road.roadSegmentList() == null || road.targetY() == null
                || road.roadSegmentList().size() != road.targetY().size()) {
            return ValidationResult.failure("invalid_road", new Collision(
                    -1, null, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, null, "invalid_road"));
        }
        int size = road.roadSegmentList().size();
        boolean[] bridge = spanMask(road, SpanType.BRIDGE);
        boolean[] crossing = spanMask(road, SpanType.ROAD_CROSSING);
        boolean[] tunnel = spanMask(road, SpanType.TUNNEL);
        // A culvert is a dry deck over open water: the bed is left alone, so the station is legal even
        // though its surface is above the water rather than inside it.
        boolean[] culvert = spanMask(road, SpanType.CULVERT);
        boolean[] reused = spanMask(road, SpanType.NETWORK_REUSE);
        boolean[] protectedGap = spanMask(road, SpanType.PROTECTED_GAP);
        // Counted while scanning: when this validation fails, "how much water did this road actually
        // touch" is the first thing the next investigation needs, and a single collision point cannot
        // say whether the route clips one puddle or runs down a river.
        int checkedColumns = 0;
        int wetColumns = 0;
        for (int index = 0; index < size; index++) {
            if (reused[index] || protectedGap[index]) continue;
            RoadSegmentPlacement segment = road.roadSegmentList().get(index);
            if (segment == null || segment.positions() == null) continue;
            int targetY = road.targetY().get(index);
            for (BlockPos position : segment.positions()) {
                if (position == null) continue;
                WaterColumn column = columns.sample(position.getX(), position.getZ());
                checkedColumns++;
                if (column == null || !column.available()) {
                    return ValidationResult.failure("sample_unavailable",
                            new Collision(index, position, targetY, Integer.MIN_VALUE,
                                    Integer.MIN_VALUE, null, "sample_unavailable"));
                }
                if (column.water() && targetY < column.waterSurfaceY()) wetColumns++;
                if (!column.water() || targetY >= column.waterSurfaceY()) continue;
                if (bridge[index] || crossing[index] || culvert[index]) continue;
                // A tunnel whose roof exactly meets the sampled riverbed is
                // still below the water column.  Treat equality as safe so
                // a one-block quantization difference cannot reject an
                // otherwise valid underwater crossing.
                if (tunnel[index] && targetY + Math.max(1, tunnelClearHeight) <= column.bedY()) continue;
                SpanType active = tunnel[index] ? SpanType.TUNNEL : null;
                String spanAt = bridge[index] ? "BRIDGE"
                        : crossing[index] ? "ROAD_CROSSING"
                        : tunnel[index] ? "TUNNEL"
                        : reused[index] ? "NETWORK_REUSE"
                        : protectedGap[index] ? "PROTECTED_GAP" : "ordinary";
                LOGGER.warn("【普通施工列入水，水域校验失败】 " + "RoadWeaver water validation failed road={}->{} segment={} station={} pos={} reason=ordinary_construction_in_water "
                                + "targetY={} waterSurfaceY={} bedY={} source={} span={} wetColumns={} checkedColumns={}",
                        road.ownerA2dKey(), road.ownerB2dKey(), index,
                        position.getX() + "," + position.getZ(), position,
                        targetY, column.waterSurfaceY(), column.bedY(), column.source(), spanAt,
                        wetColumns, checkedColumns);
                return ValidationResult.failure("ordinary_construction_in_water",
                        new Collision(index, position, targetY,
                                column.waterSurfaceY(), column.bedY(), active, column.source()));
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("【水域校验通过，桥梁与涵洞按既有规则豁免】 " + "RoadWeaver water validation passed road={}->{} stations={} checkedColumns={} "
                            + "wetColumns={} (bridgeAndCulvertExempt=true)",
                    road.ownerA2dKey(), road.ownerB2dKey(), size, checkedColumns, wetColumns);
        }
        return ValidationResult.success();
    }

    /** 无法桥接的孤立水冲突保留明确不施工站，其他可用站不因一列失败被整路丢弃。
 *
     * Keep a route when one water column cannot be represented by a bridge or
     * direct crossing. The marked station is intentionally left unbuilt; all
     * other stations remain ordinary road construction.
     */
    public static RoadData retainProtectedGapAt(RoadData road, int index) {
        if (road == null || road.roadSegmentList() == null
                || index < 0 || index >= road.roadSegmentList().size()) return null;
        BlockPos point = road.roadSegmentList().get(index).middlePos();
        if (point == null) return null;
        List<RoadSpan> spans = new java.util.ArrayList<>(road.spans() == null
                ? List.of() : road.spans());
        boolean exists = spans.stream().anyMatch(span -> span != null
                && span.type() == SpanType.PROTECTED_GAP
                && point.equals(span.start()) && point.equals(span.end()));
        if (!exists) spans.add(new RoadSpan(point, point, SpanType.PROTECTED_GAP));
        return new RoadData(road.width(), road.roadType(), road.materials(),
                road.slabMaterials(), road.roadSegmentList(), List.copyOf(spans),
                road.targetY(), road.ownerA2dKey(), road.ownerB2dKey());
    }

    /**
     * 0.8.322：一轮兜底最多补几个保护缺口。
     *
     * <p>真机 20260909 15.21：一条 2000+ 站的路因为**两个** 1 格宽水洼（segment 617 与 619，
     * 路面比河床低 1 格、且没有 span 覆盖）被整条拒收。旧兜底只标第一个碰撞点，标完第二次
     * 校验仍在第二格失败，于是放弃整条路。</p>
     */
    public static final int MAX_PROTECTED_GAP_FALLBACK = 4;

    /** 保护缺口兜底用的水校验入口（便于在循环里反复校验）。 */
    @FunctionalInterface
    public interface WaterValidation {
        ValidationResult validate(RoadData road);
    }

    /**
     * 0.8.322：把"标保护缺口"的兜底改成可迭代——循环标记碰撞站，直到水校验通过
     * （最多 {@value #MAX_PROTECTED_GAP_FALLBACK} 格）。任何一步不满足（原因不是
     * {@code ordinary_construction_in_water}、没有碰撞点、标记后结构校验不过、
     * 循环结束仍不安全）就返回 {@code null}，调用方照旧拒收整条路。
     *
     * <p>只影响"本来就要被丢掉"的路，代价是被标记的那几格不铺路。</p>
     */
    public static RoadData retainProtectedGaps(RoadData road, int maxGaps, WaterValidation validation) {
        if (road == null || validation == null) return null;
        RoadData current = road;
        int marked = 0;
        int limit = Math.max(1, maxGaps);
        while (marked < limit) {
            ValidationResult result = validation.validate(current);
            if (result == null || result.safe()) break;
            if (!"ordinary_construction_in_water".equals(result.reason())) break;
            Collision collision = result.collision();
            if (collision == null) break;
            RoadData gapped = retainProtectedGapAt(current, collision.segmentIndex());
            if (gapped == null || gapped == current) break;
            if (!RoadDataValidator.validateFinalRoad(gapped).valid()) break;
            current = gapped;
            marked++;
        }
        if (marked == 0 || current == road) return null;
        ValidationResult finalResult = validation.validate(current);
        return finalResult != null && finalResult.safe() ? current : null;
    }

    private static boolean[] spanMask(RoadData road, SpanType type) {
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        boolean[] mask = new boolean[segments.size()];
        if (road.spans() == null || road.spans().isEmpty()) return mask;
        Map<Long, Integer> indices = new HashMap<>();
        for (int index = 0; index < segments.size(); index++) {
            RoadSegmentPlacement segment = segments.get(index);
            if (segment != null && segment.middlePos() != null) {
                indices.put(xzKey(segment.middlePos()), index);
            }
        }
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != type || span.start() == null || span.end() == null) continue;
            Integer start = indices.get(xzKey(span.start()));
            Integer end = indices.get(xzKey(span.end()));
            if (start == null || end == null) continue;
            for (int index = Math.min(start, end); index <= Math.max(start, end); index++) {
                mask[index] = true;
            }
        }
        return mask;
    }

    @FunctionalInterface
    interface ColumnSource {
        WaterColumn sample(int x, int z);
    }

    record WaterColumn(boolean available, boolean water, int waterSurfaceY, int bedY, String source) {
        static WaterColumn unavailable() {
            return new WaterColumn(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE, "unavailable");
        }

        static WaterColumn dry() {
            return new WaterColumn(true, false, Integer.MIN_VALUE, Integer.MIN_VALUE, "dry");
        }

        static WaterColumn wet(int waterSurfaceY, int bedY) {
            return wet(waterSurfaceY, bedY, "test_or_external");
        }

        static WaterColumn wet(int waterSurfaceY, int bedY, String source) {
            return new WaterColumn(true, true, waterSurfaceY, bedY,
                    source == null || source.isBlank() ? "unknown" : source);
        }
    }

    public record Collision(int segmentIndex,
                            BlockPos position,
                            int targetY,
                            int waterSurfaceY,
                            int bedY,
                            SpanType activeSpan,
                            String source) {
    }

    public record ValidationResult(boolean safe, String reason, Collision collision) {
        public static ValidationResult success() {
            return new ValidationResult(true, "ok", null);
        }

        static ValidationResult failure(String reason, Collision collision) {
            return new ValidationResult(false, reason, collision);
        }
    }

    private static long xzKey(BlockPos pos) {
        return pos == null ? Long.MIN_VALUE : ((long) pos.getX() << 32) | (pos.getZ() & 0xffffffffL);
    }
}
