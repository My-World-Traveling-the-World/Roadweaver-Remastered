package net.shiroha233.roadweaver.persistence.files;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.8.355：开局预热断点（{@code checkpoint.txt}）的解析契约。
 *
 * <p>这一版把断点从「两态标记」升级为「阶段 + 进度」，玩家崩溃/蓝屏后可以续接，
 * 而不是把几小时的预热全部重跑。旧存档的 {@code schema=1} 文件必须仍能读取
 * （解析为「无阶段」→ 调用方按从头开始处理），否则老世界会加载失败。</p>
 */
final class InitialGenerationCheckpointStoreTest {

    @Test
    void legacySchemaWithoutPhaseFallsBackToRestart() {
        // 旧版真实文件（本机存档实证）只有这三行。
        Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.parseResumePoint(List.of(
                        "schema=1",
                        "state=ACTIVE",
                        "updatedAtSeconds=1789202584"));

        assertTrue(point.isPresent(), "legacy checkpoint must still parse");
        assertFalse(point.orElseThrow().hasPhase(),
                "legacy checkpoint has no phase -> caller restarts from scratch (0.8.355 之前的行为)");
        assertEquals(0, point.orElseThrow().done());
        assertEquals(0, point.orElseThrow().total());
    }

    @Test
    void schemaTwoResumePointCarriesPhaseAndProgress() {
        Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.parseResumePoint(List.of(
                        "schema=2",
                        "state=ACTIVE",
                        "phase=ROAD_GENERATION",
                        "done=40",
                        "total=119",
                        "updatedAtSeconds=1789202600"));

        assertTrue(point.isPresent());
        assertEquals("ROAD_GENERATION", point.orElseThrow().phaseName());
        assertEquals(40, point.orElseThrow().done());
        assertEquals(119, point.orElseThrow().total());
        assertTrue(point.orElseThrow().hasPhase());
    }

    @Test
    void completedCheckpointParsesWithoutPhase() {
        // markCompleted 写入的形态：state=COMPLETED 且不带 phase。
        Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.parseResumePoint(List.of(
                        "schema=2",
                        "state=COMPLETED",
                        "updatedAtSeconds=1789202600"));

        assertTrue(point.isPresent());
        assertFalse(point.orElseThrow().hasPhase(),
                "a completed run is never resumed");
    }

    @Test
    void malformedNumbersFallBackToZeroInsteadOfThrowing() {
        // 断点文件可能被外部工具改坏或被截断；解析必须绝不抛异常。
        Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.parseResumePoint(List.of(
                        "schema=2",
                        "state=ACTIVE",
                        "phase=COARSE_SAMPLING",
                        "done=not-a-number",
                        "total=-5"));

        assertTrue(point.isPresent());
        assertEquals("COARSE_SAMPLING", point.orElseThrow().phaseName());
        assertEquals(0, point.orElseThrow().done(), "malformed done -> 0");
        assertEquals(0, point.orElseThrow().total(), "negative total -> 0");
    }

    @Test
    void blankPhaseIsTreatedAsNoPhase() {
        Optional<InitialGenerationCheckpointStore.ResumePoint> point =
                InitialGenerationCheckpointStore.parseResumePoint(List.of(
                        "schema=2",
                        "state=ACTIVE",
                        "phase=",
                        "done=3",
                        "total=9"));

        assertTrue(point.isPresent());
        assertFalse(point.orElseThrow().hasPhase(), "empty phase must not be treated as resumable");
    }

    @Test
    void emptyOrNullInputIsEmpty() {
        assertTrue(InitialGenerationCheckpointStore.parseResumePoint(List.of()).isEmpty());
        assertTrue(InitialGenerationCheckpointStore.parseResumePoint(null).isEmpty());
    }
}
