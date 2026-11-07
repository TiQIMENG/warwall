# 超级战墙 MegaWalls —— Paper 插件施工方案（阶段 A 产出）

- 项目根目录：`D:\Larkdreamcode\warwall`
- 目标平台：Paper 服务端插件，Minecraft **1.21.11**，Java **21**
- 文档核实日期：**2026-11-06**
- 参考源码（均已实读，出处标注到文件）：
  - `D:\Larkdreamcode\.refs\wallwar`（1.21.11 战墙数据包，751 个 mcfunction）
  - `D:\Larkdreamcode\.refs\wallwarplugins`（配套 Paper 插件，真实 1.21.11 实现）
  - `D:\Larkdreamcode\.refs\oss\MegaArena`（andrewyunt/MegaArena，Bukkit 实现）
  - `D:\Larkdreamcode\.refs\oss\MegaWallsSource`（TheShermanTanker，1.7.10 凋零/墙/计时）
  - `D:\Larkdreamcode\.refs\oss\megawalls`（horsenuggets，Hypixel 职业数值最完整的一份）
  - `D:\Larkdreamcode\.refs\oss\MegaWalls78-Open`（sucj，1.7.8 实现，状态机命名参考）

> 本方案只写方案，不含业务代码。所有类名/方法签名都是**待实现目标**，Paper API 部分见附录 A 的核对表。

---

## 0. 结论摘要（先看这里）

| 决策点 | 结论 |
|---|---|
| 平台 / 版本 | Paper 1.21.11 / Java 21，`api-version: '1.21.11'` |
| 依赖 | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`（compileOnly），仓库 `https://repo.papermc.io/repository/maven-public/` |
| 运行形态 | 单局单竞技场，`MatchController` 单例状态机 |
| 状态机 | `WAITING → COUNTDOWN → PREP → WALL_DROP → BATTLE → DEATHMATCH → ENDED → WAITING`（BATTLE 可提前跳 DEATHMATCH） |
| 默认时长 | 30 / 300 / 10 / 600 / 180 / 20 秒（全部可配） |
| 玩家血量 | 40 HP（20 心），对齐 Hypixel MegaWalls（三份参考实现一致） |
| 凋零血量 | 1000 HP，50% 血进入狂暴（`EntityCustomWither` 实测值） |
| 职业数 | 6（Zombie / Skeleton / Herobrine / Creeper / Enderman / Golem） |
| 能量制 | 上限 100，命中攒能量，满能量右键技能物品释放（两份 OSS 实现一致） |
| 持久化 | **YAML**（不做 SQLite），`stats/<uuid>.yml` + `matches/<时间>-<竞技场>.yml` |
| 视觉表现 | **纯原版**：Material + 显示名 + Lore + 附魔光效 + `ItemFlag`；`custom_model_data` **预留字段但默认留空**，日后接材质包只填 ID 不改代码 |
| 音效 | **全部用原版 `Sound` 常量**，不做 sounds.json 替换 |
| 资源包 | **本期不做**（用户已确认「暂时走原版」） |
| 队友发光 | **本期不做**（需 NMS/Netty 或 PacketEvents），降级为队伍前缀 + 罗盘指向 |
| 构建基线 | `mc_scaffold` 生成 paper 模板：`paper-api 1.21.11-R0.1-SNAPSHOT` + Java 21 + shadow 8.3.6 + Gradle wrapper 8.14.3，**已实测 `build` 成功（2 分 13 秒）** |
| 预计规模 | 39 个类 + 6 个职业实现，约 6000～7800 行（含配置与文案） |

---

## 1. 玩法总览与状态机

### 1.1 一局的玩家视角流程

1. 玩家进服落在 `lobby`（WAITING）。`/warwall join` 报名，`/warwall kit <id>` 选职业。
2. 满 `min-players`（默认 8）或管理员 `/warwall start` → COUNTDOWN 30 秒，全员传送进各自出生点。
3. **PREP（默认 300 秒）**：四人一队分别在自己基地。凋零在场且无敌。高墙存在且不可破坏。开放挖矿、合成、搭防御。中央不刷钻石。圈固定。
4. **WALL_DROP（默认 10 秒）**：墙被整段移除（数据包用「基岩换成铜/铁矿」的做法，我们直接移除并播放崩塌音效），中央开始刷钻石，全服标题提示。
5. **BATTLE（默认 600 秒）**：凋零可被敌方攻击；异队互相可伤害。己方凋零存活 → 死亡 5 秒后可复活；己方凋零被摧毁 → 立刻转入旁观，不再复活。战斗中按步骤缩圈。
6. **DEATHMATCH（默认 180 秒）**：所有凋零死亡即进入；若 BATTLE 计时耗尽仍有 ≥2 队存活，**强制死斗**（剩余凋零自毁并播报）。死斗内禁止复活，边界收到 `deathmatch.radius` 并持续掉血。
7. **ENDED（默认 20 秒）**：结算界面 + MVP，随后回到 WAITING。

### 1.2 阶段枚举与进入/退出条件

```java
public enum MatchState {
    WAITING, COUNTDOWN, PREP, WALL_DROP, BATTLE, DEATHMATCH, ENDED
}
```

| 阶段 | 进入条件 | 退出条件（任一） | 默认时长 | 进入时执行 |
|---|---|---|---|---|
| `WAITING` | 插件启用 / 上一局结束回落 | `startMatch()` 且人数 ≥ `min-players`；或管理员强制 | 无限 | 清场、传送 lobby、发放 lobby 物品 |
| `COUNTDOWN` | 上面那条 | 计时归零；人数掉到 `min-players` 以下（回滚到 WAITING）；`/warwall stop` | `countdown-seconds`=30 | 分队、校验竞技场、锁世界规则 |
| `PREP` | COUNTDOWN 归零 | 计时归零；`/warwall stop` | `prep-seconds`=300 | 生成凋零（无敌）、生成墙、发装备、设边界 `base-radius`、开自然回血=关 |
| `WALL_DROP` | PREP 归零 | 计时归零 | `wall-drop-seconds`=10 | 移除墙、播报、中央刷钻石、触发缩圈第一步预备 |
| `BATTLE` | WALL_DROP 归零 | 计时归零（→强制死斗）；**所有凋零死亡**（→DEATHMATCH）；`/warwall stop` | `battle-seconds`=600 | 解禁凋零受击、玩家死亡掉落、复活规则切到「看凋零」 |
| `DEATHMATCH` | 所有凋零死亡 **或** BATTLE 超时 | 计时归零；只剩 1 队有存活玩家；全部淘汰 | `deathmatch-seconds`=180 | 销毁残留凋零、禁止复活、边界收到 `deathmatch.radius`、加掉血、播放开场 |
| `ENDED` | 上面任一条 | 计时归零 → WAITING | `ended-seconds`=20 | 冻结伤害、结算、写盘、传送 |

### 1.3 阶段行为矩阵（`PhaseRules` 直接落成 record）

| 行为 | WAITING | COUNTDOWN | PREP | WALL_DROP | BATTLE | DEATHMATCH | ENDED |
|---|---|---|---|---|---|---|---|
| 异队互伤 | 否 | 否 | 否 | 否 | 是 | 是 | 否 |
| 友伤 | 否 | 否 | 否 | 否 | 否 | 否 | 否 |
| 凋零可被攻击 | — | — | 否（无敌） | 否（无敌） | 是 | 否（已销毁） | 否 |
| 凋零存在 | 否 | 否 | 是 | 是 | 是 | 否 | 否 |
| 死亡掉落物品 | 否 | 否 | 否 | 否 | 是 | 是 | 否 |
| 复活 | 是 | 是 | 是（1 秒） | 是（1 秒） | **仅己方凋零存活** | 否（旁观） | 否 |
| 墙存在且不可破坏 | 否 | 是 | 是 | 是 → 移除 | 否 | 否 | 否 |
| 缩圈 | 否 | 否 | 静止 | 静止 | 按步骤 | 强制收到终值 | 否 |
| 方块可破坏 | 否 | 否 | 是（除墙/凋零房） | 是 | 是 | 是 | 否 |
| 自然回血 | 关 | 关 | 关 | 关 | 关 | 关 | 关 |
| 可进入旁观 | 否 | 否 | 否 | 否 | 是（凋零被摧毁后） | 是 | 是 |

代码落点：

```java
public record PhaseRules(
        boolean pvpAllowed,
        boolean friendlyFire,
        boolean witherAttackable,
        boolean dropOnDeath,
        RespawnPolicy respawn,      // ALWAYS / WHILE_OWN_WITHER_ALIVE / NEVER
        boolean wallSolid,
        boolean borderActive) { }

public static PhaseRules rulesFor(MatchState state) { ... }
```

### 1.4 调试 / 快速开始旁路（不新增状态，只覆盖参数）

| 命令 | 作用 |
|---|---|
| `/warwall debug arena <world> <x> <y> <z>` | 在指定位置原地生成最小竞技场（4 块 16×16 平台、4 个凋零基座、一圈 3 格高的墙、中央方块、旁观台），并写入 `arenas.yml` 的 `_debug` 竞技场。**控制台可执行** |
| `/warwall debug fast [on\|off]` | 把 6 个阶段时长全部压到 10 秒（`MatchSettings.fastMode()`） |
| `/warwall debug solo [on\|off]` | 允许 1 人开局（绕过 `min-players`） |
| `/warwall debug phase <STATE>` | 强制跳转到指定阶段（走正常 `transition()`，不跳过进入逻辑） |
| `/warwall debug skip` | 立即把当前阶段计时清零 |
| `/warwall debug wither <color> kill` | 直接把某队凋零打到 0（验证凋零死亡 → 复活规则切换） |
| `/warwall debug energy <0-100>` | 设置自己的能量（验证技能） |
| `/warwall debug kit <id>` | 重发职业装备 |
| `/warwall status` | 打印状态机、计时、四队存活数、凋零血量、边界半径 |

`matchSettings` 覆盖优先级：`debug fast` > `config.phases` > 代码默认值。

---

## 2. 数值基线表（含出处）

> 规则：凡是能从参考源码里读到确切数字的，直接抄数字并标出处；读不到的，标「本方案自定」并给理由。

### 2.1 对局宏观数值

| 项目 | 本方案默认 | 出处 | 说明 |
|---|---|---|---|
| 玩家最大生命 | 40.0（20 心） | `megawalls/MWClassManager.java:84` `player.setMaxHealth(40)`；`MegaWallsSource/GameUpdater.java:107` Health Boost V；`MegaArena/GamePlayer.java:304` `setMaxHealth(40D)` | 三家一致 |
| 凋零最大生命 | 1000.0 | `MegaWallsSource/EntityCustomWither.java:420` `GenericAttributes.maxHealth).setValue(1000.0D)`；`GameStart.java:153` 记分板初始 1000 | 一致 |
| 凋零狂暴阈值 | ≤50% 生命 | `MegaWallsSource/EntityCustomWither.java:435` `getHealth() <= getMaxHealth()/2.0F` | — |
| 凋零技能周期 | 7 秒（狂暴后） | `EntityCustomWither.java:42` `int seconds = 7` + `:53` 重置为 7 | 敌人进入 40 格才走秒表（`:55`） |
| 凋零技能目标半径 | 20 格 | `EntityWitherSkillRunnable.java:23` `grow(20.0D, 5.0D, 20.0D)` | — |
| 凋零技能伤害 | 闪电 14 / 地震 6＋缓慢 II 100t / 黑洞 10＋拉拽 / 击退 | `EntityWitherSkillRunnable.java:63,69,70,73,78` | 5 个技能随机 |
| 凋零停怒时间 | 墙倒后 10 分钟 | `GameUpdater.java:150`（设 3000）＋`:173`（2400 时取消狂暴） | 3000-2400=600 秒 |
| 墙倒时长 | **300 秒**（本方案） | Hypixel 公开设计基线 5 分钟 | `GameStart.java:149` 文案写 10 分钟，但 `:211` 实际 `new EnhancedScheduler(20,...)` 只有 20 秒，源码自相矛盾（疑似未完成），不采信 |
| 玩家复活等待 | 5 秒 | 本方案自定 | 数据包 `system/start.mcfunction:2` `immediate_respawn false`，未给具体秒数 |
| 击杀回血 | +4 HP（2 心） | `MegaArena/GamePlayer.java:603-607` `previousHealth + 4`；文案 `messages.hearts-restored-kill` | 一致 |
| 助攻判定窗口 | 10 秒 | `MegaArena/GamePlayer.java:421` `scheduleSyncDelayedTask(..., 200L)` | 200 tick |
| 能量上限 | 100 | `MegaArena/GamePlayer.java:346-347`；`megawalls/EnergyManager.java:144-146` | 一致 |
| 复活保护 | 3 秒 | 本方案自定 | 参考实现均无，防出生点屠 |

