/* 文件职责：把已加载道路快照投影为中立地图契约，不从显示线程恢复存档。 */
package net.shiroha233.roadweaver.map;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.shiroha233.roadweaver.api.outgoing.RoadOverlayApi;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.persistence.chunk.RoadSnapshot;
import net.shiroha233.roadweaver.persistence.files.RoadFileStorage;
import net.shiroha233.roadweaver.util.WeakIdentityCache;

/** 版本按不可变快照身份分配，不拿可能碰撞的identityHashCode当地图失效依据。 */
public final class RoadOverlayProvider {
    private static final AtomicLong NEXT=new AtomicLong();
    private static final WeakIdentityCache<RoadSnapshot,Long> VERSIONS=new WeakIdentityCache<>(8);
    private static final WeakIdentityCache<RoadData,List<RoadOverlayApi.Line>> LINES=new WeakIdentityCache<>(128);
    private RoadOverlayProvider() {}
    public static void register(){RoadOverlayApi.register(RoadOverlayProvider::peek,player->
            player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    &&net.shiroha233.roadweaver.map.permission.MapAccessService.canOpenMap(serverPlayer));}
    private static RoadOverlayApi.Snapshot peek(Object world,int minX,int minZ,int maxX,int maxZ,int budget) {
        if(!(world instanceof ServerLevel level))return RoadOverlayApi.Snapshot.unavailable();
        var snapshot=RoadFileStorage.peekSnapshot(level);if(snapshot==null)return RoadOverlayApi.Snapshot.unavailable();
        long revision;
        synchronized(VERSIONS){var found=VERSIONS.get(snapshot);if(found==null){found=NEXT.incrementAndGet();VERSIONS.put(snapshot,found);}revision=found;}
        // 按预算惰性转换；视口太大时不先把后续所有道路扫描一遍再丢弃结果。
        Iterable<List<RoadOverlayApi.Line>> selected=()->snapshot.queryRect(minX,minZ,maxX,maxZ).stream().map(road->{
            var lines=LINES.get(road);if(lines==null){lines=RoadOverlayGeometry.convert(road);LINES.put(road,lines);}return lines;
        }).iterator();
        return RoadOverlayGeometry.window(selected,revision,minX,minZ,maxX,maxZ,budget);
    }
}
