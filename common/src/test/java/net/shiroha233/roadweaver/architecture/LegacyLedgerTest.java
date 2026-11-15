/* 文件职责：校验重制替换台账与代码一致 —— 台账写的不许和代码漂移。 */
package net.shiroha233.roadweaver.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重制替换台账的一致性守卫。
 *
 * <h2>它防的是什么</h2>
 * <p>重制版是「先做新实现、后删旧实现」。如果台账和代码各说各话，将来那个删除动作
 * 就会变成靠记忆的考古 —— 而记忆一定会错。所以把台账做成<b>机器受检</b>的：</p>
 *
 * <ol>
 *   <li>七批必须齐全、批号不许重复；</li>
 *   <li>状态只许是 {@code TODO / IN_PROGRESS / DONE}；</li>
 *   <li>标了 {@code DONE} 的批：新实现包必须真的存在 —— 不许「台账上说做完了」；</li>
 *   <li>标了 {@code DONE} 的批：旧实现包里每个 {@code .java} 都必须带 {@code 待删（批N）}
 *       标记，或者整个包已经不在了 —— 不许「做完却忘了标」。</li>
 * </ol>
 *
 * <h2>为什么不校验行数</h2>
 * <p>台账里的行数是<b>快照</b>（用来估工作量），重制期间旧代码会被少量改动，
 * 逐行比对会天天红。真正的删除判据是「零外部引用 + 测试全绿」，不是行数。</p>
 *
 * <p>找不到台账时整类<b>跳过</b>（{@link Assumptions}）—— 测试可能在只拷了
 * 子目录的环境里跑，那种环境下不该报假失败。</p>
 */
final class LegacyLedgerTest {

    /** 台账里的七批（批号 → 域名），写死在这里是为了「台账少写一批」也能被发现。 */
    private static final int BATCH_COUNT = 7;

    private static Path repoRoot;