### 2.2 缩圈 / 边界（数据包口径换算）

| 来源 | 数值 |
|---|---|
| 数据包初始边界 | `wall.mcfunction:11` `worldborder set 599`，`:12-15` `damage buffer 0 / damage amount 1 / warning distance 0` |
| 第一阶段收缩 | `system/phase/circle_1.mcfunction:11` `worldborder set 301 3600`（180 秒内 599→301） |
| 第二阶段收缩 | `system/phase/circle_2.mcfunction:14` `worldborder set 151 2400`（120 秒内 301→151） |
| 死斗掉血 | `system/phase/death_fight.mcfunction:13` `worldborder damage amount 20` |
| 圈外凋零效果 | `circle_1:20` y<30 → Wither I；`circle_2:23` y<30 → Wither II |

本方案把它抽象成**可配步骤表**（见 §6 `border.shrink-steps`），默认用缩小版：
`R0=300 → BATTLE+300s 收到 180（90 秒）→ BATTLE+480s 收到 105（60 秒）→ DEATHMATCH 开场收到 60（60 秒）`。

### 2.3 职业能量与技能数值（Hypixel 口径）

| 职业 | 每次命中能量 | 出处 |
|---|---|---|
| Zombie | 12（近战/弓），被箭射中 +2、被击中 +1 | `megawalls/MWZombie.java:64-67` |
| Skeleton | 弓：`25 × 拉弓力度%` | `MWSkeleton.java:63` |
| Herobrine | 25（近战/弓） | `MWHerobrine.java:52-53` |
| Creeper | 20 | `MWCreeper.java:67-68` |
| Enderman | 20 | `MWEnderman.java:62-63` |
| Golem | 10 | `MWGolem.java:62-63` |
| （旁证，另一套实现） | Zombie 4 / Skeleton 20 / Herobrine 10 / Creeper 10 | `MegaArena/Class.java:46-51` |

> 两套实现刻度不同（MegaArena 的「每刀能量」更小）。本方案以 **horsenuggets 的 100 能量制** 为准，因为它的技能文案与能量刻度自洽（例：Herobrine 25/刀 → 4 刀一次 Wrath，与原版手感一致）。

### 2.4 药水与消耗品

| 项目 | 数值 | 出处 |
|---|---|---|
| 治疗药水 | 自定义 `mwHeal=7` → 实际回 `7×2-4 = 10 HP` | `megawalls/MWHealth.java:89` |
| 速度药水 | Speed II，15 秒 | `MWPotions.java:63` `SPEED, 15*20, 1` |
| 再生药水（Golem） | Regen III，12 秒，3 瓶 | `MWGolem.java:229` |
| 减缓+虚弱溅射（Golem） | Slow I + Weakness IV，7 秒 | `MWPotions.java:111-112` |
| 金苹果冷却 | 30 秒（本方案自定，config 可调） | 参考数据包用 `used:golden_apple` 计分项做频次限制（`scores.mcfunction`） |

### 2.5 与本期无关但需记录的数据包机制（避免遗漏）

- 准备期：`gamerule keep_inventory true`、`difficulty easy`、`random_tick_speed 5`、翻越基岩墙直接死亡（`system/start.mcfunction:62-85`）。
- 战斗期：`keep_inventory false`、`difficulty hard`、击杀额外掉「玩家头颅 + 速食金苹果」、其他死因掉凋零骷髅头（`system/phase/battle_start.mcfunction:16-34`）。
- 死斗期：`effect resistance 2 4`、饥饿提示、集体传送到 Y=300 独立平台（`death_fight.mcfunction:8,22,26`）。
- 死亡处理：`death/out.mcfunction:33` 死斗内死亡给 GAME 计时 **+300 tick（15 秒）**，即「死斗延长赛」——本方案记为可选项，默认关闭。
- 土地随机抽选/地形反向选人（`system/random_teamup/terrain/*`、`docs/terrain-draft.md` 为 GBK 编码）→ **本期不做**，地图走外部选区。

---

## 3. 模块与类清单

包根：`cn.infinite.warwall`。标注 **[P0]** 本期必做、**[P1]** 可延后（不影响验收）、**[U]** 纯逻辑类（**禁止 import org.bukkit**，用于单元测试）。

### 3.1 `core`（9 个类，约 900 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `WarwallPlugin` **B** | 插件主类，装配所有服务 | `onEnable()` / `onDisable()` / `reloadAll()` / `getServices()` |
| `MatchState` [U] **B** | 阶段枚举 | `boolean isRunning()`、`boolean isCombat()`、`static MatchState parse(String)` |
| `MatchSettings` [U] **C** | 一局的时长与开关快照 | `static MatchSettings fromConfig(ConfigurationSection)`、`MatchSettings withFastMode()`、`int ticksOf(MatchState)` |
| `PhaseRules` [U] **C** | 阶段行为矩阵 | `static PhaseRules rulesFor(MatchState, MatchSettings)`、`RespawnPolicy respawn()` |
| `RespawnPolicy` [U] **C** | `ALWAYS / WHILE_OWN_WITHER_ALIVE / NEVER` | — |
| `MatchContext` **C** | 单局运行期数据（竞技场、队伍、统计、开始时间） | `Arena arena()`、`TeamManager teams()`、`MatchRecorder recorder()`、`Instant startedAt()` |
| `MatchController` **C** | 状态机核心：计时、转移、事件广播 | `boolean startMatch(CommandSender)`、`void stopMatch(EndReason)`、`void transition(MatchState, String reason)`、`MatchState state()`、`int remainingSeconds()`、`void forcePhase(MatchState)`、`void register(Player)`、`void unregister(Player)` |
| `PhaseTimer` [U] **C** | 秒级倒计时（不依赖 Bukkit） | `void tick()`、`void reset(int seconds)`、`int remaining()`、`boolean expired()`、`float progress()` |
| `MatchListenerRegistry` **C** | 统一注册监听器（便于 reload 时反注册） | `void register(Listener)`、`void unregisterAll()` |

`MatchController.transition()` 是唯一改状态的地方，内部固定顺序：
`退出旧阶段 → PhaseRules 刷新 → 世界规则下发 → UI 刷新 → 发布 PhaseChangeEvent（自定义 Bukkit 事件）→ 进入新阶段 → 启动新计时`。

### 3.2 `arena`（8 个类，约 800 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `Arena` **C** | 一座竞技场的完整定义 | `String id()`、`World world()`、`List<TeamSpawn> teamSpawns()`、`Location center()`、`double borderRadius()`、`List<Box> walls()`、`List<Box> unbreakable()`、`List<Box> miningZones()`、`List<Box> validate()` |
| `ArenaPos` [U] **C** | 可序列化位置 | `Location toLocation(World)`、`static ArenaPos of(Location)` |
| `Box` [U] **C** | AABB | `boolean contains(int x,int y,int z)`、`boolean contains(Location)`、`long volume()`、`Box intersect(Box)` |
| `ArenaManager` **C** | 加载/保存/当前竞技场 | `void load()`、`void save()`、`Arena current()`、`boolean select(String)`、`Arena create(String id, World world)` |
| `ArenaStore` **C** | `arenas.yml` 读写 | `Map<String,Arena> read(FileConfiguration)`、`void write(FileConfiguration, Collection<Arena>)` |
| `SelectionService` **C** | 玩家 pos1/pos2 选区（按 UUID 存） | `void setPos1(Player)`、`void setPos2(Player)`、`Box current(Player)` |
| `BlockSnapshot` [U] **C** | 方块快照（材料 + BlockData 字符串） | `void capture(World, Box)`、`void restoreBatch(World, int from, int to)`、`int size()` |
| `SnapshotCodec` [U] **C** | 快照与 `byte[]` 互转（gzip 友好） | `byte[] encode(BlockSnapshot)`、`BlockSnapshot decode(byte[])` |

### 3.3 `team`（4 个类，约 350 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `TeamColor` [U] **C** | `RED/BLUE/GREEN/YELLOW` + 颜色/羊毛/显示名 | `ChatColor chatColor()`、`Material wool()`、`Component displayName()`、`static TeamColor parse(String)` |
| `TeamState` **C** | 队伍运行期状态 | `TeamColor color()`、`Set<UUID> members()`、`int aliveCount()`、`TeamWither wither()`、`ArenaPos spawn()` |
| `TeamManager` **C** | 分队、Bukkit Team 绑定、查询 | `void assign(Collection<Player>)`、`TeamState of(Player)`、`TeamState ofWither(Entity)`、`List<TeamState> all()`、`org.bukkit.scoreboard.Team bukkitTeam(TeamColor)` |
| `TeamNames` [U] **C** | 队伍名/i18n 常量 | `String prefix(TeamColor)` |

### 3.4 `wither`（4 个类，约 600 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `TeamWither` **C** | 单个凋零的包装 | `TeamColor team()`、`Wither entity()`、`double maxHealth()`、`double health()`、`boolean enraged()`、`boolean alive()`、`void damage(double, Player source)`、`void destroy(String reason)` |
| `WitherManager` **C** | 生成/销毁/技能调度 | `void spawnAll(MatchContext)`、`void tick()`、`TeamWither byEntity(Entity)`、`boolean anyAlive()`、`List<TeamWither> alive()` |
| `WitherSkill` [U] **C** | 5 个技能枚举与数值 | `WitherSkill random()`、`void cast(TeamWither, Collection<Player>)` |
| `WitherSettings` [U] **C** | 从配置读取的调参对象 | `static WitherSettings fromConfig(ConfigurationSection)` |

### 3.5 `combat`（8 个类，约 900 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `TrueDamage` **C** | 穿甲伤害（走 `LivingEntity#damage` 的 CUSTOM 因） | `static void apply(LivingEntity target, double amount, Player source)`、`static void applyRaw(LivingEntity, double)` |
| `CombatRules` [U] **C** | 伤害系数/开关的快照 | `static CombatRules fromConfig(ConfigurationSection)` |
| `CombatListener` **C** | 核心伤害拦截 | `@EventHandler void onDamage(EntityDamageByEntityEvent)` |
| `AttackCooldownListener` **C** | 1.9+ 冷却防绕过 | `@EventHandler void onHeld(PlayerItemHeldEvent)`、`void onSwap(PlayerSwapHandItemsEvent)` |
| `DeathListener` **C** | 死亡结算 | `@EventHandler void onDeath(PlayerDeathEvent)` |
| `RespawnListener` **C** | 复活点/保护 | `@EventHandler void onRespawn(PlayerRespawnEvent)` |
| `CombatTracker` [U] **C** | 击杀/助攻归属 | `void recordDamage(UUID victim, UUID damager, double amount, long tick)`、`Optional<UUID> lastDamager(UUID victim)`、`Set<UUID> assisters(UUID victim)`、`void clear(UUID)` |
| `ItemRuleListener` **C** | 金苹果冷却、禁用物品、经验球取消 | `@EventHandler void onConsume(PlayerItemConsumeEvent)`、`void onPickup(EntityPickupItemEvent)`、`void onExpSpawn(EntitySpawnEvent)` |
| `ProtectionListener` **C** | 准备期保护 | `@EventHandler void onBreak(BlockBreakEvent)`、`void onPlace(BlockPlaceEvent)`、`void onBucket(PlayerBucketEmptyEvent)` |

