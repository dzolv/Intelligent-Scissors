package io.github.dsolv;

import java.util.LinkedList;
import java.util.List;



/**
 * Implements the 2D Dynamic Programming graph search (Dijkstra)
 * to find the minimum cost path from a seed pixel to all other pixels.
 * Uses a bucket queue for potentially O(N) performance, assuming integer costs.
 * Modified to support incremental expansion (single pixel per call).
 */
public class IntelligentScissorsSearch {

    private CostCalculator costCalculator;
    private int width;
    private int height;

    // Data structures for the graph search
    private int[][] cumulativeCosts; // g(p) - Stores total cost from seed to pixel
    private Pixel[][] pathPointers;    // ptr(q) - Stores the previous pixel on the optimal path
    private boolean[][] expanded;      // e(p) - Indicates if a pixel has been processed

    // The active list (bucket queue)
    // An array of lists, where each list holds pixels with the same cumulative cost modulo M
    private List<Pixel>[] activeListBuckets;
    private int M; // The range of discrete local costs [0, M-1]

    // State for finding the next minimum cost pixel in the bucket queue
    private int currentMinCostBucketIndex; // Index of the bucket where the last minimum was found or search started
    private int currentMinCostValue; // The actual cumulative cost value corresponding to the search start

    // Store the seed point
    private Pixel seedPoint;

    /**
     * Constructs the search object.
     *
     * @param costCalculator The calculator providing local link costs.
     * @param width The width of the image.
     * @param height The height of the image.
     * @param M The range of discrete local costs [0, M-1].
     */
    public IntelligentScissorsSearch(CostCalculator costCalculator, int width, int height, int M) {
        this.costCalculator = costCalculator;
        this.width = width;
        this.height = height;
        this.M = M; // Max integer cost + 1

        // Initialize data structures (dimensioned by image size)
        this.cumulativeCosts = new int[height][width];
        this.pathPointers = new Pixel[height][width];
        this.expanded = new boolean[height][width];

        // Initialize bucket queue structure
        this.activeListBuckets = new LinkedList[M];
        for (int i = 0; i < M; i++) {
            activeListBuckets[i] = new LinkedList<>();
        }

        // Initialize state for finding minimum
        this.currentMinCostBucketIndex = 0;
        this.currentMinCostValue = 0;
    }