    @BeforeAll
    static void locateRepo() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("REFACTOR.md"))) {
                repoRoot = dir;
                return;
            }
            dir = dir.getParent();
        }
    }

    private static final class Row {
        final int batch;
        final String domain;
        final String legacyPkg;
        final String newPkg;
        final String status;

        Row(int batch, String domain, String legacyPkg, String newPkg, String status) {
            this.batch = batch;
            this.domain = domain;
            this.legacyPkg = legacyPkg;
            this.newPkg = newPkg;
            this.status = status;
        }
    }

    private static List<Row> rows() throws IOException {
        Assumptions.assumeTrue(repoRoot != null, "找不到 REFACTOR.md，跳过台账守卫");
        List<String> lines = Files.readAllLines(repoRoot.resolve("REFACTOR.md"), StandardCharsets.UTF_8);
        List<Row> out = new ArrayList<>();
        boolean inside = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.contains("LEDGER:BEGIN")) { inside = true; continue; }
            if (line.contains("LEDGER:END")) { inside = false; continue; }
            if (!inside || !line.startsWith("LEDGER|")) { continue; }
            String[] parts = line.split("\\|", -1);
            assertEquals(7, parts.length, "台账行字段数必须是 7（批号|域|旧包|新包|状态|行数）: " + line);
            out.add(new Row(Integer.parseInt(parts[1]), parts[2], parts[3], parts[4], parts[5]));
        }
        return out;
    }

    @Test
    void ledgerListsEveryBatchExactlyOnce() throws IOException {
        List<Row> rows = rows();
        assertEquals(BATCH_COUNT, rows.size(), "台账必须正好 " + BATCH_COUNT + " 批");
        rows.sort(Comparator.comparingInt(r -> r.batch));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).batch, "批号必须从 1 连续到 " + BATCH_COUNT);
        }
    }

    @Test
    void statusVocabularyIsClosed() throws IOException {
        Set<String> allowed = Set.of("TODO", "IN_PROGRESS", "DONE");
        for (Row r : rows()) {
            assertTrue(allowed.contains(r.status),
                    "批 " + r.batch + " 的状态非法：" + r.status + "（只许 TODO / IN_PROGRESS / DONE）");
        }
    }

    @Test
    void inProgressBatchMustPointAtRealPackage() throws IOException {
        // ★ 2026-11-13 补：IN_PROGRESS 也必须指向真实存在的包。
        //
        // 补这条之前，台账可以写「重制中」而 newPkg 指向一个根本不存在的顶层包 ——
        // 于是「DONE ⇒ 包必须存在」和「包有文件 ⇒ 不许还是 TODO」两条断言都空转，
        // 做了一半的活（例如 pathlogic/profile 已有 9 个类）在台账上完全看不出来。
        for (Row r : rows()) {
            if (!"IN_PROGRESS".equals(r.status)) { continue; }
            Path pkgDir = mainJava().resolve(r.newPkg.replace('/', java.io.File.separatorChar));
            assertTrue(Files.isDirectory(pkgDir),
                    "批 " + r.batch + "（" + r.domain + "）标为 IN_PROGRESS，"
                            + "但 newPkg 指向的包不存在：" + r.newPkg
                            + " —— 「重制中」必须能指到真实代码，否则台账无法用于删除");
            try (Stream<Path> s = Files.walk(pkgDir)) {
                long n = s.filter(p -> p.toString().endsWith(".java")).count();
                assertTrue(n > 0, "批 " + r.batch + " 标为 IN_PROGRESS，但新实现包是空的：" + r.newPkg);
            }
        }
    }

    @Test
    void doneBatchMustHaveRealNewPackage() throws IOException {
        for (Row r : rows()) {
            if (!"DONE".equals(r.status)) { continue; }
            Path pkgDir = mainJava().resolve(r.newPkg.replace('/', java.io.File.separatorChar));
            assertTrue(Files.isDirectory(pkgDir),
                    "台账说批 " + r.batch + "（" + r.domain + "）已完成，但新实现包不存在：" + r.newPkg);
            try (Stream<Path> s = Files.walk(pkgDir)) {
                long n = s.filter(p -> p.toString().endsWith(".java")).count();
                assertTrue(n > 0, "批 " + r.batch + " 的新实现包是空的：" + r.newPkg);
            }
        }
    }

    @Test
    void doneBatchMustNotHaveUnmarkedLegacyFiles() throws IOException {
        for (Row r : rows()) {
            if (!"DONE".equals(r.status)) { continue; }
            Path legacyDir = mainJava().resolve(r.legacyPkg.replace('/', java.io.File.separatorChar));
            if (!Files.isDirectory(legacyDir)) { continue; }   // 整包已删 = 最理想的收尾
            String marker = "待删（批" + r.batch + "）";
            List<String> offenders = new ArrayList<>();
            try (Stream<Path> s = Files.walk(legacyDir)) {
                for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                    if (!Files.readString(p, StandardCharsets.UTF_8).contains(marker)) {
                        offenders.add(p.getFileName().toString());
                    }
                }
            }
            assertTrue(offenders.isEmpty(),
                    "批 " + r.batch + " 标为已完成，但旧实现包 " + r.legacyPkg
                            + " 里这些文件没打「" + marker + "」标记（将来没法照台账删）：" + offenders);
        }
    }

    /**
     * 计划中但**从未落地**的新实现包（2026-11-15 核对，逐个实测确认不存在）。
     *
     * <h2>为什么要有这张表</h2>
     * <p>2026-11-13 修过一次台账：原先 7 批的 {@code newPkg} 全指向不存在的顶层包，
     * 导致「DONE ⇒ 包必须存在」「包有文件 ⇒ 不许还是 TODO」两条断言**全部空转**。
     * 那次只改正了批 1/2/3（改成真实存在的 {@code pathlogic/profile} 与
     * {@code pathlogic/construction}），**批 4/5/6/7 的 {@code roadnet} / {@code geometry} /
     * {@code avoidance} / {@code validate} 一直留着，而这四个包至今不存在**。</p>
     *
     * <p>之所以没被发现：现有两条断言只查 {@code IN_PROGRESS} 与 {@code DONE}，
     * 而批 4/5/6/7 都是 {@code TODO} ⇒ 它们的 {@code newPkg} 从未被检查。</p>
     *
     * <h2>为什么不直接把 newPkg 改成真实路径</h2>
     * <p>因为<b>真实做法根本不是「搬到新包」</b>：{@code ARCHITECTURE.md} 顶部已定案
     * 「保留旧仓的顶层包结构，<b>就地重构</b>」，批 4/5/6/7 的实现就长在旧包里
     * （{@code pathlogic/junction} 4 类 / {@code postprocess} 13 类 /
     * {@code pathlogic/structures} 23 类 / {@code pathlogic/validation} 4 类）。</p>
     * <p>把 {@code newPkg} 写成旧包也不行：那会让「包有文件 ⇒ 不许还是 TODO」
     * 立刻误报 —— 那些文件本来就在，不是「新实现已经长出来了」。</p>
     * <p>⇒ 正确做法是<b>把「虚构」这件事显式登记</b>，并让守卫双向钉住：
     * ①台账里指向不存在包的行必须登记在这张表里；
     * ②登记过的包必须确实不存在（有人真建了包却忘更新台账时立刻红）。</p>
     */
    private static final java.util.Map<String, String> PLANNED_BUT_NOT_BUILT = java.util.Map.of(
            "roadnet", "批 4：真实做法是 pathlogic/junction 就地重构",
            "geometry", "批 5：真实做法是 postprocess 就地重构",
            "avoidance", "批 6：真实做法是 pathlogic/structures 就地重构",
            "validate", "批 7：真实做法是 pathlogic/validation 就地重构");

    @Test
    void 计划中的新包必须显式登记且确实不存在() throws IOException {
        // ① 台账里所有指向不存在包的行，必须在这张表里登记（否则就是「没人知道它是虚构的」）
        for (Row r : rows()) {
            Path pkgDir = mainJava().resolve(r.newPkg.replace('/', java.io.File.separatorChar));
            if (Files.isDirectory(pkgDir)) { continue; }
            assertTrue(PLANNED_BUT_NOT_BUILT.containsKey(r.newPkg),
                    "批 " + r.batch + "（" + r.domain + "）的 newPkg 指向一个**不存在的包**："
                            + r.newPkg + "\n"
                            + "要么它是计划中的包（请登记进 PLANNED_BUT_NOT_BUILT 并写明原因），"
                            + "要么台账写错了。\n"
                            + "★ 这正是 2026-11-13 那次台账空转的成因："
                            + "不存在的新包让两条断言全部空转，做了一半的活在台账上看不出来");
        }
        // ② 登记过的包必须确实不存在（反向：有人建了包却忘了改台账 ⇒ 立刻红）
        for (java.util.Map.Entry<String, String> e : PLANNED_BUT_NOT_BUILT.entrySet()) {
            Path pkgDir = mainJava().resolve(e.getKey());
            assertFalse(Files.isDirectory(pkgDir),
                    "PLANNED_BUT_NOT_BUILT 里登记的包 " + e.getKey()
                            + " 现在**已经存在**了（" + e.getValue() + "）。\n"
                            + "请把它从这张表删掉，并把台账对该批的 newPkg 改成真实路径");
        }
    }

    @Test
    void nonEmptyStatusNeverContradictsReality() throws IOException {
        // IN_PROGRESS 允许新包还不存在（正在建），但 DONE 必须存在 —— 已在上面两条覆盖。
        // 这条补一个反向：新实现包若已经存在，状态不该还停留在 TODO。
        for (Row r : rows()) {
            Path pkgDir = mainJava().resolve(r.newPkg.replace('/', java.io.File.separatorChar));
            if (!Files.isDirectory(pkgDir)) { continue; }
            try (Stream<Path> s = Files.walk(pkgDir)) {
                long n = s.filter(p -> p.toString().endsWith(".java")).count();
                if (n == 0) { continue; }
                assertFalse("TODO".equals(r.status),
                        "批 " + r.batch + " 的新实现包 " + r.newPkg + " 已经有 " + n
                                + " 个文件了，台账状态却还是 TODO —— 请改成 IN_PROGRESS");
            }
        }
    }

    private static Path mainJava() {
        return repoRoot.resolve("common").resolve("src").resolve("main").resolve("java")
                .resolve("net").resolve("shiroha233").resolve("roadweaver");
    }
}
