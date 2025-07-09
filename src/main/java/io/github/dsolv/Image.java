package io.github.dsolv;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Represents an image and stores its pixel data and dimensions.
 * Supports both grayscale and RGB, providing access to both forms.
 */
public class Image {

    private int width;
    private int height;
    private int[][] grayscalePixels; // Stores 0-255 intensity values
    private int[][] rgbPixels;       // Stores packed ARGB values from BufferedImage
    private boolean isColorImage;    // Flag to indicate if the original image was color

    private Image(int width, int height, int[][] grayscalePixels, int[][] rgbPixels, boolean isColorImage) {
        this.width = width;
        this.height = height;
        this.grayscalePixels = grayscalePixels;
        this.rgbPixels = rgbPixels;
        this.isColorImage = isColorImage;
    }

    /**
     * Loads an image from the specified file path. Stores original RGB (or grayscale)
     * data and also generates a grayscale version.
     *
     * @param filePath The path to the image file.
     * @return A new Image object containing pixel data.
     * @throws IOException If the file cannot be read or is not a valid image format.
     */
    public static Image loadImage(String filePath) throws IOException {
        File file = new File(filePath);
        BufferedImage originalImage = ImageIO.read(file);

        if (originalImage == null) {
            throw new IOException("Could not read image from file: " + filePath);
        }

        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        int[][] grayscalePixels = new int[height][width];
        int[][] rgbPixels = new int[height][width];
        boolean determinedIsColorImage = false; // Determine this based on pixel data

        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int rgb = originalImage.getRGB(x, y);
                rgbPixels[y][x] = rgb;

                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                int gray = (int) (0.2126 * r + 0.7152 * g + 0.0722 * b);

                grayscalePixels[y][x] = gray;

                // Check if it's a color image (if R, G, B are not all equal)
                if (!determinedIsColorImage && (r != g || g != b)) {
                    determinedIsColorImage = true;
                }
            }
        }

        // If no color was detected, it's likely a true grayscale image or a color image
        // that happens to be grayscale (R=G=B for all pixels). Treat it as grayscale.
        return new Image(width, height, grayscalePixels, rgbPixels, determinedIsColorImage);
    }

    /**
     * Gets the width of the image in pixels.
     *
     * @return The width.
     */
    public int getWidth() {
        return width;
    }

    /**
     * Gets the height of the image in pixels.
     *
     * @return The height.
     */
    public int getHeight() {
        return height;
    }

    /**
     * Gets the grayscale intensity value of the pixel at the specified coordinates.
     * This method is used by the grayscale-based ImageProcessor functions.
     *
     * @param x The x-coordinate (column).
     * @param y The y-coordinate (row).
     * @return The grayscale intensity (0-255).
     * @throws IndexOutOfBoundsException if x or y are out of bounds.
     */
    public int getPixel(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("Pixel coordinates (" + x + ", " + y + ") out of bounds.");
        }
        return grayscalePixels[y][x];
    }

     /**
      * Gets the original packed ARGB integer value for the pixel at the specified coordinates.
      *
      * @param x The x-coordinate (column).
      * @param y The y-coordinate (row).
      * @return The packed ARGB integer.
      * @throws IndexOutOfBoundsException if x or y are out of bounds.
      */
     public int getRGBPixel(int x, int y) {
         if (x < 0 || x >= width || y < 0 || y >= height) {
             throw new IndexOutOfBoundsException("Pixel coordinates (" + x + ", " + y + ") out of bounds.");
         }
         return rgbPixels[y][x];
     }

    /**
     * Checks if the original image was detected as a color image.
     *
     * @return true if the image is color, false if grayscale.
     */
    public boolean isColorImage() {
        return isColorImage;
    }

}