    /**
     * Initializes the graph search from the specified seed point.
     * Resets all internal state and places the seed in the active list.
     * Does NOT run the search to completion; subsequent calls to expandNextPixel() are needed.
     *
     * @param seed The starting pixel.
     */
    public void setSeedPoint(Pixel seed) {
        this.seedPoint = seed;

        // 1. Initialize maps (equivalent to infinite costs and no pointers)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Use Integer.MAX_VALUE to represent unreachable/infinite cost
                cumulativeCosts[y][x] = Integer.MAX_VALUE;
                pathPointers[y][x] = null;
                expanded[y][x] = false;
            }
        }

        // 2. Initialize the active list (bucket queue)
        for (int i = 0; i < M; i++) {
            activeListBuckets[i].clear(); // Clear previous search results
        }
        // Reset state for finding minimum
        currentMinCostBucketIndex = 0; // Start search for min from bucket 0
        currentMinCostValue = 0; // The current minimum cost being sought is 0 initially

        // 3. Set the seed point's cost to 0 and add to the active list bucket
        cumulativeCosts[seed.y()][seed.x()] = 0;
        // The bucket index is (cost % M). For seed, cost is 0, index is 0.
        activeListBuckets[0].add(seed);

        // The search will actually start when expandNextPixel() is called.
    }

    /**
     * Performs one step of the 2D DP graph search (Dijkstra).
     * Finds the pixel with the minimum cumulative cost from the active list, expands it,
     * and relaxes its neighbors.
     *
     * @return true if a pixel was expanded (search is ongoing), false if the active list is empty (search is complete).
     */
    public boolean expandNextPixel() {
        // Find the next pixel p with the minimum cumulative cost from the active list
        Pixel p = findAndRemoveMin();

        // If no pixel is found, the active list is empty, search is complete.
        if (p == null) {
            return false;
        }

        // If p has already been expanded (due to finding a better path earlier), skip this expansion
        // but count it as a step that processed an item.
        if (expanded[p.y()][p.x()]) {
             // This pixel was a duplicate in the queue or its cost was improved after it was added.
             // We simply discard it and return true, as the algorithm is still running.
             return true; // Indicates a pixel was processed, even if already expanded.
        }

        // Mark p as expanded
        expanded[p.y()][p.x()] = true;
        // Update the minimum cost value based on the pixel just expanded
        currentMinCostValue = cumulativeCosts[p.y()][p.x()];
        // The bucket index is implicitly updated by findAndRemoveMin

        // 5. Neighbor Relaxation: For each of p's 8 neighbors q
        List<Pixel> neighbors = getNeighbors(p);
        for (Pixel q : neighbors) {
             // Check if neighbor q is within bounds. The expanded check happens below.
             if (q.y() >= 0 && q.y() < height && q.x() >= 0 && q.x() < width) {

                 // Calculate the local cost from p to q (integer cost)
                 int localCost = costCalculator.getLinkCost(p, q);

                 // Skip invalid links (cost is Integer.MAX_VALUE)
                 if (localCost == Integer.MAX_VALUE) {
                     continue;
                 }

                 // Calculate the new potential cumulative cost to q
                 // Check for overflow before adding (though with M=256, unlikely to overflow int)
                 // Using long for intermediate sum to be safe if costs were larger or M was huge
                 long newCumulativeCostLong = (long)cumulativeCosts[p.y()][p.x()] + localCost;

                 // Convert back to int, assuming it fits
                 int newCumulativeCost = (int) newCumulativeCostLong;

                 // Relaxation Step: If found a shorter path to q
                 if (newCumulativeCost < cumulativeCosts[q.y()][q.x()]) {
                     // Update cumulative cost and path pointer for q
                     cumulativeCosts[q.y()][q.x()] = newCumulativeCost;
                     pathPointers[q.y()][q.x()] = p;

                     // Add q to the active list bucket based on its new cumulative cost
                     // Note: If q was already in a bucket, this adds a duplicate.
                     // The `expanded` check when extracting from the queue handles this.
                     int bucketIndex = newCumulativeCost % M; // Use modulo M for bucket index
                     activeListBuckets[bucketIndex].add(q);

                     // Dijkstra with bucket queue *might* require removing q from its old bucket
                     // if its cost decreased. However, the paper's pseudocode (Algorithm 2, page 26)
                     // mentions removing `q` from list `L` *if* it's already there and `gtmp<g(r)`.
                     // The findAndRemoveMin + expanded check handles the duplicate efficiently enough for O(N).
                     // Let's stick to adding the duplicate for simplicity as it matches the pseudocode flow.
                 }
             }
         }

        // Pixel p was successfully expanded
        return true; // Search is ongoing
    }

    /**
     * Finds and removes the pixel with the minimum cumulative cost from the active list buckets.
     * Implements the search logic described on page (27) starting from currentMinCostBucketIndex.
     *
     * @return The pixel with the minimum cost, or null if the active list is empty.
     */
    private Pixel findAndRemoveMin() {
        // Search for the next non-empty bucket starting from the current index.
        // We continue searching until we find a non-empty bucket.
        // The total cost represented by the current bucket index is currentMinCostValue + bucketsChecked.
        // The search wraps around the bucket array (index % M).
        // The search needs to continue through buckets until a non-empty one is found.

        int bucketsChecked = 0; // How many buckets we've checked *since the last minimum was found/search started*

        while (bucketsChecked < M) { // Search through at most M buckets to find the next non-empty one
            int bucketIdx = (currentMinCostBucketIndex + bucketsChecked) % M;

            if (!activeListBuckets[bucketIdx].isEmpty()) {
                // Found a non-empty bucket. Extract the pixel.
                // The cost of pixels in this bucket is currentMinCostValue + bucketsChecked + k*M (for k >= 0)
                // The first pixel in this bucket has the lowest such cost.
                currentMinCostBucketIndex = bucketIdx; // Update the starting index for the next search

                // Return the first pixel in the bucket. We rely on the `expanded` check in expandNextPixel().
                return activeListBuckets[bucketIdx].removeFirst();
            }
            bucketsChecked++;
        }

        // If we've checked all M buckets and found nothing, the active list is empty.
        return null;
    }


     /**
      * Helper method to get the 8 neighbors of a pixel, including diagonals.
      * Does NOT perform bounds checking here; the expandNextPixel method does that.
      *
      * @param p The pixel.
      * @return A list of 8 neighbor pixels.
      */
    private List<Pixel> getNeighbors(Pixel p) {
        List<Pixel> neighbors = new LinkedList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                // Skip the pixel itself
                if (dx == 0 && dy == 0) {
                    continue;
                }
                neighbors.add(new Pixel(p.x() + dx, p.y() + dy));
            }
        }
        return neighbors; // Contains potentially out-of-bounds pixels
    }

    /**
     * Reconstructs the optimal path from a given pixel back to the seed point
     * by following the path pointers.
     *
     * @param currentPixel The pixel to start tracing back from (e.g., the free point).
     * @return A list of pixels representing the path from the seed to the currentPixel,
     *         or null if no path exists (e.g., currentPixel is not reachable).
     */
    public List<Pixel> getPath(Pixel currentPixel) {
        LinkedList<Pixel> path = new LinkedList<>(); // Use LinkedList to add to front

        // Check if the starting point is valid and reachable (has finite cost)
         if (currentPixel.y() < 0 || currentPixel.y() >= height || currentPixel.x() < 0 || currentPixel.x() >= width || cumulativeCosts[currentPixel.y()][currentPixel.x()] == Integer.MAX_VALUE) {
            return null; // Invalid or unreachable start point
         }

        Pixel pixel = currentPixel;
        // Trace back using pointers until we reach the seed point (pointer is null AND it's the seed)
        // The seed point has a null pointer and cumulative cost 0. Other reachable pixels have costs > 0.
        while (pixel != null && !pixel.equals(seedPoint) && pathPointers[pixel.y()][pixel.x()] != null) {
            path.addFirst(pixel); // Add the current pixel to the front
            pixel = pathPointers[pixel.y()][pixel.x()]; // Move to the previous pixel
        }

        // If the loop finished because pixel is the seed point (and not null), add the seed and return the path
        if (pixel != null && pixel.equals(seedPoint)) {
             path.addFirst(seedPoint); // Add the seed
             return path;
        } else {
             // Path trace failed to reach the seed (loop might terminate if pointer is null and not seed)
             // This shouldn't happen if search was completed for the currentPixel.
             // But if search is *not* completed up to currentPixel, its pointer might be null or incorrect.
             // For the live-wire, we trace back as far as possible. If we hit a null pointer (not at seed),
             // it means the path isn't fully computed yet.
             // The most robust approach for live-wire is to just return the partial path found.
             // Let's return the partial path trace if we hit a null pointer before the seed.
             if (pixel != null) { // Add the last valid pixel found (which broke the loop) if not null
                 path.addFirst(pixel);
             }
             System.err.println("Warning: Partial path traced. Search may not be complete up to free point.");
             return path.isEmpty() ? null : path; // Return partial path, or null if path is empty
        }
    }


    // --- Helper/Debug Getters (Optional) ---
    public int getCumulativeCost(Pixel p) {
         if (p.y() >= 0 && p.y() < height && p.x() >= 0 && p.x() < width) {
             return cumulativeCosts[p.y()][p.x()];
         }
         return Integer.MAX_VALUE;
    }

     public Pixel getPathPointer(Pixel p) {
         if (p.y() >= 0 && p.y() < height && p.x() >= 0 && p.x() < width) {
             return pathPointers[p.y()][p.x()];
         }
         return null;
    }

    public boolean isExpanded(Pixel p) {
         if (p.y() >= 0 && p.y() < height && p.x() >= 0 && p.x() < width) {
             return expanded[p.y()][p.x()];
         }
         return false;
    }

    /**
     * Checks if the search from the current seed point is complete (active list is empty).
     * @return true if the search is complete, false otherwise.
     */
    public boolean isSearchComplete() {
        // Search for any non-empty bucket. If all are empty, search is complete.
        for (int i = 0; i < M; i++) {
            // We need to check *all* buckets, not just from currentMinCostBucketIndex
            // because findAndRemoveMin only guarantees finding the *next* minimum within M steps,
            // but the active list could be empty even if current bucket is not the last checked one.
            if (!activeListBuckets[i].isEmpty()) {
                return false; // Found a non-empty bucket, search is not complete
            }
        }
        return true; // All buckets are empty, search is complete
    }
}