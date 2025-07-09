package io.github.dsolv;

import java.nio.ByteBuffer; // Needed for getting channel data

import org.lwjgl.BufferUtils;

import static java.lang.Math.*; // Import static methods for sqrt, atan2, max, min


/**
 * Processes an Image to extract features necessary for Intelligent Scissors.
 * Supports both grayscale and RGB images. Computes features per channel for
 * color images and combines results by maximizing/ORing.
 */
public class ImageProcessor {

    private Image originalImage;
    private int width;
    private int height;

    // Feature maps (these will store the COMBINED features for color images)
    private double[][] ilMap;      // Laplacian Zero-Crossing map (OR combination for color)
    private double[][] ixMap;      // Gradient X map (Max magnitude channel for color)
    private double[][] iyMap;      // Gradient Y map (Max magnitude channel for color)
    private double[][] gMap;       // Gradient Magnitude map (Max magnitude channel for color)
    private double[][][] directionMap; // Gradient Direction map (Direction from max magnitude channel for color)
    private double gMax;         // Maximum Gradient Magnitude across the whole image (from the combined gMap)

    // Standard 3x3 Kernels
    private static final double[][] LAPLACIAN_KERNEL = {
            {0, 1, 0},
            {1, -4, 1},
            {0, 1, 0}
    };

    private static final double[][] SOBEL_X_KERNEL = {
            {-1, 0, 1},
            {-2, 0, 2},
            {-1, 0, 1}
    };

    private static final double[][] SOBEL_Y_KERNEL = {
            {-1, -2, -1},
            {0, 0, 0},
            {1, 2, 1}
    };

    // Default radius for the Cursor Snap neighborhood
    public static final int DEFAULT_SNAP_RADIUS = 7; // Results in a 15x15 neighborhood

    /**
     * Constructs an ImageProcessor for the given image.
     * Assumes an OpenGL context is current on the calling thread.
     *
     * @param image The image to process.
     */
    public ImageProcessor(Image image) {
        this.originalImage = image;
        this.width = image.getWidth();
        this.height = image.getHeight();

        // Initialize maps (using double for precision) - dimensioned by image size
        this.ilMap = new double[height][width];
        this.ixMap = new double[height][width];
        this.iyMap = new double[height][width];
        this.gMap = new double[height][width];
        this.directionMap = new double[height][width][2]; // [y][x][0] = Iy, [y][x][1] = -Ix

        // Process the image to compute features
        computeFeatures();
    }

    /**
     * Computes all necessary feature maps from the original image.
     * Handles both grayscale and color processing.
     */
    private void computeFeatures() {
        if (originalImage.isColorImage()) {
            System.out.println("Processing color image features...");
            computeColorFeatures();
        } else {
            System.out.println("Processing grayscale image features...");
            computeGrayscaleFeatures();
        }

        // Find Max Gradient Magnitude *after* combining channels
        findMaxGradientMagnitude();
    }

    /**
     * Computes features for a grayscale image using existing convolution logic.
     */
    private void computeGrayscaleFeatures() {
        // Reuse the convolve method with the grayscale channel data
        ByteBuffer grayChannel = getGrayscaleChannel();
        convolve(LAPLACIAN_KERNEL, ilMap, grayChannel); // Pass channel data

        // Need temporary maps for Sobel results before magnitude/direction
        double[][] tempIx = new double[height][width];
        double[][] tempIy = new double[height][width];

        convolve(SOBEL_X_KERNEL, tempIx, grayChannel);
        convolve(SOBEL_Y_KERNEL, tempIy, grayChannel);

        // Compute Gradient Magnitude and Direction from grayscale Sobel results
        computeGradientMagnitudeAndDirection(tempIx, tempIy, ixMap, iyMap, gMap, directionMap);

         // ilMap is already populated by the Laplacian convolution
    }

