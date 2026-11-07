# 超级战墙 MegaWalls（warwall）

Paper 1.21.11 的四队团队竞技插件。主骨架取自
[ShiinaYumeka/wallwar](https://github.com/ShiinaYumeka/wallwar) 数据包的时间轴，
叠加 Hypixel Mega Walls「凋零」与「核心战争」的「核心」两层胜负条件，战斗按 UHC 规则设计
（无自然回血 + 保留 1.9+ 攻击冷却）。

## 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21.11（构建 132 及以上；`api-version: "1.21.11"`） |
| Java | 21 |
| 依赖 | 无第三方插件依赖，无数据库，无资源包 |
| 世界 | 建议专用世界，插件会改该世界的世界规则与世界边界 |

## 构建

```bash
cd warwall
./gradlew build          # Windows: gradlew.bat build
```

产物：`build/libs/warwall-1.0.0.jar`，拷进 `plugins/` 后**重启**服务端（不要用 `/reload`）。

## 快速开始

1. 建竞技场（需要 `warwall.setup` 权限，默认 OP）：

```
/warwall setup create arena1 world
/warwall setup select arena1
```

2. 逐项选区。站到相应位置执行：

```
/warwall setup lobby                    # 大厅
/warwall setup spectator                # 旁观区
/warwall setup center                   # 地图中心（中央刷资源点）
/warwall setup team red spawn           # 红队出生点
/warwall setup team red wither          # 红队凋零基座
/warwall setup team red respawn         # 红队复活点（可省，默认同 spawn）
```

黄 / 绿 / 蓝三队同理。

3. 选两个角（`/warwall setup pos1` / `pos2`），再写入区域：

```
/warwall setup wall add                 # 天空之壁（战斗阶段会变成可挖的矿物墙）
/warwall setup team red nexus           # 红队核心区域（融合玩法，可关）
/warwall setup unbreakable add          # 任何阶段都不许破坏的区域
/warwall setup mining add               # 只允许在这些区域挖矿（不配 = 全图可挖）
```

4. 校验并保存：

```
/warwall setup validate
/warwall setup save
```

5. 开局：

```
/warwall start
```

调试：`/warwall debug arena world 0 100 0` 会原地生成一个最小可玩竞技场并自动选中，配合
`/warwall debug fast on` 可以在两分钟内跑完整局。

## 一局的流程（默认时长）

时间轴默认值来自 wallwar 数据包的 tick 数 ÷ 20：

| 阶段 | 默认时长 | 内容 |
|---|---|---|
| `COUNTDOWN` | 30 秒 | 分队、传送、清点 |
| `PREP` | 5 分钟 | 天空之壁站立，凋零与核心无敌，全员**急迫 II**，挖矿冶炼、造防御 |
| `BATTLE` | 10 分钟 | 墙变成可挖的矿物墙，凋零与核心可被攻击，死亡掉落全部物品 |
| `SHRINK_1` | 5 分钟 | 第一次缩圈 |
| `SHRINK_2` | 5 分钟 | 第二次缩圈 |
| `FINAL_FIGHT` | 3 分钟 | 死斗，边界收到终点，随后进入压迫阶段 |
| 压迫阶段 | 120/90/90/90/180 秒 | 团队增益 → 全体中毒 → 全体饥饿 → 全体凋零 → 伊甸之果 → 最终死斗 |
| `ENDED` | 20 秒 | 结算、MVP、写盘 |

嫌长就在 `config.yml` 的 `phases` 里压缩，例如准备 600、战斗 600、两次缩圈各 300，一局约 25 分钟。

## 融合模式（只有一种，没有模式开关）

本插件不是「好几种玩法任选其一」，而是把三套东西缝成**一条**完整规则，跑在同一局里：

| 层 | 来自 | 在这条规则里负责什么 |
|---|---|---|
| **发育与战斗** | UHC | 开局只有木镐木斧石剑，铁/钻石装备、附魔、药水全靠自己挖与炼；关闭自然回血，回血只能靠金苹果与药水；保留 1.9+ 攻击冷却，剑 1.6 击/秒 |
| **对局骨架** | 战墙 MegaWalls | 四队、每队一座凋零、准备期挖矿造防御、墙倒开战、**己方凋零存活才可复活**、击杀回血 |
| **阶段推进** | wallwar 数据包 | 准备→战斗→两次缩圈→死斗，死斗内再按「团队增益→中毒→饥饿→凋零→伊甸之果」逐步加压 |
| **第二胜利条件** | 核心战争 Annihilation | 每队一座核心，在敌方核心区域放/破方块扣血；核心决定复活点与团队增益 |

四条规则互相咬合，不是并列关系：

1. 准备期（5 分钟、急迫 II）既是**发育窗口**，也是给队伍凋零与核心加固的窗口。
2. 墙倒之后，敌人可以同时打你的**凋零**（断复活）和**核心**（断增益与出生点）。
3. 凋零没了、核心还在 → 还能复活，但在残骸附近复活、没有增益，等于被赶出家门。
4. 核心没了、凋零还在 → 还能复活，但少了团队增益与安全出生点，长期战会被磨死。
5. 两个都没了 → 全队淘汰。

## 两层胜负条件

这是本插件与普通战墙最大的不同：每队有两个关键物件，**作用不重叠**。

| 物件 | 作用 | 被摧毁后 |
|---|---|---|
| **凋零 Wither**（400 HP） | 决定还能不能复活 | 该队死亡即转旁观，不再复活 |
| **核心 Nexus**（75 HP） | 决定在哪复活 + 团队增益 | 失去团队增益，复活点变成核心残骸附近，每次复活随机掉一件护甲 |

核心按「核心战争」的规则打：**在敌方核心区域里放置或破坏方块**，每个方块扣 1 点核心血量。
只在战斗阶段之后生效，准备期打不动。

淘汰条件：全队都无法复活（凋零已毁）且场上无人存活。

## 职业

| id | 名称 | 定位 |
|---|---|---|
| `zombie` | 僵尸 | 治疗、续航 |
| `skeleton` | 骷髅 | 远程输出 |
| `herobrine` | 恐惧魔王 | 真伤爆发 |
| `creeper` | 爬行者 | 范围爆破 |
| `enderman` | 末影人 | 机动突进 |
| `golem` | 傀儡 | 坦克、拉拽 |
| `miner` | 矿工 | 挖矿效率、资源自给 |
| `builder` | 建筑师 | 快速建筑、核心加固 |

用 `/warwall kit <id>` 选职业。数值与高版本校准过程见 [`docs/BALANCE-1.21.11.md`](docs/BALANCE-1.21.11.md)。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/warwall status` | `warwall.play` | 打印阶段、剩余时间、四队存活、凋零/核心血量 |
| `/warwall join` / `leave` | `warwall.play` | 报名 / 退出 |
| `/warwall kit <id>` | `warwall.play` | 选职业 |
| `/warwall start` / `stop` | `warwall.admin` | 开局 / 中止 |
| `/warwall reload` | `warwall.admin` | 重载配置与竞技场 |
| `/warwall setup ...` | `warwall.setup` | 竞技场选区与保存 |
| `/warwall debug ...` | `warwall.admin` | 调试：建竞技场、快进、跳阶段、杀凋零、加能量 |

## 配置

`config.yml` 每个键都带中文注释与取值范围，重点几组：

- `phases`：阶段时长
- `players`：血量、击杀回血、复活延迟与无敌
- `combat`：UHC 规则、伤害系数、物品与药水黑名单
- `wither`：凋零血量、狂暴、骷髅射击
- `nexus`：核心血量、放/破方块扣血、团队增益
- `border`：缩圈步进
- `wall`：墙体积上限、还原速度

`arenas.yml` 是竞技场数据，由 `/warwall setup` 写入，也可以手改后 `/warwall reload`。

## 高版本相关的取舍

1. **不改攻速**：1.21.11 剑是 1.6 击/秒，UHC 手感就建立在这个节奏上。想更快就调
   `combat.max-no-damage-ticks`，但那会放大真伤职业的收益。
2. **锋利被削弱**：1.21.11 的 Sharpness 5 级只有 +3.0（1.8 是 +6.25），所以职业强度靠职业技能，
   不靠堆附魔。
3. **重锤与风弹默认拉黑**：Mace + Density 能高空秒人，Wind Charge 会把团战打散，都在
   `combat.item-blacklist` 里。
4. **攻击冷却防护**：切换快捷栏或副手时调用 `Player#resetCooldown()`，避免绕过冷却连点。

## 验收方法

```bash
./gradlew build
```

冒烟测试（会真的启一个 Paper 1.21.11 服务端）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .ops\smoke-test.ps1 `
  -PluginJar (Resolve-Path 'build\libs\warwall-1.0.0.jar') `
  -ServerDir (Resolve-Path '.ops\testserver-1.21.11')
```

判据：`booted = True`、日志无 `Exception`、`warwall status` 每次都有输出。

## 参考与出处

玩法与数值的来源、以及反编译了哪些同类插件，全部记录在
[`docs/REFERENCES.md`](docs/REFERENCES.md)；融合规则见 [`docs/DESIGN-FUSION.md`](docs/DESIGN-FUSION.md)。

## 实现清单

已经跑通并有验证的：

- 阶段机与时间轴（8 个阶段 + 7 档压迫阶段），准备期急迫 II
- 四队分队、独立计分板、侧边栏、四队 BossBar、标题与动作条
- 天空之壁：捕获 1077 方块 → 战斗期转为矿物墙 → 收局分批还原
- 凋零：400 HP、`setAware(false)` 钉住基座、自己发射凋零骷髅、关掉原版血条
- 核心：75 HP、在核心区放/破方块扣血、团队增益、残骸复活
- UHC 战斗：关自然回血、友伤、真伤、金苹果限频、物品黑名单、换栏重置攻击冷却
- 8 个职业：主动技能 + **被动**（僵尸坚韧、骷髅回收、恐惧魔王连击、爬行者意志、
  末影人吞噬、傀儡铁皮、矿工矿脉、建筑师快速搭建）
- 统计：单局击杀/死亡/伤害/助攻 + 结算 MVP + `stats/<uuid>.yml` 跨局累计
- 中央定时刷新与死斗伊甸之果
- 19 项单元测试（阶段矩阵、时长换算、压迫链、Box 几何）

还没做：

- 队友发光（需要 NMS/Netty，参考实现出过线上事故）
- 资源包纹理（用户已确认本期走原版）
- 金币经济与职业升级树
- 数据包里的建筑系统（`build/*`）与整套自定义物品（`weapon/*`、`armor/*`）
- 猎人模式与 Boss 模式

