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
        String body=bodies.getFirst()+methods("pathlogic/construction/ConstructionStationWork.java").get("accept").getFirst();
        assertTrue(body.contains("ConstructionSpanMasks.of(data, cfg.bridgeRampSegments())"),"生产必须按同一冻结道路和坡道配置复用");
        for(String call:List.of("networkReuseAt(i)","protectedGapAt(i)","preserveGradeAt(i)","directCrossingAt(i)","tunnelAt(i)"))
            assertTrue(body.contains("spanMasks."+call),"生产必须实际消费掩码: "+call);
        assertTrue(body.contains("buildFrame.copyForUser()"),"运行期桥晋级必须持有独立可变副本");
        assertTrue(body.contains("targetY.stream().mapToInt(Integer::intValue).toArray()"),"隧道拒绝会改高度，不能跨区块共享数组");
    }

    @Test void liveConstructionUsesSynchronousCursorAndKeepsFinalizationOutsideIt() throws Exception {
        String slice=methods("pathlogic/PathFeature.java").get("processRoadSlice").getFirst();
        assertTrue(slice.contains("new net.shiroha233.roadweaver.pathlogic.construction.ConstructionSliceState"));
        assertTrue(slice.contains("state.cursor().runAll(work)"));
        assertTrue(slice.contains("new net.shiroha233.roadweaver.pathlogic.construction.WorldConstructionStationAccess(world, server)"));assertTrue(slice.contains("return state.completed()"));
        assertFalse(slice.contains("markComplete("));assertFalse(slice.contains("releaseChunk("));
        var all=methods("pathlogic/PathFeature.java").get("placeChunk");
        assertTrue(all.stream().anyMatch(body->body.contains("DecorationExecutor.tryPlaceDecorations(decorations)")
                && body.contains("RoadConstructionLedger.markComplete(") && body.contains("ConstructionChunkTransaction") && body.contains("HugeTreeReservation.releaseChunk(")));
    }
    @Test void worldStationAdapterOnlyForwardsOriginalOperationsInOriginalArgumentOrder() throws Exception {
        var adapter=methods("pathlogic/construction/WorldConstructionStationAccess.java");
        var expected=Map.ofEntries(
            Map.entry("seaLevel",List.of("{return server.getSeaLevel();}")),
            Map.entry("height",List.of("{return world.getHeight(type,x,z);}")),
            Map.entry("buoy",List.of("{BuoyBuilder.placeBuoy(world,middle,sea,random,cfg);}")),
            Map.entry("templateBridge",List.of("{return BridgeSegmentPlannerNew.processSegment(world,chunk,data,line,seg,middle,prev,deck,landing);}")),
            Map.entry("proceduralBridge",List.of("{return BridgeSegmentPlanner.processSegment(world,data,seg,middle,prev,next,width,actual,deck,index,random,cfg,ranges,profile,station,context,line);}")),
            Map.entry("tunnel",List.of("{RoadTunnelBuilder.processSegment(world,data,seg,index,centers,profile,junction,consumer);}")),
            Map.entry("terrain",List.of("{return RoadConstructionSafety.terrainHeight(world,x,z);}")),
            Map.entry("grade",List.of("{return SegmentPaver.planGrade(world,seg,index,centers,profile,tunnel,crossing);}")),
            Map.entry("pave",List.of("{return SegmentPaver.paveSegment(world,data,seg,index,centers,profile,grade,type,materials,slabs,random,cfg,refusals);}","{return SegmentPaver.paveSegment(world,data,seg,index,centers,profile,grade,type,materials,slabs,random,cfg,refusals,portal);}")),
            Map.entry("apply",List.of("{RoadTunnelBuilder.apply(world,plan);}")),
            Map.entry("bankSign",List.of("{SkippedBridgeBankSignPlanner.addIfSkippedBridgeBank(world,decorations,averaged,next,prev,width,skipped,index);}")),
            Map.entry("decorate",List.of("{DecorationPlanner.addDecoration(world,decorations,averaged,index,next,prev,centers,width,random,intervalStation,interval,mode);}"))
        );
        assertEquals(expected.keySet(),adapter.keySet(),"适配器不能夹带结案、读写或随机操作");
        for(var entry:expected.entrySet()) {
            var actual=adapter.get(entry.getKey()).stream().map(body->body.replaceAll("\\s+", "")).sorted().toList();
            var frozen=entry.getValue().stream().map(body->body.replaceAll("\\s+", "")).sorted().toList();
            assertEquals(frozen,actual,"原操作参数及转发次数不可改变:"+entry.getKey());
        }
    }
    @Test void chunkWorldAdapterRetainsFrozenTransactionOperations() throws Exception {
        // 行为测试只替换端口；再对冻结旧入口核对边界正文，防止真实适配器成为空壳。
        var frozen=parsedMethods(Path.of("src/test/resources/construction/chunk-transaction-5b399d5.java.txt"));
        var old=frozen.get("placeChunk").stream().filter(m->m.getParameters().size()==6).toList();
        assertEquals(1,old.size());
        var original=(TryTree)old.getFirst().getBody().getStatements().getFirst();
        var statements=original.getBlock().getStatements();
        assertEquals(20,statements.size(),"冻结旧入口发生变化时必须逐句重新核实");
        var live=methods("pathlogic/PathFeature.java");
        java.util.function.BiConsumer<String,String> same=(name,body)-> {
            assertEquals(1,live.get(name).size(),name);
            assertEquals(compact(body),compact(live.get(name).getFirst()),name);
        };
        var initial=(VariableTree)statements.get(0);
        same.accept("loadInitial","{return "+initial.getInitializer()+";}");
        var empty=(IfTree)statements.get(1);
        same.accept("isEmpty","{return "+((ParenthesizedTree)empty.getCondition()).getExpression()+";}");
        var emptyBody=((BlockTree)empty.getThenStatement()).getStatements();
        same.accept("emptyPlan","{"+join(emptyBody.subList(0,emptyBody.size()-1))+"}");
        StringBuilder prepare=new StringBuilder("{");
        for(int i=2;i<=9;i++) {
            var statement=statements.get(i);
            if(statement instanceof VariableTree variable)
                prepare.append(variable.getName()).append("=").append(variable.getInitializer()).append(';');
            else prepare.append(statement);
        }
        same.accept("prepare",prepare.append('}').toString());
        same.accept("process","{"+statements.get(10)+"}");
        var repair=(IfTree)statements.get(13);
        same.accept("repairRefusals","{return "+((ParenthesizedTree)repair.getCondition()).getExpression()+";}");
        var repairBody=((BlockTree)repair.getThenStatement()).getStatements();
        var refreshed=(VariableTree)repairBody.get(1);
        same.accept("loadRefreshed","{return "+refreshed.getInitializer()+";}");
        var nonEmpty=(IfTree)repairBody.get(2);
        var reset=((BlockTree)nonEmpty.getThenStatement()).getStatements().get(1);
        same.accept("resetRefusals","{"+reset+"}");
        same.accept("decorate","{"+statements.get(14)+"}");
        same.accept("commitCompleted","{"+statements.get(15)+"}");
        same.accept("finish","{"+join(statements.subList(16,statements.size()))+"}");
        same.accept("release",original.getFinallyBlock().toString());
    }
    private static String join(List<? extends StatementTree> statements) {
        return statements.stream().map(tree->tree.toString()+(tree instanceof VariableTree?";":""))
                .collect(java.util.stream.Collectors.joining());
    }
    private static String compact(String value) {
        // 只忽略格式空白，不忽略调用、参数或分支。
        return value.replaceAll("\\s+", "");
    }
    private static Map<String,List<MethodTree>> parsedMethods(Path source) throws Exception {
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var result=new HashMap<String,List<MethodTree>>();
        String text=Files.readString(source);
        var file=new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///Fixture.java"),javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore){return text;}
        };
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,List.of(file));
            for(var unit:task.parse())new TreeScanner<Void,Void>() {
                @Override public Void visitMethod(MethodTree tree,Void unused) {
                    if(tree.getBody()!=null)result.computeIfAbsent(tree.getName().toString(),k->new ArrayList<>()).add(tree);
                    return super.visitMethod(tree,unused);
                }
            }.scan(unit,null);
        }
        return result;
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
