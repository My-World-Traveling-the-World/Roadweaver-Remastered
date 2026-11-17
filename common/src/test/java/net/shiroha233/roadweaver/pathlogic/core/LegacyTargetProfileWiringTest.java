/* 文件职责：以语法树限定旧模式分派和世界端口接线；此守卫不冒充真实世界施工测试。 */
package net.shiroha233.roadweaver.pathlogic.core;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyTargetProfileWiringTest {
    @Test
    void legacyRunsOnlyInDpAbsentBranchAndRetainsRealWorldPortOrder() throws Exception {
        Path root = Files.isDirectory(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("common/src/main/java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask)compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(root.resolve("net/shiroha233/roadweaver/pathlogic/core/TargetYComputation.java")));
            var unit = task.parse().iterator().next();
            var found = new ArrayList<TreePath>();
            new TreePathScanner<Void, Void>() {
                public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                    if (call.getMethodSelect().toString().equals("LegacyTargetProfile.apply")) found.add(getCurrentPath());
                    return super.visitMethodInvocation(call, unused);
                }
            }.scan(unit, null);
            assertEquals(1, found.size());
            TreePath current = found.getFirst();
            while (current.getParentPath() != null && !(current.getParentPath().getLeaf() instanceof IfTree)) current = current.getParentPath();
            var dispatch = assertInstanceOf(IfTree.class, current.getParentPath().getLeaf());
            assertEquals("(dpProfile.isPresent())", dispatch.getCondition().toString());
            assertSame(dispatch.getElseStatement(), current.getLeaf());
            var call = (MethodInvocationTree)found.getFirst().getLeaf();
            var ports = assertInstanceOf(NewClassTree.class, call.getArguments().getLast()).getClassBody();
            var portCalls = new LinkedHashMap<String, List<String>>();
            for (var member : ports.getMembers()) if (member instanceof MethodTree method) {
                var names = new ArrayList<String>();
                new TreeScanner<Void, Void>() {
                    public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                        names.add(invocation.getMethodSelect().toString());
                        return super.visitMethodInvocation(invocation, unused);
                    }
                }.scan(method.getBody(), null);
                portCalls.put(method.getName().toString(), names);
            }
            assertEquals(List.of("applyCrossingProfiles"), portCalls.get("crossing"));
            var sea = portCalls.get("undersea");
            assertNotNull(sea);
            assertEquals(1, Collections.frequency(sea, "UnderseaTunnelPlanner.convertLongOceanBridgesToUnderseaTunnels"));
            assertEquals(1, Collections.frequency(sea, "UnderseaTunnelPlanner.applyUnderseaTunnelProfile"));
            assertTrue(sea.indexOf("UnderseaTunnelPlanner.convertLongOceanBridgesToUnderseaTunnels")
                    < sea.indexOf("UnderseaTunnelPlanner.applyUnderseaTunnelProfile"));
        }
    }
}
