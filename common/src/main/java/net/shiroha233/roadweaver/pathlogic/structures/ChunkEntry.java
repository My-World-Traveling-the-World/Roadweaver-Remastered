/* 文件职责：区块级的结构盒缓存项 —— 一个区块解析出来的盒集合，外加「解析完了没」。 */
package net.shiroha233.roadweaver.pathlogic.structures;

import java.util.Collections;
import java.util.Set;
import static net.shiroha233.roadweaver.pathlogic.structures.PreparedStartResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.EndpointOwnerResolver.*;
import static net.shiroha233.roadweaver.pathlogic.structures.PathCorridorBuilder.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ProtectedGapEvaluator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.SnapshotOrchestrator.*;
import static net.shiroha233.roadweaver.pathlogic.structures.WorldgenAvoidance.*;
import static net.shiroha233.roadweaver.pathlogic.structures.ObstacleJudge.*;

class ChunkEntry {
    final Set<OwnedStructureBox> boxes;
    final boolean ready;
    ChunkEntry(Set<OwnedStructureBox> boxes, boolean ready) {
        this.boxes = boxes;
        this.ready = ready;
    }
    static final ChunkEntry UNREADY = new ChunkEntry(Collections.emptySet(), false);
}
