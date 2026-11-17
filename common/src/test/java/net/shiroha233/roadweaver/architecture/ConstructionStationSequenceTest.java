/* 文件职责：用编译器解析冻结站循环和生产站动作，证明搬迁未改写世界操作顺序。 */
package net.shiroha233.roadweaver.architecture;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionStationSequenceTest {
    @Test void synchronousCursorRetainsFrozenWorldOperationBody() throws Exception {
        String old=Files.readString(Path.of("src/test/resources/construction/station-loop-3e2af6a.java.txt"));
        String production=Files.readString(Path.of("src/main/java/net/shiroha233/roadweaver/pathlogic/construction/ConstructionStationWork.java"));
        String[] before={null},after={null};
        scan(old,new TreeScanner<Void,Void>() {
            @Override public Void visitForLoop(ForLoopTree tree,Void unused){
                if(tree.getCondition().toString().contains("localIndex < slice.segmentCount()"))before[0]=tree.getStatement().toString();
                return super.visitForLoop(tree,unused);
            }
        });
        scan(production,new TreeScanner<Void,Void>() {
            @Override public Void visitMethod(MethodTree tree,Void unused){
                if(tree.getName().contentEquals("accept"))after[0]=tree.getBody().toString();
                return super.visitMethod(tree,unused);
            }
        });
        assertNotNull(before[0]);assertNotNull(after[0]);
        String expected=before[0].replace("int i = slice.segmentIndexAt(localIndex);","").replace("continue;","return;")
                .replace("completed[0] = false;","state.fail();").replace("constructionRefused[i] = true;","state.refuse(i);")
                .replace("!(constructionRefused != null && i < constructionRefused.length && constructionRefused[i])","!state.refused(i)");
        expected=compact(expected);
        expected=expected.replace(compact("processedSegments.add(new ProcessedSegment(roadFingerprint, i))"),compact("claim.test(i)"));
        expected=expected.replace(compact("server.getSeaLevel()"),compact("access.seaLevel()"));
        expected=expected.replace(compact("world.getHeight("),compact("access.height("));
        expected=expected.replace(compact("BuoyBuilder.placeBuoy(world,"),compact("access.buoy("));
        expected=expected.replace(compact("BridgeSegmentPlannerNew.processSegment( world,"),compact("access.templateBridge("));
        expected=expected.replace(compact("BridgeSegmentPlanner.processSegment(world,"),compact("access.proceduralBridge("));
        expected=expected.replace(compact("RoadTunnelBuilder.processSegment(world,"),compact("access.tunnel("));
        expected=expected.replace(compact("RoadConstructionSafety.terrainHeight( world,"),compact("access.terrain("));
        expected=expected.replace(compact("SegmentPaver.planGrade( world,"),compact("access.grade("));
        expected=expected.replace(compact("SegmentPaver.planGrade( world,"),compact("access.grade("));
        expected=expected.replace(compact("SegmentPaver.paveSegment(world,"),compact("access.pave("));
        expected=expected.replace(compact("RoadTunnelBuilder.apply(world, tunnel)"),compact("access.apply(tunnel)"));
        expected=expected.replace(compact("SkippedBridgeBankSignPlanner.addIfSkippedBridgeBank( world,"),compact("access.bankSign("));
        expected=expected.replace(compact("DecorationPlanner.addDecoration( world,"),compact("access.decorate("));
        expected=expected.replace(compact("refusalSink.stations()"),compact("refusals"));
        String actual=after[0];
        actual=actual.replace("RoadData data = inputs.data();","");
        actual=actual.replace("long roadFingerprint = inputs.roadFingerprint();","");
        actual=actual.replace("ChunkPos currentChunk = inputs.currentChunk();","");
        actual=actual.replace("RoadChunkSlice slice = inputs.slice();","");
        actual=actual.replace("ModConfig cfg = inputs.cfg();","");
        actual=actual.replace("boolean bridgeEnabled = inputs.bridgeEnabled();","");
        actual=actual.replace("int roadType = inputs.roadType();","");
        actual=actual.replace("int roadWidth = inputs.roadWidth();","");
        actual=actual.replace("List<BlockState> materials = inputs.materials();","");
        actual=actual.replace("List<BlockState> slabMaterials = inputs.slabMaterials();","");
        actual=actual.replace("List<RoadSegmentPlacement> segments = inputs.segments();","");
        actual=actual.replace("List<BlockPos> middlePositions = inputs.middlePositions();","");
        actual=actual.replace("boolean[] isBridge = inputs.isBridge();","");
        actual=actual.replace("List<int[]> bridgeRanges = inputs.bridgeRanges();","");
        actual=actual.replace("boolean[] skipSegments = inputs.skipSegments();","");
        actual=actual.replace("ConstructionSpanMasks.Masks spanMasks = inputs.spanMasks();","");
        actual=actual.replace("boolean useBuoysInstead = inputs.useBuoysInstead();","");
        actual=actual.replace("boolean useBuoysWhenSkipped = inputs.useBuoysWhenSkipped();","");
        actual=actual.replace("boolean[] buoyMarkersForBridge = inputs.buoyMarkersForBridge();","");
        actual=actual.replace("boolean[] buoyMarkersForSkipped = inputs.buoyMarkersForSkipped();","");
        actual=actual.replace("BridgeSegment bridgeSegment = inputs.bridgeSegment();","");
        actual=actual.replace("int deckY = inputs.deckY();","");
        actual=actual.replace("BridgeSegmentPlanner.Context bridgeCtx = inputs.bridgeCtx();","");
        actual=actual.replace("BuildFrame.Frames buildFrame = inputs.buildFrame();","");
        actual=actual.replace("int[] baseYArr = state.profile();","");
        assertEquals(compact(expected),compact(actual),"仅允许循环控制和状态载体转换，世界写/随机/拒绝/装饰顺序必须相同");
    }
    private static String compact(String value){return value.replaceAll("\\s+","");}
    private static void scan(String source,TreeScanner<Void,Void> visitor) throws Exception {
        var compiler=Objects.requireNonNull(ToolProvider.getSystemJavaCompiler());
        var file=new SimpleJavaFileObject(java.net.URI.create("string:///Fixture.java"),JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignore){return source;}};
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,List.of(file));
            for(var tree:task.parse())visitor.scan(tree,null);
        }
    }
}

