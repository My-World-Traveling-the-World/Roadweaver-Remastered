/* 文件职责：拥有同步区块施工的事务次序，拒绝半写重放，最终释放与成功结案分别守卫。 */
package net.shiroha233.roadweaver.pathlogic.construction;

/** 端口只搬世界边界；不把同步事务当作已具备跨tick租约、卸载或计划修订隔离。 */
final class FrozenConstructionTransaction<P> {
    /** CLOSED表示本次调用结束，包含正常空计划或既定终态放弃，不代表道路完整落成。 */
    public enum State { NEW, RUNNING, CLOSED, FAILED }
    private final ConstructionChunkTransaction.Access<P> access;
    private State state=State.NEW;
    public FrozenConstructionTransaction(ConstructionChunkTransaction.Access<P> access) { this.access=java.util.Objects.requireNonNull(access); }
    /** 同一对象只执行一次，异常后不能重放已有落块；原调用方是否创建新事务重试仍按既有政策。 */
    public boolean run() {
        return run(null);
    }
    /** 显式诊断才包装真实端口；保留未开启计时的原事务次序。 */
    public boolean run(String context) {
        return execute(ConstructionPhaseTiming.wrap(access,context));
    }
    private boolean execute(ConstructionChunkTransaction.Access<P> access) {
        if(state!=State.NEW)throw new IllegalStateException("区块施工事务不能重复执行: "+state);
        state=State.RUNNING;
        try {
            P plan=access.loadInitial();
            if(access.isEmpty(plan)) { access.emptyPlan();return false; }
            access.prepare(plan);
            access.process(plan);
            P evidencePlan=plan;
            boolean repaired=access.repairRefusals();
            if(repaired) {
                P refreshed=access.loadRefreshed();
                // 新鲜空计划保持旧拒绝集合及证据；非空才重置并做第二轮，不能无限修复循环。
                if(!access.isEmpty(refreshed)) {
                    evidencePlan=refreshed;
                    access.resetRefusals();
                    access.process(refreshed);
                }
            }
            access.decorate();
            access.commitCompleted();
            return access.finish(evidencePlan,repaired);
        } catch(RuntimeException|Error failure) {
            state=State.FAILED;throw failure;
        } finally {
            try { access.release(); }
            catch(RuntimeException|Error failure) { state=State.FAILED;throw failure; }
            if(state==State.RUNNING)state=State.CLOSED;
        }
    }
    public State state() { return state; }
}
