package first.carbon.client;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import first.carbon.compat.CarbonItemExtensions;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

public final class ItemBatchRenderer {
    private final Logger logger = LoggerFactory.getLogger("Carbon");
    private final Int2ObjectOpenHashMap<List<GpuItemBatch>> buckets = new Int2ObjectOpenHashMap<>();
    private final IdentityHashMap<Class<?>, Boolean> dynamicFoil = new IdentityHashMap<>();
    private final Long2IntOpenHashMap lightCells = new Long2IntOpenHashMap();
    private final BlockPos.MutableBlockPos lightPosition = new BlockPos.MutableBlockPos();
    private final List<Entity> remaining = new ArrayList<>();
    private final List<Entity> shadowRemaining = new ArrayList<>();
    private final List<ItemEntity> updateQueue = new ArrayList<>();
    private Set<ItemEntity> remainingItems = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<ItemEntity> updateRemainingItems = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Matrix4f visibilityMatrix = new Matrix4f(), sceneMatrix = new Matrix4f();
    private Entity sceneCameraEntity;
    private boolean updating, updateStationary, updateTickStable, sceneTickStable;
    private int updateIndex, updateInstances, updateCollected;
    private double updateDistanceMargin;
    private final Matrix4f transform = new Matrix4f();
    private ClientLevel level;
    private Vec3 camera = Vec3.ZERO, sceneCamera = Vec3.ZERO;
    private Frustum frustum;
    private float partialTick, clock;
    private int ticks, frame, generation, program, entityProgram, preparedProgram, framebuffer;
    private int irisGeneration;
    private double originX, originY, originZ;
    private GpuInstanceProcessor processor;
    private GpuShadowBatch shadows;
    private boolean active, failed, reused, stationary, sceneValid, sceneShadows, sceneSkip;
    private boolean shadowCollected, shadowFlushed;
    private long sceneTick, builds, reuses;
    private int sceneSkyDarken, sceneRenderDistance;
    private double sceneDistanceMargin, sceneViewScale, sceneEntityDistance;
    private int sceneEntityCount, sceneInstances, sceneCollected;
    public volatile boolean sceneDirty = true;
    public volatile boolean blockCacheDirty = true;
    public long cacheUpdateNs;
    public int pendingUpdates, updatedEntities;
    boolean physics;
    private boolean dedicated;
    public boolean capturing;
    private List<?> sceneEntities;
    public int instances, collected, drawCalls, cacheMisses, shadowInstances, shadowDrawCalls;
    public long uploadedBytes, modelBytes;