### 3.6 `kit`（10 个类，约 1500 行；6 个职业实现是主要工作量）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `Kit`（接口） **C** | 职业契约 | `KitId id()`、`Material icon()`、`int iconModel()`（**预留，返回 -1 表示不写 CMD**）、`void equip(KitContext ctx)`、`String description()`（多行） |
| `KitId` [U] **C** | 6 个枚举 + 中文名 | `static KitId parse(String)`、`String zhName()` |
| `KitContext` **C** | 装配上下文（玩家、队伍、能量服务、插件） | — |
| `KitRegistry` **C** | 注册/查询 | `Kit get(KitId)`、`Collection<Kit> all()`、`Kit selected(Player)`、`void select(Player, KitId)` |
| `EnergyService` **C** | 能量增减、显示、满能量判定 | `void add(Player, int)`、`boolean consume(Player)`、`int energy(Player)`、`void reset(Player)`、`int energyPerHit(KitId)` |
| `AbilityService` **C** | 技能触发入口 | `@EventHandler void onInteract(PlayerInteractEvent)`、`boolean tryCast(Player)`、`void onCooldownReady(Player)` |
| `AbilityItem` **C** | 技能物品构建（原版 Material + 队伍色显示名 + Lore；CMD 字段预留不写） | `static ItemStack create(KitId)`、`static boolean isAbilityItem(ItemStack)`、`static boolean matchesKit(ItemStack, KitId)` |
| `ZombieKit` / `SkeletonKit` / `HerobrineKit` / `CreeperKit` / `EndermanKit` / `GolemKit` **C** | 各 ~150 行 | `equip(KitContext)`、`onAbility(Player)`、`onHit(Player victim)`、`onDamaged(Player)`、`onKill(Player victim)`（后 4 个由 `AbilityService`/`CombatListener` 反射式分发，接口里给默认空实现） |

### 3.7 `wall`（2 个类，约 300 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `WallService` **C** | 墙的捕获/移除/还原 | `void captureAll(Arena)`、`void removeAll(Arena)`、`int restoreAll(Arena)`、`boolean isWallBlock(Location)` |
| `WallRestoreTask` **C** | 分帧还原（默认 2000 方块/tick） | `void start()`、`boolean done()` |

### 3.8 `ui`（5 个类，约 500 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `SidebarService` **C** | 每玩家独立记分板：阶段/计时/四队凋零血量/存活数 | `void applyAll(MatchContext)`、`void update()`、`void clear(Player)` |
| `ActionBarService` **C** | 能量条、圈外警告 | `void showEnergy(Player)`、`void showBorderWarning(Player, double dist)` |
| `TitleService` **C** | 阶段过场标题 | `void phaseTitle(MatchState)`、`void broadcastTitle(Component, Component)` |
| `Messages` **C** | 文案（config 可覆盖占位符） | `Component get(String key, Object... args)`、`void raw(CommandSender, String key, Object... args)` |
| `SoundService` **C** | 统一播放点 | `void play(Player, Sound, float, float)`、`void phaseSound(MatchState)` |

### 3.9 `stats`（6 个类，约 600 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `PlayerMatchStats` **C** | 单局单玩家统计 | `kills/deaths/assists/damageDealt/damageTaken/witherDamage/blocksBroken/mvpScore()` |
| `MatchRecord` **C** | 一局结果 | `String arena()`、`Instant startedAt()`、`int durationSeconds()`、`Map<UUID,PlayerMatchStats> players()`、`List<TeamResult> ranking()` |
| `StatsStore`（接口） **C** | 持久化抽象 | `void savePlayer(UUID, PlayerLifetimeStats)`、`void saveMatch(MatchRecord)`、`PlayerLifetimeStats load(UUID)` |
| `YamlStatsStore` **C** | YAML 实现（异步写） | 同上，内部 `CompletableFuture` 落盘 |
| `MatchRecorder` **C** | 采集与结算 | `void onDamage(...)`、`void onDeath(...)`、`void onWitherDamage(...)`、`MatchRecord finish()` |
| `ResultView` **C** | 结算界面 | `void show(MatchRecord)`、`static UUID mvp(MatchRecord)`、`List<String> lines(MatchRecord, int n)` |

### 3.10 `command`（5 个类，约 700 行）

| 类 | 职责 | 关键公开方法 |
|---|---|---|
| `WarwallCommand` **C** | 根命令分发 + Tab | `boolean onCommand(...)`、`List<String> onTabComplete(...)` |
| `SetupCommand` **C** | `/warwall setup` 子命令树 | `boolean handle(CommandSender, String[])` |
| `DebugCommand` **C** | `/warwall debug` | `boolean handle(CommandSender, String[])` |
| `StatsCommand` **C** | `/warwall stats [player]` | `boolean handle(...)` |
| `CommandGuards` [U] **C** | 参数解析与范围校验 | `static int intArg(String, int min, int max, int def)`、`static TeamColor team(String)` |

**合计：39 个类 + 6 个职业实现。**（原计划里的 `resourcepack` 三件套已随「不做资源包」取消）

---

## 4. 配置 schema

### 4.1 `config.yml`

```yaml
# ===== 基础 =====
debug: false                       # 开=打印阶段转移与伤害调试日志
language: zh_CN                    # 预留，当前仅 zh_CN
prefix: "&8[&c战墙&8] &r"          # 聊天前缀（& 颜色码）

# ===== 阶段时长（秒）=====
phases:
  countdown-seconds: 30            # 1..600，进 PREP 前的等待
  prep-seconds: 300                # 60..1800，挖矿发育期
  wall-drop-seconds: 10            # 3..60，墙倒过场
  battle-seconds: 600              # 120..3600，常规战斗
  deathmatch-seconds: 180          # 60..1200，死斗
  ended-seconds: 20                # 5..120，结算展示
  warn-at-seconds: [300, 180, 60, 30, 10]   # 准备期剩余多少秒时播报

# ===== 玩家 =====
players:
  max-health: 40.0                 # 2.0..40.0，写 Attribute.MAX_HEALTH 的 base value
  heal-on-kill: 4.0                # 0..20，击杀者回血（穿甲、可溢出到上限）
  respawn-delay-seconds: 5         # 0..30，BATTLE 期死亡复活等待
  respawn-invulnerable-seconds: 3  # 0..15，复活后无敌
  keep-inventory-in-prep: true     # PREP/WALL_DROP 死亡不掉落
  drop-head-on-death: true         # 战斗期被玩家击杀掉头颅
  drop-golden-apple-on-death: true # 战斗期被玩家击杀额外掉 1 个金苹果
  golden-apple-on-death-cooldown: 0
  spectator-after-wither-death: true  # 己方凋零没了 → 死亡后旁观
  clear-kit-on-spectate: true

# ===== 战斗 =====
combat:
  natural-regeneration: false      # 强制关世界自然回血（UHC）
  friendly-fire: false             # 写 Team#setAllowFriendlyFire
  melee-damage-multiplier: 1.0     # 0.1..5.0
  projectile-damage-multiplier: 1.0
  true-damage-multiplier: 1.0      # 职业真伤的全局系数
  attack-knockback: 1.0            # 0..4，写 Attribute.ATTACK_KNOCKBACK 的 base value
  max-no-damage-ticks: 20          # 1..40，setMaximumNoDamageTicks
  reset-cooldown-on-swap: true     # 切手/换栏重置攻击冷却（防 1.9 冷却绕过）
  assist-window-seconds: 10        # 1..30
  assist-min-damage: 6.0           # 造成这么多伤害才算助攻
  gapple-cooldown-seconds: 30      # 0..300，金苹果冷却（0=不限）
  potion-blacklist: [POISON, WITHER, SLOWNESS, WEAKNESS, LEVITATION, HARM]  # 喝下即取消并提示
  item-blacklist: [END_CRYSTAL, RESPAWN_ANCHOR, TOTEM_OF_UNDYING, ELYTRA, ENDER_CHEST, SHULKER_BOX, ENCHANTED_GOLDEN_APPLE]

# ===== 凋零 =====
wither:
  max-health: 1000.0               # 100..4000
  invulnerable-seconds: 5          # 0..120，生成后无敌（PREP 期恒无敌）
  enrage-health-percent: 50        # 1..100，低于该百分比进入狂暴
  skull-interval-seconds: 4        # 1..30，普通状态骷髅射击间隔
  enrage-skull-interval-seconds: 2 # 1..30
  skill-interval-seconds: 7        # 1..60，狂暴后技能间隔
  skill-radius: 20                 # 4..40，技能判定半径
  aggro-radius: 40                 # 8..80，进入射程才计时
  damage-to-wither-multiplier: 1.0 # 0.1..5.0，玩家对凋零的伤害系数

# ===== 边界 / 缩圈 =====
border:
  base-radius: 300.0               # 50..2000，PREP 起生效
  damage-per-second: 0.5           # 0..20，圈外基础掉血（另叠加 WorldBorder 自身伤害）
  warning-distance: 5              # 0..50
  shrink-steps:                    # 相对 BATTLE 开始时间
    - at-battle-seconds: 300
      radius: 180.0
      duration-seconds: 90
    - at-battle-seconds: 480
      radius: 105.0
      duration-seconds: 60
  deathmatch:
    radius: 60.0                   # 0..1000
    duration-seconds: 60           # 收到该值的时长
    damage-per-second: 4.0         # 死斗圈外掉血
  outside-warning-interval-ticks: 40

# ===== 墙 =====
wall:
  max-volume: 200000               # 单块墙面板允许的最大方块数（超了拒绝捕获）
  restore-per-tick: 2000           # 1..20000，还原分批大小
  drop-sound: BLOCK_STONE_BREAK

# ===== UI =====
ui:
  sidebar: true
  actionbar-energy: true
  titles: true
  sidebar-update-ticks: 20

# ===== 视觉（纯原版）=====
visual:
  # 预留开关：日后接材质包时置 true，代码只改这一行 + 素材，不动逻辑
  use-custom-model-data: false
  custom-model-data-base: 1000        # 若开启，CMD = base + kit.ordinal()
  hide-attributes: true               # 给自定义物品加 ItemFlag（隐藏“+7 攻击伤害”等提示），枚举名编译期确认
  item-glint-on-ability-ready: true   # 能量满时给技能物品套附魔光效（真实附魔 + 光效）
  team-colored-names: true            # 自定义物品显示名按队伍色着色

# ===== 统计 =====
stats:
  enabled: true
  keep-match-records: 200          # 0..5000，超出后删最旧的
  announce-mvp: true

# ===== 文案（节选，全部可覆盖）=====
messages:
  no-permission: "&c你没有执行该命令的权限。"
  not-in-game: "&c本局尚未开始。"
  already-running: "&c已有一局正在进行。"
  phase.prep: "&e资源收集阶段——挖掘、合成、加固你的基地。"
  phase.wall-drop: "&c天空之壁已经倒塌！"
  phase.battle: "&6战斗阶段——摧毁敌方凋零。"
  phase.deathmatch: "&4死斗阶段——凋零全部死亡，没有退路。"
  wither-destroyed: "&c%team% 的凋零已被摧毁，该队不再复活。"
  border-shrinking: "&e世界边界将在 %time% 秒内缩小至 %radius% 格。"
  border-outside: "&c你已在安全区外，正在持续掉血。"
  respawn-in: "&7你将在 %seconds% 秒后复活。"
  eliminated: "&c你的队伍已被淘汰，转为旁观。"
  ability-ready: "&a技能已就绪，右键技能物品释放。"
```

