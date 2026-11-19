package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.api.RoadWeaverApi;
import net.shiroha233.roadweaver.api.RoadWeaverBackend;
import net.shiroha233.roadweaver.api.incoming.StructureReservationApi;
import net.shiroha233.roadweaver.apiimpl.ApiBackendImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tannyjung.tanshugetrees_handcode.systems.Caches.TreeShape;
import static net.shiroha233.roadweaver.pathlogic.construction.HugeTreeReservation.ReservationStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** 通过公开API检查真实非空发布和失败结果，并用冻结旧投影核对去重后的列集合与顺序。 */
class TreeReservationOutcomeTest {
    private final BlockPos center = new BlockPos(-17, 64, -1);
    private ServerLevel level;
    private RoadWeaverBackend previous;

    @BeforeEach void prepare() throws Exception {
        // 仅需世界身份作索引键；未初始化的世界一旦被读取即失败，不冒充完整世界生成。
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        level = (ServerLevel) ((sun.misc.Unsafe) field.get(null)).allocateInstance(ServerLevel.class);
        previous = RoadWeaverApi.backend();
        RoadWeaverApi.installBackend(ApiBackendImpl.INSTANCE);
        HugeTreeReservation.clearAll();
        TreeShape.data = new short[]{1143, 0, 0, 0, -1143, 0, 15, 0, 1143, 1, 0, 0};
        TreeShape.failure = null;
        TreeShape.reads = 0;
    }

    @AfterEach void reset() {
        HugeTreeReservation.clearAll();
        TreeShape.data = new short[0];
        TreeShape.failure = null;
        RoadWeaverApi.installBackend(previous);
    }

    @Test void publicApiOnlyReportsSuccessAfterPublishingDistinctColumns() {
        assertTrue(reserve("trees/oak"));
        assertTrue(StructureReservationApi.isReserved(level, center));
        assertTrue(StructureReservationApi.isReserved(level, center.east()));
        assertFalse(StructureReservationApi.isReserved(level, center.north()));
        var result = HugeTreeReservation.reserveResult(level, "trees/oak", center, null, -1);
        assertEquals(RESERVED, result.status());
        assertEquals(2, result.columns(), "两层同列不能按体素数虚报占地");
        StructureReservationApi.releaseChunk(level, new ChunkPos(center));
        assertFalse(StructureReservationApi.isReserved(level, center));
    }

    @Test void shapeReadFailureCannotBecomeAcceptedOrPublished() {
        TreeShape.failure = new IllegalStateException("shape cache unavailable");
        assertFalse(reserve("trees/oak"));
        assertEquals(SHAPE_UNAVAILABLE, outcome("trees/oak"));
        assertFalse(StructureReservationApi.isReserved(level, center));
        TreeShape.failure = null;
        assertTrue(reserve("trees/oak"), "读取恢复后同一会话必须能够真实登记");
        assertTrue(StructureReservationApi.isReserved(level, center));
    }

    @Test void failedLaterTreeDoesNotEraseAlreadyPublishedProtection() {
        assertTrue(reserve("trees/first"));
        TreeShape.failure = new NoClassDefFoundError("optional tree cache changed");
        assertFalse(StructureReservationApi.reserve(level, "trees/second", center.offset(32, 0, 0), null, -1));
        assertTrue(StructureReservationApi.isReserved(level, center));
        assertFalse(StructureReservationApi.isReserved(level, center.offset(32, 0, 0)));
    }

    @Test void publicRegistrationUsesTheRotatedMirroredFallenProjection() {
        int[] transform = {2, 1};
        var expected = frozen(TreeShape.data, transform, 3);
        assertFalse(expected.isEmpty());
        assertTrue(StructureReservationApi.reserve(level, "trees/fallen", center, transform, 3));
        var mask = HugeTreeReservation.planningMask(level, -64, -64, 64, 64, 0);
        for (int x = -40; x <= 0; x++) for (int z = -20; z <= 20; z++)
            assertEquals(expected.contains(BlockPos.asLong(x, 0, z)), mask.blocks(x, z));
    }

    @Test void laterTreeMergesNewBitsIntoAnAlreadyReservedChunk() {
        assertTrue(reserve("trees/first"));
        assertTrue(StructureReservationApi.reserve(level, "trees/next", center.east(), null, -1));
        assertTrue(StructureReservationApi.isReserved(level, center));
        assertTrue(StructureReservationApi.isReserved(level, center.east(2)), "已有区块不能漏掉新树增加的列");
    }

