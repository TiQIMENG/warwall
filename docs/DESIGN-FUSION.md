# 玩法融合设计：战墙 MegaWalls × 核心战争 Annihilation

本文是 `PLAN.md` 的补充卷。`PLAN.md` 定义工程结构与阶段机，本文只解决一件事：
**把「凋零」和「核心」两种胜利条件融成一套自洽的规则。**

## 一、三个来源各保留什么

| 来源 | 保留 | 丢弃 |
|---|---|---|
| Hypixel Mega Walls | 4 队、每队一座凋零、5 分钟准备期（急迫 II）、墙倒开战、**凋零存活才可复活**、击杀回血、死斗 | 金币经济、职业升级树（本期不做） |
| wallwar 数据包（1.21.11） | 阶段计时与播报节奏、缩圈三档、死斗收尾、中央定时刷资源 | 751 个 mcfunction 的具体建筑与自定义物品体系 |
| AnniTest / AnnihilationPro（核心战争） | **每队一个核心（Nexus）**、核心按选区定义、**在核心区域放置/破坏方块扣核心血**、阶段门禁（前期核心无敌）、核心提供团队增益、职业分类（Miner / Builder 等） | 5×10 分钟的超长阶段、XP 与商店体系、末地/地狱传送门（本期标记为可选） |

## 二、双层胜负条件（核心设计）

一局里每支队伍同时有**两个关键物件**，作用不重叠：

| 物件 | 作用 | 被摧毁的后果 |
|---|---|---|
| **凋零 Wither**（实体，400 HP） | 决定「还能不能复活」 | 该队死亡后**不能再复活**，转旁观 |
| **核心 Nexus**（方块区域，75 HP） | 决定「在哪复活 + 团队增益」 | 该队**失去团队增益**，复活点变为核心残骸（随机 8 格内），且每次复活**随机掉落一件护甲** |

这样两个目标都必须打，单一目标打穿不能直接灭队：

- 只拆凋零 → 敌人还能打，但死了就不能复活，慢慢被磨掉。
- 只拆核心 → 敌人还能复活，但没了增益、复活点在地图中央附近（等于送人头）。

**淘汰条件**：全队玩家都无法复活（凋零已毁）+ 场上无人存活。

## 三、阶段表（融合后）

| 阶段 | 时长 | 墙 | 凋零 | 核心 | 复活 | 缩圈 |
|---|---|---|---|---|---|---|
| `COUNTDOWN` | 30s | 已立 | 无敌 | 无敌 | 可 | — |
| `PREP` | 300s | **存在，不可破坏** | 无敌 | **不可伤害** | 可，1 秒 | 静止 |
| `WALL_DROP` | 10s | **移除** | 无敌 | 不可伤害 | 可，1 秒 | 静止 |
| `BATTLE` | 600s | 无 | **可伤害** | **可伤害** | 凋零存活→可 | 两次收缩 |
| `DEATHMATCH` | 180s | 无 | 已灭 | 已失效 | 不可 | 收到终点半径 |
| `ENDED` | 20s | — | — | — | 不可 | — |

进入 `DEATHMATCH` 的条件保持 `PLAN.md §1.2`：**所有凋零死亡**，或 `BATTLE` 计时耗尽（强制死斗）。
核心在此之后不再影响复活（已经禁止复活了），仅用于结算排名。

## 四、核心（Nexus）机制规格

### 4.1 数据

```yaml
nexus:
  enabled: true
  max-health: 75              # 1..500，AnniTest 默认 75，本期沿用
  blocks-placed-damage: 1     # 敌队在核心区域每放 1 个方块扣多少血
  blocks-broken-damage: 1     # 敌队在核心区域每破坏 1 个方块扣多少血
  team-buff: FAST_DIGGING      # 核心存活时全队的药水效果（原版 PotionEffectType）
  team-buff-amplifier: 0       # 0 = I 级
  respawn-scatter-radius: 8.0  # 核心被毁后复活点的随机散布半径
  respawn-armor-loss: 1        # 核心被毁后每次复活随机掉几件护甲
```

### 4.2 区域

每队一个 `Box`，写在 `arenas.yml`：

```yaml
teams:
  red:
    nexus:
      min: { x: 96, y: 60, z: -6 }
      max: { x: 104, y: 68, z: 6 }
```

用 `/warwall setup team red nexus` 把当前选区写进去（复用 `pos1`/`pos2`）。

### 4.3 伤害规则

