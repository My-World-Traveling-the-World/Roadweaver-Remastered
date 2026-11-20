/* 文件职责：在单轮共用计算中保留重复XZ的首末站，消除各跨度类型的重复索引。 */
package net.shiroha233.roadweaver.worldgen.road;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

/** 单轮共用判定的惰性XZ索引；首站与末站同时保存，不能把两种既有跨度端点语义合并掉。 */
final class RoadReuseStationIndex {
    private final List<RoadSegmentPlacement> stations;
    private Long2LongOpenHashMap ranges;

    RoadReuseStationIndex(List<RoadSegmentPlacement> stations) { this.stations = stations; }

    int first(BlockPos point) {
        long value = lookup(point);
        return value == -1L ? -1 : (int) (value >>> 32);
    }

    int last(BlockPos point) {
        long value = lookup(point);
        return value == -1L ? -1 : (int) value;
    }

    private long lookup(BlockPos point) {
        if (point == null) return -1L;
        if (ranges == null) {
            var built = new Long2LongOpenHashMap();
            built.defaultReturnValue(-1L);
            for (int i = 0; i < stations.size(); i++) {
                var station = stations.get(i);
                if (station == null || station.middlePos() == null) continue;
                long key = RoadPhysicalReuseNormalizer.xzKey(station.middlePos());
                long previous = built.get(key);
                // 站号非负，-1不会与真实打包值冲突；零键和Long.MIN_VALUE均是合法XZ键。
                long first = previous == -1L ? (long) i << 32 : previous & 0xffffffff00000000L;
                built.put(key, first | (i & 0xffffffffL));
            }
            // 完成后才发布，构建异常不能留下半张索引；实例只由本轮调用线程持有。
            ranges = built;
        }
        return ranges.get(RoadPhysicalReuseNormalizer.xzKey(point));
    }
}
