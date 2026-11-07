# 参考源码与数据来源清单

本项目的玩法、数值、API 用法都不是凭记忆写的。下面按「怎么找到的 → 拿它干了什么」记录全部来源。
所有源码都克隆到 `D:\Larkdreamcode\.refs\`，不进入构建产物，也不参与打包。

## 一、用户指定的一级参考

| 仓库 | 类型 | 我们取用它什么 |
|---|---|---|
| [ShiinaYumeka/wallwar](https://github.com/ShiinaYumeka/wallwar) | 1.21.11 **数据包**，751 个 mcfunction | **主骨架**。阶段时长（`GAMEMODE` 0/1/2/3 与 `GAME_PRO` 0~11）、`system/timer/main.mcfunction` 的计时模型、`battle_start` 的战斗期规则、`death_fight` 的死斗规则、`circle_1/2` 的缩圈三档、`scores.mcfunction` 的 bossbar 与计分项、`system/phase/*` 的压迫阶段顺序（团队增益→中毒→饥饿→凋零→伊甸之果） |
| [ShiinaYumeka/wallwarplugins](https://github.com/ShiinaYumeka/wallwarplugins) | Paper 插件（真实 1.21.11 生产代码） | `api-version: '1.21.11'` 的写法、`FoodCooldownListener` 的 `hasCooldown/setCooldown(Material,int)` 用法、`InstantDamagePlayerListener` 的药水伤害补偿、`NoQuickSwapNms` 想解决的问题（我们用 `Player#resetCooldown()` 免 NMS 实现）、`TeamMapGlowManager` 的事故记录（`NoSuchElementException: packet_handler`）→ 结论：队友发光本期不做 |
| [Circle1t/AnniTest](https://github.com/Circle1t/AnniTest) | 中文「核心战争」插件源码 | **核心 Nexus 机制**：`nexus/NexusManager.java:23` 默认 75 HP、`:190` 两角选区判定、`nexus/NexusListener.java:61/113` 放置与破坏扣血、`:135` `getCurrentPhase() < 2` 时核心无敌；`game/GamePhase.java` 的阶段对象（名称/秒数/血条颜色）；`kit/kits/` 21 个职业 → 我们取 `Miner`、`Builder` 两个功能位 |

## 二、全网检索到的同类开源实现（GitHub）

用 GitHub 搜索 API（`/search/repositories?q=megawalls`）+ 平台搜索 + 网页搜索三条路找的。

| 仓库 | 说明 | 取用它什么 |
|---|---|---|
| [andrewyunt/MegaArena](https://github.com/andrewyunt/MegaArena) | 最贴近 Hypixel 的 Bukkit 实现 | `objects/Game.java` 的对局对象划分、`Class.java` 的「职业=主动技能+两个被动+每刀能量」模型、`config.yml` 的 classes 数值表、`GamePlayer.java` 的击杀回血与助攻窗口（200 tick） |
| [TheShermanTanker/MegaWallsSource](https://github.com/TheShermanTanker/MegaWallsSource) | Mega Walls 1.7 beta 原版源码 | `EntityCustomWither.java` 的凋零 1000 HP 与狂暴阈值（≤50%）、`GameUpdater.java` 的血量与计时处理 |
| [horsenuggets/megawalls](https://github.com/horsenuggets/megawalls) | Hypixel 职业数据克隆 | `MWZombie/MWSkeleton/MWHerobrine/MWCreeper/MWEnderman/MWGolem` 的每刀能量（12/25/25/20/20/10）、`EnergyManager.java` 的「真实命中才给能量」判定 |
| [sucj/MegaWalls78-Open](https://github.com/sucj/MegaWalls78-Open) | 1.7.8 实现 | 状态命名与命令层结构对照 |
| [WoutDev/Mega-Walls](https://github.com/WoutDev/Mega-Walls) | 2016 年的 Bukkit 实现，依赖 ProtocolLib | 旧版队伍/核心的交互方式对照 |
| [yoni123100/MegaWallsFFA](https://github.com/yoni123100/MegaWallsFFA) | FFA 变体 | 个人计分与结算展示 |
| [Hirohiko360/megawalls_Server-plug-in](https://github.com/Hirohiko360/megawalls_Server-plug-in) | 中文分支版本 | 中文文案与提示风格参照 |
| [MrLittleKitty/AnnihilationPro](https://github.com/MrLittleKitty/AnnihilationPro) | 完整 Annihilation 源码（多模块） | 核心与阶段设计的交叉验证 |

## 三、查到但下载不到的商业插件（只做功能对照，未反编译）

这些资源在 SpigotMC，页面要求登录，脚本无法取得 jar，**我们没有下载也没有反编译它们**，
只把它们公开描述的功能列表作为「同类产品功能面」的参照：

| 资源 | 状态 |
|---|---|
| [The Walls X（Walls / Mega Walls / Kits / Cages / Parties）](https://www.spigotmc.org/resources/88542/) | 需登录；功能面：多模式（单人/团队）、职业、笼子、队伍、神秘箱 |
| [MegaWalls (Skelerex) 0.5](https://www.spigotmc.org/resources/58667/) | 需登录；功能面：`/walls` 管理命令、全局频道 `/all`、私聊 `/message` |
| [MegaWalls - Lite version](https://www.spigotmc.org/resources/65135/) | 需登录 |
| MegaWalls Zombie Kit / Creeper Kit / MiniWalls | 需登录 |

> 说明：SpigotMC 的资源页在未登录时直接返回「You must be logged in to do that」，
> 这不是抓取姿势问题，是站点策略。**没有绕过登录墙**，所以这几项没有源码依据，
> 相关功能一律标为「参考公开描述，无源码验证」。

## 四、实际下载并反编译的发布版 jar

工具：[Vineflower 1.11.1](https://repo1.maven.org/maven2/org/vineflower/vineflower/1.11.1/)（`java -jar vineflower.jar -dgs=1 <jar> <out>`）。

| jar | 来源 | 反编译结果 | 取用它什么 |
|---|---|---|---|
| `bedwars-plugin-25.5-SNAPSHOT.jar` | Modrinth 项目 `bedwars1058` 的版本文件（公开下载） | 880 个 `.java` | `api/arena/GameState.java` 的四态（waiting/starting/playing/restarting）、`arena/Arena.java` 的 `changeStatus` 状态机与 98KB 单类职责划分、`arena/LastHit.java` 的伤害归属表（我们据此设计助攻窗口）、`arena/SetupSession.java` 的选区向导（我们据此设计 `/warwall setup`）、`arena/Rejoin.java` 的断线重连 |
| `RealSkywars-1.3.jar` | Modrinth 项目 `realskywars` 的版本文件（公开下载） | 877 个 `.java` | 小游戏的状态机与计分板刷新节奏对照 |

反编译产物在 `D:\Larkdreamcode\.refs\jars\dec\`，只用于阅读，不进入我们的代码库。

## 五、1.21.11 的权威数据来源（不是二手资料）

| 事实 | 怎么拿到的 |
|---|---|
| `api-version` 应写 `1.21.11` | 解包 `paper-api-1.21.11.jar` 根目录的 `apiVersioning.json`：`{"currentApiVersion":"1.21.11"}` |
| Paper 1.21.11 可用构建 | PaperMC v3 API `https://fill.papermc.io/v3/projects/paper/versions/1.21.11/builds` → `build 132`，`STABLE` |
| Sharpness / Protection / Smite / Power / Fire Aspect / Density / Breach 的真实数值 | 解包本地 1.21.11 服务端 jar 的 `data/minecraft/enchantment/*.json` |
| `Mob#setAware` 而非 `setAI`；`Attribute.ATTACK_SPEED` 无 `GENERIC_` 前缀；`Player#getCooledAttackStrength`；`WorldBorder#changeSize(double,long)`；`Wither#setInvulnerableTicks` 存在 | `javap -classpath paper-api.jar` 直接反查二进制签名 |
| 1.21.11 新增 `MACE` / `WIND_CHARGE` / `*_SPEAR` / `COPPER_*` 材质 | 同上，`javap org.bukkit.Material` |
| Hypixel Mega Walls 的原始设计（100 人 4 队、凋零、5 分钟墙、复活规则） | 公开资料（百度百科词条对 Hypixel 玩法的描述）交叉核对；具体数值以三份开源实现为准 |

## 六、没有采用的东西（避免误以为遗漏）

- **队友发光（穿透地图看到队友）**：需要 NMS/Netty 管线或 PacketEvents。参考实现 `wallwarplugins` 为此出过一次线上事故（`NoSuchElementException: packet_handler`），本期不做，降级为队伍前缀 + 罗盘。
- **自定义资源包纹理**：用户已确认「暂时走原版」，`visual.use-custom-model-data` 只留开关。
- **数据包里的 751 个 mcfunction 全量移植**：商业模式太重。我们移植的是**时间轴与规则**，建筑（`build/*`）、自定义物品（`weapon/*`、`armor/*`）、猎人模式、Boss 模式均标为本期不做。
- **金币 / XP / 商店 / 职业升级树**：MegaArena 与 AnniTest 都有，本期不做，只保留结算统计。
