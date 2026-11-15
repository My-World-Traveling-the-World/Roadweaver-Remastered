package net.shiroha233.roadweaver.architecture;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JavaDependencyScanTest {
    private static final String INTERNAL = "net.shiroha233.roadweaver.";

    record References(List<String> imports, List<String> qualified) {}

    @Test
    void 普通静态通配和同行导入都有相同解析口径() throws IOException {
        var refs = parseSnippet("""
                package example; import net.shiroha233.roadweaver.topology.RoadNode;
                import static net.shiroha233.roadweaver.metric.MetricConfig.defaults;
                import static net.shiroha233.roadweaver.band.ElasticBandSolver.*;
                import net.shiroha233.roadweaver.pipeline.*;
                class Example {}
                """);
        assertEquals(List.of(INTERNAL + "topology.RoadNode", INTERNAL + "metric.MetricConfig.defaults",
                INTERNAL + "band.ElasticBandSolver.*", INTERNAL + "pipeline.*"), refs.imports());
        assertEquals(List.of(), refs.qualified(), "import 不能被全限定名扫描重复计数");
    }

    @Test
    void 全限定依赖只认实际语法并允许多行与注释() throws IOException {
        var refs = parseSnippet("""
                package example;
                class Example {
                    net.shiroha233.roadweaver
                        .pipeline./* 类型限定名可带注释 */RoadPipeline field;
                    String notCode = "net.shiroha233.roadweaver.persistence.RoadConstructionLedger";
                    // net.shiroha233.roadweaver.worldgen.RoadFeature ignored;
                }
                """);
        assertEquals(List.of(), refs.imports());
        assertEquals(List.of(INTERNAL + "pipeline.RoadPipeline"), refs.qualified());
    }

    static Map<Path, References> parseFiles(List<Path> files) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "架构守卫需要 JDK");
        Map<Path, References> result = new HashMap<>();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var input = manager.getJavaFileObjectsFromPaths(files);
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, input);
            for (CompilationUnitTree unit : task.parse()) {
                result.put(Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize(), scan(unit));
            }
            requireNoParseErrors(diagnostics);
        }
        return Map.copyOf(result);
    }

    static References parseSnippet(String snippet) throws IOException {
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "架构守卫需要 JDK");
        var source = new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return snippet; }
        };
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(source));
            var result = scan(task.parse().iterator().next());
            requireNoParseErrors(diagnostics);
            return result;
        }
    }

    private static void requireNoParseErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        if (!errors.isEmpty()) throw new AssertionError("架构守卫无法解析源码：" + errors);
    }

    private static References scan(CompilationUnitTree unit) {
        // Javac 已按语法拆分语句，因此同一行、换行、注释或 Unicode 转义不再影响识别。
        var imports = unit.getImports().stream()
                .map(tree -> tree.getQualifiedIdentifier().toString())
                .toList();
        List<String> qualified = new ArrayList<>();
        var scanner = new TreeScanner<Void, Void>() {
            @Override public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                String name = nameOf(tree);
                if (name != null && name.startsWith(INTERNAL)) {
                    qualified.add(name);
                    // A.B.C 是一处引用，不能把 A.B 及其每段前缀再计一次。
                    return null;
                }
                return super.visitMemberSelect(tree, unused);
            }
        };
        // import 已单独统计；字面字符串、注释与文档链接不构成 Java 层依赖。
        scanner.scan(unit.getPackageAnnotations(), null);
        scanner.scan(unit.getTypeDecls(), null);
        return new References(imports, List.copyOf(qualified));
    }

    private static String nameOf(Tree tree) {
        if (tree instanceof IdentifierTree id) return id.getName().toString();
        if (!(tree instanceof MemberSelectTree member)) return null;
        String prefix = nameOf(member.getExpression());
        return prefix == null ? null : prefix + "." + member.getIdentifier();
    }
}
