package first.carbon.compat;

import net.minecraft.client.Minecraft;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import first.carbon.Carbon;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

import static org.lwjgl.opengl.GL43.*;

// 通过原生 RenderPass 保留 Iris 通道状态，仅替换物品的实例变换。
public final class IrisCompatibility {
    private boolean checked, ready, active, blocked;
    private MethodHandle enabled, pipelineGetter, shadowPass, shadowView, getLevel, setLevel, overrideShaders, getEntity, setEntity, getItem, setItem, getBlock, setBlock, setSubmit;
    private Object pipeline, settings, itemEntityId;
    private Method entityIds;
    private final Map<Integer, List<Stage>> sources = new ConcurrentHashMap<>();
    private final Map<Long, Program> programs = new HashMap<>();
    private Draw current;
    public int generation, draws, shadowDraws;
    public long mainInstances, shadowInstances;

    public void beginFrame() {
        draws = shadowDraws = 0;
        mainInstances = shadowInstances = 0;
        if (!checked) {
            checked = true;
            try {
                var lookup = MethodHandles.publicLookup();
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                enabled = lookup.unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance);
                Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
                pipelineGetter = lookup.unreflect(manager.getClass().getMethod("getPipelineNullable")).bindTo(manager);
                shadowPass = lookup.unreflect(Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered"));
                shadowView = lookup.unreflectGetter(Class.forName("net.irisshaders.iris.shadows.ShadowRenderer").getField("MODELVIEW"));
                Class<?> immediate = Class.forName("net.irisshaders.iris.vertices.ImmediateState");
                getLevel = lookup.unreflectGetter(immediate.getField("isRenderingLevel"));
                setLevel = lookup.unreflectSetter(immediate.getField("isRenderingLevel"));
                overrideShaders = lookup.unreflect(Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline").getMethod("shouldOverrideShaders"));
                Class<?> captured = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                Object state = captured.getField("INSTANCE").get(null);
                getEntity = lookup.unreflect(captured.getMethod("getCurrentRenderedEntity")).bindTo(state);
                setEntity = lookup.unreflect(captured.getMethod("setCurrentEntity", int.class)).bindTo(state);
                getItem = lookup.unreflect(captured.getMethod("getCurrentRenderedItem")).bindTo(state);
                setItem = lookup.unreflect(captured.getMethod("setCurrentRenderedItem", int.class)).bindTo(state);
                getBlock = lookup.unreflect(captured.getMethod("getCurrentRenderedBlockEntity")).bindTo(state);
                setBlock = lookup.unreflect(captured.getMethod("setCurrentBlockEntity", int.class)).bindTo(state);
                setSubmit = lookup.unreflect(Class.forName("net.irisshaders.iris.mixinterface.ModelStorage").getMethod("iris$set"));
                Class<?> world = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                settings = world.getField("INSTANCE").get(null);
                entityIds = world.getMethod("getEntityIds");
                itemEntityId = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId").getConstructor(String.class, String.class).newInstance("minecraft", "item");
                ready = true;
            } catch (ClassNotFoundException ignored) { }
            catch (ReflectiveOperationException exception) {
                blocked = true;
                org.slf4j.LoggerFactory.getLogger("Carbon").warn("Iris bridge initialization unavailable", exception);
            }
        }
        if (enabled != null) {
            try {
                boolean using = (boolean) enabled.invokeExact();
                Object next = pipelineGetter == null ? null : pipelineGetter.invoke();
                if (using != active || next != pipeline) {
                    invalidate();
                    active = using;
                    pipeline = next;
                }
            } catch (Throwable exception) { active = blocked = true; }
        }
    }

    public boolean active() { return active; }

    public boolean canInstance() {
        var capabilities = GL.getCapabilities();
        return !active || ready && !blocked && (capabilities.OpenGL43 || capabilities.GL_ARB_shader_storage_buffer_object
                && capabilities.GL_ARB_program_interface_query && capabilities.GL_ARB_shading_language_420pack);
    }

    public boolean shadowPass() {
        boolean shadow = false;
        if (active) {
            try { shadow = (boolean) shadowPass.invokeExact(); }
            catch (Throwable exception) { blocked = true; }
        }
        return shadow;
    }

    public boolean canSubmit() {
        boolean allowed = !active;
        if (active && ready) {
            try { allowed = (boolean) getLevel.invokeExact() && (boolean) overrideShaders.invoke(pipeline); }
            catch (Throwable exception) { blocked = true; }
        }
        return allowed;
    }

    public org.joml.Matrix4fc shadowView() {
        try { return (org.joml.Matrix4fc) shadowView.invoke(); }
        catch (Throwable exception) { throw new IllegalStateException(exception); }
    }

    public void captureProgram(int program) {
        int[] shaders = new int[glGetProgrami(program, GL_ATTACHED_SHADERS)];
        glGetAttachedShaders(program, new int[1], shaders);
        List<Stage> stages = new ArrayList<>();
        boolean item = false;
        for (int shader : shaders) {
            int type = glGetShaderi(shader, GL_SHADER_TYPE);
            String text = glGetShaderSource(shader);
            stages.add(new Stage(type, text));
            item |= type == GL_VERTEX_SHADER && text.contains("iris_Position");
        }
        if (item) sources.put(program, stages);
    }

    public void releaseProgram(int source) {
        sources.remove(source);
        programs.entrySet().removeIf(entry -> {
            boolean match = entry.getKey() >>> 3 == source;
            if (match) entry.getValue().close();
            return match;
        });
    }

    @SuppressWarnings("unchecked")
    public int[] capture(Object submit) {
        int[] previous = null;
        if (active && !blocked) {
            try {
                previous = new int[]{(int) getEntity.invokeExact(), (int) getItem.invokeExact(), (int) getBlock.invokeExact(), (boolean) getLevel.invokeExact() ? 1 : 0};
                if (submit != null) setSubmit.invoke(submit);
                else {
                    setLevel.invokeExact(true);
                    Object ids = entityIds.invoke(settings);
                    setEntity.invokeExact(ids == null ? 0 : ((ToIntFunction<Object>) ids).applyAsInt(itemEntityId));
                }
            } catch (Throwable exception) { blocked = true; }
        }
        return previous;
    }

    public void restore(int[] previous) {
        if (previous != null) {
            try {
                setEntity.invokeExact(previous[0]);
                setItem.invokeExact(previous[1]);
                setBlock.invokeExact(previous[2]);
                setLevel.invokeExact(previous[3] != 0);
            } catch (Throwable exception) { blocked = true; }
        }
    }

    public long identifiers() {
        long ids = 0;
        if (active) {
            try { ids = ((int) getEntity.invokeExact() & 65535L) | (((int) getBlock.invokeExact() & 65535L) << 16) | (((int) getItem.invokeExact() & 65535L) << 32); }
            catch (Throwable exception) { blocked = true; }
        }
        return ids;
    }

    public void draw(RenderType type, GpuBuffer vertices, int vertexBuffer, int indexBuffer, VertexFormat format, int indices,
                     int instances, int instanceBuffer, int mode, Vector3f camera, float clock, float scale, Vector3f center, Vector3f halfSize) {
        var prepared = type.prepare();
        var transforms = prepared.dynamicTransforms();
        var autoIndices = RenderSystem.getSequentialBuffer(type.primitiveTopology());
        var elements = autoIndices.getBuffer(indices);
        current = new Draw(vertexBuffer, indexBuffer, format, indices, instances, instanceBuffer, mode, camera, clock, scale, center, halfSize);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Carbon Iris items", Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(), Optional.<org.joml.Vector4fc>empty(), Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(prepared.pipeline()));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, vertices.slice());
            pass.setIndexBuffer(elements, autoIndices.type());
            var scissor = prepared.scissorState();
            if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            for (var texture : prepared.textures()) pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
            pass.drawIndexed(0, 0, indices, 1, 0);
        } finally { current = null; }
    }