### 4.2 `arenas.yml`

```yaml
default-arena: ""          # 留空 = 启动时不自动选
arenas:
  arena1:
    enabled: true
    world: "world"         # 必填，世界必须已加载
    min-players: 8
    max-players: 100
    border-center: { x: 0.0, z: 0.0 }   # 缺省 = center 的 x/z
    center: { x: 0.0, y: 64.0, z: 0.0 } # 中央刷钻/死斗中心
    lobby:      { x: 0.5, y: 64.0, z: 0.5, yaw: 0.0, pitch: 0.0 }
    spectator:  { x: 0.5, y: 80.0, z: 0.5, yaw: 0.0, pitch: 0.0 }
    teams:
      red:
        spawn:   { x: 100.5, y: 64.0, z: 0.5, yaw: 90.0, pitch: 0.0 }
        respawn: { x: 100.5, y: 64.0, z: 0.5, yaw: 90.0, pitch: 0.0 }  # 可省，默认=spawn
        wither:  { x: 110.5, y: 65.0, z: 0.5 }
      blue:   { ... }
      green:  { ... }
      yellow: { ... }
    walls:                       # 墙面板，WALL_DROP 时整段移除
      - { min: { x: 50, y: 40, z: -150 }, max: { x: 51, y: 90, z: 150 } }
    unbreakable:                 # 任何阶段都不许破坏（凋零房）
      - { min: { x: 105, y: 60, z: -5 }, max: { x: 120, y: 70, z: 5 } }
    mining-zones: []             # 空 = 全图可挖；非空 = 只有这些盒里能挖
    no-build-zones: []           # 禁止放置
    diamond-drops:
      - { x: 0.5, y: 65.0, z: 0.5, every-seconds: 300, amount: 2, item: DIAMOND }
```

缺省值规则：`border-center` 缺省取 `center.x/z`；`respawn` 缺省取 `spawn`；`mining-zones` 为空表示不限制；`diamond-drops` 为空表示不刷。

`Arena#validate()` 必须检查并返回**人类可读的中文错误列表**：世界不存在 / 四队缺 spawn / wither 坐标在虚空或未加载区块 / 任意两个 team spawn 距离 < 30 / wall 盒体积超 `wall.max-volume` / center 不在边界内。

### 4.3 `/warwall setup` 子命令树 ⇄ schema 对照

| 命令 | 写入的键 |
|---|---|
| `/warwall setup create <id> [world]` | `arenas.<id>`（骨架） |
| `/warwall setup select <id>` | 内存当前竞技场 |
| `/warwall setup pos1` / `pos2` / `clear` | 选区（内存，不落盘） |
| `/warwall setup team <color> spawn [here]` | `arenas.<id>.teams.<color>.spawn` |
| `/warwall setup team <color> respawn [here]` | `...teams.<color>.respawn` |
| `/warwall setup team <color> wither [here]` | `...teams.<color>.wither` |
| `/warwall setup center [here]` | `...center` |
| `/warwall setup border center [here]` | `...border-center` |
| `/warwall setup border radius <n>` | `config.yml` 的 `border.base-radius`（全局，写入时会提示“这是全局值”） |
| `/warwall setup wall add` | `...walls[]`（追加当前选区） |
| `/warwall setup wall list` / `remove <i>` / `clear` | `...walls` |
| `/warwall setup unbreakable add` / `list` / `clear` | `...unbreakable` |
| `/warwall setup mining add` / `list` / `clear` | `...mining-zones` |
| `/warwall setup nobuild add` / `list` / `clear` | `...no-build-zones` |
| `/warwall setup lobby [here]` / `spectator [here]` | `...lobby` / `...spectator` |
| `/warwall setup diamond add <every> <amount>` | `...diamond-drops[]` |
| `/warwall setup min-players <n>` / `max-players <n>` | `...min-players` / `...max-players` |
| `/warwall setup validate` | 不写盘，跑 `Arena#validate()` 打印结果 |
| `/warwall setup save` | `ArenaStore.write()` → `arenas.yml` |

---

## 5. 职业（Kit）设计表

通用规则（6 职业一致）：

- 装备全部是**原版物品**，不依赖客户端模组，**也不需要资源包**；技能物品放快捷栏第 9 格（index 8），用「各自代表物 + 队伍色显示名 + Lore」区分。
- `ItemMeta#setCustomModelData(Integer)` **预留但默认不写**（`visual.use-custom-model-data: false`）。日后要接材质包，只需打开开关 + 补素材，`AbilityItem`/`Kit#equip` 的构建代码不动。详见 §8。
- 能量满 100 时，**右键技能物品**释放；`PlayerInteractEvent` 里判定 `isAbilityItem` + `getAction().name().startsWith("RIGHT_CLICK")`。
- 盔甲：统一铁制；个别职业按参考实现换部位（Skeleton 头盔、Creeper 护腿、Golem 全套、Enderman 靴子）。
- 死亡后 `equip()` 重新发放（复活时装备不保留，与 MegaWalls 一致）。
- 所有职业初始都给：铁剑/石剑、铁镐、64 圆石、16 熟牛排、2 瓶自愈药水（回 10 HP）、2 瓶速度 II 15 秒。

### 5.1 数值表

| 职业 | 初始武器/盔甲 | 被动（P） | 主动技能（A） | 能量 | 克制 |
|---|---|---|---|---|---|
| **Zombie 僵尸** | 铁剑、铁甲全套；+4 熟牛排、64 圆石 | **坚韧**：被箭命中后获得力量 I + 速度 II 6 秒（冷却 15 秒）；**挖掘加速**：破坏方块获得急迫 III 5 秒 | **治疗之环**：治疗自己 8 HP，5 格内队友各 5 HP | 12/刀，被箭射中 +2 | 克 Creeper 的爆发（能回血）；被 Herobrine 的持续真伤慢慢磨死 |
| **Skeleton 骷髅** | 石剑、弓、64 箭、铁头盔（弹射保护 II）；铁镐 | **回收**：箭命中后返还 2 支箭、+2 饥饿值；**灵敏**：箭命中后速度 II + 再生 I 7 秒（速度部分冷却 14 秒） | **爆炸箭**：射出一支爆炸箭，6 格半径造成 6 点伤害（半穿甲），不破坏地形 | 弓：`25 × 拉弓力度%` | 克 Golem 这类慢速近战；被 Enderman 突进贴脸 |
| **Herobrine 恐惧魔王** | 钻石剑（耐久 X）、铁头盔（保护 II）；铁镐 | **力量**：击杀获得力量 I 6 秒；**连击**：每 3 次命中获得速度 II 3 秒 + 再生 I 5 秒 | **狂怒之雷**：5 格半径内所有敌人受 4.5 点真伤 + 闪电特效 | 25/刀 | 克密集阵型与 Creeper；被 Skeleton 远距离消耗 |
| **Creeper 爬行者** | 铁剑、铁甲；每级护腿加成（简化：铁护腿 + 爆炸保护 I） | **意志**：生命低于 20 HP 时获得速度 II + 再生 I 12 秒（冷却 20 秒） | **自爆**：3 秒引信后在 6 格半径造成最多 10 点真伤，每远离 1 格衰减 0.75，最低 5 点 | 20/刀 | 克 Golem 等坦克（真伤穿甲）；被 Enderman 位移规避 |
| **Enderman 末影人** | 钻石剑、铁甲、铁靴（摔落保护 II） | **灵魂吞噬**：击杀回 3 HP，助攻回 1.5 HP；**末影抗性**：满能量时获得 10 秒再生 I（冷却 5 秒） | **传送**：向视线方向传送最多 25 格，落地获得速度 III 5 秒（独立冷却 6 秒） | 20/刀 | 克 Skeleton 等后排；被 Golem 拉近后硬拼 |
| **Golem 傀儡** | 铁剑、全套铁甲（胸甲保护 I）；铁镐 | **铁皮**：受到的真伤减少 20%；被箭命中获得抗性 I 9 秒 | **铁拳**：4.5 格半径内敌人受 6 点真伤并被向自己拉拽 | 10/刀 | 克 Herobrine / Creeper 的真伤；被 Skeleton 远程风筝 |

### 5.2 实现要点

- `EnergyService.energyPerHit(KitId)` 直接查表返回上表的值；Skeleton 特殊：`energy += (int)(25 * charge)`，`charge` 用 `Player#getCooledAttackStrength(float)` 或 `Projectile` 的力度近似（**注意**：箭的拉弓力度在 1.21 需从 `Arrow#getVelocity()` 或 `Player#isHandRaised` 估算，实现时用 `Arrow#getVelocity().length()` 归一化，标 **UNVERIFIED**）。
- 职业技能对象实现 `Kit` 接口 + 4 个默认空方法钩子：`onHit(Player victim)`、`onDamaged(Player attacker)`、`onKill(Player victim)`、`onProjectileHit(Player victim, Projectile proj)`。`AbilityService` 与 `CombatListener` 统一分发，避免每个职业各写一份监听器。
- 真伤统一走 `TrueDamage.apply()`：先在 `EntityDamageByEntityEvent` 里标记 `DamageCause.CUSTOM`，Golem 的 20% 减免在 `TrueDamage` 内部按 `KitId` 判断（照抄 `MWHealth.java:117-120` 的思路）。
- 所有技能冷却/能量都存 `PlayerState`（挂在 `MatchContext` 的 `Map<UUID, PlayerRuntime>`），不写 PDC，避免落盘污染。

---

## 6. UHC 战斗规则细节

### 6.1 关闭自然回血

- 进入 PREP 时对该世界下发：`world.setGameRule(GameRule.NATURAL_REGENERATION, false)`（`combat.natural-regeneration` 仍为 false 时强制关闭）。
- 一并下发：`DO_IMMEDIATE_RESPAWN=false`（我们要自定义复活延迟）、`KEEP_INVENTORY=false`（由事件层逐阶段覆盖）、`MOB_GRIEFING=false`、`DO_FIRE_TICK=false`、`RANDOM_TICK_SPEED=3`、`ANNOUNCE_ADVANCEMENTS=false`、`SHOW_DEATH_MESSAGES=true`。
- 局结束后**不得**直接把 gamerule 改回去（可能与其它插件/世界冲突）：在 `arenas.<id>` 记录「本插件改过的键 + 原值」，`ENDED` 时恢复。

### 6.2 金苹果 / 药水限制

| 规则 | 做法 |
|---|---|
| 附魔金苹果 | 进 `item-blacklist`，`PlayerItemConsumeEvent` 取消 + 提示 |
| 普通金苹果 | 允许，但 `player.setCooldown(Material.GOLDEN_APPLE, gapple-cooldown-seconds*20)`；`hasCooldown` 时取消 |
| 治疗药水 | 允许，自定义 `mwHeal` 语义用 PDC 存（`NamespacedKey(plugin,"mw_heal")`，INTEGER），`PlayerItemConsumeEvent` 里读出来自算回血量，**不走原版 Instant Health**（避免与 `mwHeal` 双算，照抄 `MWHealth.java:89` 的公式 `heal = mwHeal*2-4`） |
| 速度药水 | 允许（Speed II 15 秒） |
| 黑名单药水 | `potion-blacklist` 内的效果喝下即取消 |

