package first.carbon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

public final class GenericShadowRenderer {
    private final List<net.minecraft.client.renderer.feature.ShadowFeatureRenderer.Submit> remaining = new ArrayList<>();
    private ByteBuffer data, previous;
    private int count, capacity, uploadedCount, gpuCapacity, program, vao, buffer, framebuffer;
    private boolean failed;
    public long totalInstances;

    public List<net.minecraft.client.renderer.feature.ShadowFeatureRenderer.Submit> collect(List<net.minecraft.client.renderer.feature.ShadowFeatureRenderer.Submit> submissions) {
        count = 0;
        if (failed || !Carbon.CONFIG.enabled || Carbon.IRIS.active() || RenderSystem.getProjectionMatrixBuffer() == null
                || !(net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture)
                || RenderSystem.getShaderFog() == null || !org.lwjgl.opengl.GL.getCapabilities().OpenGL33) return submissions;
        if (program == 0 && submissions.stream().anyMatch(submit -> submit.radius() == 0.15f)) {
            int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING), oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            try {
                program = Carbon.RENDERER.createProgram("generic_shadow.vsh", "generic_shadow.fsh");
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Projection"), 4);
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Fog"), 5);
                vao = glGenVertexArrays(); buffer = glGenBuffers(); framebuffer = glGenFramebuffers();
                glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, buffer);
                for (int column = 0; column < 4; column++) glVertexAttribPointer(column, 4, GL_FLOAT, false, 96, column * 16L);
                glVertexAttribPointer(4, 4, GL_FLOAT, false, 96, 64);
                glVertexAttribPointer(5, 3, GL_FLOAT, false, 96, 80);
                for (int attribute = 0; attribute < 6; attribute++) { glEnableVertexAttribArray(attribute); glVertexAttribDivisor(attribute, 1); }
            } catch (Exception exception) {
                invalidate(); failed = true;
                org.slf4j.LoggerFactory.getLogger("Carbon").warn("Keeping native item shadows", exception);
                return submissions;
            } finally { glBindVertexArray(oldVao); glBindBuffer(GL_ARRAY_BUFFER, oldBuffer); }
        }
        remaining.clear();
        for (var submit : submissions) {
            if (submit.radius() != 0.15f) {
                remaining.add(submit);
            } else {
                for (var piece : submit.pieces()) {
                    if (count == capacity) {
                        capacity = Math.max(64, capacity * 2);
                        data = data == null ? MemoryUtil.memAlloc(capacity * 96) : MemoryUtil.memRealloc(data, capacity * 96);
                        previous = previous == null ? MemoryUtil.memAlloc(capacity * 96) : MemoryUtil.memRealloc(previous, capacity * 96);
                    }
                    int offset = count++ * 96;
                    submit.pose().get(offset, data);
                    var bounds = piece.shapeBelow().bounds();
                    data.putFloat(offset + 64, piece.relativeX() + (float) bounds.minX);
                    data.putFloat(offset + 68, piece.relativeZ() + (float) bounds.minZ);
                    data.putFloat(offset + 72, piece.relativeX() + (float) bounds.maxX);
                    data.putFloat(offset + 76, piece.relativeZ() + (float) bounds.maxZ);
                    data.putFloat(offset + 80, piece.relativeY() + (float) bounds.minY);
                    data.putFloat(offset + 84, submit.radius());
                    data.putFloat(offset + 88, (int) (piece.alpha() * 255) / 255.0f);
                    data.putInt(offset + 92, 0);
                }
            }
        }
        return remaining;
    }

    public void flush() {
        if (count > 0) {
            int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING), oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                glBindBuffer(GL_ARRAY_BUFFER, buffer);
                boolean changed = gpuCapacity < capacity || uploadedCount != count || data.slice(0, count * 96).mismatch(previous.slice(0, count * 96)) >= 0;
                if (gpuCapacity < capacity) { glBufferData(GL_ARRAY_BUFFER, capacity * 96L, GL_DYNAMIC_DRAW); gpuCapacity = capacity; }
                if (changed) {
                    glBufferSubData(GL_ARRAY_BUFFER, 0, data.slice(0, count * 96));
                    MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(previous), count * 96L);
                }
                uploadedCount = count;
                try (GpuFrame gpu = new GpuFrame(framebuffer, true)) {
                    glUseProgram(program);
                    var view = RenderSystem.getModelViewStack();
                    view.pushMatrix();
                    try {
                        RenderSystem.getProjectionType().applyLayeringTransform(view, 1);
                        glUniformMatrix4fv(glGetUniformLocation(program, "View"), false, view.get(memory.mallocFloat(16)));
                    } finally { view.popMatrix(); }
                    glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
                    gpu.bindTexture(0, Identifier.withDefaultNamespace("textures/misc/shadow.png"));
                    glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
                    glDepthMask(false);
                    glBindVertexArray(vao);
                    glDrawArraysInstanced(GL_TRIANGLE_FAN, 0, 4, count);
                    totalInstances += count;
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to draw standard item shadows", exception);
            } finally { glBindVertexArray(oldVao); glBindBuffer(GL_ARRAY_BUFFER, oldBuffer); }
        }
    }

    public void invalidate() {
        if (program != 0) glDeleteProgram(program);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (buffer != 0) glDeleteBuffers(buffer);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        if (data != null) { MemoryUtil.memFree(data); MemoryUtil.memFree(previous); }
        data = previous = null;
        failed = false;
        count = capacity = gpuCapacity = uploadedCount = program = vao = buffer = framebuffer = 0;
    }
}