    @Test void batchPublicationKeepsEveryWordOfANegativeChunk() {
        short[] shape = new short[256 * 4];
        for (int i = 0; i < 256; i++) {
            shape[i * 4] = 1143;
            shape[i * 4 + 1] = (short) (i & 15);
            shape[i * 4 + 3] = (short) (i >> 4);
        }
        TreeShape.data = shape;
        var origin = new BlockPos(-16, 64, -16);
        var result = HugeTreeReservation.reserveResult(level, "trees/wide", origin, null, -1);
        assertEquals(256, result.columns());
        for (int x = -16; x < 0; x++) for (int z = -16; z < 0; z++)
            assertTrue(StructureReservationApi.isReserved(level, new BlockPos(x, 0, z)));
        assertFalse(StructureReservationApi.isReserved(level, BlockPos.ZERO));
    }

    @Test void batchEvictsExactlyTheSameFirstChunkAsFrozenColumnPublication() {
        short[] shape = new short[4097 * 4];
        for (int i = 0; i < 4097; i++) {
            shape[i * 4] = 1143;
            shape[i * 4 + 1] = (short) ((i % 65) * 16);
            shape[i * 4 + 3] = (short) ((i / 65) * 16);
        }
        var expected = new HugeTreeReservation.LevelReservations();
        var oldColumns = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (long column : frozenWithDuplicates(shape, new int[]{1, 0}, -1)) oldColumns.add(column);
        expected.reserveAll(oldColumns);
        var actual = new HugeTreeReservation.LevelReservations();
        actual.reserve(TreeColumnProjection.project(shape, center, null, -1));
        assertFalse(actual.contains(center.getX(), center.getZ()));
        for (long column : oldColumns)
            assertEquals(expected.contains(BlockPos.getX(column), BlockPos.getZ(column)),
                    actual.contains(BlockPos.getX(column), BlockPos.getZ(column)));
    }

    @Test void emptyNullAndMalformedShapesAreNotSuccessfulReservations() {
        for (short[] shape : new short[][]{null, new short[0], {1143, 0, 0}}) {
            TreeShape.data = shape;
            assertFalse(reserve("trees/oak"));
            assertEquals(SHAPE_UNAVAILABLE, outcome("trees/oak"));
            assertFalse(StructureReservationApi.isReserved(level, center));
        }
    }

    @Test void legitimateShapeWithoutTrunkHasADifferentOutcomeFromReadFailure() {
        TreeShape.data = new short[]{1200, 0, 0, 0, 1300, 1, 1, 1};
        assertFalse(reserve("trees/oak"));
        assertEquals(NO_TRUNK_COLUMNS, outcome("trees/oak"));
        assertFalse(StructureReservationApi.isReserved(level, center));
    }

    @Test void ignoredDecorativeTemplatesDoNotReadExternalShapeCache() {
        for (String template : List.of("pack/#nature_stuffs/flower", "pack/rock/boulder", "pack\\rock_small/tree")) {
            assertFalse(reserve(template));
            assertEquals(IGNORED_TEMPLATE, outcome(template));
        }
        assertEquals(0, TreeShape.reads);
    }

    @Test void invalidInputAndAbsentBackendNeverReportRegistration() {
        assertFalse(reserve(null));
        assertFalse(reserve(" "));
        assertFalse(StructureReservationApi.reserve(null, "trees/oak", center, null, -1));
        assertEquals(INVALID_INPUT, outcome(null));
        RoadWeaverApi.installBackend(null);
        assertFalse(reserve("trees/oak"));
        assertEquals(0, TreeShape.reads);
    }

    @Test void retainedVoidEntryStillPublishesAndClearRemovesItsColumns() {
        HugeTreeReservation.reserve(level, "trees/oak", center, null, -1);
        assertTrue(HugeTreeReservation.isReserved(level, center));
        HugeTreeReservation.clearForLevel(level);
        assertFalse(HugeTreeReservation.isReserved(level, center));
    }

