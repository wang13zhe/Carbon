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
