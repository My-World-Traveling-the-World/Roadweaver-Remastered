package net.shiroha233.roadweaver.planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.core.constants.RoadConstants;
import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.StructureConnection;
import net.shiroha233.roadweaver.persistence.sharded.RoadShardStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 把「通向村庄的失败连接」改接到「附近已有的道路上」。
 *
 * <p><b>为什么需要它</b>：0.9.078 真机取证 —— 23 条连接里 3 条彻底失败
 * （{@code all precision tiers, local repair and fallbacks were exhausted}）。
 * 但这 3 条的两端距最近已有道路只有 <b>0 / 16.6 / 49.2 / 55.0 / 64.3 格</b>
 * （只有一条的终点在 872 格外）。也就是说路其实已经修到旁边了，A* 还在坚持生成
 * 一条完整的长路，结果卡在保护缺口 / 坡度校验上。</p>
 *
 * <p><b>做法</b>：A* 失败后，查两端半径 {@code attachToExistingRoadRadiusBlocks}
 * 内的已生成道路，把「远离路的一端」就近改到那条路的站点上，然后重试。改出来的是一条
 * 几十格的接入短段，几乎必然成功。</p>
 *
 * <p><b>与 {@link ExistingRoadEndpointReuse} 的区别</b>：那个只在两条连接
 * <b>共享同一个村庄端点</b>时生效；这里不要求共享端点，任何已完成道路都能接。
 * 两者互补，不冲突。</p>
 */
public final class ExistingRoadAttachment {
    private static final Logger LOGGER = LoggerFactory.getLogger("roadweaver");
    /** 端点已经落在路上（横向距离不超过这个值）就不需要为它生成接入段。 */
    private static final int ON_ROAD_EPSILON = RoadConstants.ROAD_SNAP_THRESHOLD;
    /** 接入段短于这个长度时视为「已经连通」，不再生成以免产生碎路。 */
    private static final int MIN_ATTACH_LENGTH = 32;

    private ExistingRoadAttachment() {
    }

    /**
     * @return 改接后的连接；{@code null} 表示没有可用的接入点（保持原失败状态）
     */
    public static StructureConnection find(ServerLevel level, StructureConnection original, int maxRadius) {
        if (level == null || original == null || maxRadius <= 0) return null;
        BlockPos from = original.from();
        BlockPos to = original.to();
        if (from == null || to == null) return null;

        int minX = Math.min(from.getX(), to.getX()) - maxRadius;
        int minZ = Math.min(from.getZ(), to.getZ()) - maxRadius;
        int maxX = Math.max(from.getX(), to.getX()) + maxRadius;
        int maxZ = Math.max(from.getZ(), to.getZ()) + maxRadius;

        List<RoadData> roads;
        try {
            roads = RoadShardStorage.queryRect(level, minX, minZ, maxX, maxZ);
        } catch (Throwable couldNotQuery) {
            LOGGER.debug("RoadWeaver attachment query failed", couldNotQuery);
            return null;
        }
        if (roads == null || roads.isEmpty()) return null;

        BlockPos bestFrom = null;
        int bestFromDist = Integer.MAX_VALUE;
        BlockPos bestTo = null;
        int bestToDist = Integer.MAX_VALUE;

        for (RoadData road : roads) {
            if (road == null) continue;
            List<RoadSegmentPlacement> segments = road.roadSegmentList();
            if (segments == null) continue;
            for (RoadSegmentPlacement segment : segments) {
                if (segment == null) continue;
                BlockPos station = segment.middlePos();
                if (station == null) continue;
                int distFrom = horizontalDistance(station, from);
                if (distFrom <= maxRadius && distFrom < bestFromDist) {
                    bestFromDist = distFrom;
                    bestFrom = station;
                }
                int distTo = horizontalDistance(station, to);
                if (distTo <= maxRadius && distTo < bestToDist) {
                    bestToDist = distTo;
                    bestTo = station;
                }
            }
        }

        if (bestFrom == null && bestTo == null) return null;

        boolean fromOnRoad = bestFromDist <= ON_ROAD_EPSILON;
        boolean toOnRoad = bestToDist <= ON_ROAD_EPSILON;
        BlockPos newFrom = fromOnRoad ? from : bestFrom;
        BlockPos newTo = toOnRoad ? to : bestTo;
        if (newFrom == null) newFrom = from;
        if (newTo == null) newTo = to;
        if (newFrom.equals(from) && newTo.equals(to)) return null;

        int attachLength = horizontalDistance(newFrom, newTo);
        if (!shouldAttach(bestFromDist, bestToDist, attachLength, horizontalDistance(from, to))) return null;

        StructureConnection attached = new StructureConnection(newFrom, newTo, ConnectionStatus.PLANNED);
        LOGGER.info("RoadWeaver attaching failed connection to existing road: {} -> {} becomes {} -> {} "
                        + "(fromDistance={} toDistance={} attachLength={})",
                from, to, newFrom, newTo, bestFromDist, bestToDist, attachLength);
        return attached;
    }

    /**
     * 改接是否值得的纯决策（不碰世界状态，便于逐条钉住边界）。
     *
     * @param fromDist     起点到最近道路站点的横向距离；{@code Integer.MAX_VALUE} 表示搜索半径内没有
     * @param toDist       终点到最近道路站点的横向距离
     * @param attachLength 改接后两端之间的横向距离
     * @param originalLength 改接前两端之间的横向距离
     */
    static boolean shouldAttach(int fromDist, int toDist, int attachLength, int originalLength) {
        boolean fromOnRoad = fromDist <= ON_ROAD_EPSILON;
        boolean toOnRoad = toDist <= ON_ROAD_EPSILON;
        // 两端都已在路上 = 这条连接本质已连通，生成 0 长度碎路没有意义
        if (fromOnRoad && toOnRoad) return false;
        // 一个接入点都没有
        if (fromDist == Integer.MAX_VALUE && toDist == Integer.MAX_VALUE) return false;
        // 太短的接入段会变成碎路
        if (attachLength < MIN_ATTACH_LENGTH) return false;
        // 改接后反而更长：等于拿两个更远的点去绕，得不偿失
        if (attachLength >= originalLength) return false;
        return true;
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }
}
