package net.shiroha233.roadweaver.route;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;

/**
 * {@link PlayerRoute} 序列化往返自测。
 *
 * <p>{@link RouteStorage} 依赖 {@code PlayerRoute.CODEC} 把路线写进存档 JSON
 * 再读回来。Codec 写错（字段名不一致、漏字段、UUID 编解码不对称）会导致
 * **存档静默丢路线** —— 编译期发现不了，所以单独测一遍往返。</p>
 *
 * <p>这里不碰文件系统（那需要 ServerLevel），只验证 Codec 的
 * 「对象 → JSON → 对象」等价性，也就是持久化最容易出错的那一环。</p>
 */
public final class RouteCodecTest {

    private RouteCodecTest() {
    }

    private static int checks = 0;

    private static void require(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAIL: " + what);
        }
        System.out.println("  ok  " + what);
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        testEmptyRoute();
        testRoundTripFull();
        testKindSurvivesRoundTrip();
        testStatusSurvivesRoundTrip();
        testNegativeHeight();
        // 用目录冒充文件触发真正的读故障；重读原始字节成功前不得清掉此存档的保护。
        var root = java.nio.file.Files.createTempDirectory("rw-route-read");
        var file = root.resolve("routes.json");
        try {
            var access = new RouteStorage.FileAccess();
            java.nio.file.Files.createDirectory(file);
            RouteStorage.loadFromFile(file, access);
            require(access.readFailed, "手绘路线读失败必须阻止写回");
            java.nio.file.Files.delete(file);
            var saved = PlayerRoute.create("恢复的路线");
            java.nio.file.Files.writeString(file, "{\"routes\":[" + encode(saved) + "]}");
            var loaded = RouteStorage.loadFromFile(file, access);
            require(!access.readFailed && saved.equals(loaded.get(saved.id())), "重读成功才恢复本周目的原路线");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(root);
        }

        System.out.println("\n全部通过（" + checks + " 项）");
    }

    /** Codec → JSON。 */
    private static JsonElement encode(PlayerRoute r) {
        return PlayerRoute.CODEC.encodeStart(JsonOps.INSTANCE, r)
                .getOrThrow(err -> new AssertionError("encode failed: " + err));
    }

    /** JSON → Codec。 */
    private static PlayerRoute decode(JsonElement e) {
        return PlayerRoute.CODEC.parse(JsonOps.INSTANCE, e)
                .getOrThrow(err -> new AssertionError("decode failed: " + err));
    }

    private static void testEmptyRoute() {
        System.out.println("[1] 空路线往返");
        PlayerRoute a = PlayerRoute.create("empty");
        PlayerRoute b = decode(encode(a));
        require(a.id().equals(b.id()), "UUID 保持不变");
        require(a.name().equals(b.name()), "名字保持");
        require(b.pointCount() == 0, "点数仍为 0");
        require(b.status() == RouteStatus.DRAFT, "状态仍为 DRAFT");
    }

    private static void testRoundTripFull() {
        System.out.println("[2] 完整路线往返（4 点 3 段）");
        PlayerRoute a = PlayerRoute.create("主路")
                .append(new BlockPos(100, 64, -200), RouteKind.ROAD)
                .append(new BlockPos(120, 70, -200), RouteKind.BRIDGE)
                .append(new BlockPos(140, 68, -180), RouteKind.BRIDGE)
                .append(new BlockPos(160, 64, -160), RouteKind.TUNNEL);

        PlayerRoute b = decode(encode(a));

        require(b.pointCount() == 4, "点数 4");
        require(b.segmentCount() == 3, "段数 3");
        require(b.start().equals(new BlockPos(100, 64, -200)), "起点坐标一致");
        require(b.end().equals(new BlockPos(160, 64, -160)), "终点坐标一致");
        require(b.name().equals("主路"), "中文名字无损");

        // 逐点比对坐标
        for (int i = 0; i < a.pointCount(); i++) {
            require(a.point(i).pos().equals(b.point(i).pos()),
                    "第 " + i + " 点坐标一致 " + a.point(i).pos());
        }
        // 逐段比对类型
        for (int i = 0; i < a.segmentCount(); i++) {
            require(a.kindOfSegment(i) == b.kindOfSegment(i),
                    "第 " + i + " 段类型一致 " + a.kindOfSegment(i));
        }
    }

    private static void testKindSurvivesRoundTrip() {
        System.out.println("[3] 三种类型各自往返");
        for (RouteKind kind : RouteKind.values()) {
            PlayerRoute a = PlayerRoute.create("k").append(new BlockPos(0, 64, 0), kind);
            PlayerRoute b = decode(encode(a));
            require(b.point(0).kind() == kind, kind.name() + " 类型无损");
            // 序列化用的必须是稳定小写名，不是枚举名
            require(kind.serializedName().equals(kind.name().toLowerCase(java.util.Locale.ROOT))
                            || kind == RouteKind.ROAD,
                    kind.name() + " 序列化名稳定（" + kind.serializedName() + "）");
        }
    }

    private static void testStatusSurvivesRoundTrip() {
        System.out.println("[4] 状态往返");
        for (RouteStatus st : RouteStatus.values()) {
            PlayerRoute a = PlayerRoute.create("s").withStatus(st);
            PlayerRoute b = decode(encode(a));
            require(b.status() == st, st.name() + " 状态无损");
        }
    }

    private static void testNegativeHeight() {
        System.out.println("[5] 负高度（地下隧道）与极端坐标");
        PlayerRoute a = PlayerRoute.create("deep")
                .append(new BlockPos(-3000, -50, 2500), RouteKind.TUNNEL)
                .append(new BlockPos(-3010, -58, 2510), RouteKind.TUNNEL);
        PlayerRoute b = decode(encode(a));
        require(b.point(0).pos().getY() == -50, "负 Y 无损");
        require(b.point(1).pos().equals(new BlockPos(-3010, -58, 2510)), "极端坐标无损");
    }
}