1. 只在 `PhaseRules.nexusAttackable()` 为真时生效（`BATTLE` / `DEATHMATCH`）。
2. 只有**敌队玩家**的动作能扣血；本队在自己的核心里摆方块是**自由**的（用于加固）。
3. 扣血点：
   - `BlockPlaceEvent`：方块落在该队核心 `Box` 内 → 敌队玩家 → 扣 `blocks-placed-damage`。
   - `BlockBreakEvent`：破坏的方块在该队核心 `Box` 内 → 敌队玩家 → 扣 `blocks-broken-damage`。
4. 核心血量归零 → 广播「X 队核心已被摧毁」、`nexusAlive=false`、移除团队增益、该队所有在线玩家收到提示。
5. 核心被摧毁**不会**立即淘汰该队。

### 4.4 显示

每队一条 BossBar，标题格式：

```
红队  凋零 312/400  核心 75/75
```

凋零被毁显示 `凋零 已摧毁`，核心被毁显示 `核心 已摧毁`。
`BETA`：同时调用 `((Boss) wither).getBossBar().removeAll()` 关掉原版凋零自带血条，避免两条血条打架。

## 五、职业（8 个）

MegaWalls 的 6 个 + Annihilation 的 2 个功能位。数值见 `docs/BALANCE-1.21.11.md`。

| id | 名称 | 来源 | 定位 |
|---|---|---|---|
| `zombie` | 僵尸 | MegaWalls | 治疗、续航 |
| `skeleton` | 骷髅 | MegaWalls | 远程输出 |
| `herobrine` | 恐惧魔王 | MegaWalls | 真伤爆发 |
| `creeper` | 爬行者 | MegaWalls | 范围爆破 |
| `enderman` | 末影人 | MegaWalls | 机动突进 |
| `golem` | 傀儡 | MegaWalls | 坦克、拉拽 |
| `miner` | 矿工 | AnniTest `Miner` | 挖矿效率、资源自给 |
| `builder` | 建筑师 | AnniTest `Builder` | 快速建筑、核心加固 |

`Miner` / `Builder` 的核心价值是**服务核心机制**：矿工更快补核心方块，建筑师能在敌方核心上快速摆方块扣血。
这解决了「核心机制在只有战斗职业时会变成累活」的问题。

## 六、团队增益

核心存活时，全队每 5 秒补一次 `nexus.team-buff`（默认 `FAST_DIGGING` I）。
核心被毁后立刻移除该效果，并在播报里说明「你们失去了核心增益」。

复用了 MegaWalls 准备期「急迫 II」的思路，但把「加速」绑到核心生存上，让核心有存在感。

## 七、与既有 PLAN 的差异清单（施工时必须同步）

1. `PhaseRules` 增加字段 `nexusAttackable`。
2. `config.yml` 增加 `nexus:` 段（见 §4.1）。
3. `arenas.yml` 每队增加 `nexus: {min,max}`；`ArenaStore` 要读它，`WarwallArena.TeamSetup` 增加 `nexus` 字段。
4. `/warwall setup team <color> nexus` 子命令。
5. 职业从 6 个扩到 8 个（新增 `miner` / `builder`）。
6. 结算排名先比「摧毁核心数」，再比「摧毁凋零数」，最后比击杀。
7. `PLAYER` 复活逻辑多一个分支：核心被毁后的「残骸复活」。

## 八、参考来源

- `D:\Larkdreamcode\.refs\oss\AnniTest`（Circle1t/AnniTest，中文核心战争实现）
  - `game/GamePhase.java` + `GamePhaseManager.java`：阶段对象（名称 / 秒数 / BossBar 颜色），默认 `30 + 600×4 + 0`
  - `nexus/NexusManager.java:23` `DEFAULT_HEALTH = 75`；`:190` 起按两个角判定是否在核心区域内
  - `nexus/NexusListener.java:61` `onBlockPlace` / `:113` `onBlockBreak` 扣血；`:135` `getCurrentPhase() < 2` 时核心无敌
  - `kit/kits/` 21 个职业，本期取 `Miner`、`Builder`
- `D:\Larkdreamcode\.refs\oss\AnnihilationPro`（MrLittleKitty/AnnihilationPro，完整 Annihilation 源码，用于交叉验证核心与阶段设计）
- Hypixel Mega Walls 公开设计：凋零存活才可复活
- 1.21.11 附魔/攻速实测：见 `docs/BALANCE-1.21.11.md`
