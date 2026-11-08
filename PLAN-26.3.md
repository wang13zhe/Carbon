# Carbon → Fabric MC 26.3 移植规划 (v2，含外部源码情报)

范围：master(0.9-A) 源码 → Fabric 26.3。基线 = `fabric-26.1.2` 分支上的 Fabric 移植提交。
构建：Java 25 + Gradle 9.2.0 + Loom 1.14.10 + Fabric API `0.165.2+26.3`。
本文只记录客观接口差异、改动项、验证条件。

## 0. v2 新增情报来源（外部，本轮取证）
- **26.3 反编译源码**：`0khs/minecraft_source_26.3`（CFR 反编译，395MB），已拉到本地 `C:\Users\Admin\code\_carbon-ref-26.3\src\`。
- **已迁移到 26.3 同类模组**（GitHub code 检索证实均改用 `SubmitNodeCollector` 体系）：GeckoLib(`bernie-g/geckolib`)、AppliedEnergistics-2、Curios、TwilightForest-fabric、MineLittlePony、Aoba-Client。
- `com.mojang:brigadier:1.3.11` 本机 gradle 缓存已有。

---

## 1. 结论先行

实测 `gradle compileJava` **32 秒**失败：**146 错误 / 122 缺失符号 / 10 文件**。迭代成本极低，本机跑，不推云端。

26.3 变化三层，**只有 L1 是机械改名**：

| 层 | 性质 | 处理 |
|---|---|---|
| L1 | `blaze3d`→`renderpearl` 部分包搬迁 + `RenderType/RenderSetup/TextureTransform` 迁入 `rendertype` 包 | 低：改 import + 核对签名 |
| L2 | 类型删除且无同名对应：`MultiBufferSource`/`OutlineBufferSource` 整体消失，改 `SubmitNodeCollector` | 高：重写提交链路（参考 GeckoLib） |
| L3 | 原版方法改名/改签名（`RenderSystem`、`Gui`、`Minecraft`、`EntityRenderDispatcher` 等） | 中：逐个 javap 对签名 |

**不能只靠 sed 改包名**，L2/L3 必须实改。

---

## 2. L1 映射（26.3 jar + `_carbon-ref-26.3` 源码双证）

| 26.1.2 | 26.3 |
|---|---|
| `com.mojang.blaze3d.opengl.GlTexture/GlSampler/GlBuffer/GlStateManager/GlCommandEncoder` | `com.mojang.renderpearl.backend.opengl.*` |
| `com.mojang.blaze3d.buffers.GpuBuffer` | `com.mojang.renderpearl.api.buffers.GpuBuffer`；新增返回 `GpuBufferSlice` |
| `com.mojang.blaze3d.vertex.VertexFormat` | `com.mojang.renderpearl.api.vertex.VertexFormat` |
| `com.mojang.blaze3d.textures.FilterMode` | `com.mojang.renderpearl.api.textures.FilterMode` |
| `net.minecraft.client.renderer.RenderType` | `net.minecraft.client.renderer.**rendertype**.RenderType` |
| `RenderSetup` / `TextureTransform` | `net.minecraft.client.renderer.**rendertype**.{RenderSetup,TextureTransform}`（javap 命中，含 `TextureTransform$OffsetTextureTransform`） |

**仍在原处勿动**（javap/unzip 证实）：`blaze3d.vertex.BufferBuilder`/`VertexConsumer`/`PoseStack`/`QuadInstance`、`blaze3d.systems.RenderSystem`、`blaze3d.vertex.QuadInstance`（新用于 `putBakedQuad`）。
jar：`blaze3d/` 剩 158 类，`renderpearl/` 268 类 —— 部分迁移。
`GlTexture` 新公开接口：`glId()/fboMipLevel()/addAssociatedFbo(FrameBufferCache$CacheKey)/addViews()/removeViews()`，父 `BaseGpuTexture`，实现 `FrameBufferAttachment`；Carbon 取 GL id 用法按此核对。

---

## 3. L2 提交链路新架构（源码级证据）

**旧**：`ItemFeatureRenderer.renderItem(MultiBufferSource.BufferSource, OutlineBufferSource, ...)` + `renderSolid/renderTranslucent`。

**新（`_carbon-ref-26.3/.../feature/ItemFeatureRenderer.java` 实测）**：
- `ItemFeatureRenderer extends RenderTypeFeatureRenderer<ItemFeatureRenderer.Submit>`，`TYPE: FeatureRendererType<Submit>`，常量 `ENCHANTED_GLINT_ITEM/ARMOR`、`NO_TINT`、`SPECIAL_FOIL_TEXTURE_SCALE`。
- `RenderTypeFeatureRenderer<Submit extends SubmitNode>` 抽象基类：`protected abstract buildGroup(FeatureFrameContext, List<Submit>)`；`getVertexBuilder(RenderType)`→`VertexConsumer.putBakedQuad(PoseStack.Pose, BakedQuad, QuadInstance)`；内部 `Group` + `List<Group> groups`。
- `Submit` = `record Submit(PoseStack.Pose, ItemDisplayContext, int light, int overlay, int outlineColor, int[] tintLayers, List<BakedQuad>, ItemStackRenderState.FoilType) implements TranslucentSubmit`。
- `OrderedSubmitNodeCollector`（物品提交入口）暴露 `submitItem(PoseStack, ItemDisplayContext, int, int, int, int[], ItemQuads, FoilType)`、`submitModel(...)`、`submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`、`order(int)`。
- `StagedVertexBuffer`（`appendDraw(format, topology, VertexSorting)`→`StagedVertexBuffer.Draw`）、`FeatureRenderPhase`、`TranslucentSubmit`、`PreparedRenderType`。
- `RenderType` 新方法：`prepare()`→`PreparedRenderType`、`canConsolidateConsecutiveGeometry()`、`sortOnUpload()`、`format()`、`primitiveTopology()`。
- `RenderSystem.getProjectionType().vertexSorting()` → `VertexSorting`。

**Carbon 侧后果**：主 mixin `ItemFeatureRendererMixin`（注入 `renderSolid/renderTranslucent/renderItem`）在 26.3 **无对应方法可注入** → 注入点需重找：
候选(a) 注入 `RenderTypeFeatureRenderer.Group`/`buildGroup` 或其 `getVertexBuilder`；候选(b) 注入 `StagedVertexBuffer` 的 draw 收集；候选(c) 在 `LevelRenderer` 侧仍存在的钩子拦截。
参考实现优先看 GeckoLib `GeoItemRenderer`/`GeoBlockRenderer`（`preRenderPass(RenderPassInfo, SubmitNodeCollector)`）与 Aoba `LevelRendererMixin.submitBlockOutline(...SubmitNodeCollector...)`。

---

## 4. L3 方法差异（待阶段 C 逐个 javap 定名，已确认存在变化）
`RenderSystem.getMatrix()/getTextures()`、`Minecraft.getMainRenderTarget()`、`Gui.getGuiTicks()`、`RenderSetup.prepareQuadList()`、`ItemStackRenderState.LayerRenderState.getModelViewMatrix()`、`TextureTransform.GLINT_TEXTURING`、`ItemFeatureRenderer.getFoilRenderType(RenderType,boolean)`、`RenderPipeline.getVertexShader()/getFragmentShader()`、`EntityRenderDispatcher.shouldRender(...)` 签名不匹配(2处)。

## 5. 错误分布（146，按文件）
GpuFrame 32 / IrisCompatibility 28 / ItemBatchRenderer 20 / GpuItemBatch 18 / GenericItemRenderer 16 / ItemFeatureRendererMixin 10 / GenericShadowRenderer 10 / ShadowFeatureRendererMixin 4 / GlStateManagerMixin 4 / GlBufferAccessor 4。

## 6. 依赖侧
`BoolArgumentType`：26.3 brigadier 不在 merged jar，改显式 `implementation 'com.mojang:brigadier:1.3.11'`（版本与 loader 传递依赖核对后再定）。`mappings` 行保持不写。

---

## 7. 阶段

**A. L1 包名/迁包替换**（只做 §2 映射，不动"勿动"类）。验证 `compileJava`(~30s)，错误应大幅下降。
**B. brigadier 依赖**，消除 `BoolArgumentType`。
**C. GPU 层签名对齐**：GpuFrame/GpuItemBatch/GlBufferAccessor/GlStateManagerMixin/GlCommandEncoderMixin，每个符号 `javap -cp jar -protected <类>` 读真实签名后改，禁猜。不改 GLSL 逻辑。
**D. 提交链路重设计（最难）**：先通读本地 `_carbon-ref-26.3` + GeckoLib/Aeba 参考 → 定注入点(a/b/c) → 改 ItemFeatureRendererMixin/ShadowFeatureRendererMixin/ItemBatchRenderer/Generic{Item,Shadow}Renderer/IrisCompatibility。
  门槛：若确认 26.3 无可接入公共接缝，**停下上报**两条路线（mixin 私有 vs 降级功能），不擅自阉割。
**E. 编译收敛**：循环至 `compileJava` 0 错，再 `gradle build` 出 jar。
**F. 游戏内验证**（隔离桌面）：Fabric 26.3 + API 0.165.2，掉落物场景确认实例化生效、无花屏、拾取/阴影正常、`/carbon stats`、`/carbon enabled false` 可用。

## 8. 仓库环境
sparse-checkout 需含 `src/build.gradle/gradle.properties/gradle/.gitignore/settings.gradle/gradlew*`，否则 `git add` 静默跳过。临时物 `_compile_*`/`_research_*`/`_blaze3d`/`_build_*` 已 gitignore。提交不含对话信息。

## 9. 查证闭环（v2 尾部补记，全部源码实证，本地 `_carbon-ref-26.3/`）

1. **注入点** → 已定。`FeatureRenderer<Submit>` 接口（35 行全文读）定义了生命周期：
   `beginPrepare(FeatureFrameContext)` → `prepareGroup(ctx, List<Submit>, boolean)` → `executeGroup(ctx, OitStage, RenderPass, int, List<Submit>, boolean)` → `finishExecute`。
   `ItemFeatureRenderer` 的实际绘制发生在 `prepareGroup`/`executeGroup`（quad→`putBakedQuad`/`putBakedQuadWithGlint`）。
   Carbon 拦截点：`@Inject ItemFeatureRenderer.prepareGroup/executeGroup`（拿到 `List<Submit>` 即可分流自己的实例化批次），或 `@Redirect RenderTypeFeatureRenderer.getVertexBuilder`。旧的 renderSolid/renderItem 三方法注入全部作废。
2. **ItemSubmit/ShadowSubmit 替代** → 已定：
   `SubmitNodeStorage.ItemSubmit` → `ItemFeatureRenderer.Submit`（record: Pose, ItemDisplayContext, light, overlay, outlineColor, tintLayers[], List<BakedQuad>, FoilType，implements TranslucentSubmit）。
   `SubmitNodeStorage.ShadowSubmit` → `ShadowFeatureRenderer.Submit`（record: Matrix4fc pose, float radius, List<EntityRenderState.ShadowPiece>，implements SubmitNode）。
   `getShadowSubmits()` → `SubmitNodeCollection.shadows`（`SimpleFeatureRenderPhase`，公开字段，`submit(Submit)`/`sortInto(Output)`/`isEmpty()`）。
3. **Iris 载体** → 部分确认：`RenderPipeline` 在 26.3 存在但 `getVertexShader()/getFragmentShader()` 已移除；`Blaze3D 大重构 = renderpearl 分层（api/backend{opengl,vulkan}/frontend）`。阶段 D 具体处理，若 Iris 26.3 未发布对应 API 则 `IrisCompatibility` 先整类禁用（config 开关），不造假实现。
4. **brigadier** → 本机缓存有 1.3.10/1.3.11；`build.gradle` 先加 `implementation 'com.mojang:brigadier:1.3.11'`，若与 loader 传递版本冲突以实际编译结果定。
5. **Sodium/Chloride** → Sodium `dev` 分支已引用 renderpearl（GitHub 检索证实，即已在做 26.3）；发布版分支最高 `26.2/stable`。Chloride 无 26.3 版本 → 阶段 F 验证不带 Chloride（Carbon 0.9-A 的 Chloride 收集逻辑仅分支路径，不影响基础验证）；Sodium 用 dev 自编译或暂不带（`SodiumCompatibility` 需运行时探测，缺 Sodium 时应自动旁路——阶段 D 检查该类是否有 null 安全）。
