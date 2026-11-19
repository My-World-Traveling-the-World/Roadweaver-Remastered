package net.shiroha233.roadweaver.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.world.level.ChunkPos;

/** 审计探针只改隔离临时目录，证明读故障恢复后的真实落盘结果。 */
public final class DeepAuditRoadProbe {
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createDirectories(Path.of("../.analysis_tmp").toAbsolutePath());
        Path directory = Files.createTempDirectory(scratch, "ledger-audit-");
        Path path = directory.resolve("constructed-chunks-v3.txt");
        ChunkPos first = new ChunkPos(-3, 7), second = new ChunkPos(9, -2), third = new ChunkPos(1, 1);
        var original = RoadConstructionLedger.load(path);
        original.markComplete(first, 101);
        original.markComplete(second, 202);
        RoadConstructionLedger.flush(original, () -> path);
        String before = Files.readString(path);
        var reopened = RoadConstructionLedger.load(path);
        require(reopened.isComplete(first, 101) && reopened.isComplete(second, 202), "夹具必须确实持久化两项");
        RoadConstructionLedger.State degraded;
        try (var locked = FileChannel.open(path, StandardOpenOption.READ,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ)) {
            boolean denied = false;
            try { Files.readAllBytes(path); } catch (IOException expected) { denied = true; }
            require(denied, "必须确认真实读故障，不能把无效文件锁当证据");
            degraded = RoadConstructionLedger.load(path);
            require(!degraded.isComplete(first, 101) && !degraded.isComplete(second, 202), "记录读取结果");
        }
        // 解除瞬时文件占用后继续同一会话，模拟后续另一个区块完成触发同步落盘。
        degraded.markComplete(third, 303);
        RoadConstructionLedger.flush(degraded, () -> path);
        var after = RoadConstructionLedger.load(path);
        System.out.println("AUDIT_LEDGER_READ_FAULT first=" + after.isComplete(first, 101)
                + " second=" + after.isComplete(second, 202) + " third=" + after.isComplete(third, 303)
                + " bytesUnchanged=" + before.equals(Files.readString(path)) + " file=" + path);
        require(!after.isComplete(first, 101) && !after.isComplete(second, 202) && after.isComplete(third, 303),
                "当前版本应复现：旧两项永久丢失，仅剩新一项");

        // 一条坏记录也不能把已经存在的其余合法完工项变成可重铺的空台账。
        Files.writeString(path, before + "entry=bad,0,77\n");
        var malformed = RoadConstructionLedger.load(path);
        malformed.markComplete(third, 303);
        RoadConstructionLedger.flush(malformed, () -> path);
        var afterMalformed = RoadConstructionLedger.load(path);
        System.out.println("AUDIT_LEDGER_PARSE_FAULT first=" + afterMalformed.isComplete(first, 101)
                + " second=" + afterMalformed.isComplete(second, 202) + " third=" + afterMalformed.isComplete(third, 303));
        require(!afterMalformed.isComplete(first, 101) && !afterMalformed.isComplete(second, 202), "坏行反例应复现");
        revisionGrowth();
        disabledRoadsBecomeTerminal();
    }
    private static void revisionGrowth() throws Exception {
        // 空的计划缓存也会永久持有每个受影响区块的版本号，检查容量是否真的受 LRU 约束。
        Class<?> sessionType=Class.forName("net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache$Session");
        var constructor=sessionType.getDeclaredConstructor();constructor.setAccessible(true);
        var session=constructor.newInstance();
        var invalidate=sessionType.getDeclaredMethod("invalidate",java.util.Collection.class);invalidate.setAccessible(true);
        var keys=new java.util.ArrayList<Long>();
        for(int i=0;i<5000;i++)keys.add(ChunkPos.asLong(i*10,0));
        invalidate.invoke(session,keys);
        var revisions=sessionType.getDeclaredField("chunkRevisions");revisions.setAccessible(true);
        var full=sessionType.getDeclaredField("nonEmptyPlans");full.setAccessible(true);
        var empty=sessionType.getDeclaredField("emptyPlans");empty.setAccessible(true);
        System.out.println("AUDIT_REVISION_GROWTH sourceChunks="+keys.size()+" revisions="+((java.util.Map<?,?>)revisions.get(session)).size()
                +" fullPlans="+((java.util.Map<?,?>)full.get(session)).size()+" emptyPlans="+((java.util.Map<?,?>)empty.get(session)).size());
        require(((java.util.Map<?,?>)revisions.get(session)).size()==125000,
                "当前版本应保留全部互不重叠的 25 邻域修订号");
    }
    private static void disabledRoadsBecomeTerminal() throws Exception {
        // 本分支只读维度标识，构造最小世界标识即可，绝不伪造高度、区块或落块成功。
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        var level=(net.minecraft.server.level.ServerLevel)unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
        var dimension=net.minecraft.world.level.Level.class.getDeclaredField("dimension");dimension.setAccessible(true);
        dimension.set(level,net.minecraft.world.level.Level.OVERWORLD);
        var cfg=new net.shiroha233.roadweaver.config.ModConfig();
        cfg.roadAppearance().setRoadsEnabled(false);
        var chunk=new ChunkPos(17,-9);
        var plan=net.shiroha233.roadweaver.worldgen.road.RoadWorldgenPlanCache.get(level,chunk,cfg);
        require(plan.slices().isEmpty(),"关闭道路时正式计划入口返回空计划");
        var replay=net.shiroha233.roadweaver.pathlogic.PathFeature.class.getDeclaredMethod("replayPlan",
                java.util.function.Supplier.class,java.util.function.Predicate.class);
        replay.setAccessible(true);
        var outcome=replay.invoke(null,(java.util.function.Supplier<Object>)()->plan,
                (java.util.function.Predicate<Object>)ignored->{throw new AssertionError("关闭时不应落块");});
        require(outcome==net.shiroha233.roadweaver.pathlogic.PathFeature.LoadedPlacement.EMPTY_PLAN,"空状态流向必须实际确认");
        // 与 runBackfill 对 EMPTY_PLAN 的分支一样，使用真实终态标记入口。
        net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService.markNoConstructionRequired(level,
                java.util.List.of(chunk.toLong()));
        cfg.roadAppearance().setRoadsEnabled(true);
        boolean terminal=net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService
                .isMarkedNoConstructionRequired(level,chunk.toLong());
        System.out.println("AUDIT_DISABLED_ROADS outcome="+outcome+" enabledAgain="+cfg.roadAppearance().roadsEnabled()
                +" stillTerminal="+terminal);
        require(terminal,"当前代码会保留终态直到额外解标或世界关闭");
        net.shiroha233.roadweaver.generation.DeferredInitialRoadConstructionService.clear(level);
    }
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
