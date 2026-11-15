import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL45C.glClipControl;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.GL_NEGATIVE_ONE_TO_ONE;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;

/** Offscreen GPU regression: the actual item capture, light batches and composition. */
class FrameRendererGpuTest {
    static final int W = 640, H = 360;
    static Path sources;
    static int nextBinding;

    static int program(String name, boolean zeroToOne) throws Exception {
        int program = glCreateProgram();
        for (String stage : List.of("vert", "frag")) {
            String source = Files.readString(sources.resolve(name + "-opengl-0." + stage));
            source = source.replace("#version 330", "#version 330\n#define gl_VertexIndex gl_VertexID\n"
                + (zeroToOne ? "#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE 1\n" : ""));
            int shader = glCreateShader(stage.equals("vert") ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) throw new AssertionError(glGetShaderInfoLog(shader));
            glAttachShader(program, shader);
            glDeleteShader(shader);
        }
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new AssertionError(glGetProgramInfoLog(program));
        return program;
    }

    static int texture(int internal, int format, int type, ByteBuffer pixels, int w, int h) {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return texture;
    }

    static int framebuffer(int color, int depth) {
        int framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        if (depth != 0) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete test framebuffer");
        return framebuffer;
    }

    static void sampler(int program, String name, int unit, int texture) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, texture);
        glUniform1i(glGetUniformLocation(program, name), unit);
    }

    static void matrix(ByteBuffer bytes, int offset, Matrix4f matrix) {
        FloatBuffer data = BufferUtils.createFloatBuffer(16);
        matrix.get(data);
        for (int i = 0; i < 16; i++) bytes.putFloat(offset + i * 4, data.get(i));
    }

    static void block(int program, String name, java.util.function.Consumer<ByteBuffer> fill) {
        int index = glGetUniformBlockIndex(program, name);
        if (index == GL_INVALID_INDEX) return;
        int size = glGetActiveUniformBlocki(program, index, GL_UNIFORM_BLOCK_DATA_SIZE);
        ByteBuffer bytes = BufferUtils.createByteBuffer(size);
        fill.accept(bytes);
        bytes.position(0).limit(size);
        int buffer = glGenBuffers();
        glBindBuffer(GL_UNIFORM_BUFFER, buffer);
        glBufferData(GL_UNIFORM_BUFFER, bytes, GL_STREAM_DRAW);
        int binding = nextBinding++ % 16;
        glUniformBlockBinding(program, index, binding);
        glBindBufferBase(GL_UNIFORM_BUFFER, binding, buffer);
    }

    static int[] pixel(int x, int y) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(4);
        glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
        return new int[] {bytes.get(0) & 255, bytes.get(1) & 255, bytes.get(2) & 255};
    }

    static int packetWord(int slot, int index) {
        int[] rgb = pixel((slot % 64) * 5 + index % 5, (slot / 64) * 7 + index / 5);
        return Math.round(rgb[0] / 17f) | Math.round(rgb[1] / 17f) << 4 | Math.round(rgb[2] / 17f) << 8;
    }

    static float packetFloat(int slot, int first) {
        return Float.intBitsToFloat(packetWord(slot, first) | packetWord(slot, first + 1) << 12 | (packetWord(slot, first + 2) & 255) << 24);
    }

    static int stablePayload(int slot, int rgb) {
        int[] tags = {8, 9, 11, 12, 14, 15, 6, 7};
        return tags[slot / 16] << 20 | 15 << 16 | (slot & 15) << 12
            | (rgb >> 20 & 15) << 8 | (rgb >> 12 & 15) << 4 | (rgb >> 4 & 15);
    }

    static void verify(boolean zeroToOne, int rgb, boolean sky, int stableSlot, boolean mixed) throws Exception {
        nextBinding = 0;
        glClipControl(GL_LOWER_LEFT, zeroToOne ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
        Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), (float)W / H, 512f, 0.05f, zeroToOne);
        var clip = projection.transform(new org.joml.Vector4f(0, 0, -10, 1));
        float sceneDepth = clip.z / clip.w;
        if (!zeroToOne) sceneDepth = sceneDepth * 0.5f + 0.5f;
        int scene = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null, W, H);
        int depth = texture(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT, null, W, H);
        framebuffer(scene, depth);
        glViewport(0, 0, W, H);
        glClearColor(.12f, .12f, .12f, 1);
        glClearDepth(sky ? 0.0 : sceneDepth);
        glDepthMask(true);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GREATER); // Minecraft 26.3's reversed-Z world pipeline.
        glEnable(GL_CULL_FACE);
        glFrontFace(GL_CCW);
        glDisable(GL_BLEND);
        glDisable(GL_DITHER);
        int capture = program("item", zeroToOne);
        glUseProgram(capture);
        block(capture, "Projection", b -> matrix(b, 0, projection));
        block(capture, "DynamicTransforms", b -> {
            matrix(b, 0, new Matrix4f());
            matrix(b, 64, new Matrix4f());
            for (int i = 0; i < 4; i++) b.putFloat(128 + i * 4, 1);
        });
        block(capture, "Globals", b -> { b.putFloat(32, W); b.putFloat(36, H); });
        block(capture, "Lighting", b -> {});
        block(capture, "Fog", b -> {});
        ByteBuffer carrierPixel = BufferUtils.createByteBuffer(4).put(new byte[] {37, (byte)211, 83, (byte)255}).flip();
        int carrier = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, carrierPixel, 1, 1);
        sampler(capture, "Sampler0", 0, carrier);
        sampler(capture, "Sampler1", 0, carrier);
        sampler(capture, "Sampler2", 0, carrier);
        int value = stableSlot >= 0 ? stablePayload(stableSlot, rgb)
            : 0xAF0005 | ((rgb >> 20 & 15) << 12) | ((rgb >> 12 & 15) << 8) | ((rgb >> 4 & 15) << 4);
        // VertexConsumer.putBakedQuad applies the item's pose on the CPU.
        // The shader receives the source point in Position, not model-local .5.
        float sourceZ = stableSlot >= 0 ? -9 : -5;
        glVertexAttrib3f(0, 0, 0, sourceZ);
        glVertexAttrib4f(1, (value >> 16 & 255) / 255f, (value >> 8 & 255) / 255f, (value & 255) / 255f, 1);
        glVertexAttrib2f(2, .5f, .5f);
        glVertexAttribI2i(3, 0, 0);
        glVertexAttribI2i(4, stableSlot < 0 ? 0 : 240, stableSlot < 0 ? 0 : 240); // XmasLights uses full brightness; its slot is in the payload.
        glVertexAttrib3f(6, 0, 1, 0);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
        int packetSlot = stableSlot >= 0 ? 64 + stableSlot : 0;
        int[] header = pixel(packetSlot % 64 * 5, packetSlot / 64 * 7);
        if (header[0] != 51 || header[1] != 187 || header[2] != 119) {
            throw new AssertionError("Light packet occluded/missing with reversed-Z " + zeroToOne + ": " + Arrays.toString(header));
        }
        float x = packetFloat(packetSlot, 3), y = packetFloat(packetSlot, 6), z = packetFloat(packetSlot, 9);
        if (Math.abs(x) > .00001 || Math.abs(y) > .00001 || Math.abs(z - sourceZ) > .00001)
            throw new AssertionError("Item pose lost: expected [0,0," + sourceZ + "], got [" + x + "," + y + "," + z + "]");

        if (mixed) {
            int blue = stablePayload(127, 0x0000FF);
            glVertexAttrib3f(0, 0, 0, -9);
            glVertexAttrib4f(1, (blue >> 16 & 255) / 255f, (blue >> 8 & 255) / 255f, (blue & 255) / 255f, 1);
            glVertexAttribI2i(4, 240, 240);
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0L);
            if (packetWord(0, 0) != 0x7B3 || packetWord(191, 0) != 0x7B3)
                throw new AssertionError("Native and XmasLights packets collided");
        }
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        int lighting = program("frame_lights", zeroToOne);
        int[] lights = new int[8];
        int integration = 0, previous = scene;
        for (int batch = 0; batch < 24; batch++) {
            int target = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null, W, H);
            if (batch < 8) lights[batch] = target; else integration = target;
            framebuffer(target, 0);
            glUseProgram(lighting);
            sampler(lighting, "SceneSampler", 0, scene);
            sampler(lighting, "DepthSampler", 1, depth);
            sampler(lighting, "PreviousSampler", 2, batch == 8 ? scene : previous);
            int first = batch * 8;
            block(lighting, "FrameConfig", b -> b.putInt(0, first));
            glDrawArrays(GL_TRIANGLES, 0, 3);
            previous = target;
        }
        int output = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, null, W, H);
        framebuffer(output, 0);
        int compose = program("frame_compose", zeroToOne);
        glUseProgram(compose);
        sampler(compose, "SceneSampler", 0, scene);
        for (int i = 0; i < 8; i++) sampler(compose, "Light" + i + "Sampler", i + 1, lights[i]);
        sampler(compose, "IntegrationSampler", 9, integration);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        int[] result = pixel(W / 2, H / 2);
        int[] expected = {rgb >> 16 & 255, rgb >> 8 & 255, mixed ? 255 : rgb & 255};
        for (int i = 0; i < 3; i++) {
            if (sky) {
                if (Math.abs(result[i] - 31) > 1) throw new AssertionError("Sky must remain unlit: " + Arrays.toString(result));
                continue;
            }
            if (expected[i] > 0 && result[i] < 80 || expected[i] == 0 && result[i] > 40)
                throw new AssertionError("Wrong RGB for #" + Integer.toHexString(rgb) + ": " + Arrays.toString(result));
        }
        if (stableSlot >= 0) {
            int[] outside = pixel(W - 1, H - 1);
            for (int channel : outside) if (Math.abs(channel - 31) > 1)
                throw new AssertionError("XmasLights radius leaked outside eight blocks: " + Arrays.toString(outside));
        }
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("OpenGL error");
        System.out.println("GPU PASS reversed-Z " + (zeroToOne ? "[0,1]" : "[-1,1]")
            + (sky ? " sky" : " #" + Integer.toHexString(rgb))
            + (stableSlot >= 0 ? " XmasLights slot=" + stableSlot : mixed ? " native+XmasLights" : " native") + " -> " + Arrays.toString(result));
    }

    public static void main(String[] args) throws Exception {
        sources = Path.of(args[0]);
        if (!SDL_Init(SDL_INIT_VIDEO)) throw new AssertionError(SDL_GetError());
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 4);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 5);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
        long window = SDL_CreateWindow("StrobeLights offscreen GPU regression", 64, 64, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
        if (window == 0) throw new AssertionError(SDL_GetError());
        long context = SDL_GL_CreateContext(window);
        if (context == 0) throw new AssertionError(SDL_GetError());
        try {
            SDL_GL_MakeCurrent(window, context);
            GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER));
            int vao = glGenVertexArrays();
            glBindVertexArray(vao);
            int indices = glGenBuffers();
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[] {0, 1, 2, 2, 3, 0}, GL_STATIC_DRAW);
            for (boolean zeroToOne : new boolean[] {false, true}) {
                for (int rgb : new int[] {0xFF00FF, 0xFF0000, 0x00FF00, 0x0000FF}) verify(zeroToOne, rgb, false, -1, false);
                verify(zeroToOne, 0xFF00FF, true, -1, false);
                for (int slot : new int[] {0, 15, 16, 32, 48, 63, 64, 80, 96, 127}) verify(zeroToOne, 0xFF00FF, false, slot, false);
                for (int rgb : new int[] {0xFF0000, 0x00FF00, 0x0000FF}) verify(zeroToOne, rgb, false, 127, false);
                verify(zeroToOne, 0xFF0000, false, -1, true);
            }
        } finally {
            SDL_GL_DestroyContext(context);
            SDL_DestroyWindow(window);
            SDL_Quit();
        }
    }
}
