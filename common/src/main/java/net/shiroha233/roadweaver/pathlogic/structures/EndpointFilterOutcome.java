/* 文件职责：端点筛选的结果载体 —— kept / filtered / 是否整轮放弃。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import net.shiroha233.roadweaver.core.model.StructureConnection;
import java.util.List;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;

/**
 * 端点筛选结果。
 *
 * <p>{@code heldBack} 表示这一轮<b>整体放弃</b>（一条都不砍）：当被判定为
 * 「未知端点」的连接太多时，多半是快照没准备好而不是真的该删路 ——
 * 宁可这轮不筛，也不能一次砍掉一大片。原因写在 {@code heldBackReason} 里。</p>
 */
public record EndpointFilterOutcome(List<StructureConnection> kept,
                                    List<StructureConnection> filtered,
                                    boolean heldBack,
                                    String heldBackReason) {
    public int filteredCount() {
        return filtered == null ? 0 : filtered.size();
    }
}
