/* 文件职责：为倾斜地图提供真实高度的独立线段，不改变原二维地图契约。 */
package net.shiroha233.roadweaver.map;

import java.util.*;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi.*;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.pathlogic.construction.ConstructionSpanMasks;

/** 复用施工跨度掩码，缺口和让给捐赠路的区间不得被显示层重新连起来。 */
public final class RoadOverlayGeometry {
    private RoadOverlayGeometry() {}
    public static List<Line> convert(RoadData road) {
        var stations=road.roadSegmentList();if(stations.size()<2)return List.of();
        var masks=ConstructionSpanMasks.of(road,0);
        var result=new ArrayList<Line>();var run=new ArrayList<Point>();Kind current=null;
        for(int i=1;i<stations.size();i++) {
            if(masks.networkReuseAt(i-1)||masks.networkReuseAt(i)||masks.protectedGapAt(i-1)||masks.protectedGapAt(i)) {
                append(result,current,run);current=null;continue;
            }
            Kind kind=masks.tunnelAt(i-1)||masks.tunnelAt(i)?Kind.TUNNEL:
                    masks.directCrossingAt(i-1)||masks.directCrossingAt(i)?Kind.CROSSING:
                    masks.preserveGradeAt(i-1)||masks.preserveGradeAt(i)?Kind.BRIDGE:Kind.ROAD;
            if(current!=kind){append(result,current,run);current=kind;}
            var a=point(road,i-1);var b=point(road,i);
            if(run.isEmpty())run.add(a);run.add(b);
        }
        append(result,current,run);return List.copyOf(result);
    }
    private static Point point(RoadData road,int index) {
        var p=road.roadSegmentList().get(index).middlePos();
        return new Point(p.getX(),index<road.targetY().size()?road.targetY().get(index):p.getY(),p.getZ());
    }
    private static void append(List<Line> result,Kind kind,List<Point> run) {
        if(run.size()>=2)result.add(new Line(kind,compact(run)));run.clear();
    }
    /** 只删三维同向共线站，不用近似误差阈值；保留坡折、回头和桥隧边界且减少地图投影量。 */
    private static List<Point> compact(List<Point> points) {
        var result=new ArrayList<Point>();
        for(var point:points){
            while(result.size()>=2){
                var a=result.get(result.size()-2);var b=result.getLast();
                long x=(long)b.x()-a.x(),y=(long)b.y()-a.y(),z=(long)b.z()-a.z();
                long xx=(long)point.x()-b.x(),yy=(long)point.y()-b.y(),zz=(long)point.z()-b.z();
                if(x*yy!=y*xx||x*zz!=z*xx||y*zz!=z*yy||x*xx+y*yy+z*zz<0)break;
                result.removeLast();
            }
            result.add(point);
        }
        return result;
    }
    public static Snapshot window(Iterable<List<Line>> roads,long revision,int minX,int minZ,int maxX,int maxZ,int budget) {
        int lowX=Math.min(minX,maxX),highX=Math.max(minX,maxX),lowZ=Math.min(minZ,maxZ),highZ=Math.max(minZ,maxZ);
        var output=new ArrayList<Line>();int used=0;
        for(var road:roads)for(var line:road) {
            var run=new ArrayList<Point>();
            for(int i=1;i<line.points().size();i++) {
                var a=line.points().get(i-1);var b=line.points().get(i);
                boolean intersects=Math.max(a.x(),b.x())>=lowX&&Math.min(a.x(),b.x())<=highX
                        &&Math.max(a.z(),b.z())>=lowZ&&Math.min(a.z(),b.z())<=highZ;
                if(!intersects){used+=run.size();append(output,line.kind(),run);continue;}
                int needed=run.isEmpty()?2:1;
                if(used+run.size()+needed>budget){
                    append(output,line.kind(),run);
                    return new Snapshot(output.isEmpty()?State.EMPTY:State.READY,revision,true,output);
                }
                if(run.isEmpty())run.add(a);run.add(b);
            }
            used+=run.size();append(output,line.kind(),run);
        }
        return new Snapshot(output.isEmpty()?State.EMPTY:State.READY,revision,false,output);
    }
}
