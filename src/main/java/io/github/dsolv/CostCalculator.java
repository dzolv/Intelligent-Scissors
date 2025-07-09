package io.github.dsolv;

import static java.lang.Math.*;

/**
 * Calculates the local cost for a link between two pixels based on image features.
 * This version implements the cost function for grayscale images, using the acos
 * formulation for Gradient Direction cost.
 */
public class CostCalculator {

    private ImageProcessor processor;
    private int width;
    private int height;

    private static final double W_Z = 0.3; // ωz
    private static final double W_G = 0.3; // ωG
    private static final double W_D = 0.1; // ωD

    private static final double LAPLACIAN_ZERO_THRESHOLD = 1e-6;

    private static final double ORTHOGONAL_GRADIENT_SCALE = 1.0 / sqrt(2); // ~0.707
    private static final double DIAGONAL_GRADIENT_SCALE = 1.0;           // 1.0

    // Scaling factor for the Gradient Direction cost (based on paper's Eq 4 on page (15))
    private static final double GRADIENT_DIRECTION_SCALE = 2.0 / (3.0 * PI); // 2/(3π)

    // Small epsilon for floating point comparisons and vector normalization near zero
    private static final double EPSILON = 1e-6;

    // --- Parameters for Integer Cost Mapping ---
    private static final int M = 256; // Range of discrete local costs [0, M-1]
    // Maximum possible floating-point cost with current weights and max feature costs (1.0)
    private static final double MAX_FLOAT_COST = W_Z * 1.0 + W_G * 1.0 + W_D * 1.0; // 0.3 + 0.3 + 0.1 = 0.7
    private static final double FLOAT_TO_INT_SCALE_FACTOR = (double) (M - 1) / MAX_FLOAT_COST;


    /**
     * Constructs a CostCalculator with access to processed image features.
     *
     * @param processor The ImageProcessor containing computed feature maps.
     */
    public CostCalculator(ImageProcessor processor) {
        this.processor = processor;
        // Assuming all feature maps have the same dimensions as the original image
        this.width = processor.getGMap()[0].length;
        this.height = processor.getGMap().length;
    }

    /**
     * Calculates the local cost for a directed link from pixel p to pixel q.
     * The cost is defined as:
     * l(p,q) = ωz * f_z(q) + ωD * f_D(p,q) + ωG * f_G(q) + ... pixel value terms (currently 0)
     * The resulting floating-point cost is scaled and rounded to an integer
     * in the range [0, M-1] for the O(N) graph search.
     *
     * @param p The starting pixel coordinates.
     * @param q The ending pixel coordinates (neighbor of p).
     * @return The local cost of the link l'(p,q) as an integer in [0, M-1]. Returns Integer.MAX_VALUE for invalid links.
     */
    public int getLinkCost(Pixel p, Pixel q) {
        // Basic bounds check for the neighbor pixel q
        if (q.x() < 0 || q.x() >= width || q.y() < 0 || q.y() >= height) {
             // Invalid link. Return max integer value.
             return Integer.MAX_VALUE;
        }
        // We also need to ensure p is within bounds
         if (p.x() < 0 || p.x() >= width || p.y() < 0 || p.y() >= height) {
             return Integer.MAX_VALUE;
         }

        // --- Calculate individual feature costs (Floating Point) ---
        double f_z = getLaplacianCost(q.x(), q.y());
        double f_G = getGradientMagnitudeCost(p.x(), p.y(), q.x(), q.y());
        double f_D = getGradientDirectionCost(p.x(), p.y(), q.x(), q.y());

        // --- Combine feature costs with weights (Floating Point) ---
        // Pixel value feature costs (fp, fi, fo) are 0 without training.
        double floatCost = W_Z * f_z + W_G * f_G + W_D * f_D; // Range [0.0, MAX_FLOAT_COST]

        // --- Scale and Round to Integer Cost [0, M-1] ---
        // Clamp floatCost to the expected max to handle potential minor floating point errors
        floatCost = min(MAX_FLOAT_COST, max(0.0, floatCost));

        int integerCost = (int) round(floatCost * FLOAT_TO_INT_SCALE_FACTOR);

        // Ensure the integer cost is within the expected range [0, M-1]
        integerCost = min(M - 1, max(0, integerCost));

        return integerCost;
    }