### 6.3 攻击冷却（保留 1.9+ 手感）

- **不做任何削弱**：不修改 `ATTACK_SPEED` 的 base value，武器原样。
- 防绕过：`PlayerItemHeldEvent`（MONITOR）+ `PlayerSwapHandItemsEvent` → `player.resetCooldown()`（1.21.11 已核实存在，语义「把冷却计数器归零，重新开始冷却」）。效果等价于 `wallwarplugins/NoQuickSwapNms.java` 的 `resetAttackStrengthTicker`，但**无需 NMS**。
- `max-no-damage-ticks` 默认 20（原版玩家值）。若要更快的连击节奏调到 10，注意会放大真实伤害职业的收益。
- 能量只在「真实命中」时累加：判定条件照抄 `megawalls/EnergyManager.java:75` —— `getNoDamageTicks() >= 12` 时不给能量，`damage == 0` 或 cancelled 也不给。

### 6.4 伤害与击退系数

| 项 | 做法 |
|---|---|
| 近战 | `EntityDamageByEntityEvent` 里 `event.getDamager() instanceof Player` → `damage *= combat.melee-damage-multiplier` |
| 弹射物 | `damager instanceof Projectile` 且 `getShooter() instanceof Player` → `*= projectile-damage-multiplier` |
| 真伤 | `TrueDamage.apply()`：`LivingEntity#damage(amount)` + 直接 `setHealth(health-amount)`（照 `MWHealth.java:114-127`：先 `damage(0.01)` 触发红血反馈，再改血；血量不足直接用大伤害击杀） |
| 击退 | 只改 `Attribute.ATTACK_KNOCKBACK` 的 base value（默认 0，即原版）；不改速度向量 |
| 凋零伤害 | `wither.damage-to-wither-multiplier` 系数；凋零不吃击退、不吃火、不吃落地 |
| 环境伤害 | 落地/爆炸/火焰正常；`fall-damage` 不减免（UHC 风格） |

### 6.5 死亡处理

| 阶段 | 掉落 | 击杀者 | 复活 |
|---|---|---|---|
| PREP / WALL_DROP | 不掉落（`getDrops().clear()`） | 记统计 | 1 秒后原地复活（掉血原因若是自杀则回出生点） |
| BATTLE | 全掉落；若死于玩家，**额外掉 1 个玩家头颅**（`SkullMeta#setOwningPlayer`）+ 1 个金苹果 | 击杀者 +4 HP、击杀数 +1、播报 | 己方凋零存活 → `respawn-delay-seconds` 后回 `respawn` 点；凋零已毁 → 旁观 |
| DEATHMATCH | 全掉落 | 同上 | 直接旁观，不再复活 |

实现顺序（`DeathListener`）：

1. 记录死亡原因与击杀者（`CombatTracker` 给 lastDamager，10 秒窗口）。
2. 组 `MatchRecorder.onDeath()`。
3. 按阶段处理 `getDrops()`：非战斗期 `clear()`；战斗期追加头颅 + 金苹果。
4. `setDroppedExp(0)`，`EntitySpawnEvent` 取消经验球。
5. 判定「该队是否已被淘汰」：`aliveCount == 0 且无人可复活` → 全队旁观 + 播报。
6. 若可复活：`player.setRespawnLocation(respawnPos, true)`（1.21.11 已核实），传送到旁观/等待区，`respawn-delay-seconds` 后传送回场并给 3 秒无敌（`setInvulnerable(true)` 或 `LivingEntity#setNoDamageTicks(60)`）。
7. 若不可复活：`player.setGameMode(SPECTATOR)`，清空背包。

断线处理：`PlayerQuitEvent` → 计入死亡（不掉落、不算击杀），标记 `disconnected=true`；重连（`PlayerJoinEvent`）若当局仍在跑且未淘汰 → 传送回场，按当前阶段重发装备。

### 6.6 友伤规则

- 三层保险：
  1. 计分板 Team：`team.setAllowFriendlyFire(false)`（已核实签名）。
  2. `CombatListener` 里同队直接 `event.setCancelled(true)`（防止 Team 绑定缺失）。
  3. 凋零伤害：只有「非自己队」的玩家能造成伤害，自己队打自己队凋零 **取消并提示**「不能攻击己方凋零」。

### 6.7 凋零受击规则

| 规则 | 值/做法 |
|---|---|
| 生成 | `world.spawn(loc, Wither.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false)`，**`setAware(false)`** 固定在基座，`setPersistent(true)`；阶段无敌用 `setInvulnerable(true)` 到 `wither.invulnerable-seconds` 结束 |
| 血量 | `getAttribute(Attribute.MAX_HEALTH).setBaseValue(wither.max-health)` 后 `setHealth(max)` |
| 名字 | `setCustomName("红队凋零")`，`setCustomNameVisible(true)` |
| 攻击 | `WitherManager.tick()` 每 `skull-interval-seconds` 向最近的敌对玩家发射 1 颗凋零骷髅（`wither.launchProjectile(WitherSkull.class, dir)`），狂暴后间隔减半 + 每 7 秒放一次 `WitherSkill` |
| 受伤 | 只接受「敌对玩家造成的近战/弹射/真伤」；其它一律取消（含火焰、掉落、TNT、其它生物） |
| 狂暴 | `health <= max * enrage-health-percent / 100` → `enraged = true`，播报全服 |
| 死亡 | 播报「<队伍> 的凋零已被摧毁」；该队所有存活玩家收到「你不再复活」；记 `MatchRecorder.onWitherDamage` 归属 |
| 全灭 | 最后一个凋零死亡 → `MatchController.transition(DEATHMATCH)` |
| 清理 | 局结束 `wither.destroy()`，并 `EntityDeathEvent` 里拦掉凋零的掉落物 |

> 备注：`Wither#setInvulnerableTicks(int)` / `setInvulnerabilityTicks(int)` **已核实存在**（主代理 javap paper-api 1.21.11），但它管的是「生成后的自然无敌期」，与阶段语义不同；本项目统一用 `setInvulnerable(boolean)` + 自建计时，避免两套无敌互相打架。
>
> **硬性约束（已核实，违反即编译失败）**：1.21.11 的 `Mob` 上没有 `setAI`，只有 `Mob#setAware(boolean)` / `isAware()`。

---

## 7. 缩圈死斗

### 7.1 收缩曲线（可配 + 纯函数实现）

`BorderCurve`（[U] 纯逻辑）输入：阶段、阶段内已过秒数、`border` 配置；输出：当前半径、是否正在收缩、圈外每秒伤害。

```java
public final class BorderCurve {           // 不 import org.bukkit
    public static double radiusAt(MatchState state, int elapsedSeconds, BorderSettings s);
    public static double damageAt(MatchState state, BorderSettings s);
}
```

默认曲线：

| 时刻 | 半径 | 方式 |
|---|---|---|
| PREP 开始 | 300 | `setSize(300)` 瞬设 |
| WALL_DROP | 300 | 不动 |
| BATTLE +0s ~ +300s | 300 | 不动 |
| BATTLE +300s | 300 → 180 | `setSize(180, 90*20L)`（**已核实**带时长重载） |
| BATTLE +480s | 180 → 105 | `setSize(105, 60*20L)` |
| DEATHMATCH 开始 | 105 → 60 | `setSize(60, 60*20L)` |
| ENDED | 恢复（可选，默认不恢复） | — |

实现方式：**只在阶段进入 + 到点时调用一次** `WorldBorder#setSize(double, long)`，让服务端自己做线性插值，不逐 tick 改尺寸（避免发包风暴）。所有 `setCenter(double,double)` 在 PREP 进入时调用一次。

### 7.2 圈外提示与伤害

- 每 `outside-warning-interval-ticks`（默认 40 tick = 2 秒）遍历在场玩家：
  - `dist = player 到 border 中心的水平距离 - border.getSize()/2`
  - `dist > 0` → `ActionBarService.showBorderWarning(player, dist)`：`&c安全区外 %d 格，正在掉血`，并播 `Sound.BLOCK_NOTE_BLOCK_BASS`。
  - 每 1 秒结算一次额外伤害：`TrueDamage.applyRaw(player, border.damage-per-second)`（WorldBorder 自身的 `damage-amount` 也同时生效，两者叠加，配置里已说明）。
- 进入安全区提示一次「已回到安全区」。

### 7.3 结束判定与名次

`MatchController` 在 DEATHMATCH 每秒检查：

```
aliveTeams = 仍有「非旁观存活玩家」的队伍
if (aliveTeams.size() <= 1) → 结束
if (deathmatch 计时耗尽) → 结束
if (all players spectator) → 无胜者结束
```

名次排序（`MatchRecord.ranking()`，纯函数，可单测）：

1. 是否获胜（唯一存活队 / 计时结束时的排序第一名）
2. 存活玩家数（降序）
3. 队伍总击杀（降序）
4. 队伍总伤害（降序）
5. 最后击杀发生得更晚的队伍优先
6. 仍相同 → 并列

**计时结束且多队存活**时：按上面 2→5 排序，第一名记「胜」，其余按序记 2/3/4 名（对应 Hypixel「击杀最多的队获胜」的规则，这里升级为多键排序）。

---

## 8. 原版视觉方案（本期不做资源包）

### 8.0 结论与边界

- 本期**不提供资源包**（用户已确认「暂时走原版」）：不打包、不分发、不维护 `pack_format`、不碰 1.21.4+ 的「物品模型定义」体系（`assets/<ns>/items/*.json`）—— 这一整套兼容风险直接归零。
- 可辨识度全部靠四件事表达：**原版 Material 选型 + 显示名（队伍色）+ Lore + 附魔光效 / ItemFlag**。
- `ItemMeta#setCustomModelData(Integer)` 在构建函数里**预留形参** `int model`，但 `visual.use-custom-model-data: false` 时传 `-1` 且**不调用 setter**。日后接材质包只需改配置 + 补素材，`AbilityItem` / `Kit#equip` 一行代码不用动。

### 8.1 职业代表物（选择 GUI + 玩家端可辨识）

| 职业 | 代表 Material | 选择理由 | 技能物品 Material |
|---|---|---|---|
| Zombie | `ROTTEN_FLESH` | 僵尸掉落物，零歧义 | `GOLDEN_CARROT` |
| Skeleton | `BONE` | 骷髅掉落物 | `BONE` |
| Herobrine | `REDSTONE` | 红眼/雷电意象 | `BLAZE_ROD` |
| Creeper | `GUNPOWDER` | 苦力怕掉落物 | `GUNPOWDER` |
| Enderman | `ENDER_PEARL` | 末影意象，同时是传送职业 | `ENDER_EYE` |
| Golem | `IRON_INGOT` | 铁傀儡 | `IRON_BLOCK` |

GUI（27 格）：标题「选择职业」，第 11/12/13/15/16/17 位放 6 个职业图标（代表物 + 名字 + Lore），底行放「关闭」`BARRIER`。Lore 内容：初始装备清单 / 被动 / 主动 / 每刀能量。

### 8.2 关键道具与 UI 的原版表达

