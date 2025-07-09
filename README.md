
# Intelligent Scissors: Interactive Image Segmentation

This project is a Java-based implementation of the "Intelligent Scissors" algorithm, a classic interactive tool for image segmentation. It allows a user to quickly and accurately extract objects from an image by tracing their edges with minimal effort.

This implementation is built using Java and the **LWJGL 3** library for windowing and rendering. It features an advanced, non-blocking architecture to maintain a responsive user interface even while pathfinding on large images.

![Demo GIF](assets/demo.gif)

## Core Features

-   **Advanced Feature Extraction**: Computes a cost map for pathfinding based on:
    -   Laplacian Zero-Crossing
    -   Gradient Magnitude
    -   Gradient Direction
-   **Efficient Incremental Search**: Utilizes a Dijkstra-based graph search with a priority queue for excellent performance. The search is interleaved across frames to prevent UI lock-ups on large images.
-   **Interactive "Live-Wire"**: Displays the optimal path from the last seed point to the current mouse position in real-time.
-   **Cursor Snap**: Automatically snaps the cursor to the pixel with the highest gradient magnitude in a small neighborhood, aiding in precise seed point placement.
-   **Automatic Path Cooling**: Intelligently tracks user history on stable paths. If a path segment is traced repeatedly, the tool automatically "cools" it by placing a new seed point, streamlining the workflow.
-   **Interactive Controls**: Simple and intuitive keyboard and mouse controls for a fluid user experience.

## Getting Started

### Prerequisites

-   [Java Development Kit (JDK)](https://www.oracle.com/java/technologies/downloads/) (**Version 21 or newer** is recommended).
    -   *Note: While the `pom.xml` may specify a very new version like 24, the project will likely compile and run on any recent LTS release like JDK 21. Requiring the absolute latest version can be a barrier for users.*
-   [Apache Maven](https://maven.apache.org/download.cgi) (for building the project).

### Building the Project

1.  **Clone the repository.**
    
2.  **Build with Maven:**
    From the root directory of the project, run the following command. This will compile the source code and package it into a single, executable JAR file.
    ```bash
    mvn clean package
    ```
    The runnable JAR will be created in the `target/` directory, named `IntelligentScissors-1.0.jar`.

### Running the Application

You must provide a path to an image as a command-line argument to run the application.

```bash
java -jar target/IntelligentScissors-1.0.jar "path/to/your/image.png"
```

**Example using an image from the repository:**
```bash
java -jar target/IntelligentScissors-1.0.jar "assets/test_image_grayscale.png"
```

## How to Use the Tool

The interactive controls are designed for speed. Note the distinction between the first click (`Left-Click`) and all subsequent clicks (`Right-Click`).

| Action                     | Control         | Description                                                                                         |
| -------------------------- | --------------- | --------------------------------------------------------------------------------------------------- |
| **Place First Seed**       | `Left-Click`    | Places the very first anchor point on an object's edge to begin the boundary.                       |
| **Add Subsequent Segment** | `Right-Click`   | Commits the current live-wire path and sets a new anchor point at the cursor's current location.    |
| **Reset Boundary**         | `C` Key         | Deletes the entire boundary and all anchor points, allowing you to start over.                      |
| **Exit Application**       | `ESC` Key       | Closes the program.                                                                                 |

### Step-by-Step Workflow

1.  Launch the application with your chosen image.
2.  Move your mouse to the edge of the object you want to trace.
3.  **`Left-Click` once** to place the first seed. This is the only time you will use the left mouse button.
4.  Move the mouse along the object's boundary. The "live-wire" will automatically snap to the edge.
5.  **`Right-Click`** to anchor the current path and start a new live-wire from that point.
6.  Continue tracing the object by **right-clicking** along its edge. The tool may automatically place seeds for you on stable paths thanks to the Path Cooling feature.
7.  If you make a mistake, press the **`C`** key to clear the entire path and start again.

## Acknowledgments

This work is an implementation of the concepts presented in the following foundational papers:
-   [1] Mortensen, E. N., & Barrett, W. A. (1995). Intelligent scissors for image composition. In *Proceedings of the 22nd annual conference on Computer graphics and interactive techniques* (pp. 191-198).
-   [2] Mortensen, E. N., & Barrett, W. A. (1997). Interactive live-wire boundary extraction. *Medical Image Analysis, 1*(4), 331-341.

