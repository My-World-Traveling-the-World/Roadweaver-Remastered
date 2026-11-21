/* 文件职责：按Java语法检查生产日志中文主说明，防止注释或异常参数中的中文掩盖英文日志。 */
package net.shiroha233.roadweaver.architecture;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChineseLogGuardTest {
    private static final Set<String> LEVELS = Set.of("info", "warn", "error", "debug", "trace", "fatal");
    private record Call(String file, long line, String level, String description, int valueArguments) {
        boolean chinese() { return description.codePoints().anyMatch(c -> c >= 0x3400 && c <= 0x9fff); }
    }

    @Test
    void everyProductionLoggingCallHasAChineseDescription() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> sources = new ArrayList<>();
        // 显式遍历全部模块；目录消失不能被当成零日志自动通过。
        for (String module : List.of("api", "api-fabric", "common", "fabric", "neoforge")) {
            Path directory = root.resolve(module);
            assertTrue(Files.isDirectory(directory), "缺少生产模块：" + directory);
            Path main = directory.resolve("src/main");
            if (!Files.exists(main)) continue; // neoforge仅有构建配置，没有生产源码。
            try (var walk = Files.walk(main)) {
                sources.addAll(walk.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        assertFalse(sources.isEmpty(), "没有扫描生产源码");
        var calls = parse(sources, null);
        assertFalse(calls.isEmpty(), "解析器没有识别任何日志调用");
        var failures = calls.stream().filter(c -> !c.chinese()).toList();
        assertEquals(List.of(), failures, "日志格式参数必须有中文主说明，不能靠异常参数、注释或类名蒙混");
        System.out.println("CHINESE_LOG_AUDIT sources=" + sources.size() + " loggerFiles="
                + calls.stream().map(Call::file).distinct().count() + " calls=" + calls.size()
                + " chinese=" + calls.stream().filter(Call::chinese).count());
    }

    @Test
    void parserAcceptsMultilineConcatenationAndQualifiedReceivers() throws IOException {
        var calls = snippet("""
            class Example { void run() {
                app.RoadWeaver.LOGGER.info("【预热完成】 " +
                    "[READY] count={}", 3); logger /*同行*/ .warn("读取失败：{}", problem);
                LOG.debug("重复查询：{}", key);
            }}
            """);
        assertEquals(3, calls.size());
        assertTrue(calls.stream().allMatch(Call::chinese));
        assertEquals(List.of("info", "warn", "debug"), calls.stream().map(Call::level).toList());
    }

    @Test
    void commentsArgumentsAndDynamicMessagesCannotSupplyTheDescription() throws IOException {
        var calls = snippet("""
            class Example { void run() {
                // LOGGER.info("中文注释不是实际日志");
                String example = "LOGGER.warn(假的中文)";
                LOGGER.info("failed {}", "中文参数不能充数");
                logger.error(/* 中文注释不能充数 */ "failed", failure);
                LOG.warn(dynamic);
                LOGGER.trace("failed" + arbitraryChineseVariable);
            }}
            """);
        assertEquals(4, calls.size());
        assertTrue(calls.stream().noneMatch(Call::chinese));
    }

    @Test
    void compilerDecodesEscapesAndIgnoresNonLoggerMethods() throws IOException {
        var calls = snippet("class Example { void run() { LOGGER.info(\"\\u8bfb\\u53d6失败\");"
                + " collection.info(\"ordinary\"); logger.toString(); }}");
        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().chinese());
    }

    @Test
    void declaredLoggerAliasesAndDirectFactoriesCannotEscapeTheScan() throws IOException {
        var calls = snippet("""
            class Example { void run(org.slf4j.Logger sink) {
                sink.info("failed", "中文参数");
                org.slf4j.Logger output = null; output.warn("读取失败");
                org.slf4j.LoggerFactory.getLogger("road").error("failed");
            }}
            """);
        assertEquals(3, calls.size());
        assertEquals(List.of(false, true, false), calls.stream().map(Call::chinese).toList());
    }

    @Test
    void protectedGapDiagnosticKeepsCoordinatesDeltaAndOwnersAligned() throws IOException {
        assertDiagnosticArguments("pathlogic/validation/RoadDataValidator.java",
                "RoadWeaver protected-gap height step", 11);
    }

    @Test
    void continuityDiagnosticKeepsRangeWidthAndOwnersAligned() throws IOException {
        assertDiagnosticArguments("pathlogic/pathcalc/RoadPathCalculator.java",
                "RoadWeaver continuity preflight:", 12);
    }

    private static void assertDiagnosticArguments(String relative, String marker, int expected) throws IOException {
        Path source = Path.of("src/main/java/net/shiroha233/roadweaver").resolve(relative);
        var calls = parse(List.of(source), null).stream().filter(c -> c.description().contains(marker)).toList();
        assertEquals(1, calls.size(), "必须找到真实生产诊断，不能因入口删除而空转");
        var call = calls.getFirst();
        assertEquals(expected, call.valueArguments(), "诊断必须保留完整坐标、范围及身份参数");
        // 这两条日志没有尾随Throwable或Object[]，每个参数都必须占一个格式槽。
        assertEquals(expected, call.description().split("\\{\\}", -1).length - 1,
                "占位符数量不符会把后续字段串错；中文主说明检查无法发现这种错误");
    }

    private static List<Call> snippet(String text) throws IOException {
        return parse(List.of(), new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return text; }
        });
    }

    private static List<Call> parse(List<Path> paths, JavaFileObject snippet) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "日志守卫必须在JDK中运行");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<Call> calls = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var inputs = snippet == null ? files.getJavaFileObjectsFromPaths(paths) : List.of(snippet);
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, inputs);
            var positions = com.sun.source.util.Trees.instance(task).getSourcePositions();
            for (var unit : task.parse()) {
                var aliases = new java.util.HashSet<String>();
                // 按声明类型补上非LOGGER命名的参数/字段，避免仅凭变量名漏掉sink等别名。
                new TreeScanner<Void, Void>() {
                    @Override public Void visitVariable(VariableTree variable, Void unused) {
                        String type = variable.getType() == null ? "" : variable.getType().toString();
                        if (type.equals("Logger") || type.endsWith(".Logger")) aliases.add(variable.getName().toString());
                        return super.visitVariable(variable, unused);
                    }
                }.scan(unit, null);
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                        if (call.getMethodSelect() instanceof MemberSelectTree method
                                && LEVELS.contains(method.getIdentifier().toString())
                                && loggerReceiver(method.getExpression(), aliases)) {
                            long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, call));
                            String message = call.getArguments().isEmpty() ? "" : description(call.getArguments().getFirst());
                            calls.add(new Call(unit.getSourceFile().getName(), line, method.getIdentifier().toString(),
                                    message, Math.max(0, call.getArguments().size() - 1)));
                        }
                        return super.visitMethodInvocation(call, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList(), "源码解析错误不能静默漏扫");
        return List.copyOf(calls);
    }

    private static boolean loggerReceiver(ExpressionTree receiver, Set<String> aliases) {
        if (receiver instanceof MethodInvocationTree factory && factory.getMethodSelect() instanceof MemberSelectTree method)
            return method.getIdentifier().contentEquals("getLogger")
                    && method.getExpression().toString().endsWith("LoggerFactory");
        String raw = receiver.toString();
        String name = raw.substring(raw.lastIndexOf('.') + 1);
        String tail = name.toLowerCase(Locale.ROOT);
        return aliases.contains(name) || tail.equals("log") || tail.contains("logger") || tail.startsWith("log_");
    }

    private static String description(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String s) return s;
        if (expression instanceof ParenthesizedTree p) return description(p.getExpression());
        // 只看格式串本身；不遍历参数和方法体，否则中文异常消息也会让守卫误绿。
        if (expression instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS)
            return description(b.getLeftOperand()) + description(b.getRightOperand());
        return "";
    }
}
