/* 文件职责：隔离屏幕与世界的道路请求，拒绝已经结束的查询结果。 */
package net.shiroha233.roadweaver.client.fabric.diorama;

import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi;

/** 请求和结果都归属具体屏幕及世界，晚到结果不能把另一存档路线画进当前地图。 */
final class RoadOverlayState {
    record View(RoadOverlayApi.Snapshot snapshot,ViewportBounds bounds) {}
    private Object world;
    private long epoch;
    private boolean pending;
    private View view=new View(RoadOverlayApi.Snapshot.unavailable(),null);
    synchronized long begin(Object currentWorld) {
        if(world!=currentWorld){world=currentWorld;epoch++;pending=false;view=new View(RoadOverlayApi.Snapshot.unavailable(),null);}
        if(pending)return -1;
        pending=true;return epoch;
    }
    synchronized void complete(Object currentWorld,long requestEpoch,RoadOverlayApi.Snapshot next,ViewportBounds bounds) {
        if(world!=currentWorld||epoch!=requestEpoch)return;
        // 结果必须和它实际查询的窗口一起发布，否则拖动期间会误用新视口缓存旧结果。
        pending=false;view=new View(next,bounds);
    }
    synchronized void clear() {world=null;epoch++;pending=false;view=new View(RoadOverlayApi.Snapshot.unavailable(),null);}
    synchronized View view(){return view;}
}