| 道具 / 元素 | Material | 表达方式 |
|---|---|---|
| 技能触发物 | 见 8.1 右列 | 显示名「&e&l<职业> · <技能名>」；Lore 写「能量满后右键释放」「当前能量 <n>/100」；`ItemFlag` 隐藏属性；能量满时 `addUnsafeEnchantment(Enchantment.UNBREAKING, 1)` + `ItemFlag.HIDE_ENCHANTS` 制造光效，消耗能量后移除 |
| 治疗药水 | `POTION` | 显示名「&c治疗药水 · 回 5 心」；实际回血走 PDC `mw_heal` 自算（见 §6.2），不用原版 Instant Health |
| 速度药水 | `POTION` | 「&b速度药水 · 速度 II 15 秒」 |
| 队伍信物 / 基地旗帜 | `TeamColor.wool()` / 对应 `BANNER` | 出生点放旗、背包里放羊毛信物 |
| 敌方凋零指向 | `COMPASS` | 显示名「&a指向最近的敌方凋零」，`Player#setCompassTarget(Location)`（标准 API，编译验证） |
| 击杀掉落的头颅 | `PLAYER_HEAD` + `SkullMeta#setOwningPlayer` | 显示名「&4<玩家名> 的头颅」 |
| 凋零血条（侧边栏） | 无贴图 | 纯文字：`&c红 ❤ 950/1000`，队伍色由 `Team#setColor` 提供 |
| 能量条 | 无贴图 | 原版经验条：`player.setLevel(energy)` + `setExp(energy/100f)`（两份 OSS 实现同款），动作栏再补一行文字 |

### 8.3 音效清单（全部用原版 `Sound` 常量）

> 原来的资源包目录结构（`pack.mcmeta` / `sounds.json` / `assets/*`）已随「本期不做资源包」整体移除。
> 命名依据：`Sound` 是 interface，常量写作 `Sound.BLOCK_NOTE_BLOCK_PLING` 这种扁平形式（已核实）。下表常量名写代码时以 IDE 补全为准，缺哪个就换同语义项。

| 场景 | Sound 常量 | 音量 / 音调 |
|---|---|---|
| 阶段倒计时最后 5 秒（每秒） | `Sound.BLOCK_NOTE_BLOCK_PLING` | 1.0 / 1.6 |
| 阶段切换（例如 PREP→BATTLE） | `Sound.ENTITY_ENDER_DRAGON_GROWL` | 0.6 / 1.0 |
| 墙倒塌 | `Sound.ENTITY_GENERIC_EXPLODE` + `Sound.BLOCK_STONE_BREAK` | 1.0 / 0.8、1.0 / 0.6 |
| 凋零受击（单次掉血 ≥20） | `Sound.ENTITY_WITHER_HURT` | 0.8 / 1.0 |
| 凋零死亡 | `Sound.ENTITY_WITHER_DEATH` | 1.0 / 1.0 |
| 凋零进入狂暴 | `Sound.ENTITY_WITHER_SPAWN` | 1.0 / 0.7 |
| 技能能量攒满 | `Sound.ENTITY_PLAYER_LEVELUP` | 0.5 / 1.8 |
| 技能释放 | `Sound.ENTITY_ILLUSIONER_CAST_SPELL` | 1.0 / 1.2 |
| 技能命中（真伤结算） | `Sound.ENTITY_PLAYER_ATTACK_CRIT` | 0.8 / 1.0 |
| 缩圈提示 | `Sound.BLOCK_ANVIL_LAND` | 0.7 / 1.0 |
| 圈外掉血警告 | `Sound.BLOCK_NOTE_BLOCK_BASS` | 1.0 / 0.8 |
| 死斗开场 | `Sound.ENTITY_ENDER_DRAGON_GROWL` | 1.0 / 0.6 |
| 击杀提示 | `Sound.ENTITY_EXPERIENCE_ORB_PICKUP` | 1.0 / 1.4 |
| 复活倒计时 | `Sound.BLOCK_NOTE_BLOCK_HAT` | 0.8 / 1.0 |
| 队伍淘汰 / 本方凋零被毁 | `Sound.ENTITY_VILLAGER_DEATH` | 1.0 / 0.6 |
| 结算 MVP | `Sound.UI_TOAST_CHALLENGE_COMPLETE` | 1.0 / 1.0 |

实现位置：`SoundService`（§3.8），方法 `play(Player, Sound, float, float)` 与 `phaseSound(MatchState)`；上表所有常量集中在这一个类里，将来想换音效只改这里。

### 8.3 1.21.4+ 物品模型的坑（必须写进实现约定）

**1.21.4 起旧 `models/item/*.json` 的 `overrides` + `predicate.custom_model_data` 谓词体系已废除**，改为 `assets/<ns>/items/<item>.json` 的「物品模型定义」，用 `minecraft:select` / `minecraft:range_dispatch` 的 `property: minecraft:custom_model_data` 来分支（来源：minecraft.wiki `Items_model_definition`，2026-11-06 核实）。

因此实现约定：

- 代码侧：`ItemMeta#setCustomModelData(Integer)` 写入逻辑 ID（`1000 + kit.ordinal()`）。**若实测客户端不认**，退回 `ItemMeta#getCustomModelDataComponent()/setCustomModelDataComponent(...)`（已核实存在）写入结构化 CMD。
- 包侧：先只对 **4 个物品**做覆盖（`iron_sword`、`bow`、`stone_sword`、`diamond_sword`），其余职业图标走 **GUI 菜单**（`Inventory` 里放 `PAPER` 带 CMD，只影响图标显示，不影响游戏内物品）。
- 具体的 `entries/cases/fallback` 字段名与结构**以实现时以 minecraft.wiki 为准**（附录 A #14 标 UNVERIFIED-EXACT），D 阶段必须进客户端实测一次：拿技能物品看是否显示自定义图标。
- `pack_format` 数值同样 UNVERIFIED，用 `supported_formats` 宽区间兜底。

### 8.4 下发流程与失败降级

```
玩家加入 → PackDistributor.send(player)
  ├─ 已完成加载过（缓存 UUID+hash）→ 跳过
  ├─ 资源包禁用/未配置 → 跳过
  └─ addResourcePack(id=固定 UUID, url, sha1, prompt, force)
PlayerResourcePackStatusEvent:
  SUCCESSFULLY_LOADED → 记录，发提示「资源包已加载」
  DECLINED / FAILED_DOWNLOAD / INVALID_URL → 提示「资源包未生效，CustomModelData 图标将显示为默认贴图，不影响玩法」并记 WARN 日志
```

降级原则：**资源包纯粹是表现层**，任何失败都不能阻塞开局。所有自定义物品同时带 `displayName` + `lore`，即使没有任何贴图也能玩。

### 8.5 端口与网络风险

- `EMBEDDED` 模式必须让 `public-base-url` 对客户端可达；本机测试用 `http://127.0.0.1:8123`，公网服要把 `public-base-url` 改成公网地址（或在反代上开 `/warwall-pack.zip`）。
- 客户端对 `http://` 是接受的，但公网建议走 https 反代，避免中间人篡改（hash 会校验失败，玩家只会看到下载失败）。
- 端口被占用 → `EmbeddedPackServer.start()` 捕获 `BindException`，WARN 日志 + 自动降级为「不下发资源包」，不抛异常。

---

## 9. 数据与结算

### 9.1 字段

单局单玩家（`PlayerMatchStats`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `kills` / `deaths` / `assists` | int | 助攻按 `assist-window-seconds` + `assist-min-damage` 判定 |
| `damageDealt` / `damageTaken` | double | 玩家对玩家（不含凋零） |
| `witherDamage` | double | 对敌方凋零造成的伤害 |
| `blocksBroken` / `blocksPlaced` | int | 准备期挖矿量 |
| `energySpent` | int | 释放技能次数 ×100 |
| `kitId` | String | 本局职业 |
| `survivedSeconds` | int | 存活时长 |
| `placement` | int | 队伍名次 1..4 |
| `mvpScore` | double | 见下 |

累计（`PlayerLifetimeStats`，按 UUID）：

`games, wins, kills, deaths, assists, kdr, bestKillStreak, totalWitherDamage, totalDamageDealt, lastPlayedAt`

### 9.2 持久化：**选 YAML**

结论：YAML，不上 SQLite。理由：

1. 零依赖、无需 shadow 打包 JDBC 驱动（`sqlite-jdbc` 约 13 MB，且要处理 native 加载）。
2. 单局单竞技场、每局最多 100 人，写盘量极小。
3. 备份/人工排查最方便（直接看文件）。

文件布局：

```
plugins/Warwall/
├── config.yml
├── arenas.yml
├── stats/<uuid>.yml              # 每人一个文件，累计数据
└── matches/<yyyyMMdd-HHmmss>-<arena>.yml   # 每局一个文件，保留 stats.keep-match-records 个
```

写入策略：主线程只做快照（`Map` 拷贝），落盘走 `runTaskAsynchronously`，`onDisable` 时同步刷一次。`StatsStore` 是接口，将来换 SQLite 只加一个实现。

### 9.3 结算界面与 MVP

- **标题**：`&6&l对局结束` / 副标题 → 胜者队名 + 名次。
- **聊天**：逐行打印 1-4 名（队名、存活数、总击杀）、本方 MVP。
- **个人摘要**：击杀/死亡/助攻/伤害/凋零伤害/挖矿量 + 本局得分。
- **MVP 公式**（可单测）：

```
mvpScore = kills * 3.0
         + assists * 1.0
         + witherDamage / 10.0
         + damageDealt / 20.0
         + (存活到结束 ? 5.0 : 0.0)
```

  取全场（不分队伍）最高者；并列时先比 `kills`，再比 `witherDamage`。
- 结算后 20 秒回 `WAITING`，清空 `MatchContext`。

---

## 10. 验收标准

### 10.1 构建（D 执行）

| 步骤 | 命令 | 判据 |
|---|---|---|
| 1. 编译 | `mc_gradle` projectDir=`D:\Larkdreamcode\warwall` task=`build` timeoutMs=**1800000** | `BUILD SUCCESSFUL`；`build/libs/warwall-<version>.jar` 存在 |
| 2. 单测 | `mc_gradle` task=`test` timeoutMs=600000 | 全部通过；无 `@Disabled` 残留 |
| 3. 资源包任务 | `mc_gradle` task=`packZip` timeoutMs=300000 | `build/pack/warwall-pack.zip` 存在且 < 20 MB |
| 4. jar 内容 | `mc_gradle` task=`jar` 后检查（或直接 `jar tf`） | 含 `plugin.yml`、`config.yml`、`arenas.yml`、`pack/warwall-pack.zip`、所有 `.class` |
| 5. 清理重编 | `mc_gradle` task=`clean` 然后 `build` timeoutMs=1800000 | 二次构建仍成功（验证没有依赖陈旧产物） |

首次构建要下载 paper-api 及其传递依赖，冷缓存建议给到 30 分钟；构建失败时看 `--stacktrace` 输出。

### 10.2 单元测试要点（纯逻辑类，不启动服务器）

| 测试类 | 断言 |
|---|---|
| `PhaseTimerTest` | 秒→tick 换算；`tick()` 到 0 后 `expired()`；`progress()` 单调递减 |
| `MatchSettingsTest` | `config` 缺键时取默认；`withFastMode()` 把所有阶段压到 10 秒 |
| `BorderCurveTest` | 5 个关键时刻（0/299/300/480/deathmatch）半径符合曲线；`damageAt` 单调不减 |
| `PhaseRulesTest` | 每个阶段的 `respawn/dropOnDeath/witherAttackable` 与 §1.3 矩阵逐行一致 |
| `BoxTest` | `contains` 边界（min/max 含端点）；`volume`；`intersect` 空 |
| `SnapshotCodecTest` | 编解码 round-trip 后 `Material` 与 `BlockData` 字符串逐条相等 |
| `CombatTrackerTest` | 窗口内 2 人打伤害 → 2 个助攻；超窗后清空；自杀无 lastDamager |
| `StatsRankingTest` | 6 条排序键逐层生效；全平局返回并列 |
| `EnergyTest` | 上限 100 封顶；`consume` 不足 100 返回 false；每职业每刀数值查表正确 |
| `ArenaValidateTest` | 缺队伍/世界名错误/盒子超限 → 返回对应中文错误串 |

