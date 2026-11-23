# RoadWeaver Remastered

English | [简体中文](README_CN.md)

RoadWeaver builds a road network between villages and other structures in Minecraft. Follow the roads to discover settlements, cross rivers on bridges, or travel through tunnels under steep terrain.

- Roads follow the landscape and route around protected structures.
- Road surfaces, signs, lamps, and roadside decorations give the network a consistent look.
- Press **H** to view the road network or draw a connection yourself.

## What you need

**Minecraft 1.21.1, Fabric Loader 0.16.10 or later, Java 21 or later, Fabric API, and Cloth Config API.** Install RoadWeaver Remastered and the [standalone RoadWeaver API](https://github.com/My-World-Traveling-the-World/Roadweaver-Remastered/releases/tag/api-v1.1.1) as separate JARs. Use one copy of the API when combining any of the three mods.

## Playing with other mods

RoadWeaver builds roads on its own. These integrations are optional:

- **StreamsReflowing**: Roads use the waterways planned by a compatible build when choosing routes and crossings.
- [**PlainCraft Great Wall**](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall): Roads wait for the wall's startup work and avoid its protected footprint during construction.
- **Tans Huge Trees**: A compatible build can register tree footprints for roads to avoid.

With all three installed, startup runs **rivers → Great Wall → roads**. With rivers and roads only, it runs **rivers → roads**.

[Developer documentation](docs/README.md) · [Release notes](docs/RELEASES.md) · [MIT license](LICENSE)

Based on [RoadWeaver by shiroha233](https://github.com/shiroha-233/RoadWeaver), with inspiration from [Countered’s Settlement Roads](https://modrinth.com/mod/countereds-settlement-roads) and [RoadArchitect](https://github.com/FranckRJ/RoadArchitect).
