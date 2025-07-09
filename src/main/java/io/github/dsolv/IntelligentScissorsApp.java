package io.github.dsolv;

import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

// Use the independent Pixel class
import io.github.dsolv.Pixel;


/**
 * The main application class for Intelligent Scissors.
 * Manages the window, user input, and orchestrates the image processing,
 * search algorithm, and rendering.
 * Includes Cursor Snap, Path Cooling (history tracking and automatic seed placement),
 * non-blocking incremental search (interleaving), the ability to clear the current boundary,
 * and loads image from command-line argument.
 * Fixes live-wire display after automatic commits and correctly handles path updates/cooling
 * after search completion.
 */
public class IntelligentScissorsApp implements Runnable {

    private long window; // GLFW window handle
    private int windowWidth = 800; // Default window size (can be image size or larger)
    private int windowHeight = 600;
    private int imageWidth; // Store actual image dimensions
    private int imageHeight;

    private String imagePath = null; // Field to store the image path

    private Image image;
    private ImageProcessor imageProcessor;
    private CostCalculator costCalculator;
    private IntelligentScissorsSearch search;
    private ImageCanvas imageCanvas;

    private Pixel seedPoint = null; // The current seed point for the search
    private Pixel freePoint = null; // The current mouse position (pixel), AFTER snapping

    // Stores completed, "committed" path segments
    private List<List<Pixel>> committedSegments = new ArrayList<>();

    // --- Mouse Input State ---
    private boolean leftMouseButtonPressed = false;
    private boolean rightMouseButtonPressed = false;

    // --- Path State ---
    private List<Pixel> currentLiveWirePath = null;
    private List<Pixel> previousLiveWirePath = null; // Store path from previous frame (used for history)


    // --- Cursor Snap ---
    private int snapRadius = ImageProcessor.DEFAULT_SNAP_RADIUS;

    // --- Path Cooling History ---
    private int[][] redrawHistory; // Number of times a pixel was on the live wire path
    private double[][] timeHistory; // Accumulated time + scaled gradient magnitude

    // --- Time Tracking for History ---
    private double lastFrameTime; // Time at the beginning of the last frame loop iteration

    // --- Path Cooling Thresholds ---
    private static final int REDRAW_LOWER_THRESHOLD = 200;
    private static final double TIME_LOWER_THRESHOLD = 100;
    private static final int MIN_COOLED_SEGMENT_LENGTH = 10;

    // --- Interleaving Search Control ---
    private static final int EXPANSION_STEPS_PER_FRAME = 1000;
    private boolean isSearchRunning = false; // Controls whether expandNextPixel() is called


    /**
     * Creates a new IntelligentScissorsApp instance.
     *
     * @param imagePath The path to the image file to load. Can be null.
     */
    public IntelligentScissorsApp(String imagePath) {
        this.imagePath = imagePath;
    }

    /**
     * The main entry point for the application thread.
     */
    @Override
    public void run() {
        System.out.println("Hello LWJGL " + Version.getVersion() + "!");

        // Check if an image path was provided
        if (imagePath == null || imagePath.isEmpty()) {
            System.err.println("No image path provided. Please specify an image path via command-line arguments.");
             // Delay closing slightly to allow message to be seen
             try { Thread.sleep(2000); } catch (InterruptedException e) {}
            glfwSetWindowShouldClose(NULL, true); // Use NULL for the window handle here
            return; // Exit run method immediately
        }


        init(); // Initialize GLFW, window, and OpenGL
        loadAndProcessImage(imagePath); // Use the provided path
        loop();

        cleanup();
    }