> 架构约束（必须遵守，否则单测写不出来）：`core`/`arena`/`combat`/`stats` 里的 `[U]` 标注类**禁止 import `org.bukkit.*`**（`Box`、`ArenaPos` 需要坐标计算的话用 `double` 而非 `Location`，转换放在调用方）。

### 10.3 Paper 1.21.11 冒烟测试

准备（D 手工）：

```
建目录 D:\Larkdreamcode\warwall\.smoke\
  1) 复制 D:\Larkdreamcode\silverhunter\.build-tools\mcdata\1.21.11-server.jar 为 .smoke\server.jar
  2) 写 .smoke\eula.txt:            eula=true
  3) 写 .smoke\server.properties（最小集）:
       online-mode=false
       level-type=minecraft\:flat
       level-name=world
       spawn-protection=0
       max-players=8
       view-distance=4
       sync-chunk-writes=false
       enable-command-block=false
  4) 复制 build\libs\warwall-*.jar 到 .smoke\plugins\
```

**Run A —— 命令驱动（约 90 秒，验证加载/命令/状态机跳转）**

`.smoke\commands.txt`：

```
warwall status
warwall debug arena world 0 100 0
warwall setup select _debug
warwall setup validate
warwall debug fast on
warwall debug solo on
warwall start
warwall debug phase PREP
warwall debug phase WALL_DROP
warwall debug phase BATTLE
warwall debug energy 100
warwall debug wither red kill
warwall status
warwall debug phase DEATHMATCH
warwall debug end
warwall stop
stop
```

执行：

```
cd D:\Larkdreamcode\warwall\.smoke
"C:\Program Files\Java\jdk-21\bin\java.exe" -Xms2G -Xmx2G -jar server.jar --nogui < commands.txt
```

判据：

- 日志出现 `[Warwall] 已启用`，且**没有** `Could not load plugin` / `Unsupported api-version`。
- `warwall status` 每次输出都包含阶段名与剩余秒数。
- `warwall debug arena` 后能在世界里看到 4 个平台 + 4 个凋零 + 一圈墙（人工 `/tp` 看一眼，或看日志里打印的方块数）。
- `warwall debug wither red kill` 后日志有「红队凋零已被摧毁」。
- `grep -iE "Exception|Caused by|ERROR"` 结果为空（允许 `WARN`）。

**Run B —— 时间驱动（约 150 秒，验证计时器自行推进）**

`.smoke\commands.txt` 换成：

```
warwall debug arena world 0 100 0
warwall setup select _debug
warwall debug fast on
warwall debug solo on
warwall start
```

执行（**故意让它超时被杀**，`mc_gradle` 会杀掉整棵进程树）：

```
mc_gradle  projectDir=D:\Larkdreamcode\warwall  task=smokeServer  timeoutMs=150000
```

（`smokeServer` 由 B 提供一个 `Exec` 任务，工作目录 `.smoke`，`commandLine` 用 `System.getenv('JAVA_HOME')` 拼 `bin/java`，缺省回退硬编码 `C:\Program Files\Java\jdk-21\bin\java.exe`。）

判据：`.smoke\logs\latest.log` 里按时间顺序出现 `COUNTDOWN → PREP → WALL_DROP → BATTLE`，且每段没有报错；边界半径日志从 300 变到 180/105。

**Run C（可选，人工 5 分钟）**：真客户端连进去，跑一遍完整局（不 fast），确认：能挖矿、墙倒后有声音、凋零能被打、队友不掉血、死后能复活、凋零死了转旁观、缩圈有提示、结算有 MVp。

### 10.4 验收清单（Host 用）

- [ ] `build` / `test` / `packZip` 三个任务全绿
- [ ] jar 结构正确，`plugin.yml` 的 `api-version` 是 `1.21.11`
- [ ] Run A 无异常，状态机可跳
- [ ] Run B 阶段按时间自动推进
- [ ] `config.yml` / `arenas.yml` 的每个键都在 §4 里有说明，代码里没有硬编码数值
- [ ] 6 个职业都能 `/warwall kit <id>` 发放且技能可用（debug energy 100 后右键）
- [ ] 没有任何 `org.bukkit` 进入 `[U]` 类（`grep` 校验）

---

## 11. 风险与取舍

### 11.1 本期做 / 不做

| 做（P0） | 不做（本期明确砍掉） | 砍掉后的降级 |
|---|---|---|
| 状态机 6 阶段 + 调试旁路 | 多竞技场并发/多房间 | 单局单场，`MatchController` 单例 |
| 四队 + 四凋零 | 猎人模式 / BOSS 追击 / 事件系统（数据包 `event/*`） | 完全不做 |
| 墙捕获/移除/还原 | 地形随机抽选与反向选人（`random_teamup/terrain/*`） | 地图由 `setup` 选区固定 |
| 6 职业 + 能量制 | 职业等级（I~IX）与金币升级商店 | 每职业只有 1 档数值 |
| UHC 战斗 + 1.9 冷却 | 队友发光（NMS/Netty 或 PacketEvents） | 队伍前缀 + Tab 颜色 + 罗盘指向最近的敌方凋零（`Player#setCompassTarget`，标准 API 待编译验证） |
| 缩圈死斗 | 死斗独立平台传送（数据包 Y=300 那套） | 沿用主战场，靠缩圈逼到中央 |
| YAML 统计 + 结算/MVP | MySQL / 跨服统计 | 只落本地 |
| 资源包（音效 + 职业图标） | 全量自定义物品贴图 | 只覆盖 4 个武器 + 6 个 GUI 图标，其余靠名字/lore |
| `/warwall setup` 全命令树 | WorldEdit 依赖 | 自带 AABB 选区 |

### 11.2 风险清单（按严重度）

| # | 风险 | 影响 | 应对 |
|---|---|---|---|
| R1 | **1.21.4+ 物品模型体系**：旧 `models/item` + `overrides` 失效，CMD 分支写法变了 | 自定义图标不显示 | 包只覆盖 4 个物品；实现时以 minecraft.wiki 为准；D 阶段必须客户端实测；失败即降级为纯名字 |
| R2 | `pack_format` 数值未知 | 客户端提示资源包不兼容 | 用 `supported_formats` 宽区间；实测后写死 |
| R3 | 凋零在 1.21.11 的行为差异（AI、破方块、无敌帧） | 凋零乱飞/拆家 | **`setAware(false)`** 固定基座（`setAI` 在 1.21.11 的 `Mob` 上不存在，主代理 javap 已核实）；`MOB_GRIEFING=false`；阶段无敌用 `setInvulnerable(boolean)` |
| R4 | 墙快照体积大、还原卡主线程 | 局末卡顿 | `wall.max-volume` 上限 20 万；分帧 2000/ tick；还原在 `ENDED` 阶段做（此时玩家已传送走） |
| R5 | **JDK toolchain 解析失败**（本机 jdk-21 与 jdk-25 并存，Gradle 可能选 25） | 构建失败 | `options.release = 21` 兜底；toolchain 找不到时删掉 toolchain 配置改用 `release`；不用 Gradle 的自动下载（离线可能失败） |
| R6 | `resetCooldown()` 语义与客户端指示器不同步 | 手感差异/玩家困惑 | Run C 人工验证；若不一致改成只在「切到剑」时重置，避免影响弓/药水 |
| R7 | `PlayerDeathEvent` + `RespawnLocation` + `KEEP_INVENTORY` 的组合顺序 | 掉落/复活点错乱 | 统一在 `DeathListener` 里显式 `getDrops().clear()`，不依赖 gamerule；复活点每次死亡都重设 |
| R8 | 资源包内置 HTTP 端口被占 / 公网不可达 | 玩家看不到贴图 | 捕获 `BindException` 降级；配置项允许外链 |
| R9 | 计分板与其它插件冲突（共用 main scoreboard） | 侧边栏被覆盖 | 每玩家 `Bukkit.getScoreboardManager().getNewScoreboard()` 独立板；Team 也建在独立板上 |
| R10 | 队伍 Team 在玩家离开后残留 | 内存泄漏/颜色错乱 | `ENDED` 时 `team.unregister()` + 清空引用 |
| R11 | 断线重连导致状态错乱 | 双份能量/重复计杀 | `PlayerRuntime` 以 UUID 为键，重连复用之；`PlayerQuitEvent` 标记 disconnected |
| R12 | 世界边界是 per-world 的，会影响该世界所有玩家（含旁观/路人） | 误伤 | 文档要求专用世界；`arenas.world` 建议是独立世界 |
| R13 | 回调顺序：`BlockBreakEvent` 与墙/凋零房判定 | 准备期被挖穿 | 三级判定顺序：阶段 → unbreakable → walls → mining-zones |
| R14 | 性能：每 tick 遍历玩家 + Sidebar 刷新 | TPS 抖动 | Sidebar 每 20 tick 一次；边界检测 40 tick 一次；不进 `PlayerMoveEvent` |
| R15 | 参考源码本身的不一致（数据包 40 分钟开发期 vs Hypixel 5 分钟） | 数值争议 | 已建「数值基线表」逐项标出处，默认取 Hypixel 口径，全部可在 config 改 |

### 11.3 需要 Host 后续确认的开放问题（不阻塞施工）

1. 单局最少人数到底 8 还是 100？（当前默认 8，`min-players` 可配）
2. 墙倒前是否要「翻墙直接死」（数据包行为）？当前不做。
3. 死斗是否要给「残存队伍加时 15 秒」（数据包 `death/out.mcfunction:33`）？当前不做，留配置点。
4. 是否需要中文以外的语言？当前只有 `zh_CN`。

---

## 附录 A：API 核对清单

> 规则：凡是没在这里列出的 Paper API，实现时**必须**先在 `jd.papermc.io/paper/1.21.11/` 或技能参考文件里查到再写。

