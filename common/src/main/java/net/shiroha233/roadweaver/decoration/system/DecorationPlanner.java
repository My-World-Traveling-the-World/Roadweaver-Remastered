/* 文件职责：装饰规划器。 */
package net.shiroha233.roadweaver.decoration.system;

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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 装饰规划器
 */
public final class DecorationPlanner {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");

    private DecorationPlanner() {}

    public enum Mode { ARTIFICIAL, NATURAL }

    private static final int SIDE_OFFSET = 2;

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
                LOGGER.debug("RoadWeaver lamp placed mode={} pos={},{} y={} chunk=[{},{}] segment={} interval={}",
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

    /**
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
        int side = left ? 1 : -1;
        int chunkMinX = Math.floorDiv(roadPos.getX(), 16) * 16;
        int chunkMinZ = Math.floorDiv(roadPos.getZ(), 16) * 16;
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        int baseY = roadPos.getY();

        // Search a small band beside the road. Tangential offsets allow the
        // lamp to move around a local hole while remaining next to this road.
        // O-14（0.9.027）：「路侧灯压到路面最外列」。
        // 搜索起点原来是 Math.max(1, sideOffset - 1)；width=5 ⇒ halfWidth=2、
        // sideOffset=max(2, 3)=3 ⇒ 起点 lateral=2 = **正好是路面最外一列**，
        // 于是灯基座落在铺装边缘上，灯柱插在路面里。
        // 起点必须是 sideOffset 本身（= max(SIDE_OFFSET, halfWidth+1)），才保证在路面外一格。
        for (int lateral = Math.max(1, sideOffset); lateral <= sideOffset + 3; lateral++) {
            for (int tangent = -3; tangent <= 3; tangent++) {
                int x = roadPos.getX() + side * ortho.getX() * lateral + ortho.getZ() * tangent;
                int z = roadPos.getZ() + side * ortho.getZ() * lateral - ortho.getX() * tangent;
                if (x < chunkMinX || x > chunkMaxX || z < chunkMinZ || z > chunkMaxZ) continue;
                for (int yOffset : new int[]{0, -1, 1}) {
                    BlockPos candidate = new BlockPos(x, baseY + yOffset, z);
                    if (!isSupportedLampBase(world, candidate)) continue;
                    int score = lateral * 100 + Math.abs(tangent) * 10 + Math.abs(yOffset) * 3;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isSupportedLampBase(WorldGenLevel world, BlockPos base) {
        BlockState below = world.getBlockState(base.below());
        if (!below.isFaceSturdy(world, base.below(), Direction.UP)) return false;
        if (!below.getFluidState().isEmpty()) return false;
        if (!world.getBlockState(base).canBeReplaced()) return false;
        for (int y = 1; y <= 6; y++) {
            if (!world.getBlockState(base.above(y)).canBeReplaced()) return false;
        }
        return true;
    }

    private static int computeApproxDistanceMeters(WorldGenLevel world, BlockPos fromPos, boolean isStart, List<BlockPos> middlePositions) {
        BlockPos target = isStart ? middlePositions.get(middlePositions.size() - 1) : middlePositions.get(0);
        long dx = (long) target.getX() - fromPos.getX();
        long dz = (long) target.getZ() - fromPos.getZ();
        double d = Math.sqrt((double) dx * dx + (double) dz * dz);
        return (int) Math.round(d);
    }
}
