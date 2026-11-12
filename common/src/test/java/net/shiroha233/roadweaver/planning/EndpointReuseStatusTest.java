package net.shiroha233.roadweaver.planning;

import net.shiroha233.roadweaver.core.model.ConnectionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享端点复用的状态判据与接入半径（0.9.082）。
 *
 * <p>真机取证（存档 20261111 1704）：24 条初始连接失败 4 条，而接入逻辑
 * {@code attaching failed connection} 恰好 0 次 —— 因为它只挂在
 * {@code generateTaskResult} 上，初始生成根本不走那条路径。同一份日志里
 * {@code existing-road endpoint candidate} 也是 0 次：原判据只认
 * {@code status() == COMPLETED}，而 24 条路是并行生成的，处理第 2 条时
 * 第 1 条还是 PLANNED。</p>
 */
class EndpointReuseStatusTest {

    @Test
    void settledRoutesAreReusable() {
        assertTrue(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.COMPLETED));
        assertTrue(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.PROTECTED_GAP),
                "PROTECTED_GAP 是有路但穿村留缺口，路本体已存在，可以接");
    }

    @Test
    void sameBatchRoutesAreReusable() {
        // 0.9.082 的核心放宽：同批次并行生成的 PLANNED/GENERATING 也能被复用
        assertTrue(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.PLANNED),
                "并行生成时先完成规划的邻居还是 PLANNED，不放宽就永远看不到它");
        assertTrue(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.GENERATING));
    }

    @Test
    void failedOrFilteredRoutesAreNeverReusable() {
        assertFalse(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.FAILED),
                "失败的路没有本体，接上去就是断头路");
        assertFalse(ExistingRoadEndpointReuse.isReusableStatus(ConnectionStatus.FILTERED));
        assertFalse(ExistingRoadEndpointReuse.isReusableStatus(null));
    }

    @Test
    void approachRadiusCoversParallelPlanningDistance() {
        // 12 格是「路已建好只差几格接上」的口径；两条同时规划的路在村口相距几十格
        assertEquals(48, ExistingRoadEndpointReuse.APPROACH_THRESHOLD,
                "接入点搜索半径必须覆盖两条并行规划路的实际间距");
    }
}
