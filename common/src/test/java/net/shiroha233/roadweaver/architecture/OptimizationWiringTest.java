package net.shiroha233.roadweaver.architecture;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 行为端口测试之外核对真实调用树；不能让新算法只在测试中执行。 */
class OptimizationWiringTest {
    @Test void stableHeightRequiresServerWorldAndOwningThread() throws Exception {
        var tree = parse("pathlogic/pathcalc/HeightProfileService.java");
        var conditions = new ArrayList<String>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitIf(IfTree node, Void ignored) {
                if (calls(node.getThenStatement()).contains("buildStable"))
                    conditions.add(node.getCondition().toString().replaceAll("\\s+", ""));
                return super.visitIf(node, ignored);
            }
        }.scan(tree, null);
        assertEquals(List.of("(worldinstanceofServerLevelserver&&server.getServer().isSameThread())"), conditions);
        assertTrue(calls(tree).contains("StableHeightWindow.build"));
    }

    @Test void realWaterValidatorAndBoundedQueryBothUseTheBatch() throws Exception {
        assertEquals(1, Collections.frequency(calls(parse("pathlogic/validation/FinalRoadWaterValidator.java")),
                "riverSession.withQueryBatch"));
        var queries = calls(parse("compat/StreamsReflowingCompat.java"));
        assertTrue(queries.containsAll(List.of("StreamsQueryBatch.run", "StreamsQueryBatch.active", "StreamsQueryBatch.query")));
    }

    @Test void physicalReuseUsesCandidatesForBothClaimAndOrphanVerification() throws Exception {
        assertEquals(2, Collections.frequency(calls(parse("worldgen/road/RoadPhysicalReuseNormalizer.java")),
                "preparation.candidates"));
    }

    private static List<String> calls(Tree tree) {
        var result = new ArrayList<String>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                result.add(call.getMethodSelect().toString());
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(tree, null);
        return result;
    }

    private static CompilationUnitTree parse(String path) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var source = files.getJavaFileObjects(Path.of("src/main/java/net/shiroha233/roadweaver", path));
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null, source);
            return task.parse().iterator().next();
        }
    }
}
