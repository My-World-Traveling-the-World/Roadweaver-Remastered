package net.shiroha233.roadweaver.pathlogic.core;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.core.model.RoadSpan;
import net.shiroha233.roadweaver.core.model.SpanType;
import net.shiroha233.roadweaver.pathfinding.FillBridgeExtender;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileDpSource;
import net.shiroha233.roadweaver.pathlogic.profile.ProfileFallback;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 复现现场的“已通过→补判干谷→按原陡岸锁高→整路失败”，不是重放现场全部地形。 */
final class FinalBridgeClassificationTest {
    @Test void productionFinishesBridgeClassificationInsideTheProfileRecoveryBoundary() throws Exception {
        // 行为测试之外核对真正生成入口，防止只修离线端口而生产仍沿用旧循环。
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("common/src/main/java/net/shiroha233/roadweaver/pathlogic/core/Road.java")))
            root = root.getParent();
        assertNotNull(root);
        var source = root.resolve("common/src/main/java/net/shiroha233/roadweaver/pathlogic/core/Road.java");
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, files, null,
                    List.of("-proc:none"), null, files.getJavaFileObjects(source.toFile()));
            var calls = new java.util.ArrayList<String>();
            new com.sun.source.util.TreeScanner<Void, String>() {
                @Override public Void visitMethod(com.sun.source.tree.MethodTree method, String context) {
                    return super.visitMethod(method, method.getName().toString());
                }
                @Override public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree call, String method) {
                    calls.add(method + ":" + call.getMethodSelect());
                    return super.visitMethodInvocation(call, method);
                }
            }.scan(task.parse(), null);
            assertEquals(1, calls.stream().filter("solveInitialProfile:RoadProfileRecovery.finish"::equals).count());
            assertEquals(List.of("solveInitialProfile:FillBridgeExtender.refineFinalFillBridgeSpans"),
                    calls.stream().filter(call -> call.endsWith(":FillBridgeExtender.refineFinalFillBridgeSpans")).toList());
        }
    }

    @Test void dryCrossingKeepsTheAcceptedGradeInsteadOfRelockingTheRawBanks() {
        int[] terrain = {104, 104, 106, 98, 108, 108, 108};
        var segments = line(terrain);
        var solved = TargetProfileRepair.solveForRoad(terrain, null, 5, 4, 1, 1,
                ProfileDpSource.Constraints.none(), ignored -> new boolean[terrain.length]).orElseThrow();
        var heights = Arrays.stream(solved).boxed().toList();
        var input = new RoadProfileRecovery.Profile(segments, List.of(), heights);
        var centers = segments.stream().map(RoadSegmentPlacement::middlePos).toList();
        int[] lowest = terrain.clone();
        // 中心线本身有解，但路边一列深六格；必须确实产生补判跨度，不能用空集合过关。
        lowest[3] = solved[3] - 6;
        var output = RoadProfileRecovery.finish(input, spans -> spans, spans -> {
            var crossing = FillBridgeExtender.specialSpanMask(spans, centers);
            var next = TargetProfileRepair.solveForRoad(terrain, crossing, 5, 4, 1, 1,
                    ProfileDpSource.Constraints.none(), ignored -> new boolean[terrain.length]).orElseThrow();
            return new RoadProfileRecovery.Profile(segments, spans, Arrays.stream(next).boxed().toList());
        }, current -> FillBridgeExtender.refineFinalFillBridgeSpans(
                current.spans(), centers, current.heights(), lowest));
        assertEquals(List.of(new RoadSpan(centers.get(2), centers.get(4), SpanType.ROAD_CROSSING)), output.spans());
        assertSame(heights, output.heights(), "分类不能重新钉高已经合规的道路");
        assertEquals(0, ProfileFallback.violations(solved, 1, 1));
        // 负面对照：旧接线必定复现锁高矛盾，确保夹具真的区分新旧行为。
        assertThrows(ProfileConflict.Rejected.class, () -> TargetProfileRepair.solveForRoad(terrain,
                FillBridgeExtender.specialSpanMask(output.spans(), centers), 5, 4, 1, 1,
                ProfileDpSource.Constraints.none(), ignored -> new boolean[terrain.length]));
    }

    @Test void riverExtensionIsSolvedBeforeTheFinalDryClassification() {
        var segments = line(new int[]{64, 64, 64, 64, 64});
        var first = List.of(new RoadSpan(segments.get(1).middlePos(), segments.get(3).middlePos(), SpanType.BRIDGE));
        var extended = List.of(new RoadSpan(segments.getFirst().middlePos(), segments.getLast().middlePos(), SpanType.BRIDGE));
        var input = new RoadProfileRecovery.Profile(segments, first, List.of(64, 64, 64, 64, 64));
        var raised = List.of(65, 65, 65, 65, 65);
        var calls = new AtomicInteger();
        var output = RoadProfileRecovery.finish(input, spans -> extended, spans -> {
            calls.incrementAndGet();
            assertEquals(extended, spans);
            return new RoadProfileRecovery.Profile(segments, spans, raised);
        }, current -> {
            assertSame(raised, current.heights(), "干谷必须按河岸结算后的剖面分类");
            return current.spans();
        });
        assertEquals(1, calls.get(), "稳定的河岸不应重复求解");
        assertEquals(extended, output.spans());
        assertSame(raised, output.heights());
    }

    @Test void infeasibleRiverExtensionIsStillRejected() {
        var segments = line(new int[]{64, 64, 64});
        var input = new RoadProfileRecovery.Profile(segments, List.of(), List.of(64, 64, 64));
        var conflict = new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT, 0, 2, 90, 65);
        var failure = assertThrows(ProfileConflict.Rejected.class, () -> RoadProfileRecovery.finish(input,
                spans -> List.of(new RoadSpan(segments.getFirst().middlePos(), segments.getLast().middlePos(), SpanType.BRIDGE)),
                spans -> { throw new ProfileConflict.Rejected(conflict); },
                current -> { throw new AssertionError("河岸无解不能伪装成只补分类"); }));
        assertEquals(conflict, failure.conflict());
    }

    private static List<RoadSegmentPlacement> line(int[] terrain) {
        return IntStream.range(0, terrain.length).mapToObj(i -> new BlockPos(i, terrain[i], 0))
                .map(p -> new RoadSegmentPlacement(p, List.of(p))).toList();
    }
}
