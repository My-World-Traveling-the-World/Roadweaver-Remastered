/* 文件职责：按地图视口、世界和权限展示只读道路快照。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.diorama.MapScreen;
import dev.diorama.Projection;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi;

/** 每屏幕独立缓存XYZ；渲染只做投影，查询在权限确认后用不可变快照异步执行。 */
public final class DioramaRoadOverlay {
    private final DrawingOverlay renderer=new DrawingOverlay();
    private final RoadOverlayState state=new RoadOverlayState();
    private long nextRequest,revision=-1;
    private Object context;
    private int facing=-1;
    private double pitch=Double.NaN;
    private ViewportBounds projectedBounds;
    private List<Drawing> strokes=List.of();
    private String status="等待道路计划";
    private boolean failureLogged;
    public void render(MapScreen screen,GuiGraphics graphics) {
        var mc=Minecraft.getInstance();var view=screen.view();var server=mc.getSingleplayerServer();
        if(view==null||!"minecraft:overworld".equals(screen.dimension())||view.layer()!=Integer.MIN_VALUE)return;
        if(server==null||mc.player==null||mc.level==null){
            state.clear();strokes=List.of();status="道路叠图：远端服务器尚未接入几何同步";
        }else {
            if(context!=mc.level){context=mc.level;state.clear();strokes=List.of();revision=-1;nextRequest=0;}
            long now=System.nanoTime();
            if(now>=nextRequest){
                var bounds=ViewportBounds.of(view.centerU(),view.centerV(),view.width()/view.zoom(),view.height()/view.zoom(),
                        mc.level.getMinBuildHeight(),mc.level.getMaxBuildHeight(),Projection.facing().turns,Projection.sin(),Projection.cos());
                request(mc,screen,bounds);nextRequest=now+500_000_000L;
            }
            var published=state.view();var snapshot=published.snapshot();
            if(snapshot.revision()!=revision||facing!=Projection.facing().turns||pitch!=Projection.sin()
                    ||!Objects.equals(projectedBounds,published.bounds())){
                strokes=project(snapshot.lines());revision=snapshot.revision();facing=Projection.facing().turns;
                pitch=Projection.sin();projectedBounds=published.bounds();
            }
            status=snapshot.state()==RoadOverlayApi.State.UNAVAILABLE?"等待道路计划":
                    snapshot.truncated()?"道路叠图已达顶点预算，请放大查看":"";
        }
        renderer.draw(graphics,mc.font,view,strokes,null,null,false);
        if(!status.isEmpty())graphics.drawString(mc.font,status,view.x()+8,view.y()+24,0xFFE8CF91);
    }
    private void request(Minecraft mc,MapScreen screen,ViewportBounds area) {
        var world=mc.level;var server=mc.getSingleplayerServer();var playerId=mc.player.getUUID();
        long epoch=state.begin(world);if(epoch<0)return;
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(playerId);
            if(!RoadOverlayApi.isAllowed(player)){
                mc.execute(()->state.complete(world,epoch,RoadOverlayApi.Snapshot.unavailable(),area));return;
            }
            // 只读提供方不读世界方块；转换不挤占渲染线程或服务端主线程。
            CompletableFuture.supplyAsync(()->RoadOverlayApi.peek(server.overworld(),area.minX(),area.minZ(),area.maxX(),area.maxZ(),32768))
                    .whenComplete((result,failure)->server.execute(()->{
                        // 转换期间权限可能已撤销；发布前再次在服务端核定，拒绝结果同样受世界代次约束。
                        boolean allowed=RoadOverlayApi.isAllowed(server.getPlayerList().getPlayer(playerId));
                        mc.execute(()->{
                        if(mc.level!=world||mc.getSingleplayerServer()!=server||mc.screen!=screen){
                            state.complete(world,epoch,RoadOverlayApi.Snapshot.unavailable(),area);return;
                        }
                        if(failure!=null){
                            if(!failureLogged){System.getLogger("plaincraft-diorama-roads").log(System.Logger.Level.WARNING,"道路叠图读取失败",failure);failureLogged=true;}
                            state.complete(world,epoch,RoadOverlayApi.Snapshot.unavailable(),area);
                        }else state.complete(world,epoch,allowed?result:RoadOverlayApi.Snapshot.unavailable(),area);
                    });}));
        });
    }
    static List<Drawing> project(List<RoadOverlayApi.Line> lines) {
        var output=new ArrayList<Drawing>();
        for(var line:lines){
            var points=line.points();double[] uv=new double[points.size()*2];
            for(int i=0;i<points.size();i++){var p=points.get(i);uv[i*2]=Projection.u(p.x()+.5,p.z()+.5);uv[i*2+1]=Projection.v(p.x()+.5,p.y(),p.z()+.5);}
            // 隧道用独立颜色表达地下走向；二维叠图不声称已经实现地形深度遮挡。
            int color=switch(line.kind()){case BRIDGE->0xFF65C8EF;case TUNNEL->0xFFA991D1;case CROSSING->0xFF96DE9D;default->0xFFFFC247;};
            output.add(new Drawing(Drawing.Kind.STROKE,"minecraft:overworld",color,uv,""));
        }
        return List.copyOf(output);
    }
}