| # | 用途 | 签名 | 来源 | 状态 |
|---|---|---|---|---|
| 1 | 依赖坐标 | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`，仓库 `https://repo.papermc.io/repository/maven-public/` | 主代理已核实 | ✅ |
| 2 | plugin.yml 版本 | `api-version: '1.21.11'` | 主代理核实 `apiVersioning.json` 的 `currentApiVersion` | ✅ |
| 3 | 攻击冷却 | `Player#getCooldownPeriod(): float`、`getCooledAttackStrength(float): float`、`resetCooldown(): void` | 主代理 javap + jd.papermc.io/paper/1.21.11 `Player.html`（本次复核条目存在） | ✅ |
| 4 | 属性 | `Attribute.MAX_HEALTH / ATTACK_SPEED / ATTACK_DAMAGE / ATTACK_KNOCKBACK`（无 `GENERIC_` 前缀） | 主代理 javap | ✅ |
| 5 | 无伤帧 | `LivingEntity#getNoDamageTicks()/setNoDamageTicks(int)/getMaximumNoDamageTicks()/setMaximumNoDamageTicks(int)` | 主代理 javap | ✅ |
| 6 | 物品模型 | `ItemMeta#setCustomModelData(Integer)`、`getCustomModelDataComponent()/setCustomModelDataComponent(...)` | 主代理 javap | ✅ |
| 7 | 音效 | `Sound` 是 interface，常量写法不变（如 `Sound.BLOCK_NOTE_BLOCK_PLING`） | 主代理 javap；技能手册 `world-and-block.md` §5 提示版本间会变 | ✅ |
| 8 | 药水/附魔 | `PotionEffectType.SPEED/SLOWNESS/STRENGTH/INSTANT_HEALTH/INSTANT_DAMAGE/REGENERATION`；`Enchantment.SHARPNESS/PROTECTION/PROJECTILE_PROTECTION/FIRE_ASPECT/POWER/SMITE` | 主代理 javap | ✅ |
| 9 | 队伍 | `Team#prefix(Component)`、`setColor(ChatColor)`、`setAllowFriendlyFire(boolean)`、`setOption(Team.Option, Team.OptionStatus)` | 主代理 javap | ✅ |
| 10 | 边界 | **`WorldBorder#changeSize(double newSize, long ticks)`（1.21.11 首选，单位是 tick）**；`setSize(double)` 瞬间设置；`setCenter(double,double)`、`setDamageAmount/setDamageBuffer/setWarningDistance`。⚠️ `setSize(double,long)` 与 `setSize(double,TimeUnit,long)` 自 1.21.11 起 **deprecated(forRemoval=true)**，禁止使用 | 主代理实读 paper-api 1.21.11 `WorldBorder.java` 源码（2026-11-06） | ✅ **缩圈一律用 `changeSize(size, ticks)`** |
| 11 | 复活点 / 动作栏 | `Player#setRespawnLocation(Location, boolean)`；`Player#sendActionBar(String)`（javadoc 标注 **deprecated**，推荐 `Audience.sendActionBar(Component)`） | 主代理 javap + 本次 javadoc 复核 | ✅ |
| 12 | 资源包 | `Player#addResourcePack(UUID, String, byte[], String, boolean)`（**非废弃**）；`removeResourcePack(UUID)`、`removeResourcePacks()`；`setResourcePack(UUID,String,byte[],String,boolean)` 已废弃→改用 `Audience.sendResourcePacks(ResourcePackRequest)` | jd.papermc.io/paper/1.21.11 `Player.html`（2026-11-06 实读） | ✅ |
| 13 | 凋零无敌帧 | `Wither#setInvulnerableTicks(int)` 与 `setInvulnerabilityTicks(int)` **都存在** | 主代理 javap paper-api 1.21.11（2026-11-06） | ✅ 存在；但阶段控制用 `setInvulnerable(boolean)` + 自建计时，语义更清楚 |
| 14 | 物品模型 JSON 结构 | `assets/<ns>/items/<id>.json`，`type: minecraft:select`/`range_dispatch`，`property: minecraft:custom_model_data` | minecraft.wiki `Items_model_definition`（2026-11-06） | 结构方向 ✅，**字段名 UNVERIFIED-EXACT** |
| 15 | `pack_format`（1.21.11） | 具体数字 | 未查到 | ⚠️ **UNVERIFIED**，用 `supported_formats` 兜底 |
| 16 | 事件 | `EntityDamageByEntityEvent`（`getDamager/getEntity/getDamage/setDamage/getCause`）、`PlayerDeathEvent`（`getEntity/getDrops/getDeathMessage`）、`BlockBreakEvent`（`getBlock/isDropItems/setDropItems`）、`BlockPlaceEvent`（`getBlockPlaced/getBlockAgainst`）、`PlayerInteractEvent`（`getAction/getItem/getClickedBlock`）、`PlayerMoveEvent`（高频，勿重活） | 技能参考 `references/api/events.md` | ✅ |
| 17 | 玩家/物品 | `Player`/`ItemStack`/`ItemMeta`/`PlayerInventory`（`getItemInMainHand/setHelmet/...`） | 技能参考 `references/api/player-and-inventory.md` | ✅ |
| 18 | 调度/配置 | `BukkitRunnable#runTaskTimer/runTaskLater/runTaskAsynchronously`、`saveDefaultConfig/reloadConfig/getConfig`、`cfg.getString/getInt/getStringList/getConfigurationSection` | 技能参考 `references/api/scheduler-and-config.md` | ✅ |
| 19 | 世界/方块 | `World#getBlockAt/spawnEntity/dropItem/playSound/getPlayers`、`Block#setType/getState/breakNaturally/getLocation`、`Location#add/distance/clone`、`Material` 扁平化枚举 | 技能参考 `references/api/world-and-block.md` | ✅ |
| 20 | 命令 | `CommandExecutor#onCommand`、`TabCompleter#onTabComplete` | 技能参考 `references/api/commands.md` | ✅ |
| 21 | 物品冷却 | `HumanEntity#hasCooldown(Material)/setCooldown(Material,int)/getCooldown(Material)` | 实读 `wallwarplugins/FoodCooldownListener.java:34,43`（真实 1.21.11 插件） | ✅（用法级） |
| 22 | **生物 AI** | **`Mob#setAware(boolean)` / `isAware()`**；`Mob` 上**没有** `setAI` | 主代理 javap | ✅ **禁止写 `setAI`** |
| 23 | 生成实体 | `World#spawn(Location, Class<T>, CreatureSpawnEvent.SpawnReason, boolean)` | 主代理 javap | ✅ |
| 24 | 原版凋零血条 | `Boss#getBossBar()`（`Wither implements Boss`）；`BossBar#setVisible/setProgress/setTitle/addPlayer/removeAll/setColor/setStyle` | 主代理 javap | ✅ 用 `getBossBar().removeAll()` 关掉原版血条，改用自建四队 BossBar |
| 25 | 创建 BossBar | `Bukkit.createBossBar(String, BarColor, BarStyle, BarFlag...)` | 主代理 javap | ✅ |
| 26 | 实体开关 | `Entity#setPersistent(boolean)` / `setSilent(boolean)` / `setInvulnerable(boolean)` / `setGlowing(boolean)` | 主代理 javap | ✅ |
| 27 | 属性实例 | `AttributeInstance#setBaseValue(double)` / `getValue()` | 主代理 javap | ✅ |
| 28 | 死亡事件补充 | `PlayerDeathEvent#setKeepInventory(boolean)` / `deathMessage(Component)` / `deathScreenMessageOverride(Component)`；`PlayerRespawnEvent#setRespawnLocation(Location)` | 主代理 javap | ✅ |
| 29 | 世界规则 | `GameRule.NATURAL_REGENERATION / KEEP_INVENTORY / DO_IMMEDIATE_RESPAWN / MOB_GRIEFING / DO_FIRE_TICK / RANDOM_TICK_SPEED / ANNOUNCE_ADVANCEMENTS` | 主代理 javap | ✅ |
| 22 | gamerule | `GameRule.NATURAL_REGENERATION / KEEP_INVENTORY / MOB_GRIEFING / DO_IMMEDIATE_RESPAWN / RANDOM_TICK_SPEED / SHOW_DEATH_MESSAGES`；`World#setGameRule(GameRule<T>, T)` | 标准 Bukkit API（未逐条查 javadoc） | ⚠️ B 阶段编译验证，缺哪个改哪个 |
| 23 | PDC | `ItemMeta#getPersistentDataContainer()`、`PersistentDataContainer#set(NamespacedKey, PersistentDataType, V)`、`new NamespacedKey(Plugin, String)` | 标准 Bukkit API（1.14+） | ⚠️ 编译验证 |
| 24 | 记分板 | `Scoreboard#registerNewObjective(...)`、`Objective#setDisplaySlot/getScore/setDisplayName` | 标准 Bukkit API，**重载在 1.21 有变化** | ⚠️ 编译期选重载 |
| 25 | 头颅 | `SkullMeta#setOwningPlayer(OfflinePlayer)` | 标准 Bukkit API | ⚠️ 编译验证 |
| 26 | 罗盘指向 | `Player#setCompassTarget(Location)` | 标准 Bukkit API | ⚠️ 编译验证 |

---

## 附录 B：文件归属表（B 骨架 / C 内容 / D 复核）

### B（骨架，阶段 B 产出，不做业务逻辑）

| 文件 | 说明 |
|---|---|
| `settings.gradle` | `rootProject.name = 'warwall'` |
| `build.gradle` | java 插件、toolchain 21、paper-api compileOnly、JUnit5、`processResources` 展开 `plugin.yml`、`packZip`(Zip)、`smokeServer`(Exec)、`jar` 依赖 `packZip` |
| `gradle.properties` | `group=cn.infinite`、`pluginName=warwall`、`version=0.1.0`、`author=`、`apiVersion=1.21.11`、`paperVersion=1.21.11-R0.1-SNAPSHOT` |
| `gradle/wrapper/*`、`gradlew`、`gradlew.bat` | wrapper **8.14.5**（本机已缓存，不要手写） |
| `README.md` | 安装、`/warwall setup` 快速上手、构建命令 |
| `.gitignore` | `.gradle/`、`build/`、`.smoke/` |
| `src/main/resources/plugin.yml` | `name/main/version/api-version/authors/commands/permissions` |
| `src/main/resources/config.yml` | §4.1 全文默认值 |
| `src/main/resources/arenas.yml` | §4.2 空壳 + 注释示例 |
| `src/main/java/cn/infinite/warwall/WarwallPlugin.java` | 仅 `onEnable/onDisable` 骨架 + TODO 注释 |
| `src/main/java/cn/infinite/warwall/core/MatchState.java` | 枚举骨架 |
| `resourcepack/pack.mcmeta`、`resourcepack/pack.png` | 资源包根（其余素材归 C） |
| 包目录占位 | `core/ arena/ team/ wither/ combat/ kit/ wall/ ui/ stats/ resourcepack/ command/` |

### C（内容，阶段 C 产出）

§3 里除 `WarwallPlugin` 与 `MatchState` 外的**全部类**（含 6 个职业实现）、`resourcepack/` 下的全部素材与 JSON、`arenas.yml` 的注释完善、单元测试（`src/test/java/...`）。

### D（复核，阶段 D 产出）

| 动作 | 产物 |
|---|---|
| `mc_gradle build` / `test` / `packZip` / `clean build` | 构建日志摘要 |
| 冒烟 Run A / Run B | `VERIFY.md`（含日志片段、异常清单） |
| 静态审查 | 检查 `[U]` 类有无 `org.bukkit` 泄漏、有无硬编码数值、有无未处理的可空返回值 |
| 编译级修复 | 直接修编译错误与签名不匹配；业务逻辑缺陷只开清单，不擅自改设计 |
| 输出 | `D:\Larkdreamcode\.plans\warwall\VERIFY.md`（结论 + 问题清单 + 下一步） |

---

## 附录 C：施工顺序建议（给 C 参考，不强制）

1. **S1 骨架跑通**：`MatchState` + `PhaseTimer` + `MatchSettings` + `MatchController`（无 UI、无战斗），`/warwall debug phase` 能走完 6 个阶段 → 先让 Run B 冒出日志。
2. **S2 竞技场**：`Box`/`ArenaPos`/`Arena`/`ArenaStore`/`ArenaManager`/`SelectionService` + `/warwall setup` 全命令树 + `debug arena` 生成器。
3. **S3 队伍与凋零**：`TeamColor`/`TeamState`/`TeamManager` + `TeamWither`/`WitherManager`/`WitherSkill`，接通 `SidebarService`。
4. **S4 战斗**：`CombatListener`/`TrueDamage`/`DeathListener`/`RespawnListener`/`CombatTracker`/`AttackCooldownListener`/`ItemRuleListener`/`ProtectionListener` + `WallService`。
5. **S5 职业**：`Kit` 接口 + `EnergyService` + `AbilityService` + 6 个实现（建议顺序：Herobrine → Zombie → Skeleton → Creeper → Golem → Enderman，从简到繁）。
6. **S6 收尾**：`BorderCurve` 接 `WorldBorder`、`MatchRecorder`/`YamlStatsStore`/`ResultView`、`PackDistributor` + `EmbeddedPackServer`、资源包素材、单元测试补齐。

每完成一个 S，`mc_gradle build` 应当保持绿。