    private void init() {
        // Only initialize if window is not already created (e.g., called loadAndProcessImage after startup)
         if (window == NULL) {
            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

            window = glfwCreateWindow(windowWidth, windowHeight, "Intelligent Scissors", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create the GLFW window");
            }

            // Setup a key callback.
            glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                    glfwSetWindowShouldClose(window, true); // Close application
                } else if (key == GLFW_KEY_C && action == GLFW_RELEASE) { // 'C' key for Clear
                    handleClearBoundary(); // Call method to clear everything
                }
            });

            glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
                if (button == GLFW_MOUSE_BUTTON_LEFT) {
                    leftMouseButtonPressed = action == GLFW_PRESS;
                    if (action == GLFW_RELEASE) {
                        handleLeftClickRelease();
                    }
                } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                    rightMouseButtonPressed = action == GLFW_PRESS;
                     if (action == GLFW_RELEASE) {
                        handleRightClickRelease();
                    }
                }
            });

            glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
                 handleMouseMovement((int) xpos, (int) ypos);
            });

            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidmode != null) {
                glfwSetWindowPos(window, (vidmode.width() - windowWidth) / 2, (vidmode.height() - windowHeight) / 2);
            }

            glfwMakeContextCurrent(window);
            glfwSwapInterval(1);
            glfwShowWindow(window);

            // GL capabilities created in ImageCanvas constructor
         }
    }

    /**
     * Loads and processes the image, and initializes the algorithm components.
     *
     * @param imagePath The path to the image file.
     */
    private void loadAndProcessImage(String imagePath) {
        try {
            // Cleanup previous image resources if any exist
            if (imageCanvas != null) imageCanvas.cleanup();
            // No explicit cleanup needed for other processors/calculators, they will be re-created

            image = Image.loadImage(imagePath);
            imageWidth = image.getWidth();
            imageHeight = image.getHeight();
            windowWidth = imageWidth;
            windowHeight = imageHeight;
            glfwSetWindowSize(window, windowWidth, windowHeight);

            imageProcessor = new ImageProcessor(image);
            costCalculator = new CostCalculator(imageProcessor);
            search = new IntelligentScissorsSearch(costCalculator, imageWidth, imageHeight, 256); // M=256

            // ImageCanvas needs the OpenGL context, so create it after glfwMakeContextCurrent
             if (imageCanvas == null) { // Create only once during initial load
                 imageCanvas = new ImageCanvas(image);
             } else {
                 // Recreate canvas for new image (cleaner state reset for GL resources)
                 imageCanvas = new ImageCanvas(image);
             }


            // --- Initialize Path Cooling History Maps ---
            // Re-initialize history maps for the new image size
            redrawHistory = new int[imageHeight][imageWidth];
            timeHistory = new double[imageHeight][imageWidth];
            // Initialized to 0 by default

            // Reset all path/boundary related state for the new image
            committedSegments.clear();
            currentLiveWirePath = null;
            previousLiveWirePath = null;
            seedPoint = null;
            freePoint = null;
            isSearchRunning = false; // Search isn't running until a seed is set

            System.out.println("Image loaded and processed: " + imagePath);
            System.out.println("Image dimensions: " + imageWidth + "x" + imageHeight);

        } catch (IOException e) {
            System.err.println("Error loading image: " + e.getMessage());
            e.printStackTrace();
            System.err.println("Failed to load image. Please check the path and file type.");
            // If initial load fails, should probably close the app.
            if (window != NULL) {
                 // Use NULL check as init might not have created window yet
                 glfwSetWindowShouldClose(NULL, true);
            }
        }
    }

    /**
     * The main application loop.
     */
    private void loop() {
        lastFrameTime = glfwGetTime(); // Initialize last frame time

        // Only proceed with the loop if imageCanvas was successfully created (image loaded)
        if (imageCanvas == null || window == NULL) {
             System.err.println("Cannot start loop: ImageCanvas or window not initialized.");
             return;
        }


        while (!glfwWindowShouldClose(window)) {
            double currentTime = glfwGetTime();
            double deltaTime = currentTime - lastFrameTime;
            lastFrameTime = currentTime; // Update last frame time

            glfwPollEvents();

            // --- Perform Incremental Search Expansion ---
            // This only happens when a search is active and not yet complete.
            if (isSearchRunning && !search.isSearchComplete()) {
                boolean searchOngoing = true;
                for (int i = 0; i < EXPANSION_STEPS_PER_FRAME && searchOngoing; i++) {
                     searchOngoing = search.expandNextPixel();
                }
                 // Update isSearchRunning *after* potentially expanding.
                if (!searchOngoing) { // If expandNextPixel returned false (search complete)
                    isSearchRunning = false;
                    System.out.println("Search from seed complete.");
                }
            }

            // --- Update Path Cooling History ---
            // Update history for pixels that were on the *previous* live wire path, only if a seed is set.
             if (seedPoint != null && previousLiveWirePath != null) {
                 for (Pixel p : previousLiveWirePath) {
                     if (p.y() >= 0 && p.y() < imageHeight && p.x() >= 0 && p.x() < imageWidth) {
                         redrawHistory[p.y()][p.x()]++;

                         double gradientMagnitude = imageProcessor.getGMap()[p.y()][p.x()];
                         double gMax = imageProcessor.getGMax();
                         double scaledGradientMagnitude = (gMax > 1e-6) ? (gradientMagnitude / gMax) : 0.0;

                         timeHistory[p.y()][p.x()] += deltaTime + scaledGradientMagnitude;
                     }
                 }
             }

            // --- Update Live Wire Path and Check Path Cooling ---
            // This happens whenever a seed and free point are set AND the free point is reachable.
            // It does NOT depend on isSearchRunning, as the path map is valid even after search completion.
            if (seedPoint != null && freePoint != null && search.getCumulativeCost(freePoint) != Integer.MAX_VALUE) {

                // *** Path Cooling Check (Step 3.6) ***
                // Check for cooled candidate *before* getting the live wire path.
                // If a commit happens, the next line will calculate the live wire path
                // from the *new* seed to the *current* freePoint.
                Pixel cooledCandidate = findCooledSeedCandidate();
                if (cooledCandidate != null) {
                    System.out.println("Cooled candidate found! Triggering automatic commit at: " + cooledCandidate);
                    handleAutomaticCommit(cooledCandidate);
                    // handleAutomaticCommit updates seed, restarts search (sets isSearchRunning=true), resets history, etc.
                    // It does NOT clear currentLiveWirePath or previousLiveWirePath anymore.
                    // The next line will calculate the live wire path from the *new* seed.
                }

                // *** Calculate/Update Live Wire Path ***
                // Get the path from the current seed (potentially updated by auto-commit) to the current free point.
                currentLiveWirePath = search.getPath(freePoint); // Get path using search results

            } else {
                 // No seed, no free point, or free point not reachable -> no live wire
                 currentLiveWirePath = null;
            }

            // --- Update Canvas with all paths ---
            // imageCanvas needs the list of committed segments AND the current live wire
            imageCanvas.updateRenderPaths(committedSegments, currentLiveWirePath);

            // *** Update previousLiveWirePath for history tracking in the *next* frame ***
            // This must happen AFTER currentLiveWirePath has been calculated for the current frame.
            previousLiveWirePath = currentLiveWirePath;


            // --- Render ---
             try (var stack = stackPush()) {
                var widthBuffer = stack.mallocInt(1);
                var heightBuffer = stack.mallocInt(1);
                glfwGetFramebufferSize(window, widthBuffer, heightBuffer);
                windowWidth = widthBuffer.get(0);
                windowHeight = heightBuffer.get(0);
             }
            imageCanvas.render(windowWidth, windowHeight);

            glfwSwapBuffers(window);
        }
    }

    /**
     * Handles the release of the left mouse button.
     * Sets the initial seed point if none is set.
     */
    private void handleLeftClickRelease() {
        Pixel clickedPixel = freePoint;

        if (clickedPixel == null) {
             System.err.println("Cannot set seed point: Free point is null.");
             return;
        }

        if (seedPoint == null) {
            seedPoint = clickedPixel;
            System.out.println("Seed point set at: " + seedPoint);

            // Initialize search for the new seed. Non-blocking.
            search.setSeedPoint(seedPoint);
            isSearchRunning = true; // Start the search expansion in the loop
             System.out.println("Search from seed initialized.");

            // Reset history for all pixels when a new object boundary starts
            resetHistory();
             System.out.println("Path history reset.");

            // freePoint is already set by mouse movement.
            // currentLiveWirePath will be calculated in the loop in the next frame.

        } else {
             // Left click after first seed does not set a new seed in this workflow.
             System.out.println("Left click after seed point doesn't set new seed.");
        }
    }

     /**
      * Handles the release of the right mouse button.
      * Commits the current live-wire segment and sets a new seed point.
      */
     private void handleRightClickRelease() {
         if (seedPoint != null && freePoint != null) {
             // Only commit if the free point is reachable by the search wavefront.
             if (search.getCumulativeCost(freePoint) != Integer.MAX_VALUE) {
                 handleAutomaticCommit(freePoint); // Use the same logic as auto-commit
             } else {
                 System.out.println("Right click ignored: Free point is not yet reachable by search.");
             }
         } else {
              System.out.println("Right click ignored: No seed point set yet.");
         }
     }

     /**
      * Handles the commitment of a live-wire segment, either manually (via right click)
      * or automatically (via path cooling).
      *
      * @param commitPixel The pixel at the end of the segment to commit (either freePoint or a cooled candidate).
      */
     private void handleAutomaticCommit(Pixel commitPixel) {
         // Get the path from the current seed back to the commitPixel
         List<Pixel> segmentToCommit = search.getPath(commitPixel);

         // Check that the commitPixel is reachable and forms a valid segment
         boolean commitPixelIsReachable = (segmentToCommit != null && !segmentToCommit.isEmpty() && segmentToCommit.get(segmentToCommit.size() - 1).equals(commitPixel));

         if (commitPixelIsReachable && segmentToCommit.size() > 1) {
             committedSegments.add(segmentToCommit);
             System.out.println("Segment committed. Path length: " + segmentToCommit.size());

             // The commitPixel becomes the new seed point for the next search
             Pixel newSeed = commitPixel;
             seedPoint = newSeed; // Update the application's seed point state
             System.out.println("New seed point set at: " + seedPoint);

             // *** IMPORTANT CHANGE: Do NOT clear currentLiveWirePath or previousLiveWirePath here. ***
             // The main loop will calculate the new path in the next iteration from the new seed.

             // Start the new search from the new seed point. Non-blocking.
             search.setSeedPoint(seedPoint);
             isSearchRunning = true; // Start the search expansion in the loop
              System.out.println("Search from new seed initialized.");

             // Reset history for all pixels when a new segment starts from a new seed
             resetHistory();
             System.out.println("Path history reset.");

             // *** IMPORTANT CHANGE: Set freePoint temporarily to the new seed. ***
             // This ensures getPath from the new seed to freePoint returns just the seed initially,
             // until the mouse moves and updates freePoint to the actual cursor position.
             freePoint = newSeed;

         } else {
             System.out.println("Cannot commit segment: Path is too short, invalid, or commit pixel unreachable.");
         }
     }

     /**
      * Clears the current boundary trace and committed segments.
      * Called when the user wants to start tracing a new object.
      */
     private void handleClearBoundary() {
         System.out.println("Clearing current boundary and starting fresh.");

         // Clear committed segments
         committedSegments.clear();

         // Clear current live wire path states
         currentLiveWirePath = null;
         previousLiveWirePath = null;

         // Reset seed and free points
         seedPoint = null;
         freePoint = null;

         // Stop any ongoing search
         isSearchRunning = false; // Ensure search is stopped

         // Reset history for all pixels
         resetHistory();

         // Update the canvas display to show nothing
         imageCanvas.updateRenderPaths(committedSegments, currentLiveWirePath); // Pass empty lists
     }


     /**
      * Resets all path cooling history counts to zero.
      * Called when a new object boundary is started or a segment is committed.
      */
     private void resetHistory() {
          for (int y = 0; y < imageHeight; y++) {
              for (int x = 0; x < imageWidth; x++) {
                  redrawHistory[y][x] = 0;
                  timeHistory[y][x] = 0.0;
              }
          }
     }

    /**
     * Finds the first pixel on the current live-wire path (starting from the seed end)
     * that meets the cooling thresholds.
     * This pixel is a candidate for automatic seed placement.
     *
     * @return A Pixel candidate for a new seed point, or null if no candidate is found.
     */
    private Pixel findCooledSeedCandidate() {
        // Need a live wire path to check
        if (currentLiveWirePath == null) {
             return null;
        }

        int pathLength = currentLiveWirePath.size();

        // Need a segment of at least MIN_COOLED_SEGMENT_LENGTH + 1 pixels to potentially cool
        // The candidate must be at least MIN_COOLED_SEGMENT_LENGTH pixels away from *both* ends.
        int startIndex = MIN_COOLED_SEGMENT_LENGTH;
        int endIndex = pathLength - 1 - MIN_COOLED_SEGMENT_LENGTH;

        if (startIndex > endIndex) {
            return null; // Path is too short to have a candidate meeting distance criteria
        }

        // Iterate through the portion of the path that can potentially cool
        for (int i = startIndex; i <= endIndex; i++) {
            Pixel p = currentLiveWirePath.get(i);

            // Check against lower thresholds
            if (redrawHistory[p.y()][p.x()] >= REDRAW_LOWER_THRESHOLD &&
                timeHistory[p.y()][p.x()] >= TIME_LOWER_THRESHOLD) {

                // Found the first pixel meeting the lower thresholds.
                return p;
            }
        }

        // No candidate found in the checked portion of the path
        return null;
    }


    /**
     * Handles mouse movement, updating the free point with Cursor Snap.
     *
     * @param xpos The current x-coordinate of the cursor (window coordinates).
     * @param ypos The current y-coordinate of the cursor (window coordinates).
     */
    private void handleMouseMovement(int xpos, int ypos) {
        // Only update freePoint if an image is loaded
        if (image == null) return;

        // Convert window coordinates to raw pixel coordinates
        int rawPixelX = (int) xpos;
        int rawPixelY = (int) ypos;

        // Clamp raw pixel coordinates to image bounds for the neighborhood center
        int clampedCenterX = Math.max(0, Math.min(rawPixelX, imageWidth - 1));
        int clampedCenterY = Math.max(0, Math.min(rawPixelY, imageHeight - 1));

        // Find the max gradient pixel in the neighborhood around the raw cursor position
        // This is the "snapped" free point.
        freePoint = imageProcessor.findMaxGradientPixelInNeighborhood(clampedCenterX, clampedCenterY, snapRadius);

        // History update happens in the loop based on previous path.
        // Live wire path is recalculated in the loop based on current freePoint.
    }


    /**
     * Cleans up GLFW and OpenGL resources.
     */
    private void cleanup() {
        if (imageCanvas != null) {
            imageCanvas.cleanup();
        }

        // Check if window was successfully created before trying to free/destroy
        if (window != NULL) {
           glfwFreeCallbacks(window);
           glfwDestroyWindow(window);
        }


        glfwTerminate();
        glfwSetErrorCallback(null).free();

        System.out.println("Cleanup complete.");
    }

    public static void main(String[] args) {
        String imagePath = null;
        if (args.length > 0) {
           imagePath = args[0]; // Use the first command line argument as the image path
        } else {
           System.out.println("Usage: java -jar your-app.jar <image_path>");
        }
        // Pass the potentially null imagePath to the constructor
        new IntelligentScissorsApp(imagePath).run();
    }

}