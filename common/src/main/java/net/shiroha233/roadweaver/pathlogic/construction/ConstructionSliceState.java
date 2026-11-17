/* 文件职责：单片段可变施工状态与结案条件，不携带世界、配置或持久化服务。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.function.IntUnaryOperator;

/** 高度可能被隧道退让改写，必须由本次区块执行持有；暂停和失败不能记作施工完成。 */
public final class ConstructionSliceState {
    private final ConstructionStationCursor cursor;
    private final boolean[] refused;
    private boolean completed=true;
    // 隧道拒绝会原位写回地表高度，数组只属本次片段，不能在多个区块间共享。
    private int[] profile;
    public void profile(int[] value){profile=value;}
    public int[] profile(){return profile;}
    public ConstructionSliceState(int stations,int count,IntUnaryOperator stationAt) {
        refused=new boolean[stations];cursor=new ConstructionStationCursor(count,stationAt);
    }
    public ConstructionStationCursor cursor(){return cursor;}
    public void fail(){completed=false;}
    public void refuse(int station){refused[station]=true;completed=false;}
    public boolean refused(int station){return refused[station];}
    /** 业务成功和遍历结案是两个条件，暂停/取消/抛错不能被误写入完成台账。 */
    public boolean completed(){return completed && cursor.state()==ConstructionStationCursor.State.COMPLETE;}
}