    /**
     * Computes the Laplacian Zero-Crossing feature cost f_z(q).
     * Based on the text description on page (12): 0 if I_L(q) is closer to zero than
     * any orthogonal neighbor with an opposite sign; 1 otherwise.
     * Using a threshold for floating point comparisons for exact zero.
     *
     * @param qx x-coordinate of pixel q.
     * @param qy y-coordinate of pixel q.
     * @return The Laplacian cost f_z(q) in [0.0, 1.0].
     */
    private double getLaplacianCost(int qx, int qy) {
        double il_q = processor.getIlMap()[qy][qx];

        // Check if IL(q) is exactly zero (within threshold)
        if (abs(il_q) < LAPLACIAN_ZERO_THRESHOLD) {
            return 0.0;
        }

        // Check orthogonal neighbors (up, down, left, right)
        int[][] orthogonalNeighbors = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}; // dy, dx

        for (int[] offset : orthogonalNeighbors) {
            int r_y = qy + offset[0];
            int r_x = qx + offset[1];

            // Check neighbor bounds
            if (r_x >= 0 && r_x < width && r_y >= 0 && r_y < height) {
                double il_r = processor.getIlMap()[r_y][r_x];

                // Check for opposite sign AND if IL(q) is closer to zero than IL(r)
                if ((il_q * il_r < 0) && (abs(il_q) < abs(il_r) - EPSILON)) { // Use EPSILON for float comparison
                    return 0.0; // Found a zero-crossing pixel at q
                }
            }
        }

