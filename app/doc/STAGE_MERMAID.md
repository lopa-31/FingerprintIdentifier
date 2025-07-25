Of course, here is a Markdown diagram of the image processing pipeline:

```mermaid
graph TD
    A[CAMERA FRAME (30fps)] --> B{STAGE 1: INITIAL CHECKS};
    B --> C[Light Check (~5-10ms)<br>Histogram];
    B --> D[Liveness Check (~10-15ms)<br>MediaPipe/ML];
    subgraph STAGE 1
        C;
        D;
    end
    D --> E{Output: ~20fps → candidateFlow};
    C --> E;
    E --> F{STAGE 2: SEGMENTATION<br>(Filtered frames only)};
    F --> G[YOLO Analysis (~50-100ms)<br>TensorFlow Lite<br>Confidence > 0.7];
    subgraph STAGE 2
        G;
    end
    G --> H{Output: ~5fps → segmentedFlow};
    H --> I{STAGE 3: QUALITY<br>(Segmented frames only)};
    I --> J[Blur Check (~20ms)];
    I --> K[Bright Spot Check (~15ms)];
    subgraph STAGE 3
        J;
        K;
    end
    K --> L[Output: finalBuffer[5]];
    J --> L;

```