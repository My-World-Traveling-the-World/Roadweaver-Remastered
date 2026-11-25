/* 文件职责：冻结生产拒绝时的完整数学输入，避免只录地形却无法复现锁高冲突。 */
package net.shiroha233.roadweaver.pathlogic.profile;

import java.util.Objects;

/** 诊断文件独立于道路存档；全长数组不截断，负高度和无桥面下界的哨兵按原值保存。 */
public record ProfileFailureSnapshot(int format, String dimension, String connection,
        long[] stationXZ, int[] terrain, boolean[] crossing, int[] pin, boolean[] pinnedStations,
        boolean[] mustBeCovered, int minCover, int[] deckFloor,
        int cut, int fill, int step, int perTwo, Conflict conflict) {
    /** 文件只携带拒绝见证的值，避免诊断格式把数学输入层反向绑定到施工异常类。 */
    public record Conflict(String reason,int station,int limitingStation,int requiredMinY,int reachableMaxY) {}
    public ProfileFailureSnapshot {
        if(format!=1)throw new IllegalArgumentException("未知的纵断面诊断格式");
        Objects.requireNonNull(dimension);Objects.requireNonNull(connection);Objects.requireNonNull(conflict);
        int n=terrain.length;
        if(n==0||stationXZ.length!=n||crossing.length!=n||pin.length!=n||pinnedStations.length!=n
                ||mustBeCovered.length!=n||deckFloor.length!=n)
            throw new IllegalArgumentException("失败快照必须保留每站的全部约束");
        // 求解器与调度器仍持有原数组；写盘以及离线重放都不能被后续改动污染。
        stationXZ=stationXZ.clone();terrain=terrain.clone();crossing=crossing.clone();pin=pin.clone();
        pinnedStations=pinnedStations.clone();mustBeCovered=mustBeCovered.clone();deckFloor=deckFloor.clone();
    }
    @Override public long[] stationXZ(){return stationXZ.clone();}
    @Override public int[] terrain(){return terrain.clone();}
    @Override public boolean[] crossing(){return crossing.clone();}
    @Override public int[] pin(){return pin.clone();}
    @Override public boolean[] pinnedStations(){return pinnedStations.clone();}
    @Override public boolean[] mustBeCovered(){return mustBeCovered.clone();}
    @Override public int[] deckFloor(){return deckFloor.clone();}
    public ProfileDpSource.Constraints constraints(){
        return new ProfileDpSource.Constraints(pin(),mustBeCovered(),minCover,deckFloor(),pinnedStations());
    }
    /** 这两种拒绝先于物理隧道选取；其他原因还需要现场隧道掩码，不能冒充纯数学重放。 */
    public boolean beforePhysicalSelection(){
        return conflict.reason().equals("LOCKED_HEIGHT")||conflict.reason().equals("COVER");
    }
}
