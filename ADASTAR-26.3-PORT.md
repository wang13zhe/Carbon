# Adastar 2.13.1 — 26.3 移植记录

整合包路径：`C:\Users\Admin\AppData\Roaming\red.ghs.axolotl\profiles\Adastar 2.13.1`
环境：Minecraft 26.3 / Fabric Loader 0.20.1 / Java 25
关键依赖：JourneyMap 26.3-6.0.10（不是 Xaero）、ModMenu 21.0.0、Sodium 0.9.3

---

## 一、Carbon 26.3（GPU instancing 物品渲染）

源码：`C:\Users\Admin\code\Carbon`，分支 `fabric-26.1.2`，远端 `wang13zhe/Carbon`
产物：`carbon-26.3-fabric-0.1.jar`（167 KB）

### 26.3 的三层变化

编译错误从 146 降到 0，靠的是逐层区分：

**L1 包名搬迁（机械替换）**
- `com.mojang.blaze3d.opengl.*` → `com.mojang.renderpearl.backend.opengl.*`
  （GlTexture / GlSampler / GlBuffer / GlStateManager / GlCommandEncoder）
- `com.mojang.blaze3d.buffers.GpuBuffer` → `com.mojang.renderpearl.api.buffers.GpuBuffer`
- `com.mojang.blaze3d.textures.FilterMode` → `com.mojang.renderpearl.api.textures.FilterMode`
- `com.mojang.blaze3d.vertex.VertexFormat` → `com.mojang.renderpearl.api.vertex.VertexFormat`
- `net.minecraft.client.renderer.RenderType` → `net.minecraft.client.renderer.rendertype.RenderType`

**仍在原处，不要动**
`blaze3d.vertex.BufferBuilder` / `VertexConsumer` / `PoseStack`、`blaze3d.systems.RenderSystem`
——26.3 只做了部分迁移，jar 里 blaze3d 剩 158 类、renderpearl 268 类。

**L2 提交链路重写（最难）**
`MultiBufferSource` / `OutlineBufferSource` 在 26.3 彻底消失，物品提交改为
`ItemFeatureRenderer.buildGroup(FeatureFrameContext, List<Submit>)` + `FeatureRenderPhase` 相位收集。
`Submit` 变成 record：`ItemFeatureRenderer.Submit` / `ShadowFeatureRenderer.Submit`。
两个 mixin 改用 `@ModifyVariable` 过滤提交列表。

**L3 方法签名变化**
`RenderSystem.getModelViewMatrixCopy()`、`getSequentialBuffer(PrimitiveTopology)`、
`Minecraft.gameRenderer.mainRenderTarget()`、`level.getGameTime()` 取代 `Gui.getGuiTicks()`、
`RenderType.prepare()` → `PreparedRenderType`、`getShaders()` 取代 `getVertexShader()`、
`RenderPass.setPipeline` 要 compiled pipeline、`bindTexture` → `setUniform`、
`EntityRenderDispatcher.shouldRender` 多一个 partialTick 参数。

### 运行期缺陷（编译通过≠正确）

| 问题 | 后果 |
|---|---|
| `submitItem` 第 7 参已是 `ItemQuads`，代码仍强转 `List` | ClassCastException，实例化全失效 |
| `begin()` 与过滤都挂 HEAD，执行顺序未定义 | active 未初始化，优化静默失效 |
| `glintType` 可为 null 却直接用 | 附魔物品 NPE |
| `drawIndexed` 参数顺序写成 `(0,0,indices,1,0)` | indexCount 传 0，Iris 路径完全不画 |
| `LevelRenderer.extractVisibleEntities` 已搬到 `LevelExtractor` | 启动崩溃 |
| `@Inject(RETURN)` 缺前三个参数 | 启动崩溃 |
| `GlCommandEncoder.drawFromBuffers` 已拆成 `executeDraw` | 启动崩溃 |
| 主菜单时 `level` 为 null | tick 第 1 次就 NPE |

### 性能优化（每帧重复 → 构造时一次）

`PreparedRenderType`、`CompiledRenderPipeline`、`GpuBufferSlice`、
10 处 `glGetUniformLocation` 查询。

---

## 二、EntityCollisionOptimizer（原生 26.3）

来源：`github.com/water2004/EntityCollisionOptimizer`
产物：`entity_collision_optimizer-1.0.0-mc26.3-alpha.12.jar`（321 KB）

