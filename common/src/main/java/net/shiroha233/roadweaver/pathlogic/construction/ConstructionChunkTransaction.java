/* 文件职责：拥有同步区块施工的事务次序，拒绝半写重放，最终释放与成功结案分别守卫。 */
package net.shiroha233.roadweaver.pathlogic.construction;

/** 同步生产门面保留；阶段游标可离线暂停，运行期跨tick租约仍由后续调度器接入。 */
public final class ConstructionChunkTransaction<P> {
    /** CLOSED表示本次调用结束，包含正常空计划或既定终态放弃，不代表道路完整落成。 */
    public enum State { NEW, RUNNING, CLOSED, CANCELLED, FAILED }
    public interface Access<P> {
        P loadInitial();
        boolean isEmpty(P plan);
        void emptyPlan();
        void prepare(P plan);
        void process(P plan);
        boolean repairRefusals();
        P loadRefreshed();
        void resetRefusals();
        void decorate();
        void commitCompleted();
        boolean finish(P evidencePlan, boolean gapRepairApplied);
        void release();
        /** 默认保持原同步能力；未来回填端口才能在完整站/装饰之间返回未结束。 */
        default boolean processSlice(P plan) { process(plan); return true; }
        default boolean decorateSlice() { decorate(); return true; }
        /** 调度端分别验证世界/区块租约与不可变计划版本，禁止半写旧版继续结案。 */
        default boolean ownerAlive() { return true; }
        default boolean isCurrent(P plan) { return true; }
    }
    private final Access<P> access;
    private State state=State.NEW;
    public ConstructionChunkTransaction(Access<P> access) { this.access=java.util.Objects.requireNonNull(access); }
    /** 同一对象只执行一次，异常后不能重放已有落块；原调用方是否创建新事务重试仍按既有政策。 */
    public boolean run() {
        return run(null);
    }
    /** 显式诊断才包装真实端口；保留未开启计时的原事务次序。 */
    public boolean run(String context) {
        return execute(ConstructionPhaseTiming.wrap(access,context));
    }
    private boolean execute(Access<P> access) {
        if(state!=State.NEW)throw new IllegalStateException("区块施工事务不能重复执行: "+state);
        state=State.RUNNING;
        var cursor=new ConstructionTransactionCursor<>(access);
        try {
            boolean result=cursor.runAll();
            state=cursor.stage()==ConstructionTransactionCursor.Stage.CANCELLED?State.CANCELLED:State.CLOSED;
            return result;
        } catch(RuntimeException|Error failure) {
            state=State.FAILED;throw failure;
        }
    }
    public State state() { return state; }
}

