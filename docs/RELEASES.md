# RoadWeaver 发布台账

## 云游四海世界生成 API 1.1.2（2026-11-23）

前置源码已迁往独立仓库。[v1.1.2](https://github.com/My-World-Traveling-the-World/PlainCraft-Worldgen-API/releases/tag/v1.1.2) 提供 `plaincraft-worldgen-api-1.1.2.jar`。发布件 SHA-256 为 `06a66b459b52357a73215079eb082cee1d45130673c29ff6455fbb48e718dca2`。升级时用它替换旧前置，`roadweaver_api` 只放一份。

## 0.9.120 / RoadWeaver API 1.1.1（2026-11-23）

道路和 API 现在是两个独立安装包。道路继续声明 `roadweaver_api`，主 JAR 没有内置副本；长城、河流可各自配 API 使用。标准构建、1867 项回归、三个坏包和 Fabric Loader 独立／合装矩阵通过。道路成品仅本地构建，未安装到 WAYFARING。

[独立 API GitHub Release](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/releases/tag/api-v1.1.1) 已公开；下载件 SHA-256 `d854ad519c69de62542758255f12619c38e6592b397a3abd097fe7996f7e7149`，模组元数据及 26 个契约类与本机验过的包一致。玩家安装及构建入口见[验证手册](VALIDATION.md)。

## 0.9.119（2026-11-23，仅构建）

精细纵断面失败后尝试同端点地表绕行；确认不可绕的局部普通道路保护冲突仅留短缺口。1867 项通过。这个历史版本仍带内嵌 API。

## 0.9.118（2026-11-23，历史本机安装）

局部修复网格独立于主网格配置。既往安装、回退路径及各期 SHA-256 查[历史发布台账](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/blob/f2c06ea93c3d77e71c982086f59e688c433bd426/docs/RELEASES.md)。最新验证见[交接](HANDOFF.md)。
