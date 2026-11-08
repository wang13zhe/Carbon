# Carbon 26.3 游戏内验证清单

构建产物：`build/libs/carbon-26.3-fabric-0.1.jar`（166 KB，47 个类）
依赖：Fabric Loader 0.20.1+ / Fabric API `0.165.2+26.3` / Minecraft 26.3
编译状态：`gradle compileJava` 0 错误，`gradle build` BUILD SUCCESSFUL

---

## 一、安装

1. 把 `carbon-26.3-fabric-0.1.jar` 放入 mods 目录。
2. 同时确保 `fabric-api-0.165.2+26.3.jar` 在同一 mods 目录（缺少会导致 mixin 应用失败）。
3. 启动 26.3 的 Fabric 配置。

依赖 jar 本机位置：
`C:\Users\Admin\.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.165.2+26.3\2e364989e3f6efb97e1dbd12d80d835c7ab8c26c\fabric-api-0.165.2+26.3.jar`

---

## 二、启动阶段（最先看这里）

如果 mixin 有问题，游戏会在启动阶段就失败，日志在 `logs/latest.log`。

**必须确认通过的项：**

| 检查项 | 通过标准 | 失败表现 |
|---|---|---|
| 模组被加载 | 日志出现 `Loading 2 mods` 且含 carbon | 不出现 carbon |
| Mixin 应用 | 无 `Mixin apply failed` / `InvalidInjectionException` | 启动崩溃或红字 |
| 入口点初始化 | 无 `Carbon` 相关 exception | 崩溃堆栈指向 first.carbon |
| 客户端进入主菜单 | 能正常看到主菜单 | 白屏 / 卡死 |

关键日志检索词：
```
Mixin apply failed
InvalidInjectionException
InjectionError
first.carbon
```

---

## 三、渲染验证（创造模式超平坦即可）

进入世界后，在物品栏取出一批掉落物：

```
/give @s minecraft:stone 64
/give @s minecraft:diamond 64
/give @s minecraft:enchanted_book 16
```

然后按 `Q` 连续丢弃，或：
```
/setblock ~ ~1 ~ minecraft:chest
```
用命令直接生成大量掉落物实体：
```
/summon minecraft:item ~ ~1 ~ {Item:{id:"minecraft:stone",count:64}}
```
重复多次，堆到 30+ 个掉落物。

**观察项：**

| 检查项 | 通过标准 | 失败表现 |
|---|---|---|
| 掉落物正常显示 | 物品外观、数量堆叠正常 | 物品消失 / 只剩阴影 |
| 实例化生效 | `/carbon stats` 显示 instance 数 > 0 | 显示 0 或命令报错 |
| 无花屏 | 物品贴图完整，无错位/马赛克 | 纹理错乱 |
| 无闪烁 | 静止和移动时都稳定 | 高频闪动 |
| 阴影正常 | 掉落物下方有正确阴影 | 阴影缺失或位置错 |
| 拾取动画 | 走近自动拾取，有吸附动画 | 卡住不动 / 瞬移 |
| 附魔光效 | 附魔物品有流动光效 | 光效丢失 |

---

## 四、命令验证

| 命令 | 期望结果 |
|---|---|
| `/carbon stats` | 输出绘制数、实例数、缓存统计，不报错 |
| `/carbon enabled false` | 关闭优化，掉落物渲染回到原版，帧率可能下降 |
| `/carbon enabled true` | 重新开启，渲染恢复正常 |

---

## 五、性能对照（可选）

关闭与开启各测一次，记录 F3 的 FPS：

1. `/carbon enabled false`，站定 10 秒，记录 FPS
2. `/carbon enabled true`，同样位置站定 10 秒，记录 FPS
3. 若开启后 FPS 不升反降，说明实例化路径有问题

---

## 六、重点风险区（本次改动最可能出问题的地方）

本次移植中，以下几处是按 26.3 新架构重写的，**如果出现问题优先怀疑这里**：

1. **`ItemFeatureRendererMixin` / `ShadowFeatureRendererMixin`**
   26.3 的提交机制从 `renderItem(MultiBufferSource...)` 改为 `buildGroup(FeatureFrameContext, List<Submit>)`。
   现做法是用 `@ModifyVariable` 过滤掉被接管的提交项。
   若注入失败 → 启动崩溃；若过滤逻辑有误 → 物品重复绘制或完全不画。

2. **`GenericShadowRenderer.collect()`**
   现在依赖 `ShadowFeatureRenderer.Submit.radius()` 判断是否为普通掉落物阴影（`0.15f`）。
   若 26.3 的 radius 语义变了 → 阴影错位或全丢。

3. **附魔光效**
   `ItemFeatureRenderer.getFoilRenderType()` 已移除，现改用 `BakedQuad.MaterialInfo.itemGlintRenderType()`。
   若光效绘制异常 → 优先查 `GpuItemBatch` 里 `glintType` 的传递。