    public void beginFrame(Camera camera, Frustum frustum, float partialTick) {
        Carbon.IRIS.beginFrame();
        if (irisGeneration != Carbon.IRIS.generation) {
            clear();
            Carbon.GENERIC.invalidate();
            irisGeneration = Carbon.IRIS.generation;
        }
        var minecraft = Minecraft.getInstance();
        boolean requested = Carbon.CONFIG.optimizeDroppedItems;
        boolean physical = requested && Carbon.CONFIG.physicalDroppedItems;
        if (physics != physical || dedicated != requested) { clear(); physics = physical; dedicated = requested; }
        Vec3 position = camera.position();
        if (level != minecraft.level || Math.abs(position.x - originX) > 1024 || Math.abs(position.y - originY) > 1024 || Math.abs(position.z - originZ) > 1024) {
            clear();
            level = minecraft.level;
            originX = Math.floor(position.x / 256) * 256;
            originY = Math.floor(position.y / 256) * 256;
            originZ = Math.floor(position.z / 256) * 256;
        }
        this.camera = position;
        this.frustum = frustum;
        camera.getViewRotationProjectionMatrix(visibilityMatrix);
        this.partialTick = partialTick;
        ticks = (int) (level.getGameTime() % 24000);
        clock = ticks + partialTick;
        frame++;
        instances = collected = drawCalls = cacheMisses = shadowDrawCalls = 0;
        uploadedBytes = modelBytes = cacheUpdateNs = 0;
        updatedEntities = 0;
        shadowCollected = shadowFlushed = false;
        active = false;
        if (!failed && Carbon.IRIS.canInstance() && Carbon.CONFIG.enabled && minecraft.gameRenderer.mainRenderTarget().getColorTexture() instanceof GlTexture && GL.getCapabilities().OpenGL33) {
            if (program == 0) {
                try {
                    program = createProgram("instanced.vsh", "instanced.fsh");
                    entityProgram = createProgram(physics ? "physics_instanced.vsh" : "entity_instanced.vsh", "instanced.fsh");
                    shadows = new GpuShadowBatch(createProgram("shadow.vsh", "shadow.fsh"));
                    framebuffer = glGenFramebuffers();
                    if (GL.getCapabilities().OpenGL43) {
                        try {
                            preparedProgram = createProgram(physics ? "physics_prepared.vsh" : "entity_prepared.vsh", "instanced.fsh");
                            processor = new GpuInstanceProcessor(physics);
                        } catch (Exception exception) {
                            logger.warn("Using vertex animation", exception);
                        }
                    }
                    logger.info("GPU item instancing ready: {}", glGetString(GL_RENDERER));
                } catch (Exception exception) {
                    clear();
                    failed = true;
                    logger.error("Keeping vanilla item rendering", exception);
                }
            }
            active = !failed && program != 0;
        }
        boolean shadowOption = minecraft.options.entityShadows().get() && !Carbon.IRIS.active();
        boolean skip = Carbon.CONFIG.skipVisibilityChecks;
        if (updating && (!position.equals(sceneCamera) || !visibilityMatrix.equals(sceneMatrix))) sceneDirty = true;
        reused = active && dedicated && skip == sceneSkip && sceneValid && stationary && !sceneDirty && !blockCacheDirty
                && (skip || position.equals(sceneCamera) && visibilityMatrix.equals(sceneMatrix))
                && level.getSkyDarken() == sceneSkyDarken && minecraft.getCameraEntity() == sceneCameraEntity
                && (sceneTickStable || level.getGameTime() == sceneTick)
                && level.getGameTime() / 24000 == sceneTick / 24000
                && sceneEntityCount == level.getEntityCount() && Entity.getViewScale() == sceneViewScale
                && minecraft.options.getEffectiveRenderDistance() == sceneRenderDistance
                && minecraft.options.entityDistanceScaling().get() == sceneEntityDistance
                && position.distanceToSqr(sceneCamera) < sceneDistanceMargin * sceneDistanceMargin && shadowOption == sceneShadows
                && !level.getChunkSource().getLightEngine().hasLightWork() && !updating;
        boolean retained = active && dedicated && sceneValid;
        if (retained) {
            instances = sceneInstances;
            collected = sceneCollected;
        }
        if (blockCacheDirty) {
            lightCells.clear();
            lightCells.defaultReturnValue(-1);
            if (shadows != null) shadows.receivers.clear();
            blockCacheDirty = false;
        } else if (shadows != null && level.getSkyDarken() != sceneSkyDarken) {
            shadows.receivers.clear();
        }
        if (shadows != null) {
            shadows.count = retained ? shadows.retainedCount : 0;
            shadows.flushed = false;
        }
        shadowInstances = shadows == null ? 0 : shadows.count;
        for (var bucket : buckets.values()) for (var batch : bucket) {
            batch.count = retained && batch.entityBatch ? batch.retainedCount : 0;
            batch.flushed = false;
            batch.processed = false;
            if (!retained) batch.physicsMoving = false;
            if (batch.count > 0 || updating && batch.updateCount > 0) batch.lastFrame = frame;
        }
    }

