# HANDOFF.md — Carbon NeoForge 26.1.2 → Fabric 26.3 移植

## 1. 任务目标

将 Carbon（GPU instancing 物品渲染模组）从 NeoForge 26.1.2 移植到 Fabric 26.3，编译通过后在游戏内验证渲染正常，并优化 instancing 速度。

## 2. 已完成并验证

### 26.1.2 Fabric 版 — BUILD SUCCESSFUL ✅

- 分支：`fabric-26.1.2`
- 编译命令：`gradle build --no-daemon --console=plain`
- 结果：`BUILD SUCCESSFUL in 5m 44s`，0 错误
- 关键适配：
  - `Carbon.java` → `ClientModInitializer`（Fabric 0.20.1 无 `@Mod` 注解）
  - `IClientItemExtensions` → `CarbonItemExtensions`（自建，Fabric 无等价物）
  - `Settings` 字段从 `ModConfigSpec.BooleanValue.get()` → 原始类型直接访问
  - `LevelRendererMixin` 添加 `flush()` 调用（替代 NeoForge `RenderLevelStageEvent`）
  - `fabric.mod.json` 新建，`neoforge.mods.toml` 删除
  - `MIGRATION_NOTES.md` 已创建（含决策记录、Yarn→Mojang 对照表、检查清单）

### 26.3 Fabric 版 — 编译配置就绪，源码层未完成 ❌

- 分支：`fabric-26.1.2`（当前分支，`minecraft_version` 改为 26.3）
- 已改文件：
  - `gradle.properties`：`minecraft_version=26.3`，`mod_version=26.3-fabric-0.1`
  - `build.gradle`：插件 ID `net.fabricmc.fabric-loom` 1.14.10，无 `mappings` 行，依赖用 `implementation`
  - `src/main/resources/fabric.mod.json`：版本号更新为 26.3
- 未改文件：所有 Java 源文件（29 个）仍是 26.1.2 的代码，未适配 26.3 的 blaze3d 重构

## 3. 已确认事实

- MC 26.1+ 未混淆，不需要 yarn 映射。Yarn 项目在 1.21.11 后停止维护。
- 插件 ID 必须是 `net.fabricmc.fabric-loom`（非重映射），不是 `fabric-loom-remap`。
- Loom 1.14.10 + Gradle 9.2.0 + Java 25 可用。
- 依赖声明用 `implementation`，不用 `modImplementation`。
- Fabric API 26.3 有可用版本：`0.165.2+26.3`。
- fabric-loader 0.20.1 支持 26.3。
- `--refresh-dependencies` 后构建进入编译阶段（未卡在映射解析），证明配置正确。
- Blaze3D 在 MC 26.2/26.3 中被大幅重构，但 `opengl`/`textures` 包未被删除（只是类可能移走或改名）。

## 4. 卡住的具体问题

26.3 编译报 73 个错误，全部是 blaze3d 类找不到：

| 错误类型 | 涉及类 | 涉及文件 |
|---|---|---|
| `程序包com.mojang.blaze3d.opengl不存在` | `GlTexture`, `GlSampler`, `GlBuffer`, `GlStateManager`, `GlCommandEncoder` | `ItemBatchRenderer`, `GpuFrame`, `GpuItemBatch`, `GenericItemRenderer`, `GenericShadowRenderer`, `IrisCompatibility`, `GlBufferAccessor`, `GlStateManagerMixin`, `GlCommandEncoderMixin` |
| `程序包VertexFormat不存在` | `VertexFormat` | `IrisCompatibility`, `GpuItemBatch` |
| `程序包com.mojang.blaze3d.buffers不存在` | `GpuBuffer` | `IrisCompatibility` |
| `程序包MultiBufferSource不存在` | `MultiBufferSource` | `ItemFeatureRendererMixin` |

**根因未定位**：这些类在 26.1.2 jar 中存在（在 `com.mojang.blaze3d.opengl` / `com.mojang.blaze3d.vertex` 包下），但在 26.3 jar 中找不到。可能是：
- (a) 类移到了其他包（如 `net.minecraft.client.gl`）
- (b) 类被重命名（如 `GlTexture` → `GpuTexture`）
- (c) 类被删除，由新类替代（如 `VertexFormat` → 新的顶点格式系统）
- (d) 本地 Loom 缓存的 jar 不完整或损坏

**无法确认的原因**：`ops_bash` 对 jar 内容搜索（`unzip -l | grep`）多次返回异常输出（"Clean working tree" 或空），无法可靠检查 26.3 jar 的实际类列表。`javap` 检查 `net.minecraft.client.gl.GlSampler` 等类也返回"找不到符号"。

## 5. 失败模式记录

**接手者不得重演以下行为**：

1. **反复 `unzip -l | grep` 搜索 jar 内容**：`ops_bash` 对大输出有显示问题，多次返回 git status 或空结果。应该用 `javap -cp <jar> <class>` 逐个验证，或直接编译让编译器报错。
2. **同一推理重复十几次**：多次"搜索 jar → 找不到 → 换搜索方式 → 还是找不到 → 再换"的循环，从未推进到实际修复。
3. **从未执行过带 `--refresh-dependencies` 的 build**：直到最后一次才执行，之前一直在用旧缓存。
4. **过度搜索，不足量修复**：花了大量时间搜索类位置，但没有实际修改任何源文件来适配 26.3。

## 6. 下一步建议（按优先级）

1. **确认 build.gradle 配置正确**：已确认插件 ID 为 `net.fabricmc.fabric-loom` 1.14.10，无 `mappings` 行，依赖为 `implementation`。无需再改。
2. **用 `--refresh-dependencies` 重跑**：已完成，73 个错误全是 blaze3d 类找不到，与缓存无关。
3. **确认 26.3 jar 里 blaze3d 类的实际位置**：
   - 用 `javap -cp <26.3-jar> com.mojang.blaze3d.opengl.GlTexture` 检查是否仍存在
   - 如果不存在，用 `javap -cp <26.3-jar> net.minecraft.client.gl.GlTexture` 检查是否移走
   - 如果都不存在，用 `unzip -l <26.3-jar> | grep -i texture` 找替代类
4. **批量修复源文件**：找到正确类名后，用 `sed` 批量替换所有文件的 import 和代码中的类引用。
5. **编译迭代**：每次修复后跑 `gradle compileJava --no-daemon --console=plain`，直到 BUILD SUCCESSFUL。
6. **如需用户提供信息**：
   - minecraft-client.jar 的实际路径和大小（确认是否完整下载）
   - 完整的编译错误日志（73 个错误的全文）
   - 或者让用户帮忙确认 26.3 中 `GlTexture`、`VertexFormat`、`GpuBuffer` 等类的新位置

## 7. 当前环境

- 仓库：`C:\Users\Admin\code\Carbon`，分支 `fabric-26.1.2`
- Gradle 9.2.0：`C:\Users\Admin\code\_gradle-dist\gradle-9.2.0`
- Java 25：`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`
- 构建命令：`export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot" && export PATH="/c/Users/Admin/code/_gradle-dist/gradle-9.2.0/bin:$PATH" && gradle build --no-daemon --console=plain`
- 参考仓库：`C:\Users\Admin\code\_carbon-fabric-ref`（Colin1456/Carbon-for-Fabric，MC 1.20.1）
- MIGRATION_NOTES.md 已创建（26.1.2 移植记录）
