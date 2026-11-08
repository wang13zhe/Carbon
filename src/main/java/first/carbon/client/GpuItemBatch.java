package first.carbon.client;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import first.carbon.compat.CarbonItemExtensions;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

final class GpuItemBatch implements AutoCloseable {
    final ItemStack stack;
    final Object model;
    final List<Mesh> meshes = new ArrayList<>();
    final List<net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites = new ArrayList<>();
    final float groundScaleY;
    final float copySpacing;
    final float[] copyOffsets = new float[15];
    final CarbonItemExtensions extensions;
    final int instanceStride;
    final boolean entityBatch;
    final boolean physics;
    final boolean gui3d;
    boolean foil, physicsMoving, preparedValid;
    float preparedClock;
    final org.joml.Vector3f center = new org.joml.Vector3f();
    final org.joml.Vector3f halfSize = new org.joml.Vector3f();
    final int instanceBuffer;
    long meshBytes;
    int count, capacity, lastFrame;
    boolean flushed, closed;
    private ByteBuffer data, previous;
    private ByteBuffer updateData;
    int updateCount;
    int updateCapacity;
    private boolean updatePhysicsMoving;
    private int gpuCapacity, uploadedCount;
    private boolean dirty;
    int preparedBuffer, preparedCapacity;
    boolean processed;
    int retainedCount;

