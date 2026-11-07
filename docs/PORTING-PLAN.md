# 照搬方案：随机地图 / 建筑系统 / 自定义物品 / Boss

> 本文是给下一轮施工用的执行清单。所有体量为实测行数，不是估计。

## 一、随机地图：照搬 `infiniteCN/warwalltool`

### 1.1 为什么能照搬

`warwalltool` 和我们一样是 **Paper 1.21.11 插件**，`map` 包全部基于 Bukkit API（`World#getBlockAt`、
`Block#setType`），没有 NMS、没有反射。它耦合的是自己的 `WarwallTool` 主类与 `PresetStore`，
所以移植工作 = **改包名 + 把 3 处依赖换成我们自己的服务**，算法一行不用动。

### 1.2 要搬的文件（实测行数）

源目录：`D:\Larkdreamcode\.refs\oss\warwalltool\src\main\java\cn\infuhc\warwalltool\`

| 文件 | 职责 | 迁移后位置 |
|---|---|---|
| `map/NaturalTerrainGenerator.java` | 保留四个象限的原版地形，不复制不镜像 | `world/gen/` |
| `map/TerrainKind.java` | 地形分类（平原/雪地/沙漠/海岛/恶地/山地） | `world/gen/` |
| `map/LandTopology.java` | 陆地连通性与「出生点 64 格内 4 列近地表水、16 列原木」保底 | `world/gen/` |
| `map/TerritoryScoring.java` | 四队领地打分（原木/矿石/裸矿/遗迹/陆地面积权重） | `world/gen/` |
| `map/ScoreSettings.java` | 打分权重与门限（中位数 ±max(5%,5分)） | `world/gen/` |
| `map/TerritoryBalancePlanner.java` | 缺口补偿编排（模板 + 矿石，最多 12 轮） | `world/gen/` |
| `map/FairnessPlanner.java` | 重试策略与失败降级 | `world/gen/` |
| `map/ResourceMetrics.java` / `Resource.java` | 资源统计模型 | `world/gen/` |
| `map/ChunkMetricsIndex.java` | 按区块缓存统计，避免全图重扫 | `world/gen/` |
| `map/SnapshotScanner.java` | 区块快照，用于增量复查 | `world/gen/` |
| `map/TerritoryStructure.java` | 以队伍为单位的构建数据 | `world/gen/` |
| `map/CentralStructures.java` | 中央共享奖励结构 | `world/gen/` |
| `map/OriginalRuin.java` / `OriginalRuinCatalog.java` | 24 种 wallwar 原创自然遗迹，按命名空间估值 | `world/gen/` |
| `map/MapPreparation.java` | 总编排（生成→评分→补偿→复查） | `world/gen/` |
| `map/ArenaGeometry.java` | 四队几何（方位、半径、墙线） | `world/gen/` |
| `paper/ArenaWallPlan.java` 等 | 墙的分段计划与冷却重置 | `world/paper/` |
| `io/PresetStore.java` / `PropertyFiles.java` | 把评分结果落 `fresh-map.properties` | `world/io/` |

**合计约 2500 行**，`room/` 包（616 行，房间管理）不搬——我们单局单竞技场。

### 1.3 三处必须解耦的地方

1. **主类引用**：它们大量 `WarwallTool.plugin()` 取配置/日志；改成构造注入我们的 `WarwallPlugin`。
2. **配置读取**：`ScoreSettings` 从 `preset` 读；改成从我们的 `config.yml` 的 `mapgen.scoring.*` 读，
   默认值照抄它们的 `ScoreSettings`。
3. **方块写入**：它们直接 `setType`；我们保留同样的写法（已经验证过性能可接受）。

### 1.4 验收标准

- 四队领地分差值 ≤ `max(5%, 5分)`（它们原设计的门限）。
- 每队至少 20% 可用陆地；出生点 64 格内 ≥4 列近地表水、≥16 列近地表原木。
- 连通性：四队出生点都能走到中央。
- 日志打印四队分数 + 中位数 + 补偿轮次。

### 1.5 与现有 `MapGenerator` 的关系

现有 `world/MapGenerator.java`（我写的对称生成 + 相对距离矿区 + 连通性检查）**保留为降级路径**：
- `mapgen.mode: VANILLA_PORT`（默认，新）→ 走照搬的 `MapPreparation`
- `mapgen.mode: SYNTHETIC`（旧）→ 走现有对称生成
- `mapgen.mode: OFF` → 走 `arenas.yml` 手工图

---

## 二、建筑系统：照搬数据包 `data/wallwar/function/build/*`

### 2.1 数据包里的 14 种建筑（实测函数数）

| 目录 | 函数数 | 性质 |
|---|---|---|
| `battle` | 28 | 大型战斗建筑（多段生效） |
| `court` | 18 | 方形剧场，按 Y 高度生效 |
| `electric` | 5 | 特斯拉塔：范围内检测玩家 |
| `farm` | 7 | 粮屯：按层数概率产面包 |
| `flag` | 7 | 号令之旗：垂直 ±5 格范围 |
| `ocean` | 12 | 海之守卫 Boss 相关 |
| `player` | 6 | 玩家私有建筑（时钟等） |
| `respawn` | 10 | 复活点建筑 |
| `sand` | 17 | 沙地建筑（含低 Y 判定） |
| `spawner` | 4 | 刷怪建筑 |
| `tower` | 7 | 高塔 |
| `trans` / `trans2` | 3 + 3 | 传送类 |
| `wither` | 15 | 凋零相关建筑 |

### 2.2 移植方式

数据包用 `item_frame` + 预置 `.nbt` 结构触发。移植到 Java 后：

1. **结构**：不搬 `.nbt`（需要客户端结构文件），改成**程序化方块模板**：每种建筑一个
   `BlockTemplate`（相对坐标 + 材质列表），放置时逐块 `setType`。
2. **触发器**：用我们的「建筑凭证」物品右键地面（去掉 item_frame 这一层间接）。
3. **光环**：按数据包的 Y 区间与半径生效，用 `tick()` 每秒结算，不是每 tick。
4. **摧毁**：核心方块被换掉即拆（已实现）。

### 2.3 分期

| 期 | 建筑 | 理由 |
|---|---|---|
| P0 | `electric` `farm` `flag` `tower` | 单建筑、单光环，最容易先立起来 |
| P1 | `court` `wither` `respawn` | 需要 Y 区间或多段效果 |
| P2 | `battle` `sand` `ocean` `spawner` `trans` `player` | 复杂触发与 Boss 联动 |

---

## 三、自定义物品：照搬 `weapon/*` 与 `armor/*`

数据包实测：`weapon` 6 个函数、`armor` 19 个函数，另有 `head`（玩家头颅）、`crown`（加冕）。

现有 `item/CustomItems.java` 已有 10 件与完整框架（PDC 标识 + 武器/使用/护甲被动三类钩子），
照搬工作 = **按数据包补到 ~25 件**，并把以下几类补齐：

| 类目 | 数据包里的行为 | 移植做法 |
|---|---|---|
| 武器强化 | `weapon_rokan` / `weapon_haku` 等计分项驱动的特殊效果 | 各自的 `onWeaponHit` 分支 |
| 护甲特效 | 19 个函数覆盖抗性/反伤/免伤 | `tickArmor` + `onDamaged` |
| 头颅 | `head/*` 玩家头颅附魔 | 物品 PDC + 属性 |
| 加冕 | `crown/*` 击杀获得头衔 | 与统计系统联动 |

同时需要把 **物品获取途径** 接上：凋零/核心被摧毁时按权重掉 1 件，配合 `item.blacklist`。

---

## 四、Boss 模式：融入方式（我的判断）

数据包 Boss 模式是**独立玩法**（`#BOSS_MODE` 开关 + 独立场地 + 多阶段）。既然你要求单一融合模式，
我的做法是**按阶段切片融进现有时间轴**，而不是加一个模式开关：

| 阶段 | Boss 内容 |
|---|---|
| `BATTLE` 后期（剩余 25%） | 中央出现「守卫者」，不掉落，只用来打断刷矿节奏 |
| `FINAL_FIGHT` · `EDEN_APPLE` | **深渊守卫**（已实现）：200 HP / 8 伤害，掉伊甸之果 + 金币 |
| `FINAL_FIGHT` · `LAST_STAND` | 「深渊守卫·觉醒」：血量翻倍、附带凋零 II 光环，逼最后一波团战 |

不做：独立 Boss 场地、独立进度与掉落表、`ocean` 海之守卫（它需要自定义 Boss 实体与水域判定）。

---

## 五、执行顺序与验收

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 照搬 `map` 包并解耦三处依赖 | `clean build` 通过 + 生成一张图且四队分差 ≤5 |
| 2 | 接通 `mapgen.mode` 三档 | 三种模式各跑一次冒烟 |
| 3 | 建筑 P0 四种 | 冒烟能放置、光环生效、核心被拆后注销 |
| 4 | 建筑 P1 三种 | 同上 |
| 5 | 物品补到 25 件 | 冒烟巡检每件都能造出来、右键有效果 |
| 6 | Boss 三阶段切片 | 冒烟跑到 `LAST_STAND` 无异常 |

每一步都以 `gradlew build` + `.ops/smoke-test.ps1` 为门槛，不允许「写完就报完成」。