    public Iterable<Entity> collect(Iterable<Entity> entities) {
        if (!active || !dedicated) return entities;
        if ((entities instanceof List<?> || sceneEntities != null) && !entities.equals(sceneEntities)) {
            reused = false;
            updating = false;
            updateQueue.clear();
        }
        if (reused) {
            reuses++;
            return remaining;
        }
        long start = System.nanoTime();
        long budget = (long) (Carbon.CONFIG.cacheUpdateBudgetMs * 1_000_000);
        var minecraft = Minecraft.getInstance();
        var dispatcher = minecraft.getEntityRenderDispatcher();
        boolean starting = !updating;
        boolean skip = updating ? sceneSkip : Carbon.CONFIG.skipVisibilityChecks;
        boolean renderShadows = updating ? sceneShadows : minecraft.options.entityShadows().get() && !Carbon.IRIS.active();
        boolean rendererChecked = false, standardRenderer = false;
        if (starting) {
            sceneEntities = entities instanceof List<?> list ? list : null;
            updating = true;
            updateIndex = updateInstances = updateCollected = 0;
            updateStationary = updateTickStable = true;
            updateDistanceMargin = Double.POSITIVE_INFINITY;
            updateRemainingItems.clear();
            sceneDirty = false;
            for (Entity entity : entities) if (entity instanceof ItemEntity item) updateQueue.add(item);
            sceneCamera = camera;
            sceneCameraEntity = minecraft.getCameraEntity();
            sceneMatrix.set(visibilityMatrix);
            sceneViewScale = Entity.getViewScale();
            sceneRenderDistance = minecraft.options.getEffectiveRenderDistance();
            sceneEntityDistance = minecraft.options.entityDistanceScaling().get();
            sceneSkyDarken = level.getSkyDarken();
            sceneTick = level.getGameTime();
            sceneEntityCount = level.getEntityCount();
            sceneShadows = renderShadows;
            sceneSkip = skip;
        }
        for (var bucket : buckets.values()) for (var batch : bucket) if (batch.entityBatch) {
            if (starting) batch.updateCount = 0;
            batch.swapUpdate();
            if (starting) batch.physicsMoving = false;
        }
        if (starting) shadows.updateCount = 0;
        shadows.swapUpdate();
        instances = updateInstances;
        collected = updateCollected;
        while (updateIndex < updateQueue.size() && (budget == 0 || updatedEntities == 0 || System.nanoTime() - start < budget)) {
            ItemEntity item = updateQueue.get(updateIndex++);
            updatedEntities++;
            boolean submitted = false;
            if (item.isRemoved()) continue;
            if (item.getClass() == ItemEntity.class && item != minecraft.getCameraEntity() && ((EntityCacheAccess) item).imo$eligible()) {
                if (!rendererChecked) {
                    standardRenderer = dispatcher.getRenderer(item).getClass() == ItemEntityRenderer.class;
                    rendererChecked = true;
                }
                if (!standardRenderer) {
                    updateRemainingItems.add(item);
                    updateStationary = false;
                    continue;
                }
                if (skip) {
                    double size = item.getBoundingBox().getSize();
                    if (Double.isNaN(size)) size = 1;
                    updateDistanceMargin = Math.min(updateDistanceMargin, Math.abs(size * 64 * Entity.getViewScale() - item.position().distanceTo(camera)));
                }
                if (skip ? !item.shouldRender(camera.x, camera.y, camera.z)
                        : !dispatcher.shouldRender(item, frustum, camera.x, camera.y, camera.z, partialTick)) continue;
                ItemStack stack = item.getItem();
                EntityCache cache = ((EntityCacheAccess) item).imo$cache();
                if (cache.generation != generation || (cache.stack != stack || cache.version != ((ComponentVersion) stack.getComponents()).imo$version() || cache.dynamicFoil && cache.foil != ((FoilState) (Object) stack).imo$foil((int) minecraft.level.getGameTime())) || cache.batch != null && cache.batch.closed) {
                    cache.generation = generation;
                    cache.stack = stack;
                    cache.version = ((ComponentVersion) stack.getComponents()).imo$version();
                    cache.foil = ((FoilState) (Object) stack).imo$foil((int) minecraft.level.getGameTime());
                    cache.dynamicFoil = !stack.has(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE) && dynamicFoil.computeIfAbsent(stack.getItem().getClass(), type -> {
                        try { return type.getMethod("isFoil", ItemStack.class).getDeclaringClass() != net.minecraft.world.item.Item.class; }
                        catch (ReflectiveOperationException ignored) { return true; }
                    });
                    ItemStackRenderState state = new ItemStackRenderState();
                    minecraft.getItemModelResolver().updateForNonLiving(state, stack, ItemDisplayContext.GROUND, item);
                    var modelId = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
                    cache.batch = modelId != null && minecraft.getModelManager().getItemModel(modelId).getClass() == net.minecraft.client.renderer.item.CuboidItemModelWrapper.class
                            ? find(stack, state, true) : null;
                }
                GpuItemBatch batch = cache.batch;
                int copies = ItemClusterRenderState.getRenderedAmount(stack.getCount());
                if (batch != null && !batch.flushed && batch.count + copies <= 65536) {
                    ((ComponentVersion) stack.getComponents()).imo$track();
                    Vec3 probe = item.getLightProbePosition(partialTick);
                    lightPosition.set(Mth.floor(probe.x), Mth.floor(probe.y), Mth.floor(probe.z));
                    long cell = lightPosition.asLong();
                    int light = lightCells.get(cell);
                    if (light == -1) {
                        light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, lightPosition), level.getBrightness(LightLayer.SKY, lightPosition));
                        lightCells.put(cell, light);
                    }
                    if (((EntityCacheAccess) item).imo$hasFire()) light = (light & 0xFFFF0000) | 240;
                    if (physics && cache.motionTick != level.getGameTime()) {
                        if (cache.motionTick != Long.MIN_VALUE) cache.roll = (cache.roll + cache.rate * Math.min(20, level.getGameTime() - cache.motionTick)) % (float) (Math.PI * 2);
                        double dx = item.getX() - item.xOld, dy = item.getY() - item.yOld, dz = item.getZ() - item.zOld;
                        double speed = dx * dx + dy * dy + dz * dz;
                        cache.rate = !item.onGround() && speed > 1.0E-8 ? (float) Math.min(0.8, Math.sqrt(speed) * 4) : 0;
                        if (item.isInWater()) cache.rate *= 0.35f;
                        if (item.isInLava()) cache.rate *= 0.15f;
                        if (item.onGround() && !batch.gui3d) cache.roll = 0;
                        cache.motionTick = level.getGameTime();
                    }
                    batch.addEntity((float) (Mth.lerp(partialTick, item.xOld, item.getX()) - originX),
                            (float) (Mth.lerp(partialTick, item.yOld, item.getY()) - originY) + (physics ? ((item.getId() * 0x9E3779B9) >>> 16) * (0.003f / 65535) : 0),
                            (float) (Mth.lerp(partialTick, item.zOld, item.getZ()) - originZ), physics ? cache.roll : item.tickCount - (float) ticks,
                            physics ? -item.getYRot() * Mth.DEG_TO_RAD : item.bobOffs, light, batch.extensions.shouldBobAsEntity(stack), batch.extensions.shouldSpreadAsEntity(stack), copies, cache.rate);
                    batch.lastFrame = frame;
                    instances += copies;
                    collected++;
                    submitted = true;
                    cache.previousBuild = cache.sceneBuild;
                    cache.sceneBuild = builds + 1;
                    updateTickStable &= !cache.dynamicFoil;
                    updateStationary &= item.xOld == item.getX() && item.yOld == item.getY() && item.zOld == item.getZ()
                            && (!physics || cache.rate == 0)
                            && batch.extensions == CarbonItemExtensions.DEFAULT;
                    if (renderShadows) shadows.add(item, partialTick, camera, originX, originY, originZ);
                }
            }
            if (!submitted) {
                updateRemainingItems.add(item);
                updateStationary = false;
            }
        }
        updateInstances = instances;
        updateCollected = collected;
        pendingUpdates = updateQueue.size() - updateIndex;
        updating = pendingUpdates > 0;
        for (var bucket : buckets.values()) for (var batch : bucket) if (batch.entityBatch) {
            if (updating) batch.swapUpdate();
            else batch.retainedCount = batch.count;
            if (batch.count > 0 || batch.updateCount > 0) batch.lastFrame = frame;
        }
        if (updating) shadows.swapUpdate();
        else shadows.retainedCount = shadows.count;
        shadowInstances = shadows.count;
        if (updating) {
            instances = sceneValid ? sceneInstances : 0;
            collected = sceneValid ? sceneCollected : 0;
        } else {
            builds++;
            sceneValid = true;
            stationary = updateStationary;
            sceneTickStable = updateTickStable;
            sceneDistanceMargin = updateDistanceMargin;
            sceneInstances = instances;
            sceneCollected = collected;
            Set<ItemEntity> previous = remainingItems;
            remainingItems = updateRemainingItems;
            updateRemainingItems = previous;
            updateQueue.clear();
        }
        cacheUpdateNs = System.nanoTime() - start;
        remaining.clear();
        for (Entity entity : entities) if (!(entity instanceof ItemEntity) || remainingItems.contains(entity)) remaining.add(entity);
        return remaining;
    }

    public Iterable<Entity> collectShadows(Iterable<Entity> entities) {
        if (!active || !dedicated || !sceneValid || !Carbon.IRIS.canInstance()) return entities;
        shadowCollected = true;
        shadowRemaining.clear();
        for (Entity entity : entities) {
            boolean cached = false;
            if (entity instanceof ItemEntity item) {
                EntityCache cache = ((EntityCacheAccess) item).imo$cache();
                cached = cache.generation == generation && (cache.sceneBuild == builds || cache.previousBuild == builds);
            }
            if (!cached) shadowRemaining.add(entity);
        }
        return shadowRemaining;
    }

    public void drawIrisShadows() {
        if (shadowCollected && !shadowFlushed && Carbon.IRIS.shadowPass()) {
            drawIris(Carbon.IRIS.shadowView(), camera, true);
            shadowFlushed = true;
        }
    }

    private void drawIris(org.joml.Matrix4fc matrix, Vec3 position, boolean shadow) {
        var view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(matrix);
        try {
            var offset = new org.joml.Vector3f((float) (position.x - originX), (float) (position.y - originY), (float) (position.z - originZ));
            for (var bucket : buckets.values()) for (var batch : bucket) if (batch.count > 0 && !batch.flushed) {
                Carbon.SODIUM.mark(batch.sprites);
                uploadedBytes += batch.upload();
                for (int pass = 0; pass < (batch.foil && !shadow ? 2 : 1); pass++) for (var mesh : batch.meshes) {
                    var type = pass == 0 || mesh.glintType == null ? mesh.type : mesh.glintType;
                    Carbon.IRIS.draw(type, pass == 0 || mesh.preparedGlint == null ? mesh.prepared : mesh.preparedGlint, mesh.vertices, mesh.vertexBuffer, mesh.indexBuffer, mesh.format, mesh.indices,
                            batch.count, batch.instanceBuffer, batch.physics ? 4 : 3, offset, clock, batch.groundScaleY, batch.center, batch.halfSize);
                    if (!shadow) drawCalls++;
                }
                if (!shadow) batch.flushed = true;
            }
        } finally { view.popMatrix(); }
    }

    private GpuItemBatch find(ItemStack stack, ItemStackRenderState state, boolean entity) {
        if (stack.isEmpty() || state.isEmpty()) return null;
        var identity = ((ItemStateAccess) state).imo$identity();
        int hash = ItemStack.hashItemAndComponents(stack);
        var bucket = buckets.computeIfAbsent(hash, key -> new ArrayList<>());
        for (var batch : bucket) if (!batch.closed && batch.entityBatch == entity && batch.model.equals(identity) && ItemStack.isSameItemSameComponents(batch.stack, stack)) return batch;
        try {
            var batch = new GpuItemBatch(stack, state, this, entity, null);
            bucket.add(batch);
            cacheMisses++;
            modelBytes += batch.meshBytes;
            return batch;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void flush(CameraRenderState state) {
        if (active) {
            if (Carbon.IRIS.active()) {
                drawIris(state.viewRotationMatrix, state.pos, false);
            } else try (GpuFrame gpuFrame = new GpuFrame(framebuffer)) {
                for (var bucket : buckets.values()) for (var batch : bucket) if (batch.count > 0 && !batch.flushed) {
                    Carbon.SODIUM.mark(batch.sprites);
                    uploadedBytes += batch.upload();
                    batch.processed = batch.entityBatch && processor != null;
                    if (batch.processed) processor.prepare(batch, clock);
                    int drawProgram = batch.entityBatch ? batch.processed ? preparedProgram : entityProgram : program;
                    uniforms(drawProgram, state);
                    glUniform1f(glGetUniformLocation(drawProgram, "GroundScaleY"), batch.groundScaleY);
                    if (batch.physics) {
                        glUniform3f(glGetUniformLocation(drawProgram, "Center"), batch.center.x, batch.center.y, batch.center.z);
                        glUniform3f(glGetUniformLocation(drawProgram, "HalfSize"), batch.halfSize.x, batch.halfSize.y, batch.halfSize.z);
                        glUniform1i(glGetUniformLocation(drawProgram, "Flat"), 1);
                    }
                    for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) {
                        glUniform1i(glGetUniformLocation(drawProgram, "GlintPass"), pass);
                        if (pass != 0) {
                            glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_COLOR, GL_ONE, GL_ZERO, GL_ONE);
                            glDepthMask(false); glDepthFunc(GL_EQUAL);
                            try (MemoryStack memory = MemoryStack.stackPush()) { glUniformMatrix4fv(glGetUniformLocation(drawProgram, "TextureMat"), false, net.minecraft.client.renderer.rendertype.TextureTransform.GLINT_TEXTURING.createMatrix().get(memory.mallocFloat(16))); }
                            glUniform1f(glGetUniformLocation(drawProgram, "GlintAlpha"), Minecraft.getInstance().options.glintStrength().get().floatValue());
                        }
                        for (var mesh : batch.meshes) {
                            if (mesh.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
                            gpuFrame.bindTexture(0, pass == 0 ? mesh.texture : net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
                            mesh.bind();
                            glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                            drawCalls++;
                        }
                    }
                    glDisable(GL_BLEND); glDepthMask(true); glDepthFunc(GL_LEQUAL);
                    batch.flushed = true;
                }
                if (shadows.count > 0) {
                    uploadedBytes += shadows.draw(state, gpuFrame);
                    shadowDrawCalls++;
                }
            }
            active = false;
            var iterator = buckets.values().iterator();
            while (iterator.hasNext()) {
                var bucket = iterator.next();
                bucket.removeIf(batch -> {
                    if (frame - batch.lastFrame > 300) { batch.close(); return true; }
                    return false;
                });
                if (bucket.isEmpty()) iterator.remove();
            }
        }
    }

    static void uniforms(int program, CameraRenderState state) {
        var renderer = Carbon.RENDERER;
        glUseProgram(program);
        try (MemoryStack memory = MemoryStack.stackPush()) {
            glUniformMatrix4fv(glGetUniformLocation(program, "View"), false, state.viewRotationMatrix.get(memory.mallocFloat(16)));
            glUniformMatrix4fv(glGetUniformLocation(program, "Projection"), false, state.projectionMatrix.get(memory.mallocFloat(16)));
        }
        glUniform3f(glGetUniformLocation(program, "Camera"), (float) (state.pos.x - renderer.originX), (float) (state.pos.y - renderer.originY), (float) (state.pos.z - renderer.originZ));
        glUniform1f(glGetUniformLocation(program, "Clock"), renderer.clock);
        glUniform1f(glGetUniformLocation(program, "GroundScaleY"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
        glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
        glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
        var fog = state.fogData;
        glUniform4f(glGetUniformLocation(program, "FogColor"), fog.color.x, fog.color.y, fog.color.z, fog.color.w);
        glUniform1f(glGetUniformLocation(program, "FogStart"), fog.environmentalStart);
        glUniform1f(glGetUniformLocation(program, "FogEnd"), fog.environmentalEnd);
        glUniform1f(glGetUniformLocation(program, "RenderFogStart"), fog.renderDistanceStart);
        glUniform1f(glGetUniformLocation(program, "RenderFogEnd"), fog.renderDistanceEnd);
        glUniform1i(glGetUniformLocation(program, "FogShape"), 0);
    }

    int createProgram(String vertex, String fragment) throws Exception {
        int result = glCreateProgram();
        try {
            for (var entry : Map.of(GL_VERTEX_SHADER, vertex, GL_FRAGMENT_SHADER, fragment).entrySet()) {
                int shader = glCreateShader(entry.getKey());
                try (InputStream stream = getClass().getResourceAsStream("/assets/carbon/shaders/" + entry.getValue())) {
                    if (stream == null) throw new IllegalStateException("Missing shader " + entry.getValue());
                    glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    glCompileShader(shader);
                    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
                    glAttachShader(result, shader);
                } finally { glDeleteShader(shader); }
            }
            glLinkProgram(result);
            if (glGetProgrami(result, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(result));
            int lighting = glGetUniformBlockIndex(result, "Lighting");
            if (lighting != GL_INVALID_INDEX) glUniformBlockBinding(result, lighting, 3);
            return result;
        } catch (Exception exception) { glDeleteProgram(result); throw exception; }
    }

    public void invalidate() {
        if (RenderSystem.isOnRenderThread()) clear(); else Minecraft.getInstance().execute(this::clear);
    }

    private void clear() {
        active = false;
        generation++;
        for (var bucket : buckets.values()) for (var batch : bucket) batch.close();
        buckets.clear();
        if (shadows != null) shadows.close();
        if (processor != null) processor.close();
        if (program != 0) glDeleteProgram(program);
        if (entityProgram != 0) glDeleteProgram(entityProgram);
        if (preparedProgram != 0) glDeleteProgram(preparedProgram);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        shadows = null;
        processor = null;
        program = entityProgram = preparedProgram = framebuffer = 0;
        updateQueue.clear();
        remaining.clear();
        shadowRemaining.clear();
        remainingItems.clear();
        updateRemainingItems.clear();
        lightCells.clear();
        dynamicFoil.clear();
        updating = false;
        pendingUpdates = updateIndex = sceneInstances = sceneCollected = 0;
        sceneCameraEntity = null;
        blockCacheDirty = true;
        sceneValid = false;
        sceneEntities = null;
        sceneDirty = true;
        failed = false;
    }

    public String statistics() {
        return String.format(Locale.ROOT, "%d instances (%d collected), %d instanced draws, %d shadow instances/%d shadow draws, %d new meshes, %d instance bytes/frame, %d mesh bytes/frame, %d scene builds/%d reuses, %d queued/%d updated, %.3f ms cache update", instances, collected, drawCalls, shadowInstances, shadowDrawCalls, cacheMisses, uploadedBytes, modelBytes, builds, reuses, pendingUpdates, updatedEntities, cacheUpdateNs / 1_000_000.0);
    }

    public interface ComponentVersion {
        int imo$version();
        void imo$track();
        boolean imo$tracked();
    }
    public interface FoilState { boolean imo$foil(int tick); }
    public interface BufferAccess { int imo$handle(); }
    public interface EntityCacheAccess {
        EntityCache imo$cache();
        boolean imo$eligible();
        boolean imo$hasFire();
        void imo$invalidate();
    }
    public interface ItemStateAccess {
        ItemStack imo$stack();
        void imo$stack(ItemStack stack);
        List<Object> imo$identity();
    }
    public static final class EntityCache {
        private ItemStack stack;
        private int version;
        private boolean foil, dynamicFoil;
        private GpuItemBatch batch;
        private int generation = -1;
        private long motionTick = Long.MIN_VALUE;
        private long sceneBuild, previousBuild;
        private float roll, rate;
    }
}
