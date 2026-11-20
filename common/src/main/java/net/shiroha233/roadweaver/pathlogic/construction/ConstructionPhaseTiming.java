/* 文件职责：在显式诊断时量真实施工事务各阶段，诊断关闭时直接使用原端口。 */
package net.shiroha233.roadweaver.pathlogic.construction;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 计时不拥有计划、租约或结案；观测失败不能改变已经发生的世界副作用。 */
final class ConstructionPhaseTiming<P> implements ConstructionChunkTransaction.Access<P> {
    enum Phase { PLAN_READ, PREPARE, PAINT, REPAIR, DECORATE, LEDGER, FINISH, RELEASE }
    record Measurement(String context,boolean failed,Map<Phase,Long> nanos,Map<Phase,Integer> calls,long totalNanos) {}
    private static final Logger LOGGER=LoggerFactory.getLogger("roadweaver");
    private static final boolean ENABLED="on".equalsIgnoreCase(
            System.getProperty("roadweaver.constructionPhaseTimings","off"));
    private final ConstructionChunkTransaction.Access<P> delegate;
    private final String context;
    private final LongSupplier clock;
    private final Consumer<Measurement> sink;
    private final EnumMap<Phase,Long> nanos=new EnumMap<>(Phase.class);
    private final EnumMap<Phase,Integer> calls=new EnumMap<>(Phase.class);
    private final long started;
    private boolean failed,published;

    static <P> ConstructionChunkTransaction.Access<P> wrap(ConstructionChunkTransaction.Access<P> access,String context) {
        return wrap(access,context,ENABLED,System::nanoTime,ConstructionPhaseTiming::log);
    }

    static <P> ConstructionChunkTransaction.Access<P> wrap(ConstructionChunkTransaction.Access<P> access,String context,
            boolean enabled,LongSupplier clock,Consumer<Measurement> sink) {
        // 关闭时不分配计数表，也不调用时钟或构造逐阶段lambda。
        return enabled?new ConstructionPhaseTiming<>(access,context,clock,sink):access;
    }

    private ConstructionPhaseTiming(ConstructionChunkTransaction.Access<P> delegate,String context,
            LongSupplier clock,Consumer<Measurement> sink) {
        this.delegate=delegate;this.context=context;this.clock=clock;this.sink=sink;
        started=clock.getAsLong();
    }

    private <T> T measure(Phase phase,Supplier<T> operation) {
        long start=clock.getAsLong();
        try { return operation.get(); }
        catch(RuntimeException|Error failure) { failed=true;throw failure; }
        finally {
            nanos.merge(phase,clock.getAsLong()-start,Long::sum);
            calls.merge(phase,1,Integer::sum);
        }
    }
    private void measure(Phase phase,Runnable operation) { measure(phase,()->{operation.run();return null;}); }

    public P loadInitial() { return measure(Phase.PLAN_READ,delegate::loadInitial); }
    public boolean isEmpty(P plan) { return measure(Phase.PLAN_READ,()->delegate.isEmpty(plan)); }
    public void emptyPlan() { measure(Phase.PLAN_READ,delegate::emptyPlan); }
    public void prepare(P plan) { measure(Phase.PREPARE,()->delegate.prepare(plan)); }
    public void process(P plan) { measure(Phase.PAINT,()->delegate.process(plan)); }
    public boolean processSlice(P plan) { return measure(Phase.PAINT,()->delegate.processSlice(plan)); }
    public boolean decorateSlice() { return measure(Phase.DECORATE,delegate::decorateSlice); }
    public boolean ownerAlive() { return delegate.ownerAlive(); }
    public boolean isCurrent(P plan) { return delegate.isCurrent(plan); }
    public boolean repairRefusals() { return measure(Phase.REPAIR,delegate::repairRefusals); }
    public P loadRefreshed() { return measure(Phase.PLAN_READ,delegate::loadRefreshed); }
    public void resetRefusals() { measure(Phase.REPAIR,delegate::resetRefusals); }
    public void decorate() { measure(Phase.DECORATE,delegate::decorate); }
    public void commitCompleted() { measure(Phase.LEDGER,delegate::commitCompleted); }
    public boolean finish(P plan,boolean repaired) { return measure(Phase.FINISH,()->delegate.finish(plan,repaired)); }
    public void release() {
        try { measure(Phase.RELEASE,delegate::release); }
        finally {
            if(!published) {
                published=true;
                var result=new Measurement(context,failed,Map.copyOf(nanos),Map.copyOf(calls),clock.getAsLong()-started);
                try { sink.accept(result); }
                catch(RuntimeException diagnosticFailure) {
                    LOGGER.warn("【施工计时输出失败，保留原施工结果】 chunk={} failure={}",context,diagnosticFailure.toString());
                }
            }
        }
    }

    private static void log(Measurement m) {
        // returned只说明同步调用正常返回，台账中的终态放弃也可正常返回，不能写成“建成”。
        LOGGER.info("【道路施工阶段耗时，正常返回不代表全部建成】 [CHUNK-PHASE-TIMING] chunk={} outcome={} planReadUs={} prepareUs={} paintUs={} repairUs={} decorateUs={} ledgerUs={} finishUs={} releaseUs={} totalUs={}",
                m.context(),m.failed()?"exception":"returned",us(m,Phase.PLAN_READ),us(m,Phase.PREPARE),
                us(m,Phase.PAINT),us(m,Phase.REPAIR),us(m,Phase.DECORATE),us(m,Phase.LEDGER),
                us(m,Phase.FINISH),us(m,Phase.RELEASE),m.totalNanos()/1000);
    }
    private static long us(Measurement m,Phase phase) { return m.nanos().getOrDefault(phase,0L)/1000; }
}
