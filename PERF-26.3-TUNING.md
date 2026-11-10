# Adastar 性能排查记录（JFR 实测）

用 JDK 自带 `jcmd JFR` 对运行中的整合包直接采样（不依赖游戏内命令，spark 的 datapack 触发
在函数解析期拿不到 spark 命令，故改用 JFR）。

## 采样环境

- 测试副本 `_adastar-test`，新世界 `sparkprof`（复制自有作弊权限的旧世界）
- 两次采样：run1 窗口失焦（游戏暂停）、run2 `pauseOnLostFocus:false`（持续渲染）
- run1 数据有偏差：暂停时渲染线程空闲，EntityCulling 剔除线程占比被放大到 26%；
  run2 修正后它只占 ~1%。**结论以 run2 为准。**

## run2 实测结果（75 秒窗口）

服务端 tick：平均 **6ms**、最大 27ms（20 TPS 的预算是 50ms）→ 稳态很健康。

GC：G1（G1New/G1Old，4 个并行线程），窗口内仅 2 次暂停、合计 49ms、单次最大 27ms → 不是瓶颈。

CPU 样本按线程：

| 线程 | 样本 | 占比 |
|---|---|---|
| Server thread | 652 | 55% |
| Render thread | 292 | 25% |
| CullThread | 77 | 6% |
| IO-Worker-31 | 63 | 5% |
| JM-task-2 | 48 | 4% |

自耗时最高的方法：

| 样本 | 方法 |
|---|---|
| 85 | `c2me...NewChunkHolderVanillaInterface.getTickingChunk` |
| 69 | `FramerateLimiter.limitDisplayFPS`（限帧休眠，非瓶颈） |
| 61 | `PalettedContainer.get` |
| 47 | `ServerLevel.tickChunk` |
| 45 | `Arrays.fill` |
| 30 | `Arrays.copyOf` |

按归属：vanilla 40%、JDK 23%、其他 22%、C2ME 7%、JourneyMap 2%、EntityCulling 1%。
自制模组：ChunkHeat 1 样本、Carbon 无、multicore 无 → **没有引入可测开销**。

## 结论与已采取的动作

1. **"卡"的根因是功耗策略，不是模组**。原先用"平衡"方案，N150 空闲降到 800MHz，
   进世界时频率爬升那段就是掉帧来源。已切到"高性能"，并把最小处理器状态（交流电）
   设为 100%，CPU 不再降频。
2. **GC / 内存不是瓶颈**。堆从 8192MB 调到 5120MB（原 8GB 堆在 11.8GB 机器上与系统
   争内存并催大 G1 回收），实测 GC 本身很干净。
3. **spark 的后台采样器是常驻开销**，已关（`config/spark/config.json`
   `backgroundProfiler: false`）；需要时仍可用 `/spark profiler start` 按需采样。
4. **MC 主线程单线程**，4 核机器上总 CPU 占用天花板约 25%，这是架构限制，
   不是配置能改的；帧率取决于单帧主线程耗时，与"占几核"无因果关系。

## 加载期

`Can't keep up! Running XXXXms or NN ticks behind` 出现在区块生成/首次加载阶段，属正常，
C2ME + Lithium 已在分担；稳态不再出现。

## 未做

- 未动 `pauseOnLostFocus`（正式包保持 `true`）：单人暂停省资源，是合理默认。
- 未改任何模组的 mixin 或源码；本轮只调配置。

---

## 更正（重要）：本文档 §"run2 实测结果" 的适用性受限

上述采样跑在**复制自"测试"存档**的世界里。事后核对该存档的生成器设置发现：

```
saves/测试/data/minecraft/world_gen_settings.dat
  minecraft:overworld → generator.type = minecraft:flat     ← 超平坦
  minecraft:the_nether → generator.type = minecraft:noise
```

**超平坦世界几乎不生成地形，区块生成成本极低。** 因此：

- "服务端 tick 平均 6ms、稳态健康" **只在超平坦场景成立**，不能外推到正常世界。
  正常地形有噪声地形、矿洞、结构、更多方块状态，加载与生成负担明显更高。
- GC 干净（2 次 / 49ms）这一条**仍然有效**——GC 与地形生成无关。
- 电源方案是"卡"的主因这一条**仍然有效**（它与场景无关）。
- EntityCulling 在暂停采样下被高估、修正后仅 ~1% 这一条**仍然有效**。

### 为补测做的尝试（未完成）

按 26.x 的存储位置改写世界生成器（`world_gen_settings.dat` 是 NBT，外层包一层
`{DataVersion, data}`），把 overworld 从 `minecraft:flat` 换成 `minecraft:noise`，
并删掉旧超平坦区块触发生成。NBT 读写经往返校验（读回确认 = `minecraft:noise`）。

