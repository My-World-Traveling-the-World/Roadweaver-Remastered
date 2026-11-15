/* 文件职责：守卫 Java 源文件的行尾形态，防 CRLF / 孤立 CR 再次进入仓库。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行尾守卫（2026-11-15 新增）。
 *
 * <h2>为什么需要它</h2>
 * <p>2026-11-15 的独立复核发现三个 Java 文件在 git 索引里被标成了 {@code -text}
 * （git 判定为非文本），于是 {@code text=auto} 的规范化被<b>跳过</b>，
 * CRLF 连同「孤立 CR」被原样提交：</p>
 * <table border="1">
 *   <caption>实测数据</caption>
 *   <tr><th>文件</th><th>孤立 CR</th><th>后果</th></tr>
 *   <tr><td>{@code Road.java}</td><td>1</td><td>提交 {@code 5024a1d} 只改 31 行，
 *       却 diff 出 <b>1655 行</b>，blame 被整文件冲掉</td></tr>
 *   <tr><td>{@code EndpointOwnerResolver.java}</td><td>12</td><td>评审困难</td></tr>
 *   <tr><td>{@code RouteRefinementOps.java}</td><td><b>323</b></td>
 *       <td><b>行数被少算一半</b>：编辑器 701 行、按 {@code \n} 切行只有 379 行
 *       ⇒ 千行守卫（{@link SourceFileSizeGuardTest}）可能被绕过</td></tr>
 * </table>
 *
 * <p>第三条才是真正危险的那条：<b>孤立 CR 不是换行</b>，任何按 {@code '\n'} 切行的
 * 工具都会把它当成同一行的一部分。一个 1200 行的文件只要含有足够多孤立 CR，
 * 就能在守卫眼里"看起来"只有 900 行。</p>
 *
 * <h2>这个测试守什么</h2>
 * <ol>
 *   <li><b>不许有孤立 CR</b>（{@code \r} 后面不是 {@code \n}）—— 直接破坏行数统计；</li>
 *   <li><b>不许有 CRLF</b> —— 仓库统一 LF，{@code .gitattributes} 已声明
 *       {@code *.java text eol=lf}；</li>
 *   <li><b>{@code .gitattributes} 里那条声明必须还在</b> —— 否则检出时又会写回 CRLF。</li>
 * </ol>
 *
 * <h2>这个测试<b>不</b>守什么</h2>
 * <p>它只看工作区文件，<b>看不到 git 索引</b>。索引里残留 {@code -text} 标记这件事
 * 只能靠 {@code git ls-files --eol} 人工复查（2026-11-15 已全部清零）。
 * 好在加了 {@code eol=lf} 之后，工作区与索引会自然一致，不会再分叉。</p>
 */
class SourceLineEndingGuardTest {

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    /** 各模块的源码根（相对仓库根）。 */
    private static final String[] MODULE_SRC = {
            "common/src/main/java", "common/src/test/java",
            "fabric/src/main/java", "fabric/src/test/java",
            "api/src/main/java", "api/src/test/java",
            "api-fabric/src/main/java", "api-fabric/src/test/java",
    };

    @Test
    void Java源码不得含孤立CR() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : javaFiles()) {
            byte[] d = Files.readAllBytes(f);
            int isolated = 0;
            for (int i = 0; i < d.length; i++) {
                if (d[i] == '\r' && (i + 1 >= d.length || d[i + 1] != '\n')) isolated++;
            }
            if (isolated > 0) offenders.add(rel(f) + " (" + isolated + " 个孤立 CR)");
        }
        assertTrue(offenders.isEmpty(),
                "以下文件含孤立 CR（\\r 后面不是 \\n）。孤立 CR 不是换行，"
                        + "会让「按 \\n 切行」的统计少算行数，千行守卫可能被绕过：\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void Java源码不得使用CRLF() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : javaFiles()) {
            byte[] d = Files.readAllBytes(f);
            int crlf = 0;
            for (int i = 0; i + 1 < d.length; i++) {
                if (d[i] == '\r' && d[i + 1] == '\n') crlf++;
            }
            if (crlf > 0) offenders.add(rel(f) + " (" + crlf + " 个 CRLF)");
        }
        assertTrue(offenders.isEmpty(),
                "以下 Java 文件使用 CRLF。仓库已统一为 LF（.gitattributes 声明 "
                        + "`*.java text eol=lf`），出现 CRLF 说明有工具绕过了规范化：\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void gitattributes必须声明Java为LF() throws IOException {
        Path attrs = REPO_ROOT.resolve(".gitattributes");
        assertTrue(Files.isRegularFile(attrs),
                "仓库根缺少 .gitattributes —— 没有它，git 无法保证 Java 源码的规范化");
        String text = Files.readString(attrs, StandardCharsets.UTF_8);
        assertTrue(text.contains("*.java") && text.contains("eol=lf"),
                "`.gitattributes` 必须声明 `*.java text eol=lf`。"
                        + "这条规则是 2026-11-15 加上的：删掉它，检出时又会写回 CRLF，"
                        + "而 git 侧不会有任何报错。实际内容：\n" + text);
    }

    @Test
    void 已知的三个历史文件现在是纯LF() throws IOException {
        // 这三个是 2026-11-15 复核时抓到的「索引被标 -text」的文件。
        // 单独钉一遍，因为它们各自代表一种成因：
        //   Road.java             —— 我们自己的脚本误写（本轮修复）
        //   EndpointOwnerResolver —— 2026-11-12 抽类时带入（历史遗留）
        //   RouteRefinementOps    —— 2026-11-12 抽类时带入，323 个孤立 CR
        String[] known = {
                "common/src/main/java/net/shiroha233/roadweaver/pathlogic/core/Road.java",
                "common/src/main/java/net/shiroha233/roadweaver/pathlogic/structures/EndpointOwnerResolver.java",
                "common/src/main/java/net/shiroha233/roadweaver/planning/RouteRefinementOps.java",
        };
        for (String rel : known) {
            Path f = REPO_ROOT.resolve(rel);
            if (!Files.isRegularFile(f)) continue;   // 文件被改名/删除时不误报
            byte[] d = Files.readAllBytes(f);
            assertFalse(contains(d, (byte) '\r'),
                    rel + " 含 CR（孤立 CR 或 CRLF）。这三个文件在 2026-11-15 之前都因此"
                            + "破坏了 diff 与行数统计，别再退回去");
        }
    }

    // ───────────────────────────── 辅助 ─────────────────────────────

    private static boolean contains(byte[] d, byte b) {
        for (byte x : d) if (x == b) return true;
        return false;
    }

    private static String rel(Path f) {
        return REPO_ROOT.relativize(f).toString().replace('\\', '/');
    }

    private static List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String mod : MODULE_SRC) {
            Path root = REPO_ROOT.resolve(mod);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .forEach(out::add);
            }
        }
        assertFalse(out.isEmpty(),
                "一个 Java 文件都没扫到 —— 说明仓库根定位错了"
                        + "（REPO_ROOT=" + REPO_ROOT + "），此时本守卫等于空转");
        return out;
    }
}