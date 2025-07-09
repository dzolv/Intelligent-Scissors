package io.github.dsolv;

import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.ArrayList;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;



/**
 * Handles OpenGL rendering for the image and the Intelligent Scissors paths.
 * Requires an active OpenGL context provided externally.
 * Modified to render multiple committed path segments and the current live wire.
 * Now supports loading and displaying RGB or grayscale images.
 */
public class ImageCanvas {

    private int width;
    private int height;
    private boolean isColorImage; // Store if the loaded image is color

    private int imageTextureID;
    private int imageVAO, imageVBO;

    private int pathVAO, pathVBO;
    private List<int[]> segmentDrawInfo = new ArrayList<>();

    private int imageShaderProgram;
    private int pathShaderProgram;

    // Uniform locations
    private int imageProjectionUniform;
    private int pathProjectionUniform;
    private int pathColorUniform; // Used for both live wire and committed segments


    /**
     * Constructs an ImageCanvas.
     * Assumes an OpenGL context is current on the calling thread.
     *
     * @param image The image to display.
     */
    public ImageCanvas(Image image) {
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.isColorImage = image.isColorImage(); // Store color flag

        GL.createCapabilities();

        glClearColor(0.2f, 0.3f, 0.3f, 1.0f);
        glEnable(GL_TEXTURE_2D);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glLineWidth(2.0f);

        loadImageTexture(image); // Modified to handle color
        setupImageQuad();
        setupShaders();
        setupPathVBO();

        // Set up the projection matrix (Orthographic)
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer projectionMatrix = stack.mallocFloat(16);
            ortho(0.0f, (float) width, (float) height, 0.0f, -1.0f, 1.0f, projectionMatrix);

            glUseProgram(imageShaderProgram);
            imageProjectionUniform = glGetUniformLocation(imageShaderProgram, "projection");
            glUniformMatrix4fv(imageProjectionUniform, false, projectionMatrix);

            glUseProgram(pathShaderProgram);
            pathProjectionUniform = glGetUniformLocation(pathShaderProgram, "projection");
            glUniformMatrix4fv(pathProjectionUniform, false, projectionMatrix);

            // Get uniform locations for path colors
            pathColorUniform = glGetUniformLocation(pathShaderProgram, "lineColor");
            // committedColorUniform = glGetUniformLocation(pathShaderProgram, "committedColor"); // Removed, using one uniform

            // Default colors are set in render() based on segment type
        }
         glUseProgram(0);
    }

    /**
     * Loads the image pixel data into an OpenGL texture.
     * Handles both grayscale and RGB images based on the Image object flag.
     *
     * @param image The image object.
     */
    private void loadImageTexture(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer pixelBuffer;
        int internalFormat, pixelFormat; // OpenGL texture formats

        if (image.isColorImage()) {
            // Handle RGB image
            pixelBuffer = BufferUtils.createByteBuffer(width * height * 3); // 3 bytes per pixel (R, G, B)
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    int rgb = image.getRGBPixel(x, y); // Get original packed ARGB
                    // Put R, G, B bytes into the buffer
                    pixelBuffer.put((byte) ((rgb >> 16) & 0xFF)); // Red
                    pixelBuffer.put((byte) ((rgb >> 8) & 0xFF));  // Green
                    pixelBuffer.put((byte) (rgb & 0xFF));       // Blue
                }
            }
            internalFormat = GL_RGB; // Store as RGB in OpenGL
            pixelFormat = GL_RGB;    // Data is in RGB format
        } else {
            // Handle Grayscale image (existing logic)
            pixelBuffer = BufferUtils.createByteBuffer(width * height);
            for (int y = height - 1; y >= 0; y--) {
                for (int x = 0; x < width; x++) {
                    pixelBuffer.put((byte) image.getPixel(x, y)); // Put grayscale value
                }
            }
            internalFormat = GL_LUMINANCE; // Store as Luminance
            pixelFormat = GL_LUMINANCE;    // Data is Luminance
        }

        pixelBuffer.flip();

        imageTextureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, imageTextureID);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        // Upload texture data
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, pixelFormat, GL_UNSIGNED_BYTE, pixelBuffer);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4); // Reset to default

        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Sets up the Vertex Array Object (VAO) and Vertex Buffer Object (VBO)
     * for drawing a quad that covers the image area.
     */
    private void setupImageQuad() {
        float[] vertices = {
            // Positions           // Texture Coords (adjusting for image top-left origin)
            0.0f,     0.0f,      0.0f, 1.0f, // Top-Left
            (float)width, 0.0f,      1.0f, 1.0f, // Top-Right
            (float)width, (float)height, 1.0f, 0.0f, // Bottom-Right
            0.0f,     (float)height, 0.0f, 0.0f  // Bottom-Left
        };

        imageVAO = glGenVertexArrays();
        glBindVertexArray(imageVAO);

        imageVBO = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, imageVBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        int posSize = 2;
        int texCoordSize = 2;
        int floatSize = 4;
        int stride = (posSize + texCoordSize) * floatSize;

        glVertexAttribPointer(0, posSize, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, texCoordSize, GL_FLOAT, false, stride, (long) posSize * floatSize);
        glEnableVertexAttribArray(1);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /**
     * Sets up the VAO and VBO for drawing the path lines.
     * The VBO data will be updated dynamically with all path segments.
     */
    private void setupPathVBO() {
         pathVAO = glGenVertexArrays();
         glBindVertexArray(pathVAO);

         pathVBO = glGenBuffers();
         glBindBuffer(GL_ARRAY_BUFFER, pathVBO);
         glBufferData(GL_ARRAY_BUFFER, (long)width * height * 2 * 4, GL_DYNAMIC_DRAW); // Generous initial size

         glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
         glEnableVertexAttribArray(0);

         glBindBuffer(GL_ARRAY_BUFFER, 0);
         glBindVertexArray(0);
    }


    /**
     * Sets up the vertex and fragment shaders.
     */
    private void setupShaders() {
        // Image shaders are fine, work with GL_LUMINANCE or GL_RGB
        String imageVertexShaderSource = """
                #version 330 core
                layout (location = 0) in vec2 aPos;
                layout (location = 1) in vec2 aTexCoord;
                out vec2 TexCoord;
                uniform mat4 projection;
                void main() {
                    gl_Position = projection * vec4(aPos.x, aPos.y, 0.0, 1.0);
                    TexCoord = aTexCoord;
                }
                """;

        String imageFragmentShaderSource = """
                #version 330 core
                in vec2 TexCoord;
                out vec4 FragColor;
                uniform sampler2D textureSampler;
                void main() {
                    FragColor = texture(textureSampler, TexCoord); // Works for LUMINANCE or RGB
                }
                """;

         String pathVertexShaderSource = """
                #version 330 core
                layout (location = 0) in vec2 aPos;
                uniform mat4 projection;
                void main() {
                    gl_Position = projection * vec4(aPos.x, aPos.y, 0.0, 1.0);
                }
                """;

         // Path fragment shader uses a single color uniform
         String pathFragmentShaderSource = """
                #version 330 core
                out vec4 FragColor;
                uniform vec4 lineColor; // Color for the current line segment
                void main() {
                    FragColor = lineColor;
                }
                """;


        imageShaderProgram = createShaderProgram(imageVertexShaderSource, imageFragmentShaderSource);
        pathShaderProgram = createShaderProgram(pathVertexShaderSource, pathFragmentShaderSource);
    }

    /**
     * Compiles and links vertex and fragment shaders into a shader program.
     */
    private int createShaderProgram(String vertexSource, String fragmentSource) {
        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);
        checkShaderError(vertexShader, GL_COMPILE_STATUS, "Vertex shader compilation failed!");

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);
        checkShaderError(fragmentShader, GL_COMPILE_STATUS, "Fragment shader compilation failed!");

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        checkShaderError(program, GL_LINK_STATUS, "Shader program linking failed!");

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        return program;
    }

    /**
     * Helper method to check shader compilation/linking status.
     */
    private void checkShaderError(int shader, int type, String errorMessage) {
        int success = -1;
        if (type == GL_COMPILE_STATUS) {
            success = glGetShaderi(shader, type);
        } else if (type == GL_LINK_STATUS) {
            success = glGetProgrami(shader, type);
        }

        if (success == GL_FALSE) {
            String infoLog = (type == GL_COMPILE_STATUS) ? glGetShaderInfoLog(shader) : glGetProgramInfoLog(shader);
            System.err.println(errorMessage + "\n" + infoLog);
            // throw new RuntimeException(errorMessage + "\n" + infoLog); // Re-enable for strict error handling
        }
    }


    /**
     * Updates the path data to be rendered. Combines all committed segments and the current live wire
     * into a single buffer and updates draw information for each segment.
     *
     * @param committedSegments The list of completed, committed path segments.
     * @param liveWirePath      The current live wire path from the seed to the free point.
     */
    public void updateRenderPaths(List<List<Pixel>> committedSegments, List<Pixel> liveWirePath) {

        segmentDrawInfo.clear(); // Clear previous draw info

        // Calculate total number of vertices needed
        int totalVertices = 0;
        for (List<Pixel> segment : committedSegments) {
            if (segment != null) totalVertices += segment.size();
        }
        if (liveWirePath != null) totalVertices += liveWirePath.size();

        if (totalVertices == 0) {
            // No paths to draw, clear VBO data if any
            glBindBuffer(GL_ARRAY_BUFFER, pathVBO);
            glBufferData(GL_ARRAY_BUFFER, 0, GL_DYNAMIC_DRAW); // Clear data, reallocate 0 size
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            return;
        }

        // Create a buffer to hold all vertices (x, y)
        FloatBuffer allPathVertices = BufferUtils.createFloatBuffer(totalVertices * 2); // 2 floats per vertex

        int currentStartIndex = 0;

        // Add committed segments to the buffer
        for (List<Pixel> segment : committedSegments) {
             if (segment != null && !segment.isEmpty()) {
                 for (Pixel pixel : segment) {
                     allPathVertices.put((float) pixel.x() + 0.5f);
                     allPathVertices.put((float) pixel.y() + 0.5f);
                 }
                 // Store draw info for this segment (offset in VBO, vertex count)
                 segmentDrawInfo.add(new int[]{currentStartIndex, segment.size()});
                 currentStartIndex += segment.size();
             }
        }

        // Add the current live wire path to the buffer
        if (liveWirePath != null && !liveWirePath.isEmpty()) {
            for (Pixel pixel : liveWirePath) {
                allPathVertices.put((float) pixel.x() + 0.5f);
                allPathVertices.put((float) pixel.y() + 0.5f);
            }
             // Store draw info for the live wire segment
            segmentDrawInfo.add(new int[]{currentStartIndex, liveWirePath.size()});
        }

        allPathVertices.flip();

        // Upload combined vertex data to the path VBO
        glBindBuffer(GL_ARRAY_BUFFER, pathVBO);
        glBufferData(GL_ARRAY_BUFFER, allPathVertices.capacity() * 4L, GL_DYNAMIC_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, allPathVertices);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }


    /**
     * Renders the image and all path segments.
     * Should be called within the main rendering loop.
     * Assumes an OpenGL context is current.
     *
     * @param windowWidth The current width of the render window.
     * @param windowHeight The current height of the render window.
     */
    public void render(int windowWidth, int windowHeight) {
        glViewport(0, 0, windowWidth, windowHeight);
        glClear(GL_COLOR_BUFFER_BIT);

        // --- Render the Image ---
        glUseProgram(imageShaderProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, imageTextureID);
        glUniform1i(glGetUniformLocation(imageShaderProgram, "textureSampler"), 0);

        glBindVertexArray(imageVAO);
        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);

        // --- Render the Paths ---
        if (!segmentDrawInfo.isEmpty()) {
            glUseProgram(pathShaderProgram);
            glBindVertexArray(pathVAO);
            glBindBuffer(GL_ARRAY_BUFFER, pathVBO);

            for (int i = 0; i < segmentDrawInfo.size(); i++) {
                int[] drawInfo = segmentDrawInfo.get(i);
                int startIndex = drawInfo[0];
                int vertexCount = drawInfo[1];

                // Set the color uniform based on whether it's a committed segment or the live wire
                if (i < segmentDrawInfo.size() - 1) {
                    // Committed segments (all except the last one)
                    glUniform4f(pathColorUniform, 0.0f, 0.0f, 1.0f, 1.0f); // Blue
                } else {
                    // The last segment is always the current live wire (if it exists)
                    glUniform4f(pathColorUniform, 1.0f, 1.0f, 0.0f, 1.0f); // Yellow
                }

                // Draw the segment as a line strip
                glDrawArrays(GL_LINE_STRIP, startIndex, vertexCount);
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
        }

        glUseProgram(0);
    }

    /**
     * Cleans up OpenGL resources (textures, VBOs, VAOs, shaders).
     */
    public void cleanup() {
        glDeleteTextures(imageTextureID);

        glDeleteBuffers(imageVBO);
        glDeleteVertexArrays(imageVAO);

        glDeleteBuffers(pathVBO);
        glDeleteVertexArrays(pathVAO);

        glDeleteProgram(imageShaderProgram);
        glDeleteProgram(pathShaderProgram);
    }

    /**
     * Helper to create an orthographic projection matrix.
     */
    private void ortho(float left, float right, float bottom, float top, float zNear, float zFar, FloatBuffer dest) {
        float tx = -(right + left) / (right - left);
        float ty = -(top + bottom) / (top - bottom);
        float tz = -(zFar + zNear) / (zFar - zNear);

        dest.put(0, 2.0f / (right - left)); dest.put(1, 0.0f); dest.put(2, 0.0f); dest.put(3, 0.0f);
        dest.put(4, 0.0f); dest.put(5, 2.0f / (top - bottom)); dest.put(6, 0.0f); dest.put(7, 0.0f);
        dest.put(8, 0.0f); dest.put(9, 0.0f); dest.put(10, -2.0f / (zFar - zNear)); dest.put(11, 0.0f);
        dest.put(12, tx); dest.put(13, ty); dest.put(14, tz); dest.put(15, 1.0f);
    }
}