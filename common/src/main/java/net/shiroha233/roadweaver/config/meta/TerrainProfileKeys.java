/* 文件职责：TerrainProfileKeys当前配置的元数据。 */
package net.shiroha233.roadweaver.config.meta;

import java.util.List;

import net.shiroha233.roadweaver.core.constants.RoadConstants;

/** 当前生产读取项的元数据；删除未接线控件，避免把显示或日志读取误判为生效。 */
public final class TerrainProfileKeys {

    private TerrainProfileKeys() {}

    /** 字段与元数据的双向一致性由回归强制检查。 */
    public static final java.util.List<ConfigKey> ALL = java.util.List.of(
            ConfigKey.integer("mountainSurfaceCutMaxBlocks", 3, 0, 1000000,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "山地最大下挖", "Mountain max surface cut",
                    "山区单站最多向下挖多少格",
                    "格", "与平原分开调，山区不该被压成平台"),

            ConfigKey.integer("mountainSurfaceFillMaxBlocks", 1, 0, 1000000,
                    ConfigCategory.PROFILE, ConfigTier.ADVANCED, ConfigStatus.ACTIVE,
                    "山地最大填方", "Mountain max surface fill",
                    "山区单站最多向上填多少格",
                    "格", ""),

            ConfigKey.integer("plainSurfaceCutMaxBlocks", 5, 0, 1000000,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "平原最大下挖", "Plain max surface cut",
                    "平原单站最多向下挖多少格",
                    "格", ""),

            ConfigKey.integer("plainSurfaceFillMaxBlocks", 2, 0, 1000000,
                    ConfigCategory.PROFILE, ConfigTier.BASIC, ConfigStatus.ACTIVE,
                    "平原最大填方", "Plain max surface fill",
                    "平原单站最多向上填多少格",
                    "格", ""),

            ConfigKey.decimal("mountainDetourMultiplier", 1.25D, 0.0, 1000000.0,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "山地绕行倍率", "Mountain detour multiplier",
                    "为避开山地最多接受直连距离的几倍",
                    "倍", "这是接受门限不是代价：调低更容易放弃绕行、直接穿山"),

            ConfigKey.decimal("coastDetourMultiplier", 1.30D, 0.0, 1000000.0,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "海岸绕行倍率", "Coast detour multiplier",
                    "为避开海岸线最多接受几倍绕行",
                    "倍", ""),

            ConfigKey.decimal("riverDetourMultiplier", 1.15D, 0.0, 1000000.0,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "河流绕行倍率", "River detour multiplier",
                    "为避开河流最多接受几倍绕行",
                    "倍", ""),

            ConfigKey.decimal("lakeDetourMultiplier", 1.30D, 0.0, 1000000.0,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "湖泊绕行倍率", "Lake detour multiplier",
                    "为避开湖泊最多接受几倍绕行",
                    "倍", ""),

            ConfigKey.decimal("oceanDetourMultiplier", 1.10D, 0.0, 1000000.0,
                    ConfigCategory.PROFILE, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "海洋绕行倍率", "Ocean detour multiplier",
                    "为避开海洋最多接受几倍绕行",
                    "倍", ""),

            ConfigKey.decimal("oceanUnderseaChance", 0.50D, 0.0, 1000000.0,
                    ConfigCategory.CROSSING, ConfigTier.EXPERT, ConfigStatus.ACTIVE,
                    "海底隧道概率", "Ocean undersea chance",
                    "跨海时有多大概率改走海底隧道",
                    "概率", "")
    );
}
