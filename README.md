# 云游四海 · RoadWeaver 重制版

**沿路寻找村庄与遗迹，过桥渡河，穿隧道翻山。**

`Minecraft 1.21.1` · `Fabric` · `Java 21+`

[安装](#安装) · [模组联动](#模组联动) · [版本更新](CHANGELOG.md) · [开发指南](HANDOFF.md)

| 功能 | 游戏里的体验 |
| --- | --- |
| 自动路网 | 村庄与其他结构自动连线，道路顺着地形延伸并绕开受保护建筑。 |
| 桥、隧道和坡道 | 遇水架桥，必要时穿山，整砖和半砖衔接地表坡道。 |
| 路边设施 | 路牌、路灯和路边装饰随道路生成。 |
| 路网地图 | 按 **H** 查看已规划的路线，也可以手绘连接。 |

## 安装

使用 **Fabric Loader 0.16.10+**，安装道路重制版、Fabric API、Cloth Config API 和[云游四海世界生成 API](https://github.com/My-World-Traveling-the-World/PlainCraft-Worldgen-API/releases)。各版本的成品状态见[版本更新](CHANGELOG.md)。

## 模组联动

道路可独立生成。合装兼容版本时，开局按 **河流 → 长城 → 道路** 推进，跳过未安装的阶段，前置只装一份。

- **StreamsReflowing** 提供规划水道，供道路选线和安排跨水路段。
- **万里长城** 公布墙体保护范围，供道路规划和施工避让。[长城项目](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall)
- **Tans Huge Trees** 的兼容版本可登记巨树占地，供道路避让。

基于 [shiroha233 的 RoadWeaver](https://github.com/shiroha-233/RoadWeaver)。灵感也来自 [Countered’s Settlement Roads](https://modrinth.com/mod/countereds-settlement-roads) 和 [RoadArchitect](https://github.com/FranckRJ/RoadArchitect)。[MIT 许可](LICENSE) · [第三方声明](NOTICE)
