package net.shiroha233.roadweaver.pathlogic.validation;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;

import java.util.ArrayList;
import java.util.List;

/** Regression coverage for single-station building protection gaps. */
public final class RoadDataValidatorTest {
    private RoadDataValidatorTest() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            BlockPos center = new BlockPos(x, 64, 0);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }

        BlockPos protectedStation = segments.get(2).middlePos();
        RoadData protectedGap = new RoadData(3, 0, List.of(), List.of(), segments,
                List.of(new RoadSpan(protectedStation, protectedStation, SpanType.PROTECTED_GAP)),
                heights, 1L, 2L);
        require(RoadDataValidator.validateFinalRoad(protectedGap).valid(),
                "a one-station protected building gap must be valid");

        RoadData legacy = new RoadData(3, 0, List.of(), List.of(), segments,
                List.of(new RoadSpan(protectedStation, protectedStation, SpanType.PROTECTED_GAP),
                        new RoadSpan(segments.get(3).middlePos(), segments.get(3).middlePos(),
                                SpanType.JUNCTION_ANCHOR)),
                heights, 1L, 2L);
        RoadData normalizedLegacy = RoadDataValidator.normalizeLegacyRoad(legacy);
        require(normalizedLegacy != null
                        && normalizedLegacy.spans().stream().anyMatch(s ->
                        s.type() == SpanType.PROTECTED_GAP && s.start().equals(protectedStation))
                        && normalizedLegacy.spans().stream().anyMatch(s ->
                        s.type() == SpanType.JUNCTION_ANCHOR
                                && s.start().equals(segments.get(3).middlePos())),
                "legacy normalization dropped single-station protection metadata");

        RoadData invalidPhysicalSpan = new RoadData(3, 0, List.of(), List.of(), segments,
                List.of(new RoadSpan(protectedStation, protectedStation, SpanType.BRIDGE)),
                heights, 1L, 2L);
        require(!RoadDataValidator.validateFinalRoad(invalidPhysicalSpan).valid(),
                "a one-station physical span must remain invalid");

        List<RoadSegmentPlacement> repeatedSegments = new ArrayList<>();
        List<Integer> repeatedHeights = new ArrayList<>();
        for (int x = 0; x < 220; x++) {
            int y = 64;
            if (x == 40 || x == 80 || x == 120 || x == 160) y = 62;
            repeatedSegments.add(new RoadSegmentPlacement(new BlockPos(x, y, 0),
                    List.of(new BlockPos(x, 0, 0))));
            repeatedHeights.add(y);
        }
        RoadData repeatedConflicts = new RoadData(3, 0, List.of(), List.of(),
                repeatedSegments, List.of(), repeatedHeights, 3L, 4L);
        require(!RoadDataValidator.validateFinalRoad(repeatedConflicts).valid(),
                "repeated height conflicts should require repair");
        RoadData repeatedRepaired = RoadDataValidator.repairFinalHeightSteps(repeatedConflicts);
        require(RoadDataValidator.validateFinalRoad(repeatedRepaired).valid(),
                "all separated height conflicts were not smoothed");

        // 0.8.327：span 两端同 xz、不同 y 时必须按 (x,z,y) 分别定位到两站，
        // 而不是都映到同一索引后被判成"单站物理 span"。真机 20260909 19.53 存档
        // (-1104,304)->(-813,716) 的 ROAD_CROSSING 就是这样被整条丢掉的。
        List<RoadSegmentPlacement> stackedSegments = new ArrayList<>();
        List<Integer> stackedHeights = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            BlockPos center = new BlockPos(x, 64, 0);
            stackedSegments.add(new RoadSegmentPlacement(center, List.of(center)));
            stackedHeights.add(64);
            if (x == 5) {
                BlockPos drop = new BlockPos(5, 63, 0);
                stackedSegments.add(new RoadSegmentPlacement(drop, List.of(drop)));
                stackedHeights.add(63);
            }
        }
        BlockPos upper = stackedSegments.get(5).middlePos();
        BlockPos lower = stackedSegments.get(6).middlePos();
        require(upper.getX() == lower.getX() && upper.getZ() == lower.getZ()
                        && upper.getY() != lower.getY(),
                "test setup must stack two stations on one X/Z");
        RoadData stackedCrossing = new RoadData(3, 0, List.of(), List.of(), stackedSegments,
                List.of(new RoadSpan(upper, lower, SpanType.ROAD_CROSSING)),
                stackedHeights, 1L, 2L);
        require(RoadDataValidator.validateFinalRoad(stackedCrossing).valid(),
                "a physical span between two same-X/Z stations must stay valid");

        System.out.println("RoadDataValidatorTest: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