    public boolean drawCurrent() {
        Draw draw = current;
        if (draw != null && !blocked) {
            int source = glGetInteger(GL_CURRENT_PROGRAM), vao = glGetInteger(GL_VERTEX_ARRAY_BINDING), array = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            try (MemoryStack memory = MemoryStack.stackPush()) {
                long key = (long) source << 3 | draw.mode;
                Program program = programs.get(key);
                if (program == null) {
                    program = new Program(source, draw.mode);
                    programs.put(key, program);
                }
                int previous = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, program.binding);
                long start = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, program.binding), size = glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, program.binding);
                int storage = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
                try {
                    glUseProgram(program.id);
                    FloatBuffer floats = memory.mallocFloat(16);
                    IntBuffer integers = memory.mallocInt(16);
                    for (Uniform uniform : program.uniforms) uniform.copy(source, floats, integers);
                    glUniform3f(program.camera, draw.camera.x, draw.camera.y, draw.camera.z);
                    glUniform1f(program.clock, draw.clock);
                    glUniform1f(program.scale, draw.scale);
                    glUniform3f(program.center, draw.center.x, draw.center.y, draw.center.z);
                    glUniform3f(program.halfSize, draw.halfSize.x, draw.halfSize.y, draw.halfSize.z);
                    glUniform1i(program.flat, 1);
                    int vertexArray = program.vaos.get(draw.vertexBuffer);
                    if (vertexArray == 0) {
                        vertexArray = glGenVertexArrays();
                        program.vaos.put(draw.vertexBuffer, vertexArray);
                        glBindVertexArray(vertexArray);
                        glBindBuffer(GL_ARRAY_BUFFER, draw.vertexBuffer);
                        var elements = draw.format.getElements();
                        for (int i = 0; i < elements.size(); i++) {
                            var element = elements.get(i);
                            String name = element.name();
                            if (name.equals("Position") || name.equals("Color") || name.equals("Normal") || name.startsWith("UV")) name = "iris_" + name;
                            int attribute = glGetAttribLocation(program.id, name);
                            if (attribute >= 0) {
                                var componentType = element.format().componentType();
                                int glType = switch (componentType) {
                                    case UNORM_8, UINT_8, OPAQUE_8 -> GL_UNSIGNED_BYTE;
                                    case SNORM_8, SINT_8 -> GL_BYTE;
                                    case UNORM_16, UINT_16, OPAQUE_16 -> GL_UNSIGNED_SHORT;
                                    case SNORM_16, SINT_16 -> GL_SHORT;
                                    case FLOAT_16, FLOAT_32, OPAQUE_32, OPAQUE_64 -> GL_FLOAT;
                                    case UINT_32 -> GL_UNSIGNED_INT;
                                    case SINT_32 -> GL_INT;
                                };
                                boolean normalized = componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.UNORM_8
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.SNORM_8
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.UNORM_16
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.SNORM_16;
                                boolean integer = componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.UINT_8
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.SINT_8
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.UINT_16
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.SINT_16
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.UINT_32
                                        || componentType == com.mojang.renderpearl.api.GpuFormat.ComponentType.SINT_32;
                                if (integer) glVertexAttribIPointer(attribute, element.format().componentCount(), glType, draw.format.getVertexSize(), element.offset());
                                else glVertexAttribPointer(attribute, element.format().componentCount(), glType, normalized, draw.format.getVertexSize(), element.offset());
                                glEnableVertexAttribArray(attribute);
                            }
                        }
                        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, draw.indexBuffer);
                    } else glBindVertexArray(vertexArray);
                    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, program.binding, draw.instanceBuffer);
                    glDrawElementsInstanced(GL_TRIANGLES, draw.indices, GL_UNSIGNED_INT, 0, draw.instances);
                    draws++;
                    if (shadowPass()) {
                        shadowDraws++;
                        shadowInstances += draw.instances;
                    } else mainInstances += draw.instances;
                } finally {
                    if (previous != 0 && size > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, program.binding, previous, start, size);
                    else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, program.binding, previous);
                    glBindBuffer(GL_SHADER_STORAGE_BUFFER, storage);
                }
            } catch (Throwable exception) {
                blocked = true;
                generation++;
                org.slf4j.LoggerFactory.getLogger("Carbon").error("Iris instancing unavailable; restoring native submissions", exception);
            } finally {
                glUseProgram(source);
                glBindVertexArray(vao);
                glBindBuffer(GL_ARRAY_BUFFER, array);
            }
        }
        return draw != null;
    }

    public void releaseMesh(int buffer) {
        for (Program program : programs.values()) {
            int vao = program.vaos.remove(buffer);
            if (vao != 0) glDeleteVertexArrays(vao);
        }
    }

    public void invalidate() {
        for (Program program : programs.values()) program.close();
        programs.clear();
        blocked = false;
        generation++;
    }

    public interface RenderTypeAccess { RenderSetup carbon$setup(); }
    public interface RenderSetupAccess { net.minecraft.client.renderer.rendertype.TextureTransform carbon$textureTransform(); }
    private record Stage(int type, String text) { }
    private record Draw(int vertexBuffer, int indexBuffer, VertexFormat format, int indices, int instances, int instanceBuffer, int mode,
                        Vector3f camera, float clock, float scale, Vector3f center, Vector3f halfSize) { }

    private static final class Program implements AutoCloseable {
        private final int id, binding, camera, clock, scale, center, halfSize, flat;
        private final List<Uniform> uniforms = new ArrayList<>();
        private final Int2IntOpenHashMap vaos = new Int2IntOpenHashMap();

        private Program(int source, int mode) throws Exception {
            List<Stage> stages = Carbon.IRIS.sources.get(source);
            if (stages == null) throw new IllegalStateException("Missing Iris shader source");
            int vertex = 0;
            String text = null;
            for (Stage stage : stages) {
                if (stage.type == GL_VERTEX_SHADER) text = stage.text;
                if (stage.type == GL_TESS_CONTROL_SHADER || stage.type == GL_TESS_EVALUATION_SHADER) throw new IllegalArgumentException("Tessellated item shader requires native submissions");
            }
            if (text == null) throw new IllegalStateException("Missing Iris vertex source");
            Set<Integer> used = new HashSet<>();
            int blocks = glGetProgramInterfacei(source, GL_SHADER_STORAGE_BLOCK, GL_ACTIVE_RESOURCES);
            for (int i = 0; i < blocks; i++) {
                int[] result = new int[1];glGetProgramResourceiv(source, GL_SHADER_STORAGE_BLOCK, i, new int[]{GL_BUFFER_BINDING}, new int[1], result);used.add(result[0]);
            }
            int selected = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) - 1;
            while (used.contains(selected)) selected--;
            if (selected < 0) throw new IllegalStateException("No free shader storage binding");
            binding = selected;
            var version = Pattern.compile("#version\\s+(\\d+)[^\\r\\n]*").matcher(text);
            if (version.find() && Integer.parseInt(version.group(1)) < 330) text = version.replaceFirst("#version 330 core");
            for (String name : new String[]{"Position", "Normal", "Color", "UV0", "UV1", "UV2"}) {
                if (Pattern.compile("\\bin\\s+\\w+\\s+" + name + "\\s*;").matcher(text).find()) {
                    text = text.replaceAll("\\b" + name + "\\b", "iris_" + name);
                }
            }
            String[] inputs = {"iris_Position", "iris_Normal", "iris_UV1", "iris_UV2", "at_tangent", "iris_Entity"};
            String[] types = {"vec3", "vec3", "ivec2", "ivec2", "vec4", "ivec3"};
            StringBuilder declarations = new StringBuilder();
            for (int i = 0; i < inputs.length; i++) {
                String name = inputs[i];
                var declaration = Pattern.compile("\\bin\\s+(\\w+)\\s+" + name + "\\s*;").matcher(text);
                if (declaration.find()) types[i] = declaration.group(1);
                else declarations.append("in ").append(types[i]).append(' ').append(name).append(";\n");
                text = text.replaceAll("\\b" + name + "\\b", "carbon_" + name);
                text = text.replaceFirst("\\bin\\s+" + types[i] + "\\s+carbon_" + name + "\\s*;", "in " + types[i] + " " + name + ";");
                declarations.append(types[i]).append(" carbon_").append(name).append(";\n");
            }
            text = text.replaceFirst("void\\s+main\\s*\\([^)]*\\)", "void carbon_original_main()");
            String kernel;
            try (var stream = IrisCompatibility.class.getResourceAsStream("/assets/carbon/shaders/iris_instancing.glsl")) {
                if (stream == null) throw new IllegalStateException("Missing instance shader bridge");
                kernel = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            String header = "\n#extension GL_ARB_shader_storage_buffer_object : require\n#extension GL_ARB_shading_language_420pack : require\n#define CARBON_MODE " + mode + "\n#define CARBON_UV1_TYPE " + types[2] + "\n#define CARBON_UV2_TYPE " + types[3]
                    + "\nlayout(std430, binding=" + binding + ") readonly buffer CarbonInstances { uint carbon_data[]; };\n" + declarations;
            int versionEnd = text.indexOf('\n');
            var extensions = Pattern.compile("(?m)^\\s*#extension[^\\r\\n]*").matcher(text);
            while (extensions.find()) versionEnd = extensions.end();
            text = text.substring(0, versionEnd + 1) + header + text.substring(versionEnd + 1) + "\n" + kernel;
            id = glCreateProgram();
            try (MemoryStack memory = MemoryStack.stackPush()) {
                vertex = glCreateShader(GL_VERTEX_SHADER);glShaderSource(vertex, text);glCompileShader(vertex);
                if (glGetShaderi(vertex, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(vertex));
                glAttachShader(id, vertex);
                for (Stage stage : stages) if (stage.type != GL_VERTEX_SHADER) {
                    int shader = glCreateShader(stage.type);
                    glShaderSource(shader, stage.text);
                    glCompileShader(shader);
                    if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
                        String log = glGetShaderInfoLog(shader);
                        glDeleteShader(shader);
                        throw new IllegalStateException(log);
                    }
                    glAttachShader(id, shader);
                    glDeleteShader(shader);
                }
                IntBuffer size = memory.mallocInt(1), type = memory.mallocInt(1);
                for (int i = 0; i < glGetProgrami(source, GL_ACTIVE_ATTRIBUTES); i++) {
                    String name = glGetActiveAttrib(source, i, 256, size, type);
                    String attribute = name.equals("Position") || name.equals("Normal") || name.equals("Color") || name.startsWith("UV") ? "iris_" + name : name;
                    glBindAttribLocation(id, glGetAttribLocation(source, name), attribute);
                }
                int outputs = glGetProgramInterfacei(source, GL_PROGRAM_OUTPUT, GL_ACTIVE_RESOURCES);
                for (int i = 0; i < outputs; i++) {
                    String name = glGetProgramResourceName(source, GL_PROGRAM_OUTPUT, i);int location = glGetFragDataLocation(source, name);
                    if (location >= 0) glBindFragDataLocation(id, location, name);
                }
                glLinkProgram(id);
                if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(id));
                for (int i = 0; i < glGetProgrami(source, GL_ACTIVE_UNIFORM_BLOCKS); i++) {
                    String name = glGetActiveUniformBlockName(source, i);
                    int block = glGetUniformBlockIndex(id, name);
                    if (block != GL_INVALID_INDEX) glUniformBlockBinding(id, block, glGetActiveUniformBlocki(source, i, GL_UNIFORM_BLOCK_BINDING));
                }
                for (int i = 0; i < blocks; i++) {
                    String name = glGetProgramResourceName(source, GL_SHADER_STORAGE_BLOCK, i);
                    int block = glGetProgramResourceIndex(id, GL_SHADER_STORAGE_BLOCK, name);
                    int[] originalBinding = new int[1];
                    glGetProgramResourceiv(source, GL_SHADER_STORAGE_BLOCK, i, new int[]{GL_BUFFER_BINDING}, new int[1], originalBinding);
                    if (block != GL_INVALID_INDEX) glShaderStorageBlockBinding(id, block, originalBinding[0]);
                }
                for (int i = 0; i < glGetProgrami(source, GL_ACTIVE_UNIFORMS); i++) {
                    String name = glGetActiveUniform(source, i, 256, size, type);
                    for (int element = 0; element < size.get(0); element++) {
                        String uniform = size.get(0) > 1 ? name.replace("[0]", "[" + element + "]") : name;
                        int location = glGetUniformLocation(id, uniform);
                        if (location >= 0) uniforms.add(new Uniform(glGetUniformLocation(source, uniform), location, type.get(0)));
                    }
                }
            } catch (Throwable exception) {
                glDeleteProgram(id);throw exception;
            } finally { if (vertex != 0) glDeleteShader(vertex); }
            camera = glGetUniformLocation(id, "carbonCamera");clock = glGetUniformLocation(id, "carbonClock");scale = glGetUniformLocation(id, "carbonScale");
            center = glGetUniformLocation(id, "carbonCenter");halfSize = glGetUniformLocation(id, "carbonHalfSize");flat = glGetUniformLocation(id, "carbonFlat");
        }

        @Override
        public void close() {
            for (int vao : vaos.values()) glDeleteVertexArrays(vao);
            glDeleteProgram(id);
        }
    }

    private record Uniform(int source, int target, int type) {
        private void copy(int program, FloatBuffer floats, IntBuffer integers) {
            floats.clear();integers.clear();
            switch (type) {
                case GL_FLOAT, GL_FLOAT_VEC2, GL_FLOAT_VEC3, GL_FLOAT_VEC4, GL_FLOAT_MAT2, GL_FLOAT_MAT3, GL_FLOAT_MAT4, GL_FLOAT_MAT2x3, GL_FLOAT_MAT2x4, GL_FLOAT_MAT3x2, GL_FLOAT_MAT3x4, GL_FLOAT_MAT4x2, GL_FLOAT_MAT4x3 -> {
                    glGetUniformfv(program, source, floats);
                    switch (type) {
                        case GL_FLOAT -> glUniform1fv(target, floats.limit(1));
                        case GL_FLOAT_VEC2 -> glUniform2fv(target, floats.limit(2));
                        case GL_FLOAT_VEC3 -> glUniform3fv(target, floats.limit(3));
                        case GL_FLOAT_VEC4 -> glUniform4fv(target, floats.limit(4));
                        case GL_FLOAT_MAT2 -> glUniformMatrix2fv(target, false, floats.limit(4));
                        case GL_FLOAT_MAT3 -> glUniformMatrix3fv(target, false, floats.limit(9));
                        case GL_FLOAT_MAT2x3 -> glUniformMatrix2x3fv(target, false, floats.limit(6));
                        case GL_FLOAT_MAT2x4 -> glUniformMatrix2x4fv(target, false, floats.limit(8));
                        case GL_FLOAT_MAT3x2 -> glUniformMatrix3x2fv(target, false, floats.limit(6));
                        case GL_FLOAT_MAT3x4 -> glUniformMatrix3x4fv(target, false, floats.limit(12));
                        case GL_FLOAT_MAT4x2 -> glUniformMatrix4x2fv(target, false, floats.limit(8));
                        case GL_FLOAT_MAT4x3 -> glUniformMatrix4x3fv(target, false, floats.limit(12));
                        default -> glUniformMatrix4fv(target, false, floats.limit(16));
                    }
                }
                case GL_UNSIGNED_INT, GL_UNSIGNED_INT_VEC2, GL_UNSIGNED_INT_VEC3, GL_UNSIGNED_INT_VEC4 -> {
                    glGetUniformuiv(program, source, integers);
                    switch(type) {
                        case GL_UNSIGNED_INT_VEC2 -> glUniform2uiv(target, integers.limit(2));
                        case GL_UNSIGNED_INT_VEC3 -> glUniform3uiv(target, integers.limit(3));
                        case GL_UNSIGNED_INT_VEC4 -> glUniform4uiv(target, integers.limit(4));
                        default -> glUniform1uiv(target, integers.limit(1));
                    }
                }
                default -> {
                    glGetUniformiv(program, source, integers);
                    switch(type) {
                        case GL_INT_VEC2, GL_BOOL_VEC2 -> glUniform2iv(target, integers.limit(2));
                        case GL_INT_VEC3, GL_BOOL_VEC3 -> glUniform3iv(target, integers.limit(3));
                        case GL_INT_VEC4, GL_BOOL_VEC4 -> glUniform4iv(target, integers.limit(4));
                        default -> glUniform1iv(target, integers.limit(1));
                    }
                }
            }
        }
    }
}
