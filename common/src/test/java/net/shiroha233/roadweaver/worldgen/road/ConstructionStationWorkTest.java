/* 文件职责：用生产站动作对拍冻结旧循环的完整事件，任意站边界暂停不能改变随机、拒绝和高度回写。 */
package net.shiroha233.roadweaver.worldgen.road;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.shiroha233.roadweaver.config.ModConfig;
import net.shiroha233.roadweaver.core.model.*;
import net.shiroha233.roadweaver.decoration.base.Decoration;
import net.shiroha233.roadweaver.pathlogic.bridge.*;
import net.shiroha233.roadweaver.pathlogic.construction.*;
import net.shiroha233.roadweaver.pathlogic.tunnel.TunnelPlan;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConstructionStationWorkTest {
    @Test void actualProductionBranchesMatchFrozenLoopAcrossEveryBatchSize() {
        int events=0;
        for(int mode=0;mode<4;mode++)for(int batch=1;batch<=24;batch++) {
            Fixture old=new Fixture(mode),now=new Fixture(mode);
            boolean completed=FrozenConstructionStationWork.run(old.inputs,old.state,old.access,
                    old::claim,old.decorations,old.random,old.refusals);
            var work=now.work();
            while(now.state.cursor().state()==ConstructionStationCursor.State.READY) {
                now.state.cursor().advance(batch,()->true,work);
                if(now.state.cursor().state()==ConstructionStationCursor.State.READY)assertFalse(now.state.completed());
            }
            assertEquals(old.events,now.events,"生产分支、世界读、写入委托与随机次序必须与旧循环相同");
            assertEquals(old.refusals,now.refusals);assertArrayEquals(old.state.profile(),now.state.profile());
            assertEquals(completed,now.state.completed());
            for(int i=0;i<24;i++)assertEquals(old.state.refused(i),now.state.refused(i));
            assertEquals(old.random.nextLong(),now.random.nextLong());events+=now.events.size();
            assertTrue(now.events.stream().anyMatch(e->e.startsWith("pave:")));
            assertTrue(now.events.stream().anyMatch(e->e.startsWith("decorate:")));
            if(mode==0) {
                assertTrue(now.events.stream().anyMatch(e->e.startsWith("template:1:")));
                assertTrue(now.events.contains("procedural:2"));
                assertTrue(now.events.contains("terrain:7"));assertTrue(now.state.refused(10));
                assertEquals("protected-column",now.refusals.get(10));
                assertFalse(now.events.stream().anyMatch(e->e.startsWith("apply:10")));
                assertEquals(93,now.state.profile()[7]);
                assertTrue(now.state.refused(7));assertEquals("protected-column",now.refusals.get(7));
            }
        }
        assertTrue(events>5000,"事件总量必须非空且确实覆盖大量生产操作，不能空集合空转");
    }
    @Test void cancellationAfterRealProductionPrefixCannotReplayOrComplete() {
        var fixture=new Fixture(0);var work=fixture.work();
        assertEquals(7,fixture.state.cursor().advance(7,()->true,work));
        assertTrue(fixture.events.size()>10);var prefix=List.copyOf(fixture.events);
        fixture.state.cursor().advance(24,()->false,work);
        assertEquals(ConstructionStationCursor.State.CANCELLED,fixture.state.cursor().state());
        fixture.state.cursor().runAll(work);assertEquals(prefix,fixture.events);assertFalse(fixture.state.completed());
    }
    @Test void failureAfterProductionSideEffectCannotReplayStation() {
        var fixture=new Fixture(0);fixture.throwAfterPave=true;var work=fixture.work();
        assertThrows(IllegalStateException.class,()->fixture.state.cursor().runAll(work));
        assertTrue(fixture.events.stream().anyMatch(e->e.startsWith("pave:")));
        var prefix=List.copyOf(fixture.events);fixture.state.cursor().runAll(work);
        assertEquals(prefix,fixture.events);assertEquals(ConstructionStationCursor.State.FAILED,fixture.state.cursor().state());
        assertFalse(fixture.state.completed());
    }
    private static final class Fixture {
        final List<String> events=new ArrayList<>();
        final Map<Integer,String> refusals=new TreeMap<>();
        final Set<Integer> claimed=new HashSet<>(Set.of(4));
        final Set<Decoration> decorations=new HashSet<>();
        final RandomSource random=RandomSource.create(506);
        final ConstructionSliceState state=new ConstructionSliceState(24,24,i->i);
        final ConstructionStationWork.Inputs inputs;
        final ConstructionStationAccess access;
        boolean throwAfterPave;
        Fixture(int mode) {
            var segments=new ArrayList<RoadSegmentPlacement>();var ys=new ArrayList<Integer>();
            for(int i=0;i<24;i++){var p=new BlockPos(i,64,0);segments.add(new RoadSegmentPlacement(p,List.of(p,p.offset(0,0,1))));ys.add(64+i%3);}
            var spans=List.of(span(segments,5,SpanType.NETWORK_REUSE),span(segments,6,SpanType.PROTECTED_GAP),
                    span(segments,8,SpanType.TUNNEL),span(segments,9,SpanType.TUNNEL));
            var road=new RoadData(3,0,List.of(),List.of(),segments,spans,ys,1,2);
            var cfg=new ModConfig();var chunk=new ChunkPos(0,0);
            int[] indices=new int[24];for(int i=0;i<24;i++)indices[i]=i;
            var slice=new RoadChunkSlice(road,chunk,indices,new int[]{9});
            var frame=BuildFrame.of(506,segments,spans,cfg);
            boolean[] bridges=new boolean[24],skips=new boolean[24],markers=new boolean[24];
            for(int i:new int[]{1,2,16,17})bridges[i]=true;
            skips[3]=true;Arrays.fill(markers,true);
            int[] profile=ys.stream().mapToInt(Integer::intValue).toArray();state.profile(profile);
            inputs=new ConstructionStationWork.Inputs(road,506,chunk,slice,cfg,mode!=3,0,3,List.of(),List.of(),
                    segments,frame.middlePositions(),bridges,List.of(new int[]{1,2},new int[]{16,17}),skips,
                    ConstructionSpanMasks.of(road,cfg.bridgeRampSegments()),mode==1,mode==2,markers,markers,
                    new BridgeSegment(bridges,segments,profile),68,BridgeSegmentPlanner.newContext(),frame);
            // 动态代理只实现世界操作，未命名操作立即报错；测试不复制生产编排分支。
            access=(ConstructionStationAccess)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ConstructionStationAccess.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "seaLevel": events.add("sea");return 63;
                    case "height": events.add("height:"+args[0]+":"+args[1]+":"+args[2]);return 70+events.size()%4;
                    case "terrain": events.add("terrain:"+args[0]);return 93;
                    case "buoy": events.add("buoy:"+args[0]+":"+random.nextInt());return null;
                    case "templateBridge": {
                        int i=((BlockPos)args[4]).getX();events.add("template:"+i+":"+args[6]+":"+args[7]);return i!=2;
                    }
                    case "proceduralBridge":events.add("procedural:"+args[13]);return false;
                    case "tunnel": {
                        int i=(int)args[2];events.add("tunnel:"+i+":"+args[5]);
                        TunnelPlan plan=i==7?TunnelPlan.blockedPlan():i==8?TunnelPlan.protectedGapPlan():
                            i==9?new TunnelPlan(List.of(new BlockPos(i,64,0)),List.of(),null,false,road,true,false,false,false,false):TunnelPlan.none();
                        @SuppressWarnings("unchecked") var consumer=(java.util.function.Consumer<TunnelPlan>)args[6];
                        consumer.accept(plan);return null;
                    }
                    case "grade": {
                        int i=(int)args[1];events.add("grade:"+i+":"+Arrays.toString((int[])args[3])+":"+args[4]+":"+args[5]);
                        return new SegmentPaver.SegmentGrade(new int[]{i},new int[]{93},(boolean)args[4],(boolean)args[5],false);
                    }
                    case "pave": {
                        int i=(int)args[2];events.add("pave:"+i+":"+Arrays.toString((int[])args[4])+":"+random.nextInt()+":"+(args.length==13?args[12]:"surface"));
                        if(throwAfterPave)throw new IllegalStateException("模拟已产生施工副作用后失败");
                        if(i==7 || i==10){refusals.put(i,"protected-column");return false;}return true;
                    }
                    case "apply": {
                        var plan=(TunnelPlan)args[0];events.add("apply:"+(plan.interior().isEmpty()?"none":plan.interior().getFirst().getX()));return null;
                    }
                    case "bankSign":events.add("sign:"+args[6]);return null;
                    case "decorate":events.add("decorate:"+args[2]+":"+args[8]+":"+args[9]+":"+random.nextInt());return null;
                    default:throw new AssertionError("未覆盖的世界端口:"+method);
                }
            });
        }
        private static RoadSpan span(List<RoadSegmentPlacement> segments,int i,SpanType type){
            return new RoadSpan(segments.get(i).middlePos(),segments.get(i).middlePos(),type);
        }
        boolean claim(int i){events.add("claim:"+i);return claimed.add(i);}
        ConstructionStationWork work(){return new ConstructionStationWork(inputs,state,access,this::claim,decorations,random,refusals);}
    }
}

