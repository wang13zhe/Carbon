package first.carbon.client;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL43.*;

// 在原生物品通道内合批，保留提交者提供的姿态与光照。
public final class GenericItemRenderer {
    private final Map<Key, GpuItemBatch> meshes = new HashMap<>();
    private final Matrix4f identity = new Matrix4f();
    private int frame, program, framebuffer;
    private long meshBytes;
    private boolean active, failed;
    private int irisGeneration;
    public int instances, drawCalls;
    public long totalInstances, totalDraws;

    public void begin() {
        if (irisGeneration != Carbon.IRIS.generation) {
            invalidate();
            irisGeneration = Carbon.IRIS.generation;
        }
        active = !failed && Carbon.CONFIG.enabled && Carbon.IRIS.canInstance() && Carbon.IRIS.canSubmit()
                && Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture() instanceof com.mojang.renderpearl.backend.opengl.GlTexture
                && GL.getCapabilities().OpenGL33 && RenderSystem.getProjectionMatrixBuffer() != null && RenderSystem.getShaderFog() != null;
        if (active && Carbon.IRIS.active()) Carbon.RENDERER.drawIrisShadows();
        frame = (int) Minecraft.getInstance().level.getGameTime();
        instances = drawCalls = 0;
        if (active && !Carbon.IRIS.active() && program == 0) {
            try {
                program = Carbon.RENDERER.createProgram("generic_instanced.vsh", "generic_instanced.fsh");
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Projection"), 4);
                glUniformBlockBinding(program, glGetUniformBlockIndex(program, "Fog"), 5);
                framebuffer = glGenFramebuffers();
            } catch (Exception exception) {
                failed = true;
                active = false;
                org.slf4j.LoggerFactory.getLogger("Carbon").error("Keeping native item submissions", exception);
            }
        }
        var iterator = meshes.values().iterator();
        while (iterator.hasNext()) {
            GpuItemBatch batch = iterator.next();
            batch.count = 0;
            if (frame - batch.lastFrame > 300) {
                meshBytes -= batch.meshBytes;
                batch.close();
                iterator.remove();
            }
        }
    }

    public boolean submit(net.minecraft.client.renderer.feature.ItemFeatureRenderer.Submit submit) {
        if (!active || submit.outlineColor() != 0 || submit.overlayCoords() != OverlayTexture.NO_OVERLAY
                || submit.foilType() == ItemStackRenderState.FoilType.SPECIAL || submit.quads().isEmpty()) return false;
        int[] captured = Carbon.IRIS.capture(submit);
        try {
            Key key = new Key(List.copyOf(submit.quads()), new IntArrayList(submit.tintLayers()), submit.foilType(), Carbon.IRIS.identifiers());
            GpuItemBatch batch = meshes.get(key);
            if (batch == null) {
                if (meshes.size() >= 256 || meshBytes >= 67108864) return false;
                ItemStackRenderState state = new ItemStackRenderState();
                var layer = state.newLayer();
                layer.setQuads(net.minecraft.client.resources.model.geometry.ItemQuads.split(submit.quads()));
                for (int tint : submit.tintLayers()) layer.tintLayers().add(tint);
                layer.setFoilType(submit.foilType());
                try {
                    batch = new GpuItemBatch(new ItemStack(Items.STONE), state, Carbon.RENDERER, false, submit);
                    meshes.put(key, batch);
                    meshBytes += batch.meshBytes;
                } catch (RuntimeException ignored) { return false; }
            }
            if (batch.count >= 65536) return false;
            batch.add(submit.pose().pose(), submit.pose().normal(), 0, 0, false, submit.lightCoords(), false, 0, 0, 0);
            batch.lastFrame = frame;
            instances++; totalInstances++;
            return true;
        } finally { Carbon.IRIS.restore(captured); }
    }

    public void flush() {
        if (active && instances != 0) {
            if (Carbon.IRIS.active()) {
                var zero = new org.joml.Vector3f();
                for (GpuItemBatch batch : meshes.values()) if (batch.count != 0) {
                    Carbon.SODIUM.mark(batch.sprites);
                    batch.upload();
                    for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) for (var mesh : batch.meshes) {
                        var type = pass == 0 || mesh.glintType == null ? mesh.type : mesh.glintType;
                        if (pass == 0 || !Carbon.IRIS.shadowPass()) {
                            Carbon.IRIS.draw(type, pass == 0 || mesh.preparedGlint == null ? mesh.prepared : mesh.preparedGlint, mesh.vertices, mesh.vertexBuffer, mesh.indexBuffer, mesh.format, mesh.indices,
                                    batch.count, batch.instanceBuffer, 0, zero, 0, 0, batch.center, batch.halfSize);
                            drawCalls++; totalDraws++;
                        }
                    }
                }
            } else try (GpuFrame gpu = new GpuFrame(framebuffer, true); MemoryStack memory = MemoryStack.stackPush()) {
                var scissor = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissor.enabled()) { glEnable(GL_SCISSOR_TEST); glScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height()); }
                glUseProgram(program);
                glUniformMatrix4fv(glGetUniformLocation(program, "View"), false, RenderSystem.getModelViewMatrixCopy().get(memory.mallocFloat(16)));
                glUniform3f(glGetUniformLocation(program, "Camera"), 0, 0, 0);
                glUniform1i(glGetUniformLocation(program, "Sampler0"), 0);
                glUniform1i(glGetUniformLocation(program, "Sampler2"), 2);
                glUniform4f(glGetUniformLocation(program, "ColorModulator"), 1, 1, 1, 1);
                for (GpuItemBatch batch : meshes.values()) {
                    if (batch.count != 0) {
                        Carbon.SODIUM.mark(batch.sprites);
                        batch.upload();
                        for (int pass = 0; pass < (batch.foil ? 2 : 1); pass++) {
                            glUniform1i(glGetUniformLocation(program, "GlintPass"), pass);
                            if (pass != 0) {
                                glEnable(GL_BLEND);
                                glBlendFuncSeparate(GL_SRC_COLOR, GL_ONE, GL_ZERO, GL_ONE);
                                glDepthFunc(GL_EQUAL);
                                glDepthMask(false);
                            }
                            glUniformMatrix4fv(glGetUniformLocation(program, "TextureMat"), false,
                                    (pass == 0 ? identity : TextureTransform.GLINT_TEXTURING.createMatrix()).get(memory.mallocFloat(16)));
                            glUniform1f(glGetUniformLocation(program, "GlintAlpha"), Minecraft.getInstance().options.glintStrength().get().floatValue());
                            for (var mesh : batch.meshes) {
                                if (mesh.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
                                gpu.bindTexture(0, pass == 0 ? mesh.texture : ItemFeatureRenderer.ENCHANTED_GLINT_ITEM);
                                mesh.bind();
                                glDrawElementsInstanced(GL_TRIANGLES, mesh.indices, GL_UNSIGNED_INT, 0, batch.count);
                                drawCalls++; totalDraws++;
                            }
                        }
                        glDisable(GL_BLEND);
                        glDepthFunc(GL_LEQUAL);
                        glDepthMask(true);
                    }
                }
            }
        }
    }

    public void invalidate() {
        for (GpuItemBatch batch : meshes.values()) batch.close();
        meshes.clear();
        if (program != 0) glDeleteProgram(program);
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
        program = framebuffer = 0;
        meshBytes = 0;
        active = failed = false;
    }

    private record Key(List<BakedQuad> quads, IntList tints, ItemStackRenderState.FoilType foil, long identifiers) { }
}
