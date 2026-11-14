/* 文件职责：吸附判定用的小载体 —— 最近点结果与连接候选（包内可见）。 */
package net.shiroha233.roadweaver.postprocess;

import net.shiroha233.roadweaver.core.model.RoadData;
import static net.shiroha233.roadweaver.postprocess.NetworkReuseMerger.*;
import static net.shiroha233.roadweaver.postprocess.EndpointApproachConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionConnector.*;
import static net.shiroha233.roadweaver.postprocess.JunctionTrimOps.*;
import static net.shiroha233.roadweaver.postprocess.EndpointConnectOps.*;

/**
 * 吸附判定用的小载体。
 *
 * <p>两个 record 放一起：{@code NearestResult} 是「最近点查询」的返回，
 * {@code ConnectionOption} 是「连接候选」——后者带一个<b>排序谓词</b>
 * （{@code betterThan}），用来在多个候选里挑最好的那个。</p>
 *
 * <p>从 {@code RoadSnapService} 里纯搬迁出来的，逻辑未改。</p>
 */
record NearestResult(int index, long dist2) {
}

record ConnectionOption(String mode,
                                RoadData road,
                                double constructionLength,
                                int reuseStations) {
    boolean betterThan(ConnectionOption other) {
        int reuseOrder = Integer.compare(reuseStations, other.reuseStations);
        if (reuseOrder != 0) return reuseOrder > 0;
        int constructionOrder = Double.compare(constructionLength, other.constructionLength);
        if (constructionOrder != 0) return constructionOrder < 0;
        int segmentOrder = Integer.compare(road.roadSegmentList().size(),
                other.road.roadSegmentList().size());
        if (segmentOrder != 0) return segmentOrder < 0;
        return mode.compareTo(other.mode) < 0;
    }
}
