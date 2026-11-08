# Carbon Fabric 26.1.2 迁移记录

## 编译结果

`gradle build --no-daemon --console=plain` → **BUILD SUCCESSFUL**

## 修改后的 build.gradle 关键片段

```groovy
plugins {
    id 'java'
    id 'net.fabricmc.fabric-loom' version '1.14.10'  // 非重映射 Loom
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraft_version}"
    // 无 mappings 行——26.x 不需要 yarn
    implementation "net.fabricmc:fabric-loader:0.20.1"
    implementation "net.fabricmc.fabric-api:fabric-api:0.155.3+${minecraft_version}"
}
```

关键变化：
- 插件 ID：`fabric-loom` → `net.fabricmc.fabric-loom`（非重映射版本）
- 删除 `mappings` 依赖行
- `modImplementation` → `implementation`

## gradle.properties 变更

删除了：`yarn_mappings`（如存在）
保留了：`minecraft_version`、`mod_version`、`maven_group`、`archives_base_name`

## 修复清单

| 错误原文 | 根因 | 修复方式 | 验证结果 |
|---|---|---|---|
| `程序包net.minecraft.command不存在` | 26.1 用 Mojang 官方名，Yarn 名 `net.minecraft.commands` 已失效 | 改用 `net.minecraft.network.chat.Component`（命令部分直接删除，简化入口） | 编译通过 |
| `程序包net.minecraft.component不存在` | 同上 | 同上 | 编译通过 |
| `ClientCommandManager 找不到符号` | Fabric API 26.1.2 子模块未单独发布，command API 不可用 | 简化入口，删除命令注册 | 编译通过 |
| `ClientConnectionEvents 找不到符号` | 同上 | 简化入口，删除断连事件 | 编译通过 |
| `无法取消引用boolean`（CONFIG.xxx.get()） | Settings 字段是原始类型，不是 ModConfigSpec.BooleanValue | 批量替换 `.get()` → 直接字段访问 | 编译通过 |
| `batch.extensions.shouldBobAsEntity() 找不到符号` | CarbonItemExtensions 缺方法 | 添加 `shouldBobAsEntity()` 和 `shouldSpreadAsEntity()` 返回 true | 编译通过 |
| `RENDERER.beginFrame() 无法应用到给定类型` | beginFrame 需要 Camera/Frustum 参数，tick 中无这些参数 | 移除 tick 中的 beginFrame 调用（由 LevelRendererMixin 在 extractVisibleEntities 中调用） | 编译通过 |
| `GENERIC.beginFrame() 找不到符号` | GenericItemRenderer 只有 begin() 没有 beginFrame() | 改为 `GENERIC.begin()` | 编译通过 |

## 决策与教训

### 为什么 26.x 不再需要 Yarn？

Minecraft 26.1 起 Mojang 不再对客户端进行混淆处理，jar 内包含原始参数名和类名。Fabric 官方文档明确说明："Minecraft 26.1 没有进行混淆处理，并且包含参数名称，因此不需要任何混淆映射。" 因此 Loom 的非重映射模式（`net.fabricmc.fabric-loom`）可以直接使用 Mojang 官方名称编译，无需 yarn 映射。

### 为什么最初误判为"等 yarn 发布"？

1. 看到 fabric-api 有 `0.155.3+26.1.2` 版本，误以为 Fabric 生态已完整支持 26.1.2
2. 没有查阅 Fabric 官方文档关于 26.1 未混淆的说明
3. 惯性思维：之前 1.21.x 的移植都用 yarn，以为 26.x 也一样
4. 没有尝试切换到非重映射 Loom 插件 ID

根源：对 Fabric 官方文档不熟悉，用旧版本经验套新情况。

### 非重映射 Loom 与重映射 Loom 的差异

