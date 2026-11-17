/* 文件职责：装饰规划器。 */
package net.shiroha233.roadweaver.decoration.system;

// 日志保留原诊断标记与字段以兼容分析工具；中文主说明使用编译期常量，避免热路径翻译开销。

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.util.RandomSource;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.decoration.types.DistanceSignDecoration;
import net.shiroha233.roadweaver.decoration.types.FallbackLanternDecoration;
import net.shiroha233.roadweaver.decoration.types.LamppostDecoration;
import net.shiroha233.roadweaver.decoration.types.LanternPostDecoration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装饰规划器
 */
public final class DecorationPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    private static final boolean LAMP_BASE_TIMINGS = "on".equalsIgnoreCase(
            System.getProperty("roadweaver.lampBaseTimings", "off"));

    private DecorationPlanner() {}

    public enum Mode { ARTIFICIAL, NATURAL }

    private static final int SIDE_OFFSET = 2;
    private static final List<LampOffset> LAMP_OFFSETS = lampOffsets();

    /**
     * 已放置路灯底座位置的跨回填去重集合。历史缺陷:同一 chunk 被 backfill
     * 反复铺装时,每轮 placeChunk 都新建 processedSegments,跨轮次去重失效,
     * 同一位置被反复放路灯形成密集堆叠(T-34 / 本次 chunk[4,70] 75,1130)。
     * 这里用底座坐标 BlockPos.asLong() 做进程级去重:同一位置只放一次路灯,
     * 无论回填几轮。坐标唯一性由 XZ+Y 决定,不同维度道路不会在相同坐标
     * 交汇,故全局去重安全;内存占用 O(路灯数) 可忽略。仅在 worldgen 后台
     * 线程访问,ConcurrentHashMap 保证并发安全。
     */
    private static final Set<Long> PLACED_LAMP_BASES = ConcurrentHashMap.newKeySet();

    /** 服务端停止时清空去重集合。 */
    public static void clearPlacedLamps() {
        PLACED_LAMP_BASES.clear();
    }

    public static void placeOnSurface(WorldGenLevel world, BlockPos placePos, List<BlockState> material, RandomSource random, ModConfig cfg, Mode mode) {
        int roadType = (mode == Mode.ARTIFICIAL) ? 0 : 1;
        SurfacePlacementUtil.placeOnSurface(world, placePos, material, roadType, random, cfg);
    }

    public static void addDecoration(WorldGenLevel world,
                                     Set<Decoration> out,
                                     BlockPos placePos,
                                     int segmentIndex,
                                     BlockPos nextPos,
                                     BlockPos prevPos,
                                     List<BlockPos> middlePositions,
                                     int roadWidth,
                                     RandomSource random,
                                     boolean distanceIntervalStation,
                                     int lampInterval,
                                     Mode mode) {
        int dx = nextPos.getX() - prevPos.getX();
        int dz = nextPos.getZ() - prevPos.getZ();
        double len = Math.sqrt((double) dx * dx + (double) dz * dz);
        int nx = len != 0 ? (int) Math.round(dx / len) : 0;
        int nz = len != 0 ? (int) Math.round(dz / len) : 0;
        Vec3i dir = new Vec3i(nx, 0, nz);
        Vec3i ortho = new Vec3i(-dir.getZ(), 0, dir.getX());
        int halfWidth = Math.max(1, roadWidth / 2);
        int sideOffset = Math.max(SIDE_OFFSET, halfWidth + 1);

        boolean isStartSign = (segmentIndex == 8);
        boolean isEndSign = (segmentIndex == middlePositions.size() - 10);

        if (isStartSign || isEndSign) {
            boolean isStart = isStartSign;
            BlockPos shifted = isStart ? placePos.offset(ortho.getX() * sideOffset, 0, ortho.getZ() * sideOffset)
                    : placePos.offset(-ortho.getX() * sideOffset, 0, -ortho.getZ() * sideOffset);
            int dist = computeApproxDistanceMeters(world, shifted, isStart, middlePositions);
            out.add(new DistanceSignDecoration(shifted, ortho, world, isStart, String.valueOf(dist)));
            return;
        }

        // 里程只决定候选，不提前取随机数或检查世界，避免改变施工时的装饰落点。
        if (distanceIntervalStation) {
            boolean left = random.nextBoolean();
            BlockPos shifted = findRoadsideLampBase(world, placePos, ortho, sideOffset, left);
            // A roadside lamp must be supported beside the road at the road
            // height. Never fall back to WORLD_SURFACE_WG: on a cliff or
            // raised road that creates floating or under-road lamps.
            if (shifted == null) return;
            // 跨回填去重:同一底座坐标只放一次路灯,避免 backfill 反复铺装
            // 导致的密集堆叠。O(1) 内存查询,无 CPU 负担。
            if (!PLACED_LAMP_BASES.add(shifted.asLong())) return;
            // 路灯坐标监测：debug 级记录每次实际放置的路灯落点，用于定位
            // 密集/重复放置问题。chunk 坐标 + 路段站号 + 距离间隔可回溯到
            // 具体是哪次铺装放的路灯。
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("【路灯放置位置与间距】 " + "RoadWeaver lamp placed mode={} pos={},{} y={} chunk=[{},{}] segment={} interval={}",
                        mode,
                        shifted.getX(), shifted.getZ(), shifted.getY(),
                        Math.floorDiv(shifted.getX(), 16), Math.floorDiv(shifted.getZ(), 16),
                        segmentIndex, lampInterval);
            }
            if (mode == Mode.ARTIFICIAL) {
                out.add(new LamppostDecoration(shifted, ortho, world));
            } else {
                out.add(new LanternPostDecoration(shifted, ortho, world));
            }
        }
    }

    /** 在当前区块内寻找接近计划高度且有支撑的路灯座，避免灯落入峡谷或占据路面。
 *
     * Finds the nearest supported lamp base in the current chunk, beside the
     * road and close to its planned Y level. This handles road edges where
     * the terrain drops away without placing a lamp in the ravine or on the
     * road surface itself.
     */
    private static BlockPos findRoadsideLampBase(WorldGenLevel world,
                                                  BlockPos roadPos,
                                                  Vec3i ortho,
                                                  int sideOffset,
                                                  boolean left) {
        LampBaseTiming timing = LAMP_BASE_TIMINGS ? new LampBaseTiming() : null;
        BlockPos best = findRoadsideLampBase(roadPos, ortho, sideOffset, left, candidate -> {
            if (timing != null) timing.candidates++;
            return isSupportedLampBase(world, candidate, timing);
        });
        if (timing != null) {
            long searchUs = (System.nanoTime() - timing.started) / 1000;
            LOGGER.info("【灯座候选检查次数与耗时】 " + "[LAMP-BASE-TIMING] pos={} left={} candidates={} directReads={} found={} searchUs={}",
                    roadPos, left, timing.candidates, timing.directReads, best != null, searchUs);
        }
        return best;
    }

    static BlockPos findRoadsideLampBase(BlockPos roadPos, Vec3i ortho, int sideOffset, boolean left,
                                         Predicate<BlockPos> supported) {
        int side = left ? 1 : -1;
        int chunkX = roadPos.getX() >> 4, chunkZ = roadPos.getZ() >> 4;
        // 跨一条横向带至少加 100 分，带内最多加 33 分；按总分的顺序找到第一处支撑即可停。
        // 从路面宽度以外开始，不能把更靠内却会压住最外铺装的列加回候选。
        for (int lateral = Math.max(1, sideOffset); lateral <= sideOffset + 3; lateral++) {
            for (LampOffset offset : LAMP_OFFSETS) {
                int x = roadPos.getX() + side * ortho.getX() * lateral + ortho.getZ() * offset.tangent();
                int z = roadPos.getZ() + side * ortho.getZ() * lateral - ortho.getX() * offset.tangent();
                if ((x >> 4) != chunkX || (z >> 4) != chunkZ) continue;
                BlockPos candidate = new BlockPos(x, roadPos.getY() + offset.dy(), z);
                if (supported.test(candidate)) return candidate;
            }
        }
        return null;
    }

    private static List<LampOffset> lampOffsets() {
        var offsets = new ArrayList<LampOffset>(21);
        for (int tangent = -3; tangent <= 3; tangent++) {
            for (int dy : new int[]{0, -1, 1}) offsets.add(new LampOffset(tangent, dy));
        }
        // 稳定排序保持旧遍历的平分次序：先负切向，再同切向的低侧高度。
        offsets.sort(Comparator.comparingInt(LampOffset::score));
        return List.copyOf(offsets);
    }

    private record LampOffset(int tangent, int dy) {
        int score() {
            return Math.abs(tangent) * 10 + Math.abs(dy) * 3;
        }
    }

    private static boolean isSupportedLampBase(WorldGenLevel world, BlockPos base, LampBaseTiming timing) {
        BlockState below = lampBlock(world, base.below(), timing);
        if (!below.isFaceSturdy(world, base.below(), Direction.UP)) return false;
        if (!below.getFluidState().isEmpty()) return false;
        if (!lampBlock(world, base, timing).canBeReplaced()) return false;
        for (int y = 1; y <= 6; y++) {
            if (!lampBlock(world, base.above(y), timing).canBeReplaced()) return false;
        }
        return true;
    }

    private static BlockState lampBlock(WorldGenLevel world, BlockPos position, LampBaseTiming timing) {
        // 只数灯座算法主动发出的读请求；方块形状查询的内部开销由总时长覆盖。
        if (timing != null) timing.directReads++;
        return world.getBlockState(position);
    }

    private static final class LampBaseTiming {
        private final long started = System.nanoTime();
        private int candidates;
        private int directReads;
    }

    private static int computeApproxDistanceMeters(WorldGenLevel world, BlockPos fromPos, boolean isStart, List<BlockPos> middlePositions) {
        BlockPos target = isStart ? middlePositions.get(middlePositions.size() - 1) : middlePositions.get(0);
        long dx = (long) target.getX() - fromPos.getX();
        long dz = (long) target.getZ() - fromPos.getZ();
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        return (int) Math.round(d);
    }
}
