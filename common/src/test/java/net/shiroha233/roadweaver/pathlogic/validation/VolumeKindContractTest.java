/* 文件职责：钉住「体积规划产出的种类，校验端必须全部认可」这条不变式。 */
package net.shiroha233.roadweaver.pathlogic.validation;

import net.shiroha233.roadweaver.pathlogic.construction.RoadConstructionVolumePlanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 体积规划与校验器的种类契约（2026-11-15）。
 *
 * <h2>它防的两件事</h2>
 * <ol>
 *   <li><b>死种类复活</b>：{@code SHOULDER} 在 2026-11-15 被删除 ——
 *       路肩本来就不建（{@code SegmentPaver} 原文「Road shoulders are intentionally
 *       disabled」），校验端也把它排除，等于让 Planner 每站白算两侧各 5 列。
 *       若有人把它加回 {@code Kind}，本测试立刻变红。</li>
 *   <li><b>新增种类却不被认可</b>：往 {@code Kind} 里加一个新值但忘了在
 *       {@link FinalRoadStructureValidator#isRoadBodyVolume} 里表态，
 *       会让这类方块「算进体积却不参与校验」—— 属于静默漏检，比多算更危险。
 *       开关表达式是 exhaustive 的，漏写会编译失败；本测试再加一道运行期锚。</li>
 * </ol>
 *
 * <h2>为什么这条不变式重要</h2>
 * <p>{@code RoadConstructionVolumePlanner} 的唯一生产消费方就是本校验器。
 * 只要「产出的种类 ⊆ 校验认可的种类」，就没有「白算」；
 * 反之若产出方多算，就是纯浪费采样预算（路肩那个种类每站浪费 10 列）。</p>
 */
class VolumeKindContractTest {

    @Test
    void 体积规划产出的每一种都必须被校验器认可() {
        for (RoadConstructionVolumePlanner.Kind kind : RoadConstructionVolumePlanner.Kind.values()) {
            assertTrue(FinalRoadStructureValidator.isRoadBodyVolume(kind),
                    "种类 " + kind + " 会被体积规划产出，但校验器不认可它 —— "
                            + "要么把它加进 isRoadBodyVolume，要么从 Kind 里删掉它。"
                            + "（2026-11-15 删 SHOULDER 就是因为这类不一致："
                            + "Planner 每站白算两侧各 5 列，校验端立刻丢弃。）");
        }
    }

    @Test
    void 路肩不会作为体积种类复活() {
        for (RoadConstructionVolumePlanner.Kind kind : RoadConstructionVolumePlanner.Kind.values()) {
            assertFalse("SHOULDER".equals(kind.name()),
                    "SHOULDER 不该回到 Kind 里：施工端明确写着"
                            + "「Road shoulders are intentionally disabled」，"
                            + "gradeShoulders 全仓无调用方。若确实要恢复路肩，"
                            + "请同时接回施工侧（SegmentPaver）再删掉这条测试，"
                            + "不要只让规划多算一遍。");
        }
    }
}
