/* 文件职责：把「生产代码必须带中文职责头」这条约定变成可 ratchet 的守卫。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件职责头守卫（2026-11-15 新增）。
 *
 * <h2>为什么需要它</h2>
 * <p>项目约定每个生产 java 文件的**第一行**是
 * {@code /* 文件职责：这一行用中文说清这个文件干什么 *}{@code /}。
 * 这条约定在实践中会被不断破坏：新文件忘了写、拆分类沿用旧文件时没补、
 * 临时文件被留下 —— 而它<b>不影响编译</b>，所以没有任何机制能发现。</p>
 *
 * <p>实测：679 个生产文件里 617 个有头（90%），62 个没有，其中
 * {@code api} 25 个 + {@code mixin} 20 个占了 73%。
 * 这 62 个正是「契约层」与「字节码注入层」——
 * 恰恰是最需要一句话说明「这个东西为什么存在」的地方。</p>
 *
 * <h2>为什么用 ratchet 而不是一刀切断言全覆盖</h2>
 * <p>一刀切会立刻红 62 处，没人能在一次提交里补齐并保证每个头都写对。
 * 所以采用与 {@code deps-baseline.txt} / {@code dead-code-baseline.txt}
 * 相同的策略：<b>把当前缺口登记成基线，只许缩小不许扩大</b>。
 * 补一个就从基线删一行；新文件必须有头，否则基线里找不到它、直接红。</p>
 */
class DutyHeaderGuardTest {

    /** 基线：允许暂时没有职责头的文件（工作区相对路径，正斜杠）。**只许删，不许加**。 */
    private static final Path BASELINE = Paths.get(
            "common", "src", "test", "resources", "architecture", "duty-header-baseline.txt");

    private static Path repoRoot;

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("common").resolve("src"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /** 生产源码根（四个模块都要扫）。 */
    private static List<Path> sourceRoots(Path root) {
        List<Path> out = new ArrayList<>();
        for (String m : new String[]{"common", "fabric", "api", "api-fabric"}) {
            Path p = root.resolve(m).resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    private static Set<String> filesWithoutHeader(Path root) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path src : sourceRoots(root)) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String first;
                    try {
                        first = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                                .findFirst().orElse("");
                    } catch (Exception readFailure) {
                        // 读不出来算「没有头」—— 宁可多报一个，也不要静默放过
                        first = "";
                    }
                    if (!first.contains("文件职责")) {
                        out.add(root.relativize(p).toString().replace('\\', '/'));
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> readBaseline(Path root) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Path f = root.resolve(BASELINE);
        if (!Files.isRegularFile(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            out.add(t);
        }
        return out;
    }

    @Test
    void 没有职责头的文件只能减少不能增加() throws IOException {
        repoRoot = findRepoRoot();
        if (repoRoot == null) {
            return; // 找不到仓库根（增量单测）—— 跳过而不是误报
        }
        Set<String> actual = filesWithoutHeader(repoRoot);
        Set<String> baseline = readBaseline(repoRoot);

        // ① 新出现的无头文件 = 违反约定（基线里没登记）
        Set<String> newly = new TreeSet<>(actual);
        newly.removeAll(baseline);
        assertTrue(newly.isEmpty(),
                "以下文件没有「/* 文件职责：… */」头，且不在基线里。\n"
                        + "新增或拆分的文件必须补一行中文职责说明；\n"
                        + "若确实需要暂时豁免，请显式登记进 " + BASELINE + " 并在提交信息里说明原因。\n"
                        + "    " + String.join("\n    ", newly));

        // ② 基线里已经补好的，必须同步删掉（否则 ratchet 失效）
        Set<String> stale = new TreeSet<>(baseline);
        stale.removeAll(actual);
        assertTrue(stale.isEmpty(),
                "以下文件在基线里，但实际已经有职责头了 —— 请从 " + BASELINE + " 删掉它们，\n"
                        + "否则基线会长期虚高，后人无法判断真实缺口。\n"
                        + "    " + String.join("\n    ", stale));
    }

    @Test
    void 基线文件必须存在且格式正确() throws IOException {
        repoRoot = findRepoRoot();
        if (repoRoot == null) {
            return;
        }
        Path f = repoRoot.resolve(BASELINE);
        assertTrue(Files.isRegularFile(f),
                "基线文件缺失：" + BASELINE + "。它是 ratchet 的锚点，必须随仓库提交。");
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertTrue(text.contains("#"), "基线文件应有注释头说明它是什么、怎么维护");
    }
}