/* 文件职责：直接执行保留模式编排，独立对拍产物并钉住世界操作顺序和末次端点重座。 */
package net.shiroha233.roadweaver.pathlogic.core;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LegacyTargetProfileTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void representativeModesMatchFrozenOrderedProductAndPortInputs(int scenario) {
        Fixture old = fixture(scenario), now = fixture(scenario);
        var before = new Passes(old, false);
        var after = new Passes(now, false);
        int[] expected = FrozenLegacyTargetProfile.apply(old.initial(), old.stations(), old.spans(),
                old.config(), old.locks(), old.connection(), before);
        int[] actual = run(now, after);
        assertEquals(13, actual.length);
        assertArrayEquals(expected, actual);
        assertEquals(before.trace, after.trace, "只比终态会漏掉后道工序掩盖的前序差异");
        assertEquals(List.of("crossing", "undersea"), after.order);
        assertEquals(old.spans(), now.spans(), "海底重分类对跨度列表的副作用也必须等价");
    }

    @Test
    void worldPassReturnedProfileIsUsedAndSingleTunnelSpikeIsRemoved() {
        Fixture f = fixture(1);
        // 交叉掩码阻止坡度闭合先抹平尖峰，确保真的测到最后的隧道去尖峰调用。
        Arrays.fill(f.stations().isCrossing(), true);
        Arrays.fill(f.stations().tunnelMask(), true);
        var passes = new Passes(f, true);
        int[] result = run(f, passes);
        int[] expected = new int[13]; Arrays.fill(expected, 70);
        assertArrayEquals(expected, result);
        assertEquals(List.of("crossing", "undersea"), passes.order);
    }

    @Test
    void lateWorldPassCannotEraseVillageOrSharedApproachLocks() {
        Fixture f = fixture(5);
        var passes = new Passes(f, true);
        int[] result = run(f, passes);
        assertArrayEquals(new int[]{76, 75, 74}, Arrays.copyOfRange(result, 0, 3));
        assertArrayEquals(new int[]{73, 74, 75}, Arrays.copyOfRange(result, 10, 13));
        assertEquals(76, result[0]);
        assertEquals(75, result[12]);
    }

    @Test
    void crossingFailurePropagatesAndUnderseaIsNeverCalled() {
        Fixture f = fixture(0);
        RuntimeException fault = new IllegalStateException("controlled crossing failure");
        var calls = new ArrayList<String>();
        var passes = new LegacyTargetProfile.WorldPasses() {
            public void crossing(int[] profile) { calls.add("crossing"); throw fault; }
            public int[] undersea(int[] profile) { calls.add("undersea"); return profile; }
        };
        assertSame(fault, assertThrows(IllegalStateException.class, () -> run(f, passes)));
        assertEquals(List.of("crossing"), calls);
    }

    private static int[] run(Fixture f, LegacyTargetProfile.WorldPasses passes) {
        return LegacyTargetProfile.apply(f.initial(), f.stations(), f.spans(), f.config(), f.locks(), f.connection(), passes);
    }

    private record Fixture(int[] initial, LegacyTargetProfile.Stations stations, List<RoadSpan> spans,
                           RoadGenerationConfig config, LegacyTargetProfile.BoundaryLocks locks,
                           StructureConnection connection, int scenario) {}

    private static Fixture fixture(int scenario) {
        List<BlockPos> centers = new ArrayList<>();
        int[] initial = {70, 72, 76, 82, 87, 90, 91, 90, 87, 82, 76, 72, 70};
        int[] terrain = initial.clone();
        boolean[] crossing = new boolean[13], tunnel = new boolean[13], pinned = new boolean[13];
        List<Road.SpanRange> ranges = new ArrayList<>();
        List<RoadSpan> spans = new ArrayList<>();
        for (int i = 0; i < 13; i++) centers.add(new BlockPos(-12 + i * 2, 70, -6));
        if (scenario == 1 || scenario == 6) {
            spans.add(new RoadSpan(centers.get(2), centers.get(10), SpanType.TUNNEL));
            Arrays.fill(tunnel, 2, 11, true);
        }
        if (scenario == 2 || scenario == 6) {
            ranges.add(new Road.SpanRange(4, 6, SpanType.BRIDGE));
            spans.add(new RoadSpan(centers.get(4), centers.get(6), SpanType.BRIDGE));
            Arrays.fill(crossing, 4, 7, true);
        }
        boolean locked = scenario >= 3 && scenario <= 5;
        if (locked) { pinned[0] = true; pinned[12] = true; }
        var locks = new LegacyTargetProfile.BoundaryLocks(locked, scenario == 3, scenario == 3,
                scenario == 4 || scenario == 5, scenario == 4 || scenario == 5,
                scenario == 5 ? new int[]{76, 75, 74} : null,
                scenario == 5 ? new int[]{75, 74, 73} : null, 76, 75);
        ModConfig config = new ModConfig();
        config.roadAppearance().setMaxSlopeStepPerTwoSegments(4);
        config.roadAppearance().setSlopeLimitEnabled(scenario != 7);
        return new Fixture(initial, new LegacyTargetProfile.Stations(centers, terrain, crossing, tunnel, pinned, ranges),
                spans, RoadGenerationConfig.from(config), locks, new StructureConnection(centers.getFirst(), centers.getLast()), scenario);
    }

    private static final class Passes implements LegacyTargetProfile.WorldPasses {
        final Fixture fixture;
        final boolean replace;
        final List<String> order = new ArrayList<>(), trace = new ArrayList<>();
        Passes(Fixture fixture, boolean replace) { this.fixture = fixture; this.replace = replace; }
        public void crossing(int[] profile) {
            order.add("crossing"); trace.add(Arrays.toString(profile));
            for (Road.SpanRange range : fixture.stations().crossingRanges())
                Arrays.fill(profile, range.start(), range.end() + 1, 69);
        }
        public int[] undersea(int[] profile) {
            order.add("undersea"); trace.add(Arrays.toString(profile));
            int[] result = profile.clone();
            if (replace) {
                Arrays.fill(result, 70);
                result[6] = 71;
                // 故意覆盖已锁端点，末次重座若被删，夹具必然失败。
            } else if (fixture.scenario() == 6) {
                fixture.spans().add(new RoadSpan(fixture.stations().centers().get(8),
                        fixture.stations().centers().get(10), SpanType.TUNNEL));
                Arrays.fill(result, 8, 11, 68);
            }
            return result;
        }
    }
}
