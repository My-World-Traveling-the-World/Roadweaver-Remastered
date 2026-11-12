package net.shiroha233.roadweaver.postprocess;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Replays the saved (528,-304) endpoint roads from the user's test save. */
public final class SavedEndpoint528SimulationTest {
    private SavedEndpoint528SimulationTest() {}

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // ★ 2026-09-25：本测试回放的是**某个特定真机存档**（`1_6_2测试08241035`）里的三条路，
        // 而该存档早已被删除 ⇒ 在绝大多数机器上它会直接抛 NoSuchFileException。
        // 它曾因此长期失败却无人发现 —— 因为虽然注册了 regression 任务，
        // 却**没有挂进 `test` 聚合**（第三轮外部审计指出）。
        //
        // 现在：**数据不在就明确 SKIP 并正常退出（退出码 0）**，数据在就照常断言。
        // 这样既能安全挂进 `test` 聚合（不会因缺存档把全量回归弄红），
        // 又能在有该存档的机器上保留原有覆盖。
        //
        // 有数据的机器上可显式指定：`-Droad.audit.root=<roads 目录>`。
        Path root = Path.of(System.getProperty("road.audit.root",
                "D:/minecraft/Plain Craft Launcher 2/.minecraft/versions/\u3010\u4e91\u6e38\u56db\u6d77\u3011WAYFARING/saves/1_6_2\u6d4b\u8bd508241035/data/roadweaver/persistence/roads/minecraft_overworld/roads"));
        Path spawnShard = root.resolve("16068679120591869979.g2.nbt");
        Path villageShard = root.resolve("7088572418556922306.g2.nbt");
        Path mountainShard = root.resolve("1745531605162883077.g1.nbt");
        if (!Files.isRegularFile(spawnShard) || !Files.isRegularFile(villageShard)
                || !Files.isRegularFile(mountainShard)) {
            System.out.println("SavedEndpoint528SimulationTest: SKIP "
                    + "(audit save not present on this machine; expected 3 road shards under " + root + ")");
            System.out.println("SavedEndpoint528SimulationTest: PASS (skipped)");
            return;
        }
        RoadData spawnToVillage = read(spawnShard);
        RoadData villageToSouth = read(villageShard);
        RoadData mountainToVillage = read(mountainShard);

        require(spawnToVillage.containsOwner(villageToSouth.ownerA2dKey()),
                "saved routes do not share endpoint (528,-304)");
        RoadData merged = JunctionConnector.connectSecondaryToPrimary(spawnToVillage, villageToSouth);
        require(merged != null, "0.8.155 could not connect the saved shared-endpoint route");

        int reuse = RoadSnapService.networkReuseStationCount(merged);
        int parallel = longestNonReusedParallelRun(spawnToVillage, merged);
        System.out.println("SavedEndpoint528SimulationTest: reuseStations=" + reuse
                + " longestParallelRun=" + parallel
                + " mergedSegments=" + merged.roadSegmentList().size());
        require(reuse >= 300, "saved shared endpoint still only reused a short tail: " + reuse);
        require(parallel <= 24, "saved endpoint replay still leaves a long parallel corridor: " + parallel);

        // The third route reaches the same village from the opposite side. It
        // is a valid independent branch when it has no shared approach, so it
        // is intentionally not required to reuse the first corridor.
        RoadData chained = JunctionConnector.connectSecondaryToPrimary(mountainToVillage, merged);
        System.out.println("SavedEndpoint528SimulationTest: thirdRouteReuse="
                + (chained == null ? 0 : RoadSnapService.networkReuseStationCount(chained)));
        System.out.println("SavedEndpoint528SimulationTest: PASS");
    }

    private static RoadData read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            CompoundTag root = NbtIo.read(input);
            return RoadData.CODEC.parse(new Dynamic<>(NbtOps.INSTANCE, root.get("road")))
                    .result().orElseThrow(() -> new IOException("road decode failed: " + path));
        }
    }

    private static int longestNonReusedParallelRun(RoadData primary, RoadData secondary) {
        List<RoadSegmentPlacement> a = primary.roadSegmentList();
        List<RoadSegmentPlacement> b = secondary.roadSegmentList();
        boolean[] reused = new boolean[b.size()];
        if (mergedReuseMask(secondary, reused)) {
            // no-op; mask is filled below
        }
        int best = 0;
        int run = 0;
        int previous = -1;
        for (int j = 0; j < b.size(); j++) {
            if (reused[j]) {
                run = 0;
                previous = -1;
                continue;
            }
            RoadSegmentPlacement segment = b.get(j);
            if (segment == null || segment.middlePos() == null) {
                run = 0;
                previous = -1;
                continue;
            }
            int nearest = -1;
            int distance = Integer.MAX_VALUE;
            for (int i = 0; i < a.size(); i++) {
                BlockPos p = a.get(i).middlePos();
                BlockPos q = segment.middlePos();
                int d = Math.max(Math.abs(p.getX() - q.getX()), Math.abs(p.getZ() - q.getZ()));
                if (d < distance) {
                    distance = d;
                    nearest = i;
                }
            }
            if (distance <= 12 && (previous < 0 || Math.abs(nearest - previous) <= 4)) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            previous = nearest;
        }
        return best;
    }

    private static boolean mergedReuseMask(RoadData road, boolean[] mask) {
        if (road.spans() == null) return false;
        java.util.Map<Long, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < road.roadSegmentList().size(); i++) {
            RoadSegmentPlacement segment = road.roadSegmentList().get(i);
            if (segment != null && segment.middlePos() != null) indices.put(segment.middlePos().asLong(), i);
        }
        boolean found = false;
        for (RoadSpan span : road.spans()) {
            if (span == null || span.type() != SpanType.NETWORK_REUSE) continue;
            Integer from = indices.get(span.start().asLong());
            Integer to = indices.get(span.end().asLong());
            if (from == null || to == null) continue;
            for (int i = Math.min(from, to); i <= Math.max(from, to); i++) mask[i] = true;
            found = true;
        }
        return found;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
