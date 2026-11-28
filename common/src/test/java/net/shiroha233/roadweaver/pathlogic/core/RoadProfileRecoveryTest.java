package net.shiroha233.roadweaver.pathlogic.core;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.postprocess.PathPostProcessor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 模拟勘测中一列深凹和旁边的缓坡；用真实施工剖面验证候选，不能靠 fake 宣称绕路成功。 */
final class RoadProfileRecoveryTest {
    private static final List<BlockPos> STRAIGHT = IntStream.rangeClosed(0, 12)
            .mapToObj(x -> new BlockPos(x, 64, 0)).toList();
    private static final List<BlockPos> AROUND = List.of(STRAIGHT.getFirst(), new BlockPos(2, 64, 3),
            new BlockPos(10, 64, 3), STRAIGHT.getLast());

    @Test void aCrestLockCanKeepBothEndsAndUseASideSlope() {
        var line = PathPostProcessor.rasterizeStations(STRAIGHT, 5);
        var original = assertThrows(ProfileConflict.Rejected.class, () -> profile(line));
        assertEquals(ProfileConflict.Reason.LOCKED_HEIGHT, original.conflict().reason());
        int expectedLimiter = original.conflict().limitingStation();
        assertEquals(6, expectedLimiter);
        var chosen = RoadProfileRecovery.solve(line, 5, RoadProfileRecoveryTest::profile, (seen, station, witness, perform) -> {
            assertEquals(STRAIGHT, seen);
            assertEquals(expectedLimiter, station);
            assertEquals(original.conflict(), witness, "地表搜索必须收到锁高端和限制端的同一份见证");
            assertFalse(perform.test(STRAIGHT), "原线不能假装是替代线");
            assertTrue(perform.test(AROUND), "相同端点有可施工的地表旁路");
        });
        assertEquals(STRAIGHT.getFirst(), chosen.segments().getFirst().middlePos());
        assertEquals(STRAIGHT.getLast(), chosen.segments().getLast().middlePos());
        assertTrue(chosen.segments().stream().anyMatch(s -> s.middlePos().getZ() == 3));
        assertEquals(64, chosen.heights().getFirst());
        assertEquals(64, chosen.heights().getLast());
        for (int i = 2; i < chosen.heights().size(); i++)
            assertTrue(Math.abs(chosen.heights().get(i) - chosen.heights().get(i - 2)) <= 1);
    }

    @Test void validRoutesDoNotSearchAndAnInvalidCandidateDoesNotPublish() {
        var valid = PathPostProcessor.rasterizeStations(PathPostProcessor.densifyStationGaps(AROUND, 2), 5);
        var untouched = RoadProfileRecovery.solve(valid, 5, RoadProfileRecoveryTest::profile,
                (line, station, witness, accept) -> fail("有效线路不应再次预采样或搜索"));
        assertSame(valid, untouched.segments());
        var input = PathPostProcessor.rasterizeStations(STRAIGHT, 5);
        var calls = new AtomicInteger();
        var failure = assertThrows(ProfileConflict.Rejected.class, () -> RoadProfileRecovery.solve(input, 5,
                RoadProfileRecoveryTest::profile, (line, station, witness, accept) -> {
                    calls.incrementAndGet();
                    assertFalse(accept.test(List.of(STRAIGHT.getFirst(), new BlockPos(11, 64, 3))));
                }));
        assertEquals(1, calls.get());
        assertEquals(ProfileConflict.Reason.LOCKED_HEIGHT, failure.conflict().reason());
        assertEquals(STRAIGHT.get(6), failure.conflict().repairCenter(),
                "下一轮必须用精采坐标选窗，正式站号不能作为粗线路下标");
    }

    private static RoadProfileRecovery.Profile profile(List<RoadSegmentPlacement> segments) {
        int n = segments.size();
        var y = segments.stream().map(RoadSegmentPlacement::middlePos)
                .mapToInt(p -> p.getX() == 6 && p.getZ() == 0 ? 48 : 64).toArray();
        int[] pin = new int[n];
        boolean[] fixed = new boolean[n];
        pin[0] = pin[n - 1] = 64;
        fixed[0] = fixed[n - 1] = true;
        var cfg = new ProfileDpSource.Constraints(pin, null, 8, null, fixed);
        var solution = TargetProfileRepair.solveForRoad(y, new boolean[n], 5, 4, 1, 1, cfg, ignored -> new boolean[n]);
        return new RoadProfileRecovery.Profile(segments, List.of(), java.util.Arrays.stream(solution.orElseThrow()).boxed().toList());
    }
}