4. **`IrisCompatibility`**
   整段按 `PreparedRenderType` 重写（`prepare()` / `pipeline()` / `dynamicTransforms()` / `textures()`）。
   装 Iris 光影时若崩溃，先禁用光影确认是否为本改动引起。

5. **渲染目标**
   `RenderSystem.outputColorTextureOverride` 在 26.3 已移除，现直接用 `gameRenderer.mainRenderTarget()`。
   若出现画面画到错误目标（黑屏/花屏）→ 查 `GpuFrame`。

---

## 七、出问题时请提供

1. `logs/latest.log`（重点是 crash 附近 50 行）
2. `crash-reports/` 下的文件（若崩溃）
3. 复现步骤：做了什么操作、看到了什么
4. `/carbon stats` 的输出

---

## 八、回退方法

若验证不通过需要回到 26.1.2 Fabric 版本：

```
git checkout 6f01ef4   # Fabric 移植基线（26.1.2 可编译）
```

或将 `gradle.properties` 的 `minecraft_version` 改回 `26.1.2` 重新构建。

---

## 九、编译通过后的代码审查修复记录

编译通过不等于运行正确。以下是在打包前逐条核对 MC 26.3 官方字节码后修正的问题：

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | `GpuItemBatch` proxy handler | `submitItem` 第 7 参数已改为 `ItemQuads`，代码仍强转 `List<BakedQuad>` | 运行期 `ClassCastException`，物品实例化完全失效 |
| 2 | `ItemFeatureRendererMixin` | `begin()` 用 `@Inject(HEAD)`，过滤用 `@ModifyVariable(HEAD)`，两者执行顺序不确定 | `active` 未初始化，所有提交被拒，优化静默失效 |
| 3 | `GenericItemRenderer` / `ItemBatchRenderer` | `mesh.glintType` 可能为 null 却直接用于绘制 | 附魔物品 `NullPointerException` |
| 4 | `ItemBatchRenderer` | `shouldRender` 新增的 float 参数硬编码 `1.0f` | 剔除框按整 tick 计算，快速移动时提前剔除 |
| 5 | `ItemBatchRenderer` | `sceneFoilTick` 替换后与 `sceneTick` 恒等，判断冗余 | 语义混淆，留下死字段 |
| 6 | `IrisCompatibility.draw` | `drawIndexed` 参数顺序错误：写成 `(0, 0, indices, 1, 0)` | **indexCount 传 0，Iris 路径完全不绘制**（最严重） |
| 7 | `IrisCompatibility.draw` | 每帧每 mesh 调 `type.prepare()`，内部解析全部纹理 | 每帧重复解析，掉帧 |

修复后的 `drawIndexed` 调用（与 MC 官方 `PreparedRenderType.drawFromBuffer` 一致）：

```java
pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, 0);
```

同时把 `PreparedRenderType` 缓存在 `Mesh` 构造期，绘制时复用。

### 仍需实测确认的点

以下无法靠静态分析确定，必须游戏内验证：

1. `@ModifyVariable` 对 `buildGroup` 的 `List` 参数改写是否被 Mixin 正确应用
2. 过滤后原版是否还会重复绘制被接管的物品（应不重复）
3. 阴影 `radius() == 0.15f` 判断在 26.3 是否仍成立
4. Iris 路径的实际画面是否正确
5. 附魔光效是否持续动画（26.3 由 `Util.getMillis()` 驱动）

---

## 十、性能优化记录

在保证功能正确的前提下，消除了每帧重复的对象解析与 GL 查询：

| 优化项 | 原实现 | 现实现 | 收益 |
|---|---|---|---|
| `PreparedRenderType` | 每帧每 mesh 调 `type.prepare()`，内部解析全部纹理 | Mesh 构造时解析一次并缓存 | 消除每帧 N×M 次纹理查找 |
| `CompiledRenderPipeline` | 每帧每 mesh 调 `getCompiledPipeline()` | Mesh 构造时获取并缓存 | 消除每帧 map 查找与潜在编译 |
| `GpuBufferSlice` | 每帧调 `vertices.slice()` 新建 | Mesh 构造时创建一次 | 减少每帧对象分配 |
| uniform 位置 | 每帧调 `glGetUniformLocation` 共 8 次（物品）+ 2 次（阴影） | 程序创建时查询一次并缓存 | 消除每帧 10 次 GL 字符串查找 |

优化后 `gradle build` 仍为 BUILD SUCCESSFUL，产物 `carbon-26.3-fabric-0.1.jar`（167 KB）。

### 后续可继续优化的方向（需实测数据支撑）

1. **批次合并粒度**：当前按 `Material`（纹理+剔除+glint）分组，若同类物品材质分散会产生较多 draw call
2. **实例缓冲更新策略**：`batch.upload()` 的上传时机可进一步批量化
3. **缓存失效频率**：`sceneDirty` 的触发条件可用实测调整，减少重建
4. **500 帧后的度量**：建议先用 `/carbon stats` 采集真实数据，再决定优化重点
