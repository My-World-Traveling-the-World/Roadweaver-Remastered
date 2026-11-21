# 云游四海 · RoadWeaver 重制版

[English](README.md) | 简体中文

在 Minecraft 里，村庄和其他结构之间会自动连起道路。你可以沿路寻找聚落，走桥过河，或穿隧道翻越陡峭地形。

- 道路顺着地形选线，绕开受保护的建筑。
- 路面、路牌、路灯和路边装饰让不同路段形成完整的路网。
- 游戏内按 **H** 打开路网地图，可以查看已有路线，也可以自己画出连接。

## 安装需要什么

**Minecraft 1.21.1、Fabric Loader 0.16.10 或以上、Java 21 或以上、Fabric API 和 Cloth Config API。** 安装 RoadWeaver 重制版成品即可；其中已包含 **RoadWeaver API**，请只保留一份 API。

## 一起安装有什么变化

单装道路也能生成路网。一起安装兼容版本的 StreamsReflowing 时，道路会读取河流规划，用于选线和规划跨水段。一起安装[万里长城](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall)时，道路会等长城预热，并在施工时避让长城的保护范围。

三者都装时，开局顺序是 **河流 → 长城 → 道路**；只装河流和道路时，就是 **河流 → 道路**。

[开发文档](docs/README.md) · [发布记录](docs/RELEASES.md) · [MIT 许可](LICENSE)

本项目基于 [shiroha233 的 RoadWeaver](https://github.com/shiroha-233/RoadWeaver)，并受到 [Countered’s Settlement Roads](https://modrinth.com/mod/countereds-settlement-roads) 和 [RoadArchitect](https://github.com/FranckRJ/RoadArchitect) 的启发。
