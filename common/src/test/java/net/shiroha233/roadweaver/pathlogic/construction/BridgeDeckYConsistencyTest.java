/* 文件职责：守卫「桥甲板 Y」三条建造路径的口径一致性，防 +1/-1 半对改坏。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桥甲板 Y 口径守卫（2026-11-15 新增）。
 *
 * <h2>为什么需要它</h2>
 * <p>桥甲板要落在哪个 Y 上，这件事在代码里分散在三条建造路径 + 一个体积估算里，
 * 历史上**出过两次错**，而且现场留下了<b>互相矛盾的两段注释</b>：</p>
 *
 * <table border="1">
 *   <caption>甲板 Y 的口径（2026-11-15 复核实测）</caption>
 *   <tr><th>路径</th><th>代码</th><th>最终甲板 Y</th></tr>
 *   <tr><td>程序化兜底</td>
 *       <td>{@code BridgeSegmentPlanner}: {@code segDeckY = baseYForThis + 1}<br>
 *           {@code BridgeBuilder}: {@code deckBlockY = deckSurfaceY - 1}</td>
 *       <td>= {@code baseYForThis}（profile Y）</td></tr>
 *   <tr><td>内置模板</td>
 *       <td>{@code BridgeTemplateStructureRegistry}: {@code heightOffset + 2}<br>
 *           {@code PathFeature} 传 {@code baseYForThis}（未 +1）</td>
 *       <td>= profile Y</td></tr>
 *   <tr><td>体积估算</td>
 *       <td>{@code RoadConstructionVolumePlanner}: {@code deckBlockY = profile[index]}</td>
 *       <td>= profile Y</td></tr>
 * </table>
 *
 * <h2>★ 这里的脆弱点在「成对」</h2>
 * <p>{@code BridgeSegmentPlanner} 的 <b>+1</b> 与 {@code BridgeBuilder} 的 <b>-1</b>
 * 必须<b>成对存在</b>，它们相加才等于 0。历史上出过两种半对修改：</p>
 * <ol>
 *   <li>曲线路径用 +2、直线路径用 +0 ⇒ 同一条路上两座桥差两格，而且都跟路面差一格
 *       ⇒ 每座桥起止都有台阶；</li>
 *   <li>体积估算用 {@code profile - 1} ⇒ 验证器核对的体积比真实甲板低两格，
 *       真实甲板顶部两格从没被校验过，反而校验了两格空气。</li>
 * </ol>
 * <p>只改一处、不改另一处，编译能过、大部分测试也能过，只有真机才看得出来 ——
 * 所以必须用守卫钉住。</p>
 *
 * <h2>这个守卫<b>不</b>守什么</h2>
 * <p>它只做<b>源码文本检查</b>，不实际建造桥（那需要 {@code WorldGenLevel}）。
 * 它能抓「有人删了 +1 或 -1」「有人把体积估算改回 profile±n」这类
 * <b>成对关系被破坏</b>的情况；抓不到「三处都改了但算错」——
 * 那只能靠真机看桥面有没有台阶。</p>
 */
class BridgeDeckYConsistencyTest {

    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();
    private static final String PKG = "common/src/main/java/net/shiroha233/roadweaver/";

    private static String read(String rel) throws IOException {
        Path p = REPO_ROOT.resolve(rel);
        assertTrue(Files.isRegularFile(p), "找不到源文件（仓库根定位错了？）: " + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String stripComments(String t) {
        return t.replaceAll("/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    @Test
    void 程序化路径的加一必须存在() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/bridge/BridgeSegmentPlanner.java"));
        assertTrue(compact(code).contains("intsegDeckY=baseYForThis+1;"),
                "BridgeSegmentPlanner 里必须有 `int segDeckY = baseYForThis + 1;`。\n"
                        + "这个 +1 与 BridgeBuilder 的 -1 是成对的："
                        + "两者相加才让甲板落在 profile Y（路面所在层）。\n"
                        + "删掉 +1 ⇒ 甲板低一格 ⇒ 桥与路面差一格，起止出台阶（真机才看得出）");
    }

    @Test
    void 建造路径的减一必须存在() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/bridge/BridgeBuilder.java"));
        assertTrue(compact(code).contains("intdeckBlockY=deckSurfaceY-1;"),
                "BridgeBuilder 里必须有 `int deckBlockY = deckSurfaceY - 1;`。\n"
                        + "它与 BridgeSegmentPlanner 的 +1 成对。改这里必须同时改那里");
    }

    @Test
    void 体积估算必须直接从profile起算() throws IOException {
        String code = stripComments(read(PKG + "pathlogic/construction/RoadConstructionVolumePlanner.java"));
        String c = compact(code);
        assertTrue(c.contains("intdeckBlockY=profile[index];"),
                "RoadConstructionVolumePlanner 里甲板起点必须是 `int deckBlockY = profile[index];`。\n"
                        + "历史错误：曾写成 profile - 1 ⇒ 验证器核对的体积比真实甲板低两格，"
                        + "真实甲板顶部两格从没被校验、反而校验了两格空气");
        assertFalse(c.contains("intdeckBlockY=profile[index]-1;")
                        || c.contains("intdeckBlockY=profile[index]+1;")
                        || c.contains("intdeckBlockY=profile[index]+2;"),
                "体积估算的甲板起点被加了一圈偏移。三条路径都必须从 profile Y 起算");
    }

    @Test
    void 模板路径的映射偏移必须与传参配套() throws IOException {
        String registry = stripComments(read(PKG + "structures/registry/BridgeTemplateStructureRegistry.java"));
        assertTrue(compact(registry).contains("structure.heightOffset+2"),
                "BridgeTemplateStructureRegistry 的体素映射必须是 `structure.heightOffset + 2`，\n"
                        + "它与 PathFeature 传的 baseYForThis（未 +1）配套，模板甲板才落在 profile Y。\n"
                        + "改这个 +2 或改 PathFeature 的传参，两边必须同步，否则模板桥整体偏一格");

        String feature = stripComments(read(PKG + "pathlogic/construction/ConstructionStationWork.java"));
        assertTrue(compact(feature).contains("intactualDeckY=baseYForThis;"),
                "PathFeature 传给模板路径的必须是 `int actualDeckY = baseYForThis;`（**不加 1**）。\n"
                        + "注意：它与传给程序化兜底的参数是同一个变量，但两条路径对它的解释不同 ——\n"
                        + "程序化路径内部再 +1，模板路径靠注册表的 +2 抵消。改这里要么两条都改，要么都别改");
    }

    @Test
    void 三处口径在注释层也必须一致() throws IOException {
        // 2026-11-15 修掉的那两段互相矛盾的注释不能再回来：
        // 它们一段说「甲板比 profile 高一格」、一段说「甲板就在 profile 上」。
        String vol = read(PKG + "pathlogic/construction/RoadConstructionVolumePlanner.java");
        assertFalse(vol.contains("The deck block sits one above the profile Y"),
                "那段「甲板比 profile 高一格」的旧注释必须保持删除状态 —— "
                        + "它与后一段「甲板就在 profile 上」互相矛盾，是更早一次修复留下的残骸");
        assertTrue(vol.contains("甲板 Y 的唯一口径"),
                "体积估算里应当保留「甲板 Y 的唯一口径：profile Y」这段权威说明，"
                        + "它是后来人判断该信哪一句的唯一依据");
    }

    /** 去掉所有空白，让断言不受缩进/换行影响。 */
    private static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }
}