/* 文件职责：持有完整区块施工阶段、计划证据和释放责任，暂停不得提前写完成台账。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.Objects;

/** 只在端口返回后协作让出；默认同步端口的一次铺设仍可能很慢，不承诺硬实时。 */
public final class ConstructionTransactionCursor<P> {
    public enum Stage { LOAD, PREPARE, PAINT, REPAIR, REFRESH, RESET, REPAINT, DECORATE, COMMIT,
        CLOSED, CANCELLED, FAILED }
    private final ConstructionChunkTransaction.Access<P> access;
    private final Thread owner = Thread.currentThread();
    private Stage stage = Stage.LOAD;
    private P checked, evidence;
    private boolean repaired, result, released;

    public ConstructionTransactionCursor(ConstructionChunkTransaction.Access<P> access) {
        this.access = Objects.requireNonNull(access);
    }
    public Stage stage() { return stage; }
    public boolean terminal() { return stage == Stage.CLOSED || stage == Stage.CANCELLED || stage == Stage.FAILED; }

    /** budget计算实际阶段/切片尝试次数，不把一次空转当作施工完成。 */
    public boolean advance(int budget) {
        checkThread();
        if (budget <= 0) throw new IllegalArgumentException("施工阶段预算必须为正数");
        for (int used = 0; used < budget && !terminal(); used++) {
            try {
                // REFRESH专门接纳本事务刚发布的修复版本；其他阶段都核验最近读取的计划。
                if (!access.ownerAlive() || (checked != null && stage != Stage.REFRESH && !access.isCurrent(checked))) {
                    cancel(); break;
                }
                step();
            } catch (RuntimeException | Error failure) {
                stage = Stage.FAILED;
                release();
                throw failure;
            }
        }
        return terminal();
    }

    private void step() {
        switch (stage) {
            case LOAD -> {
                checked = evidence = access.loadInitial();
                if (access.isEmpty(checked)) { access.emptyPlan(); close(false); }
                else stage = Stage.PREPARE;
            }
            case PREPARE -> { access.prepare(checked); stage = Stage.PAINT; }
            case PAINT -> { if (access.processSlice(checked)) stage = Stage.REPAIR; }
            case REPAIR -> { repaired = access.repairRefusals(); stage = repaired ? Stage.REFRESH : Stage.DECORATE; }
            case REFRESH -> {
                checked = access.loadRefreshed();
                // 新鲜空计划仍保留旧证据和拒绝集合；checked独立记录当前版本供后续检查。
                if (access.isEmpty(checked)) stage = Stage.DECORATE;
                else { evidence = checked; stage = Stage.RESET; }
            }
            case RESET -> { access.resetRefusals(); stage = Stage.REPAINT; }
            case REPAINT -> { if (access.processSlice(checked)) stage = Stage.DECORATE; }
            case DECORATE -> { if (access.decorateSlice()) stage = Stage.COMMIT; }
            case COMMIT -> {
                // 不在台账与finish之间暂停，维持原结案顺序和一次性副作用。
                access.commitCompleted();
                close(access.finish(evidence, repaired));
            }
            default -> throw new IllegalStateException("已结案阶段不能执行: " + stage);
        }
    }

    private void close(boolean value) {
        result = value;
        stage = Stage.CLOSED;
        release();
    }
    public void cancel() {
        checkThread();
        if (terminal()) return;
        stage = Stage.CANCELLED;
        release();
    }
    private void release() {
        if (released) return;
        released = true;
        try { access.release(); }
        catch (RuntimeException | Error failure) { stage = Stage.FAILED; throw failure; }
        finally { checked = evidence = null; }
    }
    public boolean runAll() {
        checkThread();
        while (!terminal()) advance(Integer.MAX_VALUE);
        if (stage == Stage.FAILED) throw new IllegalStateException("失败事务不能重放");
        return stage == Stage.CLOSED && result;
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("施工事务只能由所属世界线程推进");
    }
}
