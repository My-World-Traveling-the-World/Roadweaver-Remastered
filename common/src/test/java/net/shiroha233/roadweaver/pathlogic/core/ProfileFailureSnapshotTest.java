/* 文件职责：真正从生产拒绝入口落盘再重开重放，不用缩短或重造预算的地形替代现场。 */
package net.shiroha233.roadweaver.pathlogic.core;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.config.sub.RoadGenerationConfig;
import net.shiroha233.roadweaver.core.model.ProfileConflict;
import net.shiroha233.roadweaver.pathlogic.grade.SurfaceGradeLimits;
import net.shiroha233.roadweaver.pathlogic.profile.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProfileFailureSnapshotTest {
    @TempDir Path directory;

    static ProfileConflict witness(ProfileFailureSnapshot value) {
        var c=value.conflict();
        return new ProfileConflict(ProfileConflict.Reason.valueOf(c.reason()),c.station(),c.limitingStation(),c.requiredMinY(),c.reachableMaxY());
    }

    static ProfileConflict.Rejected reject(int[] terrain,boolean[] crossing,boolean brokenSolver,
            Consumer<ProfileFailureSnapshot> output) {
        var centers=new ArrayList<BlockPos>();
        for(int i=0;i<terrain.length;i++)centers.add(new BlockPos(i-terrain.length/2,terrain[i],-17));
        var config=new ModConfig();
        config.roadAppearance().setSlopeLimitEnabled(true);
        config.roadAppearance().setMaxSlopeStepPerTwoSegments(1);
        var fixed=new boolean[terrain.length];fixed[0]=true;fixed[fixed.length-1]=true;
        return assertThrows(ProfileConflict.Rejected.class,()->TargetProfileSolver.solve(null,centers,terrain,terrain,
                crossing,null,fixed,terrain[0],terrain[terrain.length-1],RoadGenerationConfig.from(config),
                new SurfaceGradeLimits(1,1,1,1,3,1),null,(i,c)->-7,
                (t,c,cut,fill,step,two,constraints)->{
                    if(brokenSolver)throw new IllegalStateException("solver-error-before-repair");
                    return ProfileDpSource.solve(t,c,cut,fill,step,two,constraints);
                },(t,c,cut,fill,step,two,constraints)->TargetProfileRepair.solveForRoad(t,c,cut,fill,step,two,constraints,
                        preview->{throw new AssertionError("锁高已互斥，不应触碰物理隧道分类");}),output));
    }

    static ProfileFailureSnapshot capture(int[] terrain,boolean[] crossing,boolean brokenSolver) {
        var captured=new ArrayList<ProfileFailureSnapshot>();
        reject(terrain,crossing,brokenSolver,captured::add);
        assertEquals(1,captured.size(),"每次真实拒绝必须交付一份完整快照");
        return captured.getFirst();
    }

    @Test void productionRejectionWritesReopensAndReplaysTheSameNegativeLockConflict() throws Exception {
        System.clearProperty("roadweaver.profileAb");
        var snapshot=capture(new int[]{-9,-9,-1},null,false);
        var file=ProfileFailureFiles.write(directory.resolve("world-a"),snapshot);
        var reopened=ProfileFailureFiles.read(file);
        assertArrayEquals(new int[]{-9,-9,-1},reopened.terrain());
        assertArrayEquals(new boolean[]{true,false,true},reopened.pinnedStations());
        assertArrayEquals(new int[]{-9,-1,-1},reopened.pin());
        assertEquals(1,reopened.cut());assertEquals(1,reopened.fill());
        assertEquals(1,reopened.step());assertEquals(1,reopened.perTwo());
        assertTrue(reopened.beforePhysicalSelection());
        var expected=new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT,2,0,-1,-8);
        assertEquals(expected,witness(reopened));
        var replay=assertThrows(ProfileConflict.Rejected.class,()->TargetProfileRepair.solveForRoad(
                reopened.terrain(),reopened.crossing(),reopened.cut(),reopened.fill(),reopened.step(),reopened.perTwo(),
                reopened.constraints(),preview->{throw new AssertionError("重放也不应借助真实世界");}));
        assertEquals(expected,replay.conflict());
    }

    @Test void lateNegativePinAndExplicitDeckFloorAreNeverTruncated() throws Exception {
        int n=4097;int[] terrain=new int[n];Arrays.fill(terrain,-9);terrain[n-1]=-1;
        boolean[] crossing=new boolean[n];crossing[123]=true;
        var snapshot=capture(terrain,crossing,false);
        var loaded=ProfileFailureFiles.read(ProfileFailureFiles.write(directory.resolve("long-world"),snapshot));
        assertEquals(n,loaded.terrain().length);assertEquals(n,loaded.stationXZ().length);
        assertEquals(-1,loaded.pin()[n-1]);assertTrue(loaded.pinnedStations()[n-1]);
        assertEquals(-7,loaded.deckFloor()[123]);assertTrue(loaded.crossing()[123]);
        assertEquals(Integer.MIN_VALUE,loaded.deckFloor()[n-1]);
        assertEquals(((long)(n-1-n/2)<<32)|(-17&0xffffffffL),loaded.stationXZ()[n-1]);
        assertEquals(n-1,loaded.conflict().station());
        terrain[n-1]=80;loaded.terrain()[n-1]=90;
        assertEquals(-1,snapshot.terrain()[n-1]);
        assertEquals(-1,loaded.terrain()[n-1]);
    }

    @Test void optimizerExceptionStillRecordsTheSubsequentRealRepairRejection() {
        var snapshot=capture(new int[]{-9,-9,-1},null,true);
        assertEquals(new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT,2,0,-1,-8),witness(snapshot));
    }

    @Test void coveredNegativePinIsRecordedAndReplayedBeforePhysicalSelection() throws Exception {
        int[] terrain={0,0,0};var snapshots=new ArrayList<ProfileFailureSnapshot>();
        var cfg=new ModConfig();cfg.roadAppearance().setSlopeLimitEnabled(true);
        assertThrows(ProfileConflict.Rejected.class,()->TargetProfileSolver.solve(null,
                List.of(new BlockPos(0,0,0),new BlockPos(1,0,0),new BlockPos(2,0,0)),terrain,terrain,null,
                new boolean[]{true,false,false},new boolean[]{true,false,false},-1,0,RoadGenerationConfig.from(cfg),
                new SurfaceGradeLimits(1,1,1,1,3,1),null,(i,c)->Integer.MIN_VALUE,ProfileDpSource::solve,
                (t,c,cut,fill,step,two,constraints)->TargetProfileRepair.solveForRoad(t,c,cut,fill,step,two,constraints,
                        preview->{throw new AssertionError("覆土冲突先于物理分类");}),snapshots::add));
        assertEquals(1,snapshots.size());
        var loaded=ProfileFailureFiles.read(ProfileFailureFiles.write(directory,snapshots.getFirst()));
        assertTrue(loaded.beforePhysicalSelection());assertTrue(loaded.mustBeCovered()[0]);assertEquals(-1,loaded.pin()[0]);
        var replay=assertThrows(ProfileConflict.Rejected.class,()->TargetProfileRepair.solveForRoad(loaded.terrain(),loaded.crossing(),
                loaded.cut(),loaded.fill(),loaded.step(),loaded.perTwo(),loaded.constraints(),
                preview->{throw new AssertionError("重放不应访问世界");}));
        assertEquals(new ProfileConflict(ProfileConflict.Reason.COVER,0,0,-1,-3),replay.conflict());
        assertEquals(replay.conflict(),witness(loaded));
    }

    @Test void successfulRoadNeverInvokesFailureRecording() {
        int[] terrain={64,64,64,64};
        var centers=java.util.stream.IntStream.range(0,4).mapToObj(i->new BlockPos(i,64,0)).toList();
        var cfg=new ModConfig();cfg.roadAppearance().setSlopeLimitEnabled(true);
        var result=TargetProfileSolver.solve(null,centers,terrain,terrain,null,null,null,64,64,RoadGenerationConfig.from(cfg),
                new SurfaceGradeLimits(1,1,1,1,3,1),null,(i,c)->Integer.MIN_VALUE,ProfileDpSource::solve,
                (t,c,cut,fill,step,two,constraints)->{throw new AssertionError("平地应直接求解");},
                snapshot->{throw new AssertionError("成功路线不能录失败快照");});
        assertArrayEquals(terrain,result.orElseThrow());
    }

    @Test void identicalFailuresStayInTheirOwnWorldDirectories() throws Exception {
        var snapshot=capture(new int[]{-9,-9,-1},null,false);
        var a=ProfileFailureFiles.write(directory.resolve("save-a"),snapshot);
        var b=ProfileFailureFiles.write(directory.resolve("save-b"),snapshot);
        assertNotEquals(a,b);assertEquals(a.getFileName(),b.getFileName());
        assertEquals(a,ProfileFailureFiles.write(directory.resolve("save-a"),snapshot));
        try(var files=Files.list(a.getParent())){assertEquals(1,files.count(),"不能遗留临时文件或重复同一输入");}
        var damaged=JsonParser.parseString(Files.readString(a)).getAsJsonObject();damaged.remove("pinnedStations");
        var bad=directory.resolve("incomplete.json");Files.writeString(bad,damaged.toString());
        assertThrows(RuntimeException.class,()->ProfileFailureFiles.read(bad),"缺锁高标记不能静默当作无锁高");
        var missingBudget=JsonParser.parseString(Files.readString(a)).getAsJsonObject();missingBudget.remove("cut");
        Files.writeString(bad,missingBudget.toString());
        assertThrows(RuntimeException.class,()->ProfileFailureFiles.read(bad),"缺预算不能被Gson默认为0");
    }

    @Test void diagnosticIoFailureNeverReplacesTheOriginalProfileConflict() throws Exception {
        var invalidDirectory=Files.createFile(directory.resolve("not-a-directory"));
        var rejected=reject(new int[]{-9,-9,-1},null,false,snapshot->ProfileFailureFiles.recordTo(invalidDirectory,snapshot));
        assertEquals(new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT,2,0,-1,-8),rejected.conflict());
    }

    @Test void failedDiagnosticSinkNeverReplacesTheOriginalProfileConflict() {
        var rejected=reject(new int[]{-9,-9,-1},null,false,snapshot->{throw new IllegalStateException("diagnostic-sink-failed");});
        assertEquals(new ProfileConflict(ProfileConflict.Reason.LOCKED_HEIGHT,2,0,-1,-8),rejected.conflict());
    }

    @Test void productionRecorderTakesItsDirectoryFromTheActualSaveRoot() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("common/src/main/java")))root=root.getParent();
        assertNotNull(root,"源码根目录缺失必须失败，不能跳过世界隔离守卫");
        var source=root.resolve("common/src/main/java/net/shiroha233/roadweaver/pathlogic/core/ProfileFailureFiles.java");
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        try(var manager=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8)) {
            var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,
                    manager.getJavaFileObjects(source.toFile()));
            var unit=task.parse().iterator().next();var bodies=new ArrayList<com.sun.source.tree.BlockTree>();
            new com.sun.source.util.TreeScanner<Void,Void>() {
                @Override public Void visitMethod(com.sun.source.tree.MethodTree method,Void p){
                    if(method.getName().contentEquals("record"))bodies.add(method.getBody());return super.visitMethod(method,p);
                }
            }.scan(unit,null);
            assertEquals(1,bodies.size());
            var statements=bodies.getFirst().getStatements();
            var declaration=assertInstanceOf(com.sun.source.tree.VariableTree.class,statements.getFirst());
            assertEquals("directory",declaration.getName().toString());
            assertTrue(declaration.getInitializer().toString().startsWith("level.getServer().getWorldPath(LevelResource.ROOT).resolve("),
                    "生产目录必须直接源于当前存档，不能写全局logs或客户端目录");
            var call=assertInstanceOf(com.sun.source.tree.ExpressionStatementTree.class,statements.get(1));
            var invocation=assertInstanceOf(com.sun.source.tree.MethodInvocationTree.class,call.getExpression());
            assertEquals("recordTo",invocation.getMethodSelect().toString());
            assertEquals("directory",invocation.getArguments().getFirst().toString());
        }
    }
}
