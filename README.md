# RoadWeaver Remastered

English | [简体中文](README_CN.md)

RoadWeaver builds a road network between villages and other structures in Minecraft. Follow the roads to discover settlements, cross rivers on bridges, or travel through tunnels under steep terrain.

- Roads follow the landscape and route around protected structures.
- Road surfaces, signs, lamps, and roadside decorations give the network a consistent look.
- Press **H** to view the road network or draw a connection yourself.

## What you need

**Minecraft 1.21.1, Fabric Loader 0.16.10 or later, Java 21 or later, Fabric API, and Cloth Config API.** Install the RoadWeaver Remastered mod. The packaged mod includes **RoadWeaver API**; install only one copy of that API.

## Playing with other mods

RoadWeaver works without a river or wall mod. With a compatible StreamsReflowing build, it reads the planned waterways and uses them when routing roads and planning crossings. With [PlainCraft Great Wall](https://github.com/My-World-Traveling-the-World/PlainCraft-Great-Wall), it waits for the wall's startup work and respects the wall's protected footprint when building roads.

With all three installed, startup runs **rivers → Great Wall → roads**. With rivers and roads only, it runs **rivers → roads**.

[Developer documentation](docs/README.md) · [Release notes](docs/RELEASES.md) · [MIT license](LICENSE)

Based on [RoadWeaver by shiroha233](https://github.com/shiroha-233/RoadWeaver), with inspiration from [Countered’s Settlement Roads](https://modrinth.com/mod/countereds-settlement-roads) and [RoadArchitect](https://github.com/FranckRJ/RoadArchitect).
