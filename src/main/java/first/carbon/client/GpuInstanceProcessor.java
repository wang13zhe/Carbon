package first.carbon.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.lwjgl.opengl.GL43.*;

// 按需在 GPU 准备掉落物实例变换。
final class GpuInstanceProcessor implements AutoCloseable {
    private final int program;

    GpuInstanceProcessor(boolean physics) throws Exception {
        int shader = glCreateShader(GL_COMPUTE_SHADER);
        int created = 0;
        try (InputStream stream = getClass().getResourceAsStream("/assets/carbon/shaders/" + (physics ? "prepare_physics.comp" : "prepare_entities.comp"))) {
            if (stream == null) throw new IllegalStateException("Missing entity animation shader");
            glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalStateException(glGetShaderInfoLog(shader));
            created = glCreateProgram();
            glAttachShader(created, shader);
            glLinkProgram(created);
            if (glGetProgrami(created, GL_LINK_STATUS) == GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(created));
        } catch (Exception exception) {
            if (created != 0) glDeleteProgram(created);
            throw exception;
        } finally {
            glDeleteShader(shader);
        }
        program = created;
    }

    boolean prepare(GpuItemBatch batch, float clock) {
        if (batch.physics && batch.preparedValid && (!batch.physicsMoving || batch.preparedClock == clock)) return false;
        int buffer0 = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 0);
        int buffer1 = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 1);
        long start0 = buffer0 == 0 ? 0 : glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 0);
        long start1 = buffer1 == 0 ? 0 : glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 1);
        long size0 = buffer0 == 0 ? 0 : glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 0);
        long size1 = buffer1 == 0 ? 0 : glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 1);
        int previous = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            if (batch.preparedBuffer == 0) batch.preparedBuffer = glGenBuffers();
            if (batch.preparedCapacity < batch.capacity) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, batch.preparedBuffer);
                glBufferData(GL_SHADER_STORAGE_BUFFER, batch.capacity * 40L, GL_DYNAMIC_DRAW);
                batch.preparedCapacity = batch.capacity;
            }
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
            glUseProgram(program);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, batch.instanceBuffer);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, batch.preparedBuffer);
            glUniform1ui(glGetUniformLocation(program, "Count"), batch.count);
            glUniform1f(glGetUniformLocation(program, "Clock"), clock);
            glUniform1f(glGetUniformLocation(program, "GroundScaleY"), batch.groundScaleY);
            if (batch.physics) {
                glUniform3f(glGetUniformLocation(program, "Center"), batch.center.x, batch.center.y, batch.center.z);
                glUniform3f(glGetUniformLocation(program, "HalfSize"), batch.halfSize.x, batch.halfSize.y, batch.halfSize.z);
                glUniform1i(glGetUniformLocation(program, "Flat"), 1);
            }
            glDispatchCompute((batch.count + 63) / 64, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
            batch.preparedValid = true;
            batch.preparedClock = clock;
        } finally {
            if (buffer0 != 0 && size0 > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, buffer0, start0, size0);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer0);
            if (buffer1 != 0 && size1 > 0) glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, buffer1, start1, size1);
            else glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffer1);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, previous);
        }
        return true;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }
}
