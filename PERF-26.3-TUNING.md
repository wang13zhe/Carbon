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