    @Test void scalarProjectionMatchesFrozenRotationMirrorAndFallenOrder() {
        short[] shape = {1100, -2, 7, 3, 1143, 4, -9, -1, -1199, 1, 12, 5,
                1143, 4, -9, -1, 1200, 9, 9, 9, 1099, 2, 2, 2, Short.MIN_VALUE, 3, 3, 3};
        for (int rotation = -1; rotation <= 5; rotation++)
            for (int mirror = -1; mirror <= 3; mirror++)
                for (int fallen = -1; fallen <= 5; fallen++) {
                    int[] transform = {rotation, mirror};
                    var projection = TreeColumnProjection.project(shape, center, transform, fallen);
                    var expected = frozen(shape, transform, fallen);
                    assertEquals(new java.util.HashSet<>(expected), columnSet(projection));
                    var expectedChunks = new LinkedHashSet<Long>();
                    for (long column : expected) expectedChunks.add(ChunkPos.asLong(BlockPos.getX(column) >> 4, BlockPos.getZ(column) >> 4));
                    assertEquals(List.copyOf(expectedChunks), List.copyOf(projection.chunks().keySet()),
                            "区块首次顺序决定容量淘汰，不能改成任意散列表顺序");
                }
        assertEquals(new java.util.HashSet<>(frozen(shape, new int[]{1, 0}, -1)),
                columnSet(TreeColumnProjection.project(shape, center, null, -1)));
        assertEquals(new java.util.HashSet<>(frozen(shape, new int[]{1, 0}, -1)),
                columnSet(TreeColumnProjection.project(shape, center, new int[0], -1)));
    }

    @Test void tallTrunkPublishesOneColumnInsteadOfThousandsOfDuplicateEntries() {
        short[] shape = new short[4096 * 4];
        for (int i = 0; i < 4096; i++) {
            shape[i * 4] = 1143;
            shape[i * 4 + 2] = (short) i;
        }
        TreeShape.data = shape;
        assertEquals(4096, frozenWithDuplicates(shape, new int[]{1, 0}, -1).size());
        assertEquals(java.util.Set.of(BlockPos.asLong(center.getX(), 0, center.getZ())),
                columnSet(TreeColumnProjection.project(shape, center, null, -1)));
        assertEquals(1, HugeTreeReservation.reserveResult(level, "trees/tall", center, null, -1).columns());
        assertTrue(StructureReservationApi.isReserved(level, center));
    }

    private boolean reserve(String template) {
        return StructureReservationApi.reserve(level, template, center, null, -1);
    }
    private HugeTreeReservation.ReservationStatus outcome(String template) {
        return HugeTreeReservation.reserveResult(level, template, center, null, -1).status();
    }
    private List<Long> frozen(short[] shape, int[] transform, int fallen) {
        return List.copyOf(new LinkedHashSet<>(frozenWithDuplicates(shape, transform, fallen)));
    }
    private java.util.Set<Long> columnSet(TreeColumnProjection.Projection projection) {
        var result = new java.util.HashSet<Long>();
        for (var entry : projection.chunks().long2ObjectEntrySet()) {
            int baseX = ChunkPos.getX(entry.getLongKey()) << 4, baseZ = ChunkPos.getZ(entry.getLongKey()) << 4;
            for (int bit = 0; bit < 256; bit++)
                if ((entry.getValue()[bit >> 6] & (1L << (bit & 63))) != 0)
                    result.add(BlockPos.asLong(baseX + (bit & 15), 0, baseZ + (bit >> 4)));
        }
        assertEquals(result.size(), projection.columns());
        return result;
    }
    private List<Long> frozenWithDuplicates(short[] shape, int[] transform, int fallen) {
        var result = new ArrayList<Long>();
        for (int i = 0; i + 3 < shape.length; i += 4) {
            int type = Math.abs((int) shape[i]);
            if (type < 1100 || type >= 1200) continue;
            int x = shape[i + 1], y = shape[i + 2], z = shape[i + 3], rx = x, rz = z;
            if (transform[0] == 2) { rx = z; rz = -x; }
            else if (transform[0] == 3) { rx = -x; rz = -z; }
            else if (transform[0] == 4) { rx = -z; rz = x; }
            if (transform[1] == 1) rx = -rx;
            else if (transform[1] == 2) rz = -rz;
            // 冻结旧的三维中间数组，不能由新投影的字段反推期望值。
            int[] offset = switch (fallen) {
                case 1 -> new int[]{y, rx, rz};
                case 2 -> new int[]{rx, rz, y};
                case 3 -> new int[]{-y, rx, rz};
                case 4 -> new int[]{rx, rz, -y};
                default -> new int[]{rx, y, rz};
            };
            result.add(BlockPos.asLong(center.getX() + offset[0], 0, center.getZ() + offset[2]));
        }
        return result;
    }
}
