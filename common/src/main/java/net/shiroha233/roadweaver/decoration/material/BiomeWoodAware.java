/* 文件职责：木材感知接口。 */
package net.shiroha233.roadweaver.decoration.material;

import net.shiroha233.roadweaver.core.model.WoodAssets;

/**
 * 木材感知接口
 */
public interface BiomeWoodAware {
    void setWoodType(WoodAssets assets);
}