服务端实体碰撞优化，自带 Lithium 适配 mixin。与 Carbon（纯客户端渲染）无冲突。

---

## 三、ChunkHeat 1.0.3-26.3（MsptMap 的 JourneyMap 版）

来源：`github.com/changtian2248/ChunkHeatMap`（MsptMap 的 JourneyMap 复刻，作者 Drizzle）
源码：`C:\Users\Admin\code\_chunkheat`，提交 `c261048`
产物：`ChunkHeat-1.0.3-26.3.jar`（805 KB）

上游目标版本是 1.21.1，移植到 26.3，200 个错误清零。

### 26.3 的 GUI 架构变化（最大改动）

26.3 把 GUI 改成两阶段渲染状态提取：

```
旧：Screen.render(GuiGraphics, ...)  →  graphics.drawString(font, text, x, y, color)
新：Screen.extractRenderState(GuiGraphicsExtractor, ...)  →  guiRenderState.addText(new GuiTextRenderState(...))
```

新增 `chunkheat/client/GuiText.java` 统一封装文字提交。

### 其余 API 变化

- `ClientCommandManager` → `ClientCommands`；本模组自己有同名类 `chunkheat.client.ClientCommands`，
  会遮蔽导入，所以调用改成全限定名
- `ResourceLocation` → `Identifier`、`ResourceKey.location()` → `identifier()`
- `ChunkPos.asLong/toLong` → `pack`，访问器是 `x()` / `z()`
- `ClickEvent` / `HoverEvent` 改成 record → `ClickEvent.RunCommand` / `HoverEvent.ShowText`
- `PayloadTypeRegistry.playC2S/playS2C` → `serverboundPlay/clientboundPlay`
- 权限 → `Commands.LEVEL_GAMEMASTERS.check(source.permissions())`
- 聊天 → `chatListener().handleSystemMessage(...)`
- 当前屏幕 → `gui.screen()`

### 剥离 Xaero 支持

整合包装的是 JourneyMap，没有 Xaero。Xaero 路径依赖 `xaero.map` / `xaero.lib`，
删掉了只服务于它的 `ChunkFocus`、`XaeroMapOpen`、`MapOverlay`、`GuiMapMixin`。
`MapOverlay` 只是转发到 `HeatColor`，所以 `ScanSummary` 改成直接调 `HeatColor` 即可。

### 编译期依赖

从整合包取的 jar（不进 git，`libs/` 已 gitignore）：
- `journeymap-api-fabric-26.3-2.0.0.jar`（从主 jar 的 `META-INF/jars/` 里抽出）
- `journeymap-fabric-26.3-6.0.10.jar`（`Fullscreen` 类在这里，不在 API jar）
- `modmenu-21.0.0.jar`（17.0.0 太老，还用 `class_437` 混淆名，不支持 26.3）

---

## 四、minecraft-multicore-mod 1.6.0-26.3

来源：`github.com/miaotiao-lin/minecraft-multicore-mod`
源码：`C:\Users\Admin\code\_multicore`，提交 `d77228d` + gitignore 修复
产物：`minecraft-multicore-mod-1.6.0-26.3.jar`（25 KB）

### 上游仓库本身是坏的

`com.dimparallel.config.DimParallelConfig` 类不存在，而其他所有类都 import 它。
根因：`.gitignore` 里的 `config/` 规则会匹配任意层级的同名目录，
把 `src/main/java/com/dimparallel/config/` 整个丢弃了。

修复：规则改成 `/config/`，并按 README 补齐该类
（`enabled` / `maxThreads` / `deferUnloadedChunkReads` / `handoffQueue` / `maxChainedNeighborUpdates`，
配置文件 `config/dimparallel.properties`）。

服务端 tick API 在 26.2 → 26.3 之间没变，9 个 mixin 注入点逐一核对后全部命中，无需改方法体。

---

## 五、整合包级验证与调优（命令行直启，非 Loom）

用 versions JSON + Fabric loader 拼 classpath、KnotClient 直启整合包副本，覆盖主菜单与世界两个阶段。

### 进入世界阶段暴露的三个 bug（主菜单阶段测不出）

1. **ChunkHeat** `NaturalSpawnerMixin.spawnForChunk`：26.3 用单个 `List<MobCategory>` 取代三个 boolean，
   handler 仍按旧形态声明。该 mixin 首次加载发生在第一次世界 tick，故主菜单正常、进世界即
   `Exception ticking world / Invalid descriptor`。已改为匹配新签名（ChunkHeat 提交 `c12d346`）。
