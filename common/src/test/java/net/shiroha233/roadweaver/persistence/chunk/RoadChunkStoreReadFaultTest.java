/* 文件职责：钉住「一条道路文件瞬时读不到，不许把索引永久改写」这条存储契约。 */
package net.shiroha233.roadweaver.persistence.chunk;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.shiroha233.roadweaver.core.model.RoadData;
import net.shiroha233.roadweaver.core.model.RoadSegmentPlacement;
import net.shiroha233.roadweaver.pathlogic.validation.RoadDataValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P0-1 回归：<b>一次短暂的文件读失败，不许把道路永久从索引里剔除。</b>
 *
 * <h2>缺陷（2026-11-15 外部独立审计抓出，已修）</h2>
 * <p>{@code RoadChunkStore} 的单条读失败路径原本是：</p>
 * <pre>
 *   catch (IOException) { skippedEntry = true; continue; }   // 读失败 = 当成「这条没了」
 *   … skippedEntry ⇒ rewriteMetadata = true
 *   if (rewriteMetadata &amp;&amp; !readOnlyDegraded) persistSnapshot(...)   // 写索引
 *   … 用的还是剔掉失败条目的那份部分快照
 * </pre>
 * <p>后果：磁盘抖动 / 文件被占用 / 权限问题导致某份 {@code .nbt} 这一刻读不到，
 * {@code index.json} 就被改写成「少一条」，而 <b>那份 .nbt 一直好好躺在磁盘上</b>
 * —— 道路从此不再被查询、不再显示、不再参与施工，只能人工恢复。</p>
 *
 * <h2>为什么保护是不对称的（根因）</h2>
 * <p>{@code readOnlyDegraded} 只保护「<b>整个索引</b>读失败」，源码里那段注释写得很好：
 * 「IO 失败不代表数据不存在，恰恰最不该把它当成空库」。
 * 但「<b>单条</b> NBT 读失败」只是循环里一个 {@code continue}，没走这条保护。</p>
 *
 * <h2>修法与本节守的东西</h2>
 * <p>把「读失败」从 {@code skippedEntry} 里拆出来单独标记，让上层进只读降级（拒绝一切写入）。
 * 读路径不变（查询继续用部分视图，不崩），索引保持原样，等下一次能完整读出时自然恢复。</p>
 * <p>⇒ 本类断言三件事：<b>①故障期间索引一个字节都不许改；②故障期间读视图允许少一条
 * （只读降级不是读挂）；③解锁后重新打开必须重新看到两条。</b></p>
 *
 * <h2>平台限制（如实标注）</h2>
 * <p>故障注入用的是 Windows 的 {@code NOSHARE_READ}（独占共享模式）。
 * 非 Windows 上该 API 抛 {@code UnsupportedOperationException}，
 * 本用例会 {@code assumeTrue} 跳过 —— <b>所以非 Windows 环境这一条不提供保护</b>。</p>
 */
class RoadChunkStoreReadFaultTest {

    /** 沿 X 轴的 2 格站距直路（满足 {@code MAX_CENTER_STEP=2}，可通过 isValidFinalRoad）。 */
    private static RoadData road(int z, long owner) {
        List<RoadSegmentPlacement> segments = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BlockPos center = new BlockPos(i * 2, 64, z);
            segments.add(new RoadSegmentPlacement(center, List.of(center)));
            heights.add(64);
        }
        return new RoadData(4, 1, List.of(), List.of(), segments, List.of(), heights, owner, owner + 1);
    }

    @Test
    void 单条道路读失败时索引必须原样不动_解锁后必须重新看到两条(@TempDir Path root) throws Exception {
        RoadData first = road(0, 1L);
        RoadData second = road(40, 3L);
        assertTrue(RoadDataValidator.isValidFinalRoad(first), "夹具必须合法，否则测的是别的东西");
        assertTrue(RoadDataValidator.isValidFinalRoad(second), "夹具必须合法");

        // ① 建库：写两条
        try (RoadChunkStore store = new RoadChunkStore(root)) {
            store.addRoads(List.of(first, second));
            assertEquals(2, store.loadAll().size(), "建库后必须能看到两条");
        }

        Path index = root.resolve("index.json");
        Path roadDir = root.resolve("roads");
        String indexBefore = Files.readString(index);

        Path target;
        try (Stream<Path> files = Files.list(roadDir)) {
            target = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        long targetSize = Files.size(target);

        // ② 故障注入：独占读锁住其中一份 NBT
        boolean faultInjected = false;
        try (FileChannel lock = FileChannel.open(target,
                StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            faultInjected = true;
            // 确认锁真的挡住了读（挡不住说明这条用例没有判别力）
            boolean denied;
            try {
                Files.readAllBytes(target);
                denied = false;
            } catch (IOException expected) {
                denied = true;
            }
            assertTrue(denied, "★ 故障没注入成功：文件仍能读 ⇒ 下面的断言会变成恒真");

            // ③ 故障期间打开：允许只看到一条（只读降级不是读挂）
            try (RoadChunkStore during = new RoadChunkStore(root)) {
                assertEquals(1, during.loadAll().size(),
                        "故障期间读视图允许少一条（这是只读降级，不是读失败）");
                // ★★ 核心断言：索引一个字节都不许改
                assertFalse(!Files.readString(index).equals(indexBefore),
                        "★ P0-1 复活：读故障期间 index.json 被改写了 —— "
                                + "那条道路会永久从索引消失，而它的 .nbt 还在磁盘上");
            }
        } finally {
            if (!faultInjected) {
                assumeTrue(false, "本平台不支持 NOSHARE_READ 独占锁，跳过故障注入（非 Windows）");
            }
        }

        assertEquals(targetSize, Files.readAllBytes(target).length, "解锁后原 NBT 必须仍可读且长度不变");

        // ④ 解锁后重新打开：必须重新看到两条
        try (RoadChunkStore after = new RoadChunkStore(root)) {
            assertEquals(2, after.loadAll().size(),
                    "★ P0-1 复活：解锁后仍只见一条 ⇒ 那条道路已被永久剔除（不可恢复）");
        }
        assertEquals(indexBefore, Files.readString(index), "全程结束后索引必须与初始逐字相同");
    }
}