但改造后的世界**两次都未加载成功**（停在资源重载末尾、`Starting integrated: 0`），
而不改动的原世界能正常进世界（`Starting integrated: 1`、13427 行日志）。
失败原因未隔离干净：既有"我改的世界有问题"的可能，也受**内存压力**干扰——
当时机器在**游戏未运行**时就已占用 76% 内存、仅剩 ~2.8GB
（DSH Web 界面 node+msedgewebview2 ≈ 1.4GB、Firefox×4 ≈ 1.2GB、QQ×2 ≈ 0.4GB、PixPin），
再叠加 2.5~3GB 堆的游戏进程会被挤死（无崩溃报告、无 hs_err、无系统事件日志）。

**结论：正常地形下的性能数据仍然缺失，不要引用本文档的 6ms 数字作为常态结论。**

### 已取得的、仍然有效的实测

在能跑通的世界里，10000 个物品落地时：

```
[00:16:47] GPU item instancing ready: Intel(R) Graphics                       ← Carbon 在核显上生效
[00:16:52] Can't keep up! Running 10366ms or 207 ticks behind                  ← 物品洪峰的 tick 积压
[00:17:20] Can't keep up! Running 12291ms or 245 ticks behind
```

无崩溃报告、无 JVM 崩溃日志 → **10000 物品压测没有导致崩溃**。

### 给后续测试者的硬性前置

1. 先确认世界的生成器类型，超平坦不能用来做地形/区块相关结论。
2. 采样前让机器空出至少 4~5GB 内存，否则进程会被挤死且不留任何崩溃痕迹。

---

## 真地形补充实测（有效数据，采用无头服务端）

超平坦那次采样作废后，改用**无头 Fabric 服务端 + RCON + spark** 拿真地形数据。
这条管线本身可复用，且不需要 GPU、内存占用小：

1. 26.3 的客户端 jar 内含服务端（`net.minecraft.server.Main`），fabric-loader 有
   `net.fabricmc.loader.impl.launch.knot.KnotServer` → 可用同一套 classpath 起服务端。
2. `server.properties` 里 `level-type=minecraft:normal`、`enable-rcon=true`。
3. **空服 60 秒会自动暂停**（`Server empty for 60 seconds, pausing`），此时 spark 拿不到
   统计并报 `Timed out waiting for world statistics` / `A command execution ... might be stuck`。
   必须用 carpet 的 `/player <name> spawn` 放一个假玩家保持 tick。
4. **spark 的输出走 "System chat"，只进日志、不进 RCON 回包** —— RCON 只能发命令，
   结果要回到 `logs/latest.log` 里读。`--save-to-file` 会把 profile 存到
   `config/spark/*.sparkprofile`（protobuf 格式，不是 gzip JSON）。

### 结果（60 秒采样，198 个类 / 1,642,730 样本）

按类聚合（同类的样本跨多个改写它的模组求和）：

| 类 | 样本 | 占比 |
|---|---|---|
| `net.minecraft.world.entity.Entity` | 337,405 | 20.5% |
| `net.minecraft.world.entity.LivingEntity` | 288,134 | 17.5% |
| `net.minecraft.server.level.ServerLevel` | 265,957 | 16.2% |
| `net.minecraft.world.level.Level` | 229,686 | 14.0% |
| `net.minecraft.server.level.ServerChunkCache` | 105,323 | 6.4% |
| `net.minecraft.server.MinecraftServer` | 104,501 | 6.4% |
| `net.minecraft.server.level.ChunkMap` | 91,361 | 5.6% |
| `FlowingFluid` | 24,872 | 1.5% |
| `NaturalSpawner` | 21,424 | 1.3% |

**解读**：真地形下开销以**实体 ticking（Entity+LivingEntity+Mob+Brain ≈ 40%）**与
**等级 ticking（ServerLevel+Level ≈ 30%）**为主，区块系统（ServerChunkCache+ChunkMap+
LevelChunk）约 13%。与超平坦的关键差别是**方块/流体/刷怪/实体 AI 全部复活**。

同期服务端日志：

```
Can't keep up! Is the server overloaded? Running 4915ms or 98 ticks behind
Can't keep up! Is the server overloaded? Running 2714ms or 54 ticks behind
```

即**真地形下新建世界的加载期确实会压垮服务端**（这正是超平坦掩盖掉的部分）。

自制模组占用：`dimparallel` 1.11%、`chunkheat` 1.10%、`carbon` 0%（客户端模组，服务端
绕不过去；其效果需在客户端侧验证，见前文 `GPU item instancing ready: Intel(R) Graphics`）。

### 注意 spark 归因的语义

spark 把每个栈帧归给**改写该类的模组**，所以"lithium 35%"指的是**lithium 所优化的那段
原版代码**占比，不是 lithium 自身的开销。同理 `dimparallel` 1.11% 主要是
`MinecraftServer.tickChildren` 这段原版代码。

### 仍未验证

客户端在真地形下的渲染帧率。上面的数据是**服务端**视角；客户端的 ItemEntity 渲染
（Carbon 的主场）只在合成世界里量到过，真地形下的客户端帧时间仍缺数据。