2. **multicore ↔ c2me**：并行维度 tick 把 `ServerChunkCache.tick` 放到工作线程，
   c2me 的 threading-issues mixin 检测到后抛 `ConcurrentModificationException`。
   两者皆服务端、单人非必需，改为默认关闭：`@WrapMethod` 直接委托原版串行体，c2me 通过。
   需要并行时手动 `enabled=true` 并移除 c2me（multicore 提交 `6924f23`）。
3. 测试副本残留旧 jar 写出的 `dimparallel.properties: enabled=true` 会覆盖新默认值，须一并清除。

据此建立静态审计：把每个 `@Inject/@WrapMethod` handler 的参数与 26.3 真实描述符逐一比对
（Mixin 允许 handler 声明目标参数的前缀子集，判定用前缀匹配而非全等）。
三个移植项目审计全部匹配，仅 Iris / JourneyMap 可选类正常缺失。

### 验证结果（148 模组，含打印机）

进世界：`Game crashed: 0`、`Exception ticking world: 0`、`ConcurrentModification: 0`、
`Mixin apply for mod failed: 0`、无新崩溃报告，Server thread 正常 tick。
加载期 `Can't keep up` 为 4 核 CPU 的初始区块/实体加载风暴，`-Xmx3G` 下同样出现，非堆大小、非 bug。

### 打印机选型（按下载量优先）

Modrinth 检索 `litematica printer`，`Litematica Printer`(3llatzyE) 311 万下载居首且官方标记 26.3
（3.2.3，前置仅 Litematica + MaLiLib；包内 0.29.1 / 0.30.2 满足 `>=0.29.0<0.30.0`、`>=0.30.1<0.31.0`），采用之。
`autyism`（GitHub 原生 26.3）与 `maoqing26zc` 合并版均验证可加载但下载量较低，autyism 备份于 `_autyism_backup.jar`。
三套 id 各异但都挂 litematica 放置流程，不可并存。
实测 `litematica_printer-26.3-3.2.3 initialized`、核心 mixin 0 失败；
对未装的 chesttracker / whereisit 报 9 条 `@Mixin target not found`，属可选集成自动跳过，无害。

### 本机（N150 4 核 / 11.8GB / 核显）配置

- `settings\game.json` 堆上限 8192 → 4096：防与系统/Firefox 争抢触发换页（备份 `_game.json.bak`）。
  此项为 OOM/换页安全，不是 `Can't keep up` 的解法。
- `options.txt` 下调七项：`entityDistanceScaling 5.0 → 0.5`（滑块下限，收益最大；注意 1.0 才是默认值）、`renderDistance 16→10`、`simulationDistance 12→8`、`particles 2→1`、`entityShadows 关`、`biomeBlendRadius 2→0`、`maxFps 90→120`（备份 `_options.bak` / `_options.before-gpu-tune.bak`）。
- `sodium-options.json` `chunk_builder_threads → 2`（4 核留主线程核）。
- `carbon-client.toml` `cacheUpdateBudgetMs → 1.0`。

### modernfix 5.27.20 有效性判定

Modrinth 无标记 26.3 的 fabric 版（`26.3 in game_versions: False`），
但 `mixin.debug.verbose` 实测 **59 个 mixin 全部 `Mixing` 成功、0 apply 失败**——
它按 feature 探测注入，不靠 game_versions 门控。
仅 1 个 `getTemperature` 与 lithium 冲突被跳过（无害，lithium 已含同款优化）。保留。
教训：非-verbose 日志没有 `Mixing` 行，不能据此误判"未生效"。

### particle_core 0.3.3+26.2

`fabric.mod.json` 声明 `minecraft: ">=26.2"`（含 26.3），启动 0 mixin 失败，按配置正常禁用对应项。
版本标签滞后但兼容，保留。

## 六、待办

1. **ChunkHeat 功能验证**：在 JourneyMap 里确认区块 MSPT 热力图真的画出来（模组已加载且 tick 无异常，但热力图本身未做视觉确认）。
2. **打印机功能验证**：加载投影并实跑一次打印任务，确认放置行为正常。
3. **开服场景**：若日后要开服，可重新启用 multicore（`enabled=true`）并移除 c2me。
