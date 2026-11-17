/* 文件职责：串起终态校验与区块施工的只读证据，不把完工台账等同于成功铺路。 */
package net.shiroha233.roadweaver.pathlogic.metrics;

import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.planning.RouteEvidenceId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

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
                + " construction=not-confirmed content=" + content(road);
    }

    public static String chunk(String dimension, long seed, RoadData road, long fingerprint,
                               int chunkX, int chunkZ, int localStations, ChunkOutcome outcome,
                               boolean repaired) {
        // 按区块输出局部数量，不为每个区块重新遍历整条道路；终态放弃必须独立于完成。
        return "[ROUTE-EVIDENCE] id=" + id(dimension, seed, road) + " stage=chunk"
                + " fingerprint=" + fingerprint + " chunk=" + chunkX + "," + chunkZ
                + " localStations=" + localStations + " outcome=" + outcome + " gapRepair=" + repaired
                + " content=" + content(road);
    }

    /** 只读诊断版本，不参与道路身份、缓存键、完工判定或任何存档字段。 */
    public static String content(RoadData road) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            // 长度前缀区分分站和列的边界；保留站序，因为反向道路的施工索引不同。
            out.writeInt(1); out.writeInt(road.width()); out.writeInt(road.roadType());
            out.writeLong(road.ownerA2dKey()); out.writeLong(road.ownerB2dKey());
            materials(out, road.materials()); materials(out, road.slabMaterials());
            out.writeInt(road.roadSegmentList().size());
            for (var station : road.roadSegmentList()) {
                point(out, station.middlePos());
                out.writeInt(station.positions().size());
                for (var position : station.positions()) point(out, position);
            }
            out.writeInt(road.targetY().size());
            for (int height : road.targetY()) out.writeInt(height);
            out.writeInt(road.spans().size());
            for (var span : road.spans()) {
                point(out, span.start()); point(out, span.end()); text(out, span.type().name());
            }
            out.flush();
            // 不按对象身份缓存：分站内的列列表未承诺深不可变，否则会记录过期内容。
            // 生产调用受buildabilityShadow开关控制；开启时为每条证据扫描输入，不改变默认游玩开销。
            return "c1-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException failure) {
            // SHA-256是JDK必备算法且空输出不接触磁盘，失败不可冒充旧版本相同。
            throw new IllegalStateException("无法计算道路施工内容诊断版本", failure);
        }
    }

    private static void point(DataOutputStream out, BlockPos position) throws IOException {
        out.writeBoolean(position != null);
        if (position != null) { out.writeInt(position.getX()); out.writeInt(position.getY()); out.writeInt(position.getZ()); }
    }

    private static void materials(DataOutputStream out, List<BlockState> materials) throws IOException {
        out.writeInt(materials.size());
        for (var state : materials) {
            text(out, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            var properties = new ArrayList<>(state.getValues().entrySet());
            properties.sort(Comparator.comparing(entry -> entry.getKey().getName()));
            out.writeInt(properties.size());
            for (var entry : properties) property(out, entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> void property(DataOutputStream out, Property<T> key, Comparable<?> value) throws IOException {
        // BlockState保证属性和值的类型对应；使用属性序列化名，不依赖对象hash或枚举toString。
        text(out, key.getName()); text(out, key.getName((T) value));
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String id(String dimension, long seed, RoadData road) {
        return road.hasOwnerPair() ? RouteEvidenceId.of(dimension, seed, road.ownerA2dKey(), road.ownerB2dKey())
                : "missing-owner-pair";
    }
}
