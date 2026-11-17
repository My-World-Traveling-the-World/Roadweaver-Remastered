/* 文件职责：以语法树守住真实世界边界的观测接线，避免仅测试格式器却生产零调用。 */
package net.shiroha233.roadweaver.planning;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteEvidenceWiringTest {
    @Test
    void realValidationCallsUseFinalResultsBehindTheExactDiagnosticGate() throws Exception {
        var unit = parse("pathlogic/core/Road.java");
        var calls = calls(unit, "RoadBuildEvidence.validation");
        assertEquals(3, calls.size());
        assertEquals(List.of("\"final-water\"", "\"final-structure\"", "\"validated\""),
                calls.stream().map(p -> ((MethodInvocationTree)p.getLeaf()).getArguments().get(3).toString()).toList());
        for (TreePath path : calls) {
            assertDirectGate(path);
            var arguments = ((MethodInvocationTree)path.getLeaf()).getArguments();
            assertEquals("rd", arguments.get(2).toString());
            assertEquals("level.dimension().location().toString()", arguments.get(0).toString());
            assertEquals("level.getSeed()", arguments.get(1).toString());
        }
        var water = ((MethodInvocationTree)calls.getFirst().getLeaf()).getArguments();
        assertEquals("waterValidation.safe()", water.get(4).toString());
        assertEquals("waterValidation.reason()", water.get(5).toString());
    }

    @Test
    void chunkEvidenceUsesActualAttemptSetsAndCoarseObserverUsesRealCoordinates() throws Exception {
        var chunk = parse("pathlogic/PathFeature.java");
        var event = only(calls(chunk, "RoadBuildEvidence.chunk"));
        assertDirectGate(event);
        var arguments = ((MethodInvocationTree)event.getLeaf()).getArguments();
        assertEquals("slice.road()", arguments.get(2).toString());
        assertEquals("slice.segmentCount()", arguments.get(6).toString());
        var outcome = (MethodInvocationTree)only(calls(chunk, "RoadBuildEvidence.outcome")).getLeaf();
        assertEquals(List.of("completedRoads.contains(fingerprint)", "incompleteRoads.contains(fingerprint)", "abandoned"),
                outcome.getArguments().stream().map(Object::toString).toList());
        var planner = parse("planning/impl/PipelinePlanner.java");
        var observed = (MethodInvocationTree)only(calls(planner, "pipeline.planTopologyWithShadow")).getLeaf();
        assertEquals(5, observed.getArguments().size(), "必须传实际证据回调，不能落回无关联旧重载");
        var sink = assertInstanceOf(LambdaExpressionTree.class, observed.getArguments().getLast());
        var identity = (MethodInvocationTree)only(calls(TreePath.getPath(planner, sink), "RouteEvidenceId.of")).getLeaf();
        assertEquals(List.of("evidenceDimension", "evidenceSeed", "RouteEvidenceId.endpoint(evidence.fromX(), evidence.fromZ())",
                "RouteEvidenceId.endpoint(evidence.toX(), evidence.toZ())"),
                identity.getArguments().stream().map(Object::toString).toList());
    }

    private static void assertDirectGate(TreePath path) {
        for (var current = path.getParentPath(); current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof IfTree branch) {
                ExpressionTree condition = branch.getCondition();
                while (condition instanceof ParenthesizedTree wrapped) condition = wrapped.getExpression();
                // 仅出现调用不够；false && enabled() 的短路假接线必须失败。
                assertEquals("RoadBuildEvidence.enabled()", condition.toString());
                return;
            }
        }
        fail("真实生产观测必须位于显式开关下");
    }

    private static CompilationUnitTree parse(String relative) throws Exception {
        Path root = Files.isDirectory(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("common/src/main/java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask)compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(root.resolve("net/shiroha233/roadweaver").resolve(relative)));
            return task.parse().iterator().next();
        }
    }

    private static List<TreePath> calls(Tree tree, String method) {
        return calls(new TreePath(assertInstanceOf(CompilationUnitTree.class, tree)), method);
    }

    private static List<TreePath> calls(TreePath path, String method) {
        var found = new ArrayList<TreePath>();
        new TreePathScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                if (call.getMethodSelect().toString().equals(method)) found.add(getCurrentPath());
                return super.visitMethodInvocation(call, unused);
            }
        }.scan(path, null);
        return found;
    }
    private static TreePath only(List<TreePath> values) {
        assertEquals(1, values.size());
        return values.getFirst();
    }
}
