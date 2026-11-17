/* 文件职责：施工只在区块边界让出主线程，单块内部不强制中断。 */
package net.shiroha233.roadweaver.util;
import java.util.function.LongSupplier;
/** 每轮至少允许一次尝试；不能用预算耗尽冒充施工失败或完成。 */
public final class ConstructionTimeBudget {
    public static final long DEFAULT_NANOS = 4_000_000L;
    private final LongSupplier clock;
    private final long started;
    private final long limit;
    /** 默认预算集中在一处，使生产与离线时钟用同一上限。 */
    public ConstructionTimeBudget(LongSupplier clock) { this(clock, DEFAULT_NANOS); }
    public ConstructionTimeBudget(LongSupplier clock, long limit) {
        this.clock = clock;
        this.limit = Math.max(1,limit);
        this.started = clock.getAsLong();
    }
    public boolean exhausted() { return clock.getAsLong() - started >= limit; }
}
