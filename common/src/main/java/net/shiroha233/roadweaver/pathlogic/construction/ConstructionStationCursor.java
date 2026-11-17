/* 文件职责：保存单片段站序和可恢复推进位置，暂停不是完成，也不跨tick持有世界或保护资源。 */
package net.shiroha233.roadweaver.pathlogic.construction;
import java.util.function.*;

/** 失败后不可重放已有副作用；换代取消由所有者提供，新代次必须新建执行状态。 */
public final class ConstructionStationCursor {
    public enum State { READY, COMPLETE, CANCELLED, FAILED }
    private final int[] indices;
    private int next;
    private State state;
    public ConstructionStationCursor(int count, IntUnaryOperator stationAt) {
        if(count<0)throw new IllegalArgumentException("negative station count");
        indices=new int[count];for(int i=0;i<count;i++)indices[i]=stationAt.applyAsInt(i);
        state=count==0?State.COMPLETE:State.READY;
    }
    /** 只在完整站操作之间让出，保持一站内随机消费/拒绝/落块顺序；异常后禁止自动重试本站。 */
    public int advance(int limit, BooleanSupplier valid, IntConsumer operation) {
        int attempted=0;
        while(state==State.READY && attempted<Math.max(0,limit)) {
            if(!valid.getAsBoolean()){state=State.CANCELLED;break;}
            try {operation.accept(indices[next]);}
            catch(RuntimeException|Error failure){state=State.FAILED;throw failure;}
            next++;attempted++;
            if(next==indices.length)state=State.COMPLETE;
        }
        return attempted;
    }
    /** 世界生成沿原顺序同步跑完；此调用不启用跨tick暂停，资源仍在外层finally释放。 */
    public void runAll(IntConsumer operation) {advance(Integer.MAX_VALUE,()->true,operation);}
    public State state(){return state;}
    public int nextOffset(){return next;}
}

