package net.shiroha233.roadweaver.planning.terrain;

import net.shiroha233.roadweaver.config.sub.TerrainSamplingMode;
import org.junit.jupiter.api.Test;

import static net.shiroha233.roadweaver.planning.terrain.RoadTerrainPlanningPort.InitialSolvePass.*;
import static org.junit.jupiter.api.Assertions.*;

final class InitialBandHandoffTest {
    @Test
    void capturesAcrossPlanningRoundsButConsumesOnlyOnceInTheSameLevelSession() {
        var session = new TerrainSamplingSession(TerrainSamplingMode.COARSE_CORRIDOR);
        assertNull(session.initialBand(REPLAY), "断点续跑、尚未求过解时不能复用");
        var captured = session.initialBand(CAPTURE);
        assertNotNull(captured);
        assertSame(captured, session.initialBand(CAPTURE), "开局可能自适应扩窗多轮");
        assertNull(session.initialBand(NONE), "游玩期不能读开局未交付的解");
        assertNull(new TerrainSamplingSession(TerrainSamplingMode.COARSE_CORRIDOR).initialBand(REPLAY));
        assertSame(captured, session.initialBand(REPLAY));
        assertNull(session.initialBand(REPLAY), "候选重试不能再次接管首轮求解");
        assertNotSame(captured, session.initialBand(CAPTURE));
        session.clearInitialBand();
        assertNull(session.initialBand(REPLAY), "中止和空任务需要丢弃本轮结果");
    }
}