        // If IL(q) is not zero and no orthogonal neighbor has opposite sign closer to zero
        return 1.0; // Not a zero-crossing pixel by this definition
    }

    /**
     * Computes the Gradient Magnitude feature cost f_G(q).
     * Calculated using the inverse linear ramp ((gMax - G(q)) / gMax),
     * and then scaled by the distance factor (1 or 1/sqrt(2)) based on the link type.
     * Based on Eq 3 on page (14) and description on page 192/14.
     *
     * @param px x-coordinate of pixel p.
     * @param py y-coordinate of pixel p.
     * @param qx x-coordinate of pixel q.
     * @param qy y-coordinate of pixel q.
     * @return The Gradient Magnitude cost f_G >= 0.
     */
    private double getGradientMagnitudeCost(int px, int py, int qx, int qy) {
        double g_q = processor.getGMap()[qy][qx];
        double gMax = processor.getGMax();

        // Calculate the inverse linear ramp: 0 for max gradient, 1 for zero gradient.
        // Avoid division by zero if gMax is 0 (flat image) - handled in ImageProcessor
        // The paper says G' = G - min(G) then scales G'. For a single image, min(G)=0 always.
        // So G' is just G. The formula is (max(G)-G)/max(G).
        double gradientCost = (gMax - g_q) / gMax; // Range [0.0, 1.0]

        // Determine the scaling factor based on the type of link (orthogonal or diagonal)
        double distanceScale;
        int dx = abs(px - qx);
        int dy = abs(py - qy);

        if ((dx == 1 && dy == 0) || (dx == 0 && dy == 1)) {
            distanceScale = ORTHOGONAL_GRADIENT_SCALE; // 1/sqrt(2)
        } else if (dx == 1 && dy == 1) {
            distanceScale = DIAGONAL_GRADIENT_SCALE;   // 1.0
        } else {
             // This should ideally not happen in a graph of 8-connected pixels
             return Double.POSITIVE_INFINITY; // Invalid link
        }

        // Apply the distance scaling factor.
        // Note: This scaling makes orthogonal links *cheaper* for this feature than diagonal ones.
        return gradientCost * distanceScale;
    }

    /**
     * Computes the Gradient Direction feature cost f_D(p,q).
     * Based on Eq 4 on page (15): fD(p,q) = 2/(3*PI) * {acos[D'(p).u_L] + acos[D'(q).u_L]}
     * where u_L is the unit link vector L(p,q)/||L(p,q)||.
     *
     * @param px x-coordinate of pixel p.
     * @param py y-coordinate of pixel p.
     * @param qx x-coordinate of pixel q.
     * @param qy y-coordinate of pixel q.
     * @return The Gradient Direction cost f_D(p,q) in [0.0, 1.0].
     */
    private double getGradientDirectionCost(int px, int py, int qx, int qy) {
        // Get normalized gradient perpendicular vectors D'(p) and D'(q)
        // D'(p) = (Normalized Iy at p, Normalized -Ix at p)
        double dpVecX = processor.getDirectionMap()[py][px][0]; // Normalized Iy at p
        double dpVecY = processor.getDirectionMap()[py][px][1]; // Normalized -Ix at p

        // D'(q) = (Normalized Iy at q, Normalized -Ix at q)
        double dqVecX = processor.getDirectionMap()[qy][qx][0]; // Normalized Iy at q
        double dqVecY = processor.getDirectionMap()[qy][qx][1]; // Normalized -Ix at q

        // Calculate the vector from p to q (q - p)
        double vec_pqX = qx - px;
        double vec_pqY = qy - py;

        // Determine the link vector L(p,q) based on the rule D'(p).(q-p) >= 0 (Formula 6, page 15)
        // This ensures L points roughly in the direction of D'(p).
        double dp_dot_pq = dpVecX * vec_pqX + dpVecY * vec_pqY; // Dot product D'(p) . (q-p)

        double linkVecX, linkVecY;
        if (dp_dot_pq >= 0) {
            linkVecX = vec_pqX; // L = q-p
            linkVecY = vec_pqY;
        } else {
            // If D'(p).(q-p) is negative, use the vector p-q = -(q-p). The paper's L(p,q) is bidirectional.
            linkVecX = -vec_pqX; // L = p-q
            linkVecY = -vec_pqY;
        }

        // Calculate the magnitude of the chosen link vector L(p,q)
        double linkMagnitude = sqrt(linkVecX * linkVecX + linkVecY * linkVecY);

        // Get the unit link vector u_L(p,q)
        double uLinkVecX, uLinkVecY;
        if (linkMagnitude > EPSILON) { // Avoid division by zero (shouldn't happen for distinct neighbors)
             uLinkVecX = linkVecX / linkMagnitude;
             uLinkVecY = linkVecY / linkMagnitude;
        } else {
             // Handle degenerate case (p == q etc.) - return 0 cost contribution as per analysis
             return 0.0;
        }

        // Calculate the dot products of D'(p) and D'(q) with the unit link vector u_L.
        // These are the cosines of the angles between the vectors.
        double cos_dp_angle = dpVecX * uLinkVecX + dpVecY * uLinkVecY;
        double cos_dq_angle = dqVecX * uLinkVecX + dqVecY * uLinkVecY;

        // Clamp dot products to [-1, 1] range to handle potential floating point inaccuracies
        cos_dp_angle = max(-1.0, min(1.0, cos_dp_angle));
        cos_dq_angle = max(-1.0, min(1.0, cos_dq_angle));

        // Compute f_D(p,q) using the acos formula and scaling factor
        // fD(p,q) = 2/(3*PI) * {acos[cos_dp_angle] + acos[cos_dq_angle]}
        double fdCost = GRADIENT_DIRECTION_SCALE * (acos(cos_dp_angle) + acos(cos_dq_angle));

        // The theoretical range of f_D is [0, 1] with this formula.
        // Clamp to ensure it's within bounds due to potential floating point issues.
        return max(0.0, min(1.0, fdCost));
    }
}
