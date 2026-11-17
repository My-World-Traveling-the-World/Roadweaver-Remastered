/* 文件职责：编译器提取成员Javadoc正文，防止文件头或参数中文掩盖缺少中文契约。 */
package net.shiroha233.roadweaver.architecture;

import com.sun.source.util.DocTrees;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.doctree.TextTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChineseCommentGuardTest {
    private record Doc(String file, long line, String body) {
        boolean chinese() { return body.codePoints().anyMatch(c -> c >= 0x3400 && c <= 0x9fff); }
    }

    @Test
    void everyAttachedProductionJavadocHasChineseBody() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>();
        // 空扫描必须失败，不能把目录消失当作已覆盖。
        for (String module : List.of("api", "api-fabric", "common", "fabric", "neoforge")) {
            Path directory = root.resolve(module);
            assertTrue(Files.isDirectory(directory), "缺少模块：" + directory);
            Path main = directory.resolve("src/main");
            if (!Files.exists(main)) continue;
            try (var walk = Files.walk(main)) {
                paths.addAll(walk.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        assertFalse(paths.isEmpty(), "生产源码为空");
        var docs = parse(paths, null);
        assertFalse(docs.isEmpty(), "没有提取到生产文档");
        assertEquals(List.of(), docs.stream().filter(d -> !d.chinese()).toList(),
                "Javadoc正文须有中文契约；参数标签、文件头和字符串不能替代正文");
        System.out.println("CHINESE_COMMENT_AUDIT sources=" + paths.size() + " attachedDocs=" + docs.size()
                + " chinese=" + docs.stream().filter(Doc::chinese).count());
    }

    @Test
    void commentsStringsAndTagsCannotSupplyTheBody() throws IOException {
        var docs = snippet("""
                /* 文件职责：中文文件头不是成员文档。 */
                /** English type. */ class Example {
                    /** English method.
                     * @param value 中文参数不能代替正文
                     */ void run(String value) { String s = "中文字符串"; }
                    /** 中文字段契约。 */ int count;
                    /** English field. */ String name = "中文值";
                }
                """);
        assertEquals(4, docs.size());
        assertEquals(List.of(false, false, true, false), docs.stream().map(Doc::chinese).toList());
    }

    @Test
    void handlesRecordsSameLineMembersAndCarriageReturns() throws IOException {
        var docs = snippet("/** 中文记录契约。 */ record Example(int n) {\r"
                + "/** English method. */ int run() { return n; } "
                + "/** 中文工厂契约。 */ static Example create() { return new Example(2); }}");
        assertEquals(3, docs.size());
        assertEquals(List.of(true, false, true), docs.stream().map(Doc::chinese).toList());
    }

    private static List<Doc> snippet(String text) throws IOException {
        return parse(List.of(), new SimpleJavaFileObject(URI.create("string:///Example.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return text; }
        });
    }

    private static List<Doc> parse(List<Path> paths, JavaFileObject snippet) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "守卫需要JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<Doc> docs = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var input = snippet == null ? files.getJavaFileObjectsFromPaths(paths) : List.of(snippet);
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, input);
            var trees = DocTrees.instance(task);
            for (var unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    private void capture() {
                        var doc = trees.getDocCommentTree(getCurrentPath());
                        if (doc == null) return;
                        long start = trees.getSourcePositions().getStartPosition(unit, getCurrentPath().getLeaf());
                        // 只检查正文；中文参数标签不能使英文契约误绿。
                        // Javac的toString可能将中文转义；读取TextTree原文才能验证实际正文。
                        StringBuilder body = new StringBuilder();
                        new DocTreeScanner<Void, Void>() {
                            @Override public Void visitText(TextTree node, Void ignored) {
                                body.append(node.getBody()).append(' ');
                                return null;
                            }
                        }.scan(doc.getFullBody(), null);
                        docs.add(new Doc(unit.getSourceFile().getName(), unit.getLineMap().getLineNumber(start), body.toString()));
                    }
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        capture(); return super.visitClass(node, unused);
                    }
                    @Override public Void visitMethod(MethodTree node, Void unused) {
                        capture(); return super.visitMethod(node, unused);
                    }
                    @Override public Void visitVariable(VariableTree node, Void unused) {
                        capture(); return super.visitVariable(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertEquals(List.of(), diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList(), "解析失败不能静默漏扫");
        return List.copyOf(docs);
    }
}
