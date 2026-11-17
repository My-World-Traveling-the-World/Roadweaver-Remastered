/* 文件职责：对无法离线创建的 ServerLevel 边界做窄接线守卫，配合真实文件行为测试。 */
package net.shiroha233.roadweaver.planning;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadinessPersistenceWiringTest {
    @Test
    void realServerCompletionCallsTheTestedFinisherInsideTheServerTask() throws Exception {
        MethodTree start = method("startPlanning");
        var execute = only(calls(start, "execute"));
        var task = assertInstanceOf(LambdaExpressionTree.class, only(execute.getArguments()));
        var finish = only(calls(task, "finishPlanning"));
        assertEquals(List.of("worldState(level)", "key", "entry", "epoch", "failure"),
                finish.getArguments().subList(0, 5).stream().map(Object::toString).toList());
        assertEquals(6, finish.getArguments().size());
        var verification = assertInstanceOf(BinaryTree.class, finish.getArguments().get(5));
        assertEquals(Tree.Kind.CONDITIONAL_AND, verification.getKind());
        assertEquals("failure == null", verification.getLeftOperand().toString());
        assertEquals("regionPlanningDataReady", name(assertInstanceOf(MethodInvocationTree.class, verification.getRightOperand())));
        // 仅找到调用仍会放过 false && finishPlanning；条件必须直接使用提交结果，才不会短路跳过。
        var resultGuards = new ArrayList<IfTree>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitIf(IfTree branch, Void ignored) {
                ExpressionTree condition = branch.getCondition();
                while (condition instanceof ParenthesizedTree wrapped) condition = wrapped.getExpression();
                if (condition == finish) resultGuards.add(branch);
                return super.visitIf(branch, ignored);
            }
        }.scan(task, null);
        assertEquals(1, resultGuards.size(), "生产完成条件必须直接使用持久化结果，不允许隐藏短路");
        assertEquals(1, calls(start, "finishPlanning").size());
        assertEquals(1, calls(start, "whenComplete").size());
        assertEquals(0, calls(start, "transition").stream()
                .filter(call -> call.getArguments().stream().anyMatch(arg -> arg.toString().equals("State.READY"))).count());
    }

    @Test
    void realAdoptionAndWorldLoadReturnTheTestedPersistenceBoundary() throws Exception {
        MethodTree adoption = method("adoptCompletedInitialCoverage");
        var returned = new ArrayList<ReturnTree>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitReturn(ReturnTree tree, Void ignored) { returned.add(tree); return super.visitReturn(tree, ignored); }
        }.scan(adoption, null);
        assertFalse(returned.isEmpty());
        var result = assertInstanceOf(MethodInvocationTree.class, returned.getLast().getExpression());
        assertEquals("adoptCoverageReady", name(result), "生产入口不能忽略持久化失败后仍返回 true");
        assertEquals(List.of("worldState(level)", "key(level, regionX, regionZ)"), result.getArguments().stream().map(Object::toString).toList());
        var open = only(calls(method("loadReady"), "open"));
        assertEquals("ReadinessCoverageStore.open", open.getMethodSelect().toString());
        assertEquals(List.of("readyPath(level)", "readinessFingerprint(level)"), open.getArguments().stream().map(Object::toString).toList());
    }

    // 用编译器语法树查调用位置，不靠逐行正则，同行代码/全限定写法也不会悄悄漏掉。
    private static MethodTree method(String name) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var files = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Path path = Path.of("src/main/java/net/shiroha233/roadweaver/planning/RoadPlanningReadinessIndex.java");
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(path));
            var matches = new ArrayList<MethodTree>();
            for (var unit : task.parse()) new TreeScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree tree, Void ignored) {
                    if (tree.getName().contentEquals(name)) matches.add(tree);
                    return super.visitMethod(tree, ignored);
                }
            }.scan(unit, null);
            return only(matches);
        }
    }
    private static List<MethodInvocationTree> calls(Tree tree, String methodName) {
        var result = new ArrayList<MethodInvocationTree>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                if (name(call).equals(methodName)) result.add(call);
                return super.visitMethodInvocation(call, ignored);
            }
        }.scan(tree, null);
        return result;
    }
    private static String name(MethodInvocationTree call) {
        String select = call.getMethodSelect().toString();
        return select.substring(select.lastIndexOf('.') + 1);
    }
    private static <T> T only(List<? extends T> items) { assertEquals(1, items.size()); return items.getFirst(); }
}