    /**
     * Computes features for a color image (RGB).
     * Processes each channel, then combines results.
     */
    private void computeColorFeatures() {
        ByteBuffer redChannel = getRGBChannel(0); // Red channel data
        ByteBuffer greenChannel = getRGBChannel(1); // Green channel data
        ByteBuffer blueChannel = getRGBChannel(2); // Blue channel data

        // Temporary maps for per-channel convolution results
        double[][] ilR = new double[height][width];
        double[][] ilG = new double[height][width];
        double[][] ilB = new double[height][width];
        double[][] ixR = new double[height][width];
        double[][] ixG = new double[height][width];
        double[][] ixB = new double[height][width];
        double[][] iyR = new double[height][width];
        double[][] iyG = new double[height][width];
        double[][] iyB = new double[height][width];

        // Convolve each channel independently
        convolve(LAPLACIAN_KERNEL, ilR, redChannel);
        convolve(LAPLACIAN_KERNEL, ilG, greenChannel);
        convolve(LAPLACIAN_KERNEL, ilB, blueChannel);

        convolve(SOBEL_X_KERNEL, ixR, redChannel);
        convolve(SOBEL_X_KERNEL, ixG, greenChannel);
        convolve(SOBEL_X_KERNEL, ixB, blueChannel);

        convolve(SOBEL_Y_KERNEL, iyR, blueChannel); // Fixed typo SOBEL_Y_KERNEL
        convolve(SOBEL_Y_KERNEL, iyR, redChannel);
        convolve(SOBEL_Y_KERNEL, iyG, greenChannel);
        convolve(SOBEL_Y_KERNEL, iyB, blueChannel);


        // Combine Laplacian (OR logic - 0 if any channel is zero-crossing, 1 otherwise)
        // Paper on page 17 says "bitwise OR operator achieves the same result as computing the maximum"
        // The f_z cost is 0 if I_L is 0, 1 otherwise. So f_z=0 if any channel has IL=0.
        // Equivalently, f_z=1 if all channels have IL != 0.
        // Since IL is double, check abs(IL) < threshold. f_z=0 if any abs(IL) < threshold.
        // Combined IL should be 0 if any channel's IL is 0, non-zero otherwise.
        // The max of absolute values works for this.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                 // Combined IL: Use the value from the channel with the largest absolute Laplacian response.
                 // This determines which channel 'dominates' the zero-crossing signal.
                 double maxAbsIL = 0;
                 maxAbsIL = max(maxAbsIL, abs(ilR[y][x]));
                 maxAbsIL = max(maxAbsIL, abs(ilG[y][x]));
                 maxAbsIL = max(maxAbsIL, abs(ilB[y][x]));
                 ilMap[y][x] = maxAbsIL; // Store the max absolute value
                 // The CostCalculator's getLaplacianCost will still use the threshold on this.
            }
        }


        // Combine Gradient Magnitude and Direction (Max magnitude channel)
        double[][] gR = new double[height][width]; // Magnitude for Red channel
        double[][] gG = new double[height][width]; // Magnitude for Green channel
        double[][] gB = new double[height][width]; // Magnitude for Blue channel

        computeGradientMagnitude(ixR, iyR, gR);
        computeGradientMagnitude(ixG, iyG, gG);
        computeGradientMagnitude(ixB, iyB, gB);

        // Combine Gradient Magnitude (take max across channels) and store associated direction
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double magR = gR[y][x];
                double magG = gG[y][x];
                double magB = gB[y][x];

                double maxMag = magR;
                int bestChannel = 0; // 0: Red, 1: Green, 2: Blue

                if (magG > maxMag) {
                    maxMag = magG;
                    bestChannel = 1;
                }
                if (magB > maxMag) {
                    maxMag = magB;
                    bestChannel = 2;
                }

                gMap[y][x] = maxMag; // Combined gradient magnitude

                // Compute and store direction (Iy, -Ix) from the channel with max magnitude
                double ix, iy;
                if (bestChannel == 0) { ix = ixR[y][x]; iy = iyR[y][x]; }
                else if (bestChannel == 1) { ix = ixG[y][x]; iy = iyG[y][x]; }
                else { ix = ixB[y][x]; iy = iyB[y][x]; } // bestChannel == 2

                // Normalize to a unit vector (Iy, -Ix)
                if (maxMag > 1e-6) {
                    directionMap[y][x][0] = iy / maxMag; // Normalized Iy
                    directionMap[y][x][1] = -ix / maxMag; // Normalized -Ix
                } else {
                    directionMap[y][x][0] = 0;
                    directionMap[y][x][1] = 0;
                }

                 // Store per-channel Ix, Iy? Not needed for CostCalculator if it uses combined D'.
                 // We need ixMap and iyMap getters if CostCalculator uses them directly for D' (it does).
                 // So we need to store the Ix, Iy *from the best channel* in ixMap and iyMap.
                 if (bestChannel == 0) { ixMap[y][x] = ixR[y][x]; iyMap[y][x] = iyR[y][x]; }
                 else if (bestChannel == 1) { ixMap[y][x] = ixG[y][x]; iyMap[y][x] = iyG[y][x]; }
                 else { ixMap[y][x] = ixB[y][x]; iyMap[y][x] = iyB[y][x]; }
            }
        }
    }


    /**
     * Performs convolution of image channel data with a given kernel.
     * Handles boundary pixels by repeating the nearest edge value (border padding).
     * Takes channel data as ByteBuffer.
     *
     * @param kernel The convolution kernel.
     * @param outputMap The map to store the convolution results.
     * @param channelData ByteBuffer containing single channel pixel data (0-255).
     */
    private void convolve(double[][] kernel, double[][] outputMap, ByteBuffer channelData) {
        int kHeight = kernel.length;
        int kWidth = kernel[0].length;
        int kRadiusY = kHeight / 2;
        int kRadiusX = kWidth / 2;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0;

                for (int i = 0; i < kHeight; i++) {
                    for (int j = 0; j < kWidth; j++) {
                        int imgX = x + j - kRadiusX;
                        int imgY = y + i - kRadiusY;

                        // Handle boundaries by repeating the nearest edge value
                        imgX = max(0, min(imgX, width - 1));
                        imgY = max(0, min(imgY, height - 1));

                        // Get pixel value from ByteBuffer
                        // Pixel data is arranged row by row. Index = y * width + x
                        int pixelValue = channelData.get(imgY * width + imgX) & 0xFF; // Use & 0xFF to convert signed byte to unsigned int

                        sum += pixelValue * kernel[i][j];
                    }
                }
                outputMap[y][x] = sum;
            }
        }
    }

    /**
     * Helper to extract grayscale channel data into a ByteBuffer.
     */
    private ByteBuffer getGrayscaleChannel() {
         ByteBuffer buffer = BufferUtils.createByteBuffer(width * height);
         for (int y = 0; y < height; y++) {
             for (int x = 0; x < width; x++) {
                 buffer.put((byte) originalImage.getPixel(x, y));
             }
         }
         buffer.flip();
         return buffer;
    }

    /**
     * Helper to extract a specific RGB channel (0:Red, 1:Green, 2:Blue) into a ByteBuffer.
     */
    private ByteBuffer getRGBChannel(int channel) {
         ByteBuffer buffer = BufferUtils.createByteBuffer(width * height);
         for (int y = 0; y < height; y++) {
             for (int x = 0; x < width; x++) {
                 int rgb = originalImage.getRGBPixel(x, y);
                 int value;
                 if (channel == 0) value = (rgb >> 16) & 0xFF; // Red
                 else if (channel == 1) value = (rgb >> 8) & 0xFF;  // Green
                 else value = rgb & 0xFF;                       // Blue
                 buffer.put((byte) value);
             }
         }
         buffer.flip();
         return buffer;
    }


    /**
     * Computes the Gradient Magnitude G = sqrt(Ix^2 + Iy^2) for given Ix, Iy maps.
     */
    private void computeGradientMagnitude(double[][] ixMap, double[][] iyMap, double[][] gMap) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double ix = ixMap[y][x];
                double iy = iyMap[y][x];
                gMap[y][x] = sqrt(ix * ix + iy * iy);
            }
        }
    }

     /**
      * Computes the Gradient Magnitude G and Direction (Iy, -Ix) normalized.
      * This version is used for Grayscale when we don't need to pick the best channel.
      */
     private void computeGradientMagnitudeAndDirection(double[][] tempIx, double[][] tempIy, double[][] outIx, double[][] outIy, double[][] outG, double[][][] outDirection) {
         for (int y = 0; y < height; y++) {
             for (int x = 0; x < width; x++) {
                 double ix = tempIx[y][x];
                 double iy = tempIy[y][x];

                 outIx[y][x] = ix; // Store for later use if needed
                 outIy[y][x] = iy;

                 // Gradient Magnitude
                 double magnitude = sqrt(ix * ix + iy * iy);
                 outG[y][x] = magnitude;

                 // Gradient Direction (Perpendicular vector rotated 90 deg clockwise: (Iy, -Ix))
                 // Normalize to a unit vector
                 if (magnitude > 1e-6) {
                     outDirection[y][x][0] = iy / magnitude; // Normalized Iy
                     outDirection[y][x][1] = -ix / magnitude; // Normalized -Ix
                 } else {
                     outDirection[y][x][0] = 0;
                     outDirection[y][x][1] = 0;
                 }
             }
         }
     }


    /**
     * Finds the maximum gradient magnitude across the entire image.
     * Operates on the *combined* gMap.
     */
    private void findMaxGradientMagnitude() {
        gMax = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (gMap[y][x] > gMax) {
                    gMax = gMap[y][x];
                }
            }
        }
        if (gMax < 1e-6) {
             gMax = 1.0;
        }
    }

    // --- New method for Cursor Snap (already implemented) ---
    /**
     * Finds the pixel with the maximum gradient magnitude within a square
     * neighborhood centered at (centerX, centerY).
     */
    public Pixel findMaxGradientPixelInNeighborhood(int centerX, int centerY, int radius) {
        double maxGradient = -1.0;
        Pixel maxPixel = null;

        int searchMinX = centerX - radius;
        int searchMaxX = centerX + radius;
        int searchMinY = centerY - radius;
        int searchMaxY = centerY + radius;

        for (int y = searchMinY; y <= searchMaxY; y++) {
            for (int x = searchMinX; x <= searchMaxX; x++) {

                int clampedX = max(0, min(x, width - 1));
                int clampedY = max(0, min(y, height - 1));

                double currentGradient = gMap[clampedY][clampedX];

                if (currentGradient > maxGradient) {
                    maxGradient = currentGradient;
                    maxPixel = new Pixel(clampedX, clampedY);
                }
            }
        }
        return maxPixel;
    }


    // --- Getters for computed maps (now return combined features for color) ---

    public double[][] getIlMap() {
        return ilMap;
    }

    public double[][] getIxMap() {
        return ixMap; // Now stores Ix from the max magnitude channel for color
    }

    public double[][] getIyMap() {
        return iyMap; // Now stores Iy from the max magnitude channel for color
    }

    public double[][] getGMap() {
        return gMap; // Combined (max channel) gradient magnitude
    }

     /**
      * Gets the Gradient Direction map (Direction from max magnitude channel for color).
      * Each pixel [y][x] stores a 2-element double array [0] = Normalized Iy, [1] = Normalized -Ix.
      *
      * @return The direction map.
      */
    public double[][][] getDirectionMap() {
        return directionMap; // Direction from the max magnitude channel
    }

    public double getGMax() {
        return gMax;
    }

}