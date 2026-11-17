/* 文件职责：预算生产接线不能只剩离线端口或注释。 */
package net.shiroha233.roadweaver.architecture;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionBudgetWiringTest {
    @Test void liveBackfillAndRouteUseBudgetAndAttemptedResultPorts() throws Exception {
        var backfill = methods("generation/LoadedChunkRoadBackfill.java");
        assertTrue(backfill.get("placeLoadedChunks").stream().anyMatch(body ->
            body.contains("new ConstructionTimeBudget(System::nanoTime)") && body.contains("verified, attempted")),
            "生产回填必须构造默认时间预算并回传真正尝试的集合");
        var route = methods("route/RouteChunkPreloader.java");
        assertTrue(route.get("placeBatch").stream().anyMatch(body -> body.contains(
            "LoadedChunkRoadBackfill.placePreloadedChunks(level, ready, attempted)")),
            "手绘生产端口不能丢弃预算等待状态");
        var deferred = methods("generation/DeferredInitialRoadConstructionService.java");
        assertTrue(deferred.get("tick").stream().anyMatch(body -> body.contains(
            "placeSelectedLoadedChunks(level, loadedForThisDrain, attempted)") && body.contains("!attempted.contains(key)")),
            "后台调度不能让未尝试区块消费失败次数");
    }
    @Test void sharedBridgeProductionUsesSparseIndexWithoutSuppressingDebugPairs() throws Exception {
        var bodies = methods("pathlogic/bridge/BridgeShareService.java").get("applyInPlace");
        assertNotNull(bodies);assertEquals(1,bodies.size());
        String body = bodies.getFirst();
        assertTrue(body.contains("BridgePairIndex.sparseBatch("),"离线索引不能脱离生产入口");
        assertTrue(body.contains("!logPairs") && body.contains("index.afterInts(i, radius)"),"必须保留完整DEBUG并实际消费有序候选");
        assertTrue(body.contains("nearby.getInt(pair)"),"不能计算候选后仍忽略索引输出");
    }
    @Test void roadConstructionUsesReadOnlySpanMasksAndKeepsMutableHeightAndBridgeCopies() throws Exception {
        var bodies=methods("pathlogic/PathFeature.java").get("processRoadSlice");assertNotNull(bodies);assertEquals(1,bodies.size());
        String body=bodies.getFirst();
        assertTrue(body.contains("ConstructionSpanMasks.of(data, cfg.bridgeRampSegments())"),"生产必须按同一冻结道路和坡道配置复用");
        for(String call:List.of("networkReuseAt(i)","protectedGapAt(i)","preserveGradeAt(i)","directCrossingAt(i)","tunnelAt(i)"))
            assertTrue(body.contains("spanMasks."+call),"生产必须实际消费掩码: "+call);
        assertTrue(body.contains("buildFrame.copyForUser()"),"运行期桥晋级必须持有独立可变副本");
        assertTrue(body.contains("targetY.stream().mapToInt(Integer::intValue).toArray()"),"隧道拒绝会改高度，不能跨区块共享数组");
    }

    @Test void liveConstructionUsesSynchronousCursorAndKeepsFinalizationOutsideIt() throws Exception {
        String slice=methods("pathlogic/PathFeature.java").get("processRoadSlice").getFirst();
        assertTrue(slice.contains("new net.shiroha233.roadweaver.pathlogic.construction.ConstructionSliceState"));
        assertTrue(slice.contains("state.cursor().runAll("));assertTrue(slice.contains("return state.completed()"));
        assertFalse(slice.contains("markComplete("));assertFalse(slice.contains("releaseChunk("));
        var all=methods("pathlogic/PathFeature.java").get("placeChunk");
        assertTrue(all.stream().anyMatch(body->body.contains("DecorationExecutor.tryPlaceDecorations(decorations)")
                && body.contains("RoadConstructionLedger.markComplete(") && body.contains("finally") && body.contains("HugeTreeReservation.releaseChunk(")));
    }
    private static Map<String,List<String>> methods(String relative) throws Exception {
        Path root=Path.of("src/main/java/net/shiroha233/roadweaver");
        assertTrue(Files.isRegularFile(root.resolve(relative)),"生产接线源文件必须存在");
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var result=new HashMap<String,List<String>>();
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,
                    manager.getJavaFileObjectsFromPaths(List.of(root.resolve(relative))));
            for(var unit:task.parse()) new TreeScanner<Void,Void>() {
                @Override public Void visitMethod(MethodTree tree,Void unused) {
                    if(tree.getBody()!=null) result.computeIfAbsent(tree.getName().toString(),key -> new ArrayList<>()).add(tree.getBody().toString());
                    return super.visitMethod(tree,unused);
                }
            }.scan(unit,null);
        }
        return result;
    }
}