    GpuItemBatch(ItemStack stack, ItemStackRenderState state, ItemBatchRenderer renderer, boolean entityBatch, net.minecraft.client.renderer.feature.ItemFeatureRenderer.Submit submit) {
        this.stack = stack.copy();
        this.model = List.copyOf(((ItemBatchRenderer.ItemStateAccess) state).imo$identity());
        this.entityBatch = entityBatch;
        physics = entityBatch && renderer.physics;
        instanceStride = entityBatch ? 40 : 144;
        AABB bounds = state.getModelBoundingBox();
        groundScaleY = (float) (-bounds.minY + 0.0625) * 4;
        gui3d = bounds.getZsize() > 0.0625;
        copySpacing = gui3d ? 0 : (float) bounds.getZsize() * 1.5f;
        extensions = CarbonItemExtensions.of(stack);
        RandomSource random = RandomSource.create(net.minecraft.client.renderer.entity.state.ItemClusterRenderState.getSeedForItemStack(stack));
        float spread = gui3d ? 0.15f : 0.075f;
        for (int copy = 1; copy < 5; copy++) {
            int offset = copy * 3;
            copyOffsets[offset] = (random.nextFloat() * 2 - 1) * spread;
            copyOffsets[offset + 1] = (random.nextFloat() * 2 - 1) * spread;
            if (gui3d) copyOffsets[offset + 2] = (random.nextFloat() * 2 - 1) * spread;
        }
        instanceBuffer = glGenBuffers();
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING), buffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        Map<Material, ByteBufferBuilder> allocators = new LinkedHashMap<>();
        Map<Material, BufferBuilder> builders = new LinkedHashMap<>();
        QuadInstance instance = new QuadInstance();
        org.joml.Vector3f minimum = new org.joml.Vector3f(Float.POSITIVE_INFINITY), maximum = new org.joml.Vector3f(Float.NEGATIVE_INFINITY);
        renderer.capturing = true;
        int[] captured = entityBatch ? Carbon.IRIS.capture(null) : null;
        try {
            SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(), new Class<?>[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                if (method.getName().equals("order")) return proxy;
                if (!method.getName().equals("submitItem") || args[7] == ItemStackRenderState.FoilType.SPECIAL || (int) args[4] != 0) {
                    throw new IllegalArgumentException("Special item submission requires vanilla rendering");
                }
                foil |= args[7] == ItemStackRenderState.FoilType.STANDARD;
                PoseStack.Pose pose = ((PoseStack) args[0]).last();
                int[] tints = (int[]) args[5];
                for (BakedQuad quad : (List<BakedQuad>) args[6]) {
                    var material = quad.materialInfo();
                    if (!sprites.contains(material.sprite())) sprites.add(material.sprite());
                    if (material.itemRenderType().hasBlending()) throw new IllegalArgumentException("Translucent material");
                    var pipeline = material.itemRenderType().pipeline();
                    String vertexShader = pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).toString();
                    boolean unlit = vertexShader.equals("neoforge:core/item_unlit");
                    if (!unlit && !vertexShader.equals("minecraft:core/item")
                            || !pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT).equals(Identifier.withDefaultNamespace("core/item"))) {
                        throw new IllegalArgumentException("Custom item shader");
                    }
                    Material key = new Material(material.sprite().atlasLocation(), pipeline.isCull(), Carbon.IRIS.active() ? material.itemRenderType() : null, material.itemGlintRenderType());
                    BufferBuilder builder = builders.computeIfAbsent(key, ignored -> {
                        ByteBufferBuilder allocator = new ByteBufferBuilder(4096);
                        allocators.put(key, allocator);
                        return new BufferBuilder(allocator, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
                    });
                    instance.setColor(material.tintIndex() >= 0 && material.tintIndex() < tints.length ? tints[material.tintIndex()] : -1);
                    instance.setLightCoords(0);
                    instance.setOverlayCoords(Carbon.IRIS.active() ? OverlayTexture.NO_OVERLAY : unlit ? 0 : 65537);
                    builder.putBakedQuad(pose, quad, instance);
                }
                return null;
            });
            if (submit == null) state.submit(new PoseStack(), collector, 0, OverlayTexture.NO_OVERLAY, 0);
            else collector.submitItem(new PoseStack(), submit.displayContext(), 0, OverlayTexture.NO_OVERLAY, 0, submit.tintLayers(), net.minecraft.client.resources.model.geometry.ItemQuads.split(submit.quads()), submit.foilType());
            for (var entry : builders.entrySet()) {
                try (MeshData mesh = entry.getValue().build()) {
                    if (mesh != null) {
                        ByteBuffer vertices = mesh.vertexBuffer();
                        int stride = mesh.drawState().format().getVertexSize();
                        for (int i = 0; i < mesh.drawState().vertexCount(); i++) {
                            if (physics) {
                                org.joml.Vector3f point = new org.joml.Vector3f(vertices.getFloat(i * stride), vertices.getFloat(i * stride + 4), vertices.getFloat(i * stride + 8));
                                minimum.min(point); maximum.max(point);
                            }
                            int alpha = vertices.get(i * stride + 15) & 255;
                            if (alpha != 0 && alpha != 255) throw new IllegalArgumentException("Translucent vertex color");
                        }
                        Mesh part = new Mesh(entry.getKey().texture, entry.getKey().cull, entry.getKey().type, vertices, mesh.drawState().format(), mesh.drawState().vertexCount(), entry.getKey().glintType);
                        meshes.add(part);
                        meshBytes += vertices.remaining() + part.indices * 4L;
                    }
                }
            }
            if (physics) { halfSize.set(maximum).sub(minimum).mul(0.5f); center.set(minimum).add(halfSize); }
            if (meshes.isEmpty()) throw new IllegalArgumentException("Empty model");
        } catch (RuntimeException exception) {
            close();
            throw exception;
        } finally {
            renderer.capturing = false;
            Carbon.IRIS.restore(captured);
            for (ByteBufferBuilder allocator : allocators.values()) allocator.close();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
        }
    }
    void add(Matrix4fc matrix, Matrix3fc normal, float ageOffset, float phase, boolean animated,
             int light, boolean bob, float x, float y, float z) {
        dirty = true;
        ensureCapacity(1);
        int offset = count++ * instanceStride;
        matrix.get(offset, data);
        data.putFloat(offset + 64, ageOffset);
        data.putFloat(offset + 68, phase);
        data.putFloat(offset + 72, animated ? 1 : 0);
        data.putFloat(offset + 76, bob ? 1 : 0);
        data.putFloat(offset + 80, light & 65535);
        data.putFloat(offset + 84, light >>> 16);
        normal.get(offset + 88, data);
        data.putFloat(offset + 124, x);
        data.putFloat(offset + 128, y);
        data.putFloat(offset + 132, z);
        data.putLong(offset + 136, 0);
    }

    // Negative z stores a compact 3D offset; nonnegative z stores the flat-item layer.
    void addEntity(float x, float y, float z, float age, float phase, int light, boolean bob, boolean spread, int copies, float rate) {
        dirty = true;
        ensureCapacity(copies);
        long address = MemoryUtil.memAddress(data) + (long) count * instanceStride;
        float center = (copies - 1) * 0.5f;
        for (int copy = 0; copy < copies; copy++) {
            int offset = copy * 3;
            MemoryUtil.memPutFloat(address, x);
            MemoryUtil.memPutFloat(address + 4, y);
            MemoryUtil.memPutFloat(address + 8, z);
            MemoryUtil.memPutInt(address + 12, light);
            MemoryUtil.memPutFloat(address + 16, age);
            MemoryUtil.memPutFloat(address + 20, phase);
            MemoryUtil.memPutFloat(address + 24, spread ? copyOffsets[offset] : 0);
            MemoryUtil.memPutFloat(address + 28, spread ? copyOffsets[offset + 1] : 0);
            MemoryUtil.memPutFloat(address + 32, physics ? gui3d ? -1 + (spread ? copyOffsets[offset + 2] : 0) : copy : (copy - center) * copySpacing + (spread ? copyOffsets[offset + 2] : 0));
            MemoryUtil.memPutFloat(address + 36, physics ? rate : bob ? 1 : 0);
            address += instanceStride;
        }
        count += copies;
        physicsMoving |= physics && rate != 0;
    }

    private void ensureCapacity(int additional) {
        if (count + additional > capacity) {
            capacity = Math.max(64, Math.max(capacity * 2, count + additional));
            data = data == null ? MemoryUtil.memAlloc(capacity * instanceStride) : MemoryUtil.memRealloc(data, capacity * instanceStride);
            if (previous == null || previous.capacity() < capacity * instanceStride) {
                previous = previous == null ? MemoryUtil.memAlloc(capacity * instanceStride) : MemoryUtil.memRealloc(previous, capacity * instanceStride);
            }
        }
    }

    void swapUpdate() {
        ByteBuffer data = this.data;
        this.data = updateData;
        updateData = data;
        int count = this.count;
        this.count = updateCount;
        updateCount = count;
        int capacity = this.capacity;
        this.capacity = updateCapacity;
        updateCapacity = capacity;
        boolean moving = physicsMoving;
        physicsMoving = updatePhysicsMoving;
        updatePhysicsMoving = moving;
        dirty = true;
    }

    long upload() {
        if (!dirty && uploadedCount == count && gpuCapacity >= capacity) return 0;
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
        int bytes = count * instanceStride;
        int first = 0;
        int last = bytes;
        if (gpuCapacity < capacity) {
            glBufferData(GL_ARRAY_BUFFER, capacity * (long) instanceStride, GL_DYNAMIC_DRAW);
            gpuCapacity = capacity;
        } else {
            int common = Math.min(count, uploadedCount) * instanceStride;
            first = data.slice(0, common).mismatch(previous.slice(0, common));
            if (first < 0) {
                first = common;
            }
            if (count <= uploadedCount && first < bytes) {
                while (last > first && data.get(last - 1) == previous.get(last - 1)) {
                    last--;
                }
            }
        }
        if (first < last) {
            preparedValid = false;
            first &= ~3;
            last = Math.min(bytes, (last + 3) & ~3);
            glBufferSubData(GL_ARRAY_BUFFER, first, data.slice(first, last - first));
            MemoryUtil.memCopy(MemoryUtil.memAddress(data) + first, MemoryUtil.memAddress(previous) + first, last - first);
        }
        uploadedCount = count;
        dirty = false;
        return Math.max(0, last - first);
    }

    @Override
    public void close() {
        closed = true;
        for (Mesh mesh : meshes) {
            mesh.close();
        }
        meshes.clear();
        glDeleteBuffers(instanceBuffer);
        if (preparedBuffer != 0) {
            glDeleteBuffers(preparedBuffer);
        }
        if (data != null) {
            MemoryUtil.memFree(data);
            data = null;
        }
        if (updateData != null) {
            MemoryUtil.memFree(updateData);
            updateData = null;
        }
        if (previous != null) {
            MemoryUtil.memFree(previous);
            previous = null;
        }
    }

    private record Material(Identifier texture, boolean cull, net.minecraft.client.renderer.rendertype.RenderType type, net.minecraft.client.renderer.rendertype.RenderType glintType) {}

    final class Mesh implements AutoCloseable {
        final Identifier texture;
        final boolean cull;
        final net.minecraft.client.renderer.rendertype.RenderType type;
        final net.minecraft.client.renderer.rendertype.RenderType glintType;
        final VertexFormat format;
        final GpuBuffer vertices;
        final int vao;
        final int vertexBuffer;
        final int indexBuffer;
        final int indices;
        private int boundInstances;

        Mesh(Identifier texture, boolean cull, net.minecraft.client.renderer.rendertype.RenderType type, ByteBuffer vertices, VertexFormat format, int vertexCount, net.minecraft.client.renderer.rendertype.RenderType glintType) {
            this.texture = texture;
            this.cull = cull;
            this.type = type;
            this.glintType = glintType;
            this.format = format;
            vao = glGenVertexArrays();
            this.vertices = RenderSystem.getDevice().createBuffer(() -> "Carbon item mesh", GpuBuffer.USAGE_VERTEX, vertices);
            vertexBuffer = ((ItemBatchRenderer.BufferAccess) this.vertices).imo$handle();
            indexBuffer = glGenBuffers();
            indices = vertexCount / 4 * 6;
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            bindVertices();
            for (int attribute = 0; attribute < 6; attribute++) {
                glEnableVertexAttribArray(attribute);
            }
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            IntBuffer elements = MemoryUtil.memAllocInt(indices);
            try {
                for (int vertex = 0; vertex < vertexCount; vertex += 4) {
                    int base = vertex;
                    elements.put(base).put(base + 1).put(base + 2);
                    elements.put(base + 2).put(base + 3).put(base);
                }
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, elements.flip(), GL_STATIC_DRAW);
            } finally {
                MemoryUtil.memFree(elements);
            }
            glBindBuffer(GL_ARRAY_BUFFER, instanceBuffer);
            boundInstances = instanceBuffer;
            if (entityBatch) {
                glVertexAttribPointer(6, 3, GL_FLOAT, false, instanceStride, 0);
                glVertexAttribPointer(7, processed && physics ? 4 : 2, GL_FLOAT, false, instanceStride, 16);
                glVertexAttribPointer(8, 4, GL_FLOAT, false, instanceStride, 24);
                glVertexAttribIPointer(9, 2, GL_UNSIGNED_SHORT, instanceStride, 12);
            } else {
                for (int column = 0; column < 4; column++) {
                    glVertexAttribPointer(6 + column, 4, GL_FLOAT, false, instanceStride, column * 16L);
                }
                glVertexAttribPointer(10, 4, GL_FLOAT, false, instanceStride, 64);
                glVertexAttribPointer(11, 2, GL_FLOAT, false, instanceStride, 80);
                for (int column = 0; column < 3; column++) {
                    glVertexAttribPointer(12 + column, 3, GL_FLOAT, false, instanceStride, 88 + column * 12L);
                }
                glVertexAttribPointer(15, 3, GL_FLOAT, false, instanceStride, 124);
            }
            for (int attribute = 6; attribute < (entityBatch ? 10 : 16); attribute++) {
                glEnableVertexAttribArray(attribute);
                glVertexAttribDivisor(attribute, 1);
            }
        }

        void bind() {
            glBindVertexArray(vao);
            int source = processed ? preparedBuffer : instanceBuffer;
            if (entityBatch && source != boundInstances) {
                glBindBuffer(GL_ARRAY_BUFFER, source);
                glVertexAttribPointer(6, 3, GL_FLOAT, false, instanceStride, 0);
                glVertexAttribPointer(7, processed && physics ? 4 : 2, GL_FLOAT, false, instanceStride, 16);
                glVertexAttribPointer(8, 4, GL_FLOAT, false, instanceStride, 24);
                glVertexAttribIPointer(9, 2, GL_UNSIGNED_SHORT, instanceStride, 12);
                boundInstances = source;
            }
        }

        private void bindVertices() {
            int stride = format.getVertexSize();
            glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
            glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, true, stride, 12);
            glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 16);
            glVertexAttribIPointer(3, 2, GL_SHORT, stride, 24);
            glVertexAttribIPointer(4, 2, GL_SHORT, stride, 28);
            glVertexAttribPointer(5, 3, GL_BYTE, true, stride, 32);
        }

        @Override
        public void close() {
            glDeleteVertexArrays(vao);
            Carbon.IRIS.releaseMesh(vertexBuffer);
            vertices.close();
            glDeleteBuffers(indexBuffer);
        }
    }
}
