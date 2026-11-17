/* 文件职责：串起终态校验与区块施工的只读证据，不把完工台账等同于成功铺路。 */
package net.shiroha233.roadweaver.pathlogic.metrics;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.planning.RouteEvidenceId;

public final class RoadBuildEvidence {
    private static final boolean ENABLED = "on".equalsIgnoreCase(System.getProperty("roadweaver.buildabilityShadow"));
    private RoadBuildEvidence() {}
    public static boolean enabled() { return ENABLED; }

    public record Product(int stations, long pavementEntries, int bridgeSpans, int tunnelSpans,
                          int reuseSpans, int protectedGapSpans) {}
    public enum ChunkOutcome { COMPLETED, ABANDONED, INCOMPLETE, NOT_ATTEMPTED, CONFLICT }

    public static Product product(RoadData road) {
        long entries = 0;
        for (var station : road.roadSegmentList()) entries += station.positions().size();
        int bridges = 0, tunnels = 0, reuse = 0, gaps = 0;
        for (var span : road.spans()) {
            switch (span.type()) {
                case BRIDGE -> bridges++;
                case TUNNEL -> tunnels++;
                case NETWORK_REUSE -> reuse++;
                case PROTECTED_GAP -> gaps++;
                default -> { }
            }
        }
        // 逐站条目可能重复同一列；明确命名，不能伪装成唯一占地或实际方块写入量。
        return new Product(road.roadSegmentList().size(), entries, bridges, tunnels, reuse, gaps);
    }

    public static ChunkOutcome outcome(boolean complete, boolean incomplete, boolean abandoned) {
        if (complete && incomplete) return ChunkOutcome.CONFLICT;
        if (complete) return ChunkOutcome.COMPLETED;
        if (incomplete) return abandoned ? ChunkOutcome.ABANDONED : ChunkOutcome.INCOMPLETE;
        return ChunkOutcome.NOT_ATTEMPTED;
    }

    public static String validation(String dimension, long seed, RoadData road, String stage,
                                    boolean safe, String reason) {
        return "[ROUTE-EVIDENCE] id=" + id(dimension, seed, road) + " stage=" + stage
                + " safe=" + safe + " reason=" + reason + " product=" + product(road)
                + " construction=not-confirmed";
    }

    public static String chunk(String dimension, long seed, RoadData road, long fingerprint,
                               int chunkX, int chunkZ, int localStations, ChunkOutcome outcome,
                               boolean repaired) {
        // 按区块输出局部数量，不为每个区块重新遍历整条道路；终态放弃必须独立于完成。
        return "[ROUTE-EVIDENCE] id=" + id(dimension, seed, road) + " stage=chunk"
                + " fingerprint=" + fingerprint + " chunk=" + chunkX + "," + chunkZ
                + " localStations=" + localStations + " outcome=" + outcome + " gapRepair=" + repaired;
    }

    private static String id(String dimension, long seed, RoadData road) {
        return road.hasOwnerPair() ? RouteEvidenceId.of(dimension, seed, road.ownerA2dKey(), road.ownerB2dKey())
                : "missing-owner-pair";
    }
}