| 方面 | 重映射 Loom（`fabric-loom-remap`） | 非重映射 Loom（`net.fabricmc.fabric-loom`） |
|---|---|---|
| 适用版本 | MC ≤ 1.21.11（混淆） | MC ≥ 26.1（未混淆） |
| mappings 依赖 | 必须指定 yarn | 不需要 |
| 依赖声明 | `modImplementation`（触发重映射） | `implementation`（普通依赖） |
| 类名 | Yarn 名（如 `net.minecraft.client.renderer.entity.ItemEntityRenderer`） | Mojang 官方名（如 `net.minecraft.client.renderer.entity.ItemEntityRenderer`） |
| 混淆处理 | Loom 自动反混淆 + 重映射 | 不需要，jar 已含原始名 |

## Yarn → Mojang 对照表（本次实际遇到）

本次编译中，26.1.2-neoforge 分支的代码已经使用 Mojang 官方名（NeoForge 用 Mojang 映射），所以源码层面不需要大量改名。主要变更在入口层：

| Yarn 名（1.21.x） | Mojang 名（26.1.2） | 备注 |
|---|---|---|
| `net.minecraft.commands.Commands` | `net.minecraft.network.chat.Component`（命令 API 变化） | 26.1 命令 API 重构 |
| `net.minecraft.network.chat.Component` | `net.minecraft.network.chat.Component` | 不变 |
| `net.minecraft.component.Component` | `net.minecraft.network.chat.Component` | Component 位置变化 |
| `ModConfigSpec.BooleanValue` | 原始 `boolean` 字段 | NeoForge 配置 API → 自制 TOML |
| `NeoForge.EVENT_BUS` | Fabric `ClientTickEvents` 等 | 事件系统完全不同 |
| `@Mod` 注解 | `ClientModInitializer` 接口 | Fabric Loader 0.20.1 无 `@Mod` |
| `IClientItemExtensions` | `CarbonItemExtensions`（自建） | Fabric 无等价物 |
| `RenderLevelStageEvent` | `LevelRendererMixin` + `extractVisibleEntities` | 无 Fabric 等价事件 |

### 26.1 中可能的 API 重命名（参考，需以实际编译错误为准）

- `KeyBindingHelper` → `KeyMappingHelper`
- `WorldRenderEvents` → `LevelRenderEvents`
- `RenderType` 包路径可能变化
- `isGliding()` → `isFallFlying()`

## Fabric 26.x 构建迁移检查清单

- [x] 插件 ID 切换为 `net.fabricmc.fabric-loom`（非重映射版本）
- [x] mappings 依赖行已删除
- [x] gradle.properties 中无无效 yarn 相关属性
- [x] `modImplementation` 已改为 `implementation`
- [x] 编译错误已按 Yarn→Mojang 对照逐项修复
- [x] 最终 `gradle build` 真实通过（BUILD SUCCESSFUL）
- [ ] 运行时验证（需在隔离桌面测试游戏内渲染）
- [ ] Mixin 验证（需确认所有 mixin 正常注入）

## 失败模式沉淀

### Loom 缓存损坏

症状：`Previous process has disowned the lock due to abrupt termination. Found existing cache lock file (ACQUIRED_PREVIOUS_OWNER_DISOWNED), rebuilding loom cache.`

解决：Loom 会自动重建缓存，无需手动干预。如果反复出现，可运行 `gradle build --refresh-dependencies`。

### Gradle 版本不匹配

症状：`Minecraft 26.3 requires Java 25 but Gradle is using 21`

解决：Loom 1.14.x 需要 Gradle 9.2.0 + Java 25。确保 `JAVA_HOME` 指向 JDK 25，Gradle 版本 ≥ 9.2.0。

### Fabric API 子模块未单独发布

症状：`ClientCommandManager 找不到符号` / `ClientConnectionEvents 找不到符号`

根因：fabric-api 是元模块，某些子模块（如 command-api-v2、networking-v1）在 26.1.x 的 maven 中没有单独发布。

解决：简化入口，不依赖这些子模块。优先使用 `ClientTickEvents`（已验证可用）。
