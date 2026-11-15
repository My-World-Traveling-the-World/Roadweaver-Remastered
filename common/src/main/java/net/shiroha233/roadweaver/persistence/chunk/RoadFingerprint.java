/* 文件职责：集中维护道路聚合根的稳定指纹算法，保证替换、删除与磁盘引用一致。 */
package net.shiroha233.roadweaver.persistence.chunk;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;

import java.util.List;
import java.util.EnumSet;
import java.util.Set;

public final class RoadFingerprint {
    private static final long FNV_OFFSET = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private RoadFingerprint() {}

    /**
     * 计算道路指纹。
     *
     * <h2>★ 端点只是「身份」，不是「内容」（2026-11-13 修正）</h2>
     * <p>旧实现里，只要 {@link RoadData#hasOwnerPair()} 为真就<b>直接返回</b>
     * 「两个端点坐标的哈希」，几何一概不看。后果：</p>
     * <ul>
     *   <li>同一对端点之间重新规划（桥改隧道、中心线改道、剖面重算）⇒ 指纹<b>不变</b>；</li>
     *   <li>{@code BuildFrame} 按指纹缓存桥掩码 ⇒ 拿<b>旧的</b>桥区间去铺<b>新的</b>路。</li>
     * </ul>
     * <p>所以现在：<b>几何永远参与哈希</b>，端点对只是在这一层之上再叠一条身份信息。</p>
     */
    public static long compute(RoadData road) {
        if (road == null || road.roadSegmentList() == null || road.roadSegmentList().isEmpty()) return 0L;

        BlockPos first = firstPosition(road.roadSegmentList());
        BlockPos last = lastPosition(road.roadSegmentList());
        if (first == null || last == null) return 0L;
        boolean forward = compare(first, last) <= 0;

        long hash = mix(mix(FNV_OFFSET, road.width()), road.roadType());
        List<RoadSegmentPlacement> segments = road.roadSegmentList();
        for (int offset = 0; offset < segments.size(); offset++) {
            int index = forward ? offset : segments.size() - 1 - offset;
            RoadSegmentPlacement segment = segments.get(index);
            if (segment != null && segment.middlePos() != null) {
                hash = mix(hash, segment.middlePos().asLong());
            }
        }

        if (road.hasOwnerPair()) {
            long firstOwner = Math.min(road.ownerA2dKey(), road.ownerB2dKey());
            long secondOwner = Math.max(road.ownerA2dKey(), road.ownerB2dKey());
            hash = mix(mix(hash, 0x524F41444F574E52L), firstOwner);
            hash = mix(hash, secondOwner);
        }
        return nonZero(hash);
    }

    public enum ConstructionChange { SURFACE, STATIONS_AND_COLUMNS, PROFILE, SPANS }

    /**
     * 比较两份施工输入，独立于用于索引与台账查询的稳定指纹。
     * <p>精确比较、保留站序和材料顺序，因为同一批列分给不同站时，目标高程和落块阶段也可能不同。</p>
     */
    public static Set<ConstructionChange> constructionChanges(RoadData before, RoadData after) {
        var changes = EnumSet.noneOf(ConstructionChange.class);
        if (before.width() != after.width() || before.roadType() != after.roadType()
                || !before.materials().equals(after.materials())
                || !before.slabMaterials().equals(after.slabMaterials())) {
            changes.add(ConstructionChange.SURFACE);
        }
        if (!before.roadSegmentList().equals(after.roadSegmentList())) changes.add(ConstructionChange.STATIONS_AND_COLUMNS);
        if (!before.targetY().equals(after.targetY())) changes.add(ConstructionChange.PROFILE);
        if (!before.spans().equals(after.spans())) changes.add(ConstructionChange.SPANS);
        return Set.copyOf(changes);
    }

    private static long mix(long hash, long value) {
        long mixed = hash;
        for (int shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
            mixed ^= (value >>> shift) & 0xFFL;
            mixed *= FNV_PRIME;
        }
        return mixed;
    }

    private static long nonZero(long hash) {
        return hash == 0L ? FNV_OFFSET : hash;
    }

    private static int compare(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int z = Integer.compare(first.getZ(), second.getZ());
        return z != 0 ? z : Integer.compare(first.getY(), second.getY());
    }

    private static BlockPos firstPosition(List<RoadSegmentPlacement> segments) {
        for (RoadSegmentPlacement segment : segments) {
            if (segment != null && segment.middlePos() != null) return segment.middlePos();
        }
        return null;
    }

    private static BlockPos lastPosition(List<RoadSegmentPlacement> segments) {
        for (int index = segments.size() - 1; index >= 0; index--) {
            RoadSegmentPlacement segment = segments.get(index);
            if (segment != null && segment.middlePos() != null) return segment.middlePos();
        }
        return null;
    }
}
