# ContactlessFinger SDK - Image Processing Pipeline Architecture

## Table of Contents
1. [Overview](#overview)
2. [Architecture Components](#architecture-components)
3. [Multi-Stage Pipeline Design](#multi-stage-pipeline-design)
4. [Warning Management System](#warning-management-system)
5. [UI State Management](#ui-state-management)
6. [Implementation Structure](#implementation-structure)
7. [Code Snippets](#code-snippets)
8. [Flow Diagrams](#flow-diagrams)
9. [Performance Considerations](#performance-considerations)
10. [Testing Strategy](#testing-strategy)

---

## Overview

The ContactlessFinger SDK implements a sophisticated multi-stage image processing pipeline designed to capture and analyze fingerprint images in real-time while providing optimal user experience through intelligent warning management and stable UI state transitions.

### Key Goals
- **High Frame Utilization**: Process most camera frames without dropping
- **Quality Selection**: Identify and collect the best 5 fingerprint images
- **Stable UI Experience**: Prevent UI state thrashing with temporal debouncing
- **Intelligent Warnings**: Show contextually relevant warnings based on processing stage and priority

### Performance Targets
- **Stage 1**: 20-25 fps (Light + Liveness checks)
- **Stage 2**: 5-8 fps (YOLO Segmentation) 
- **Stage 3**: 3-5 fps (Quality Analysis)
- **Final Output**: Best 5 images within 10-15 seconds

---

## Architecture Components

### Core Components

```kotlin
// Core processing pipeline components
class ImageProcessor(...)           // Main coordinator
class WarningManager(...)          // Multi-warning state management  
class StateManager(...)            // UI state with debouncing
class ProcessingCoordinator(...)   // Stage orchestration
class FrameQualityAnalyzer(...)    // Frame selection logic
```

### Data Flow Layers

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Camera2 API   │ -> │ Processing Core │ -> │  UI Management  │
│                 │    │                 │    │                 │
│ • ImageReader   │    │ • Multi-stage   │    │ • StateManager  │
│ • YUV_420_888   │    │ • WarningMgr    │    │ • Warning UI    │
│ • 30fps stream  │    │ • Quality Score │    │ • Debounced     │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

---

## Multi-Stage Pipeline Design

### Stage Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    CAMERA FRAME (30fps)                          │
└─────────────────────────┬────────────────────────────────────────┘
                          │
    ┌─────────────────────▼────────────────────────┐
    │            STAGE 1: INITIAL CHECKS           │
    │                                              │
    │  ┌─────────────────┐ ┌─────────────────┐    │
    │  │  Light Check    │ │ Liveness Check  │    │
    │  │  (~5-10ms)     │ │  (~10-15ms)     │    │
    │  │  Histogram     │ │  MediaPipe/ML   │    │
    │  └─────────────────┘ └─────────────────┘    │
    │                                              │
    │           Output: ~20fps → candidateFlow     │
    └──────────────────┬───────────────────────────┘
                       │ (Filtered frames only)
         ┌─────────────▼────────────────┐
         │    STAGE 2: SEGMENTATION     │
         │                              │
         │  ┌─────────────────────────┐ │
         │  │     YOLO Analysis       │ │
         │  │     (~50-100ms)        │ │
         │  │     TensorFlow Lite     │ │
         │  │     Confidence > 0.7    │ │
         │  └─────────────────────────┘ │
         │                              │
         │    Output: ~5fps → segmentedFlow │
         └──────────────┬───────────────────┘
                        │ (Segmented frames only)
           ┌────────────▼─────────────┐
           │   STAGE 3: QUALITY      │
           │                         │
           │ ┌─────────┐ ┌─────────┐ │
           │ │  Blur   │ │ Bright  │ │
           │ │ Check   │ │ Spot    │ │
           │ │(~20ms)  │ │ Check   │ │
           │ │         │ │(~15ms)  │ │
           │ └─────────┘ └─────────┘ │
           │                         │
           │ Output: finalBuffer[5]  │
           └─────────────────────────┘
```

### Frame Data Structures

```kotlin
// Raw camera frame
data class CameraFrame(
    val byteArray: ByteArray,
    val width: Int,
    val height: Int,
    val timestamp: Long,
    val rotationDegrees: Int
)

// Processed frame after Stage 1
data class CandidateFrame(
    val originalFrame: CameraFrame,
    val lightScore: Float,        // 0.0 - 1.0
    val livenessScore: Float,     // 0.0 - 1.0
    val combinedScore: Float,     // Weighted average
    val processingTime: Long
)

// Processed frame after Stage 2  
data class SegmentedFrame(
    val candidateFrame: CandidateFrame,
    val detectionResults: List<DetectionResult>,
    val segmentationConfidence: Float,
    val croppedBitmap: Bitmap?,
    val boundingBox: RectF
)

// Final processed frame after Stage 3
data class ProcessedImage(
    val segmentedFrame: SegmentedFrame,
    val blurScore: Float,
    val brightSpotScore: Float,
    val qualityScore: Float,      // Final combined score
    val processingStage: ProcessingStage.COMPLETED,
    val finalBitmap: Bitmap
)
```

### Flow Channels Configuration

```kotlin
class ImageProcessor {
    // Stage 1: High throughput, drop oldest
    private val rawFrameFlow = MutableSharedFlow<CameraFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // Stage 2: Moderate capacity, selective sampling
    private val candidateChannel = Channel<CandidateFrame>(capacity = 5)
    
    // Stage 3: Process all segmented frames
    private val segmentedChannel = Channel<SegmentedFrame>(capacity = 3)
    
    // Final collection
    private val finalBuffer = Collections.synchronizedList(
        mutableListOf<ProcessedImage>()
    )
}
```

---

## Warning Management System

### Warning State Architecture

```kotlin
sealed class WarningState {
    object None : WarningState()
    data class Active(
        val warnings: Set<ActiveWarning>,
        val displayWarning: Warning,
        val lastUpdated: Long
    ) : WarningState()
}

data class ActiveWarning(
    val warning: Warning,
    val activatedAt: Long,
    val lastSeenAt: Long,
    val sourceStage: ProcessingStage,
    val consecutiveFailures: Int
)
```

### Warning Priority Resolution

```kotlin
class WarningManager {
    private val activeWarnings = mutableSetOf<ActiveWarning>()
    private val warningTimeout = 2000L // 2 seconds
    private val minWarningDuration = 500L // 500ms minimum display
    
    fun updateWarnings(
        stage: ProcessingStage,
        newWarnings: List<Warning>,
        passedChecks: List<Warning>
    ) {
        val currentTime = System.currentTimeMillis()
        
        // Add new warnings
        newWarnings.forEach { warning ->
            val existing = activeWarnings.find { it.warning.id == warning.id }
            if (existing != null) {
                // Update existing warning
                activeWarnings.remove(existing)
                activeWarnings.add(existing.copy(
                    lastSeenAt = currentTime,
                    consecutiveFailures = existing.consecutiveFailures + 1
                ))
            } else {
                // Add new warning
                activeWarnings.add(ActiveWarning(
                    warning = warning,
                    activatedAt = currentTime,
                    lastSeenAt = currentTime,
                    sourceStage = stage,
                    consecutiveFailures = 1
                ))
            }
        }
        
        // Clear resolved warnings (with persistence)
        passedChecks.forEach { resolvedWarning ->
            activeWarnings.removeIf { activeWarning ->
                activeWarning.warning.id == resolvedWarning.id &&
                (currentTime - activeWarning.lastSeenAt) > minWarningDuration
            }
        }
        
        // Remove expired warnings
        activeWarnings.removeIf { warning ->
            (currentTime - warning.lastSeenAt) > warningTimeout
        }
        
        // Emit new warning state
        emitWarningState(currentTime)
    }
    
    private fun emitWarningState(currentTime: Long) {
        if (activeWarnings.isEmpty()) {
            _warningState.value = WarningState.None
            return
        }
        
        // Priority resolution: Highest priority first, then most recent
        val displayWarning = activeWarnings
            .sortedWith(compareByDescending<ActiveWarning> { it.warning.priority }
                .thenByDescending { it.lastSeenAt })
            .first()
            
        _warningState.value = WarningState.Active(
            warnings = activeWarnings.toSet(),
            displayWarning = displayWarning.warning,
            lastUpdated = currentTime
        )
    }
}
```

### Multi-Stage Warning Scenarios

```kotlin
// Scenario 1: Multiple warnings in same stage
Stage3Results {
    warnings: [BlurWarning(priority=3), BrightSpotWarning(priority=3)]
    → Display: Most recent warning (temporal tie-breaking)
}

// Scenario 2: Cross-stage warnings
ActiveWarnings {
    LowLightWarning(priority=1, stage=Stage1)
    BlurWarning(priority=3, stage=Stage3)
    → Display: BlurWarning (higher priority)
}

// Scenario 3: Warning persistence
Frame N: LowLightWarning active
Frame N+1: Light check passes
Frame N+2: Light check passes  
→ LowLightWarning removed after minWarningDuration (500ms)
```

---

## UI State Management

### State Machine Design

```kotlin
enum class UIState {
    INITIAL,      // Primarily Stage 1 issues
    VALIDATION,   // Stage 1 passing, Stage 2/3 issues  
    SUCCESS       // All stages passing, collecting frames
}

data class StateTransition(
    val fromState: UIState,
    val toState: UIState,
    val condition: String,
    val requiredDuration: Long
)

class StateManager {
    private val stateHistory = CircularBuffer<ProcessingResult>(size = 20)
    private val stateTransitions = listOf(
        StateTransition(INITIAL, VALIDATION, "stage1Success > 0.7", 1000L),
        StateTransition(VALIDATION, SUCCESS, "stage3Success > 0.6 && bufferSize >= 3", 500L),
        StateTransition(SUCCESS, VALIDATION, "stage3Success < 0.4", 1500L),
        StateTransition(VALIDATION, INITIAL, "stage1Success < 0.3", 2000L)
    )
}
```

### Debouncing Implementation

```kotlin
class StateManager {
    private var currentState = UIState.INITIAL
    private var stateChangeRequestTime: Long? = null
    private var pendingState: UIState? = null
    
    fun updateProcessingResult(result: ProcessingResult) {
        stateHistory.add(result)
        
        val suggestedState = calculateSuggestedState()
        
        if (suggestedState != currentState) {
            handleStateChangeRequest(suggestedState)
        } else {
            // Cancel pending state change if suggestion reverted
            if (pendingState != suggestedState) {
                cancelPendingStateChange()
            }
        }
    }
    
    private fun calculateSuggestedState(): UIState {
        val recentResults = stateHistory.getLast(10) // Last 10 frames
        
        val stage1SuccessRate = recentResults.count { it.stage1Passed } / recentResults.size.toFloat()
        val stage2SuccessRate = recentResults.count { it.stage2Passed } / recentResults.size.toFloat()  
        val stage3SuccessRate = recentResults.count { it.stage3Passed } / recentResults.size.toFloat()
        
        return when {
            stage1SuccessRate < 0.3 -> UIState.INITIAL
            stage1SuccessRate > 0.7 && stage3SuccessRate > 0.6 && bufferSize >= 3 -> UIState.SUCCESS
            stage1SuccessRate > 0.7 -> UIState.VALIDATION
            else -> currentState // No clear direction, maintain current
        }
    }
    
    private fun handleStateChangeRequest(newState: UIState) {
        val currentTime = System.currentTimeMillis()
        val transition = findTransition(currentState, newState)
        
        if (pendingState == newState) {
            // Check if enough time has passed
            stateChangeRequestTime?.let { requestTime ->
                if (currentTime - requestTime >= transition.requiredDuration) {
                    commitStateChange(newState)
                }
            }
        } else {
            // New state change request
            pendingState = newState  
            stateChangeRequestTime = currentTime
        }
    }
}
```

### State Transition Matrix

```
Current State → Suggested State → Required Duration → Condition
─────────────────────────────────────────────────────────────────
INITIAL      → VALIDATION      → 1000ms          → stage1Success > 70%
INITIAL      → SUCCESS          → 2000ms          → stage1Success > 70% && stage3Success > 60%
VALIDATION   → SUCCESS          → 500ms           → stage3Success > 60% && buffer >= 3
VALIDATION   → INITIAL          → 2000ms          → stage1Success < 30%  
SUCCESS      → VALIDATION       → 1500ms          → stage3Success < 40%
SUCCESS      → INITIAL          → 3000ms          → stage1Success < 30%
```

---

## Implementation Structure

### Main Processing Coordinator

```kotlin
@AndroidEntryPoint
class ImageProcessor(
    private val viewModel: CameraViewModel,
    private val coroutineScope: LifecycleCoroutineScope
) : ImageReader.OnImageAvailableListener {

    private val warningManager = WarningManager()
    private val stateManager = StateManager()
    private val qualityAnalyzer = FrameQualityAnalyzer()
    
    // Processing stages
    private val stage1Processor = Stage1Processor()
    private val stage2Processor = Stage2Processor()
    private val stage3Processor = Stage3Processor()
    
    init {
        setupProcessingPipeline()
        setupWarningObservation()
        setupStateObservation()
    }
    
    private fun setupProcessingPipeline() {
        // Stage 1: Light + Liveness checks
        rawFrameFlow
            .onEach { frame -> processStage1(frame) }
            .flowOn(Dispatchers.Default)
            .launchIn(coroutineScope)
            
        // Stage 2: Segmentation (sampled)
        candidateChannel.consumeAsFlow()
            .sample(100) // Max 10fps for Stage 2
            .onEach { candidate -> processStage2(candidate) }
            .flowOn(Dispatchers.Default)
            .launchIn(coroutineScope)
            
        // Stage 3: Quality analysis
        segmentedChannel.consumeAsFlow()
            .onEach { segmented -> processStage3(segmented) }
            .flowOn(Dispatchers.Default)  
            .launchIn(coroutineScope)
    }
}
```

### Stage Processing Implementation

```kotlin
// Stage 1: Initial Checks
private suspend fun processStage1(frame: CameraFrame) {
    val results = coroutineScope.async(Dispatchers.Default) {
        val lightResult = stage1Processor.checkLighting(frame.byteArray)
        val livenessResult = stage1Processor.checkLiveness(frame.byteArray)
        
        Stage1Result(
            lightPassed = lightResult.passed,
            livenessPassed = livenessResult.passed,
            lightScore = lightResult.confidence,
            livenessScore = livenessResult.confidence,
            warnings = mutableListOf<Warning>().apply {
                if (!lightResult.passed) add(Warning.LowLightWarning)
                if (!livenessResult.passed) add(Warning.LivenessWarning)
            }
        )
    }.await()
    
    // Update warning manager
    warningManager.updateWarnings(
        stage = ProcessingStage.INITIAL_CHECKS,
        newWarnings = results.warnings,
        passedChecks = listOfNotNull(
            if (results.lightPassed) Warning.LowLightWarning else null,
            if (results.livenessPassed) Warning.LivenessWarning else null
        )
    )
    
    // Forward to next stage if passed
    if (results.lightPassed && results.livenessPassed) {
        val candidateFrame = CandidateFrame(
            originalFrame = frame,
            lightScore = results.lightScore,
            livenessScore = results.livenessScore,
            combinedScore = (results.lightScore + results.livenessScore) / 2f,
            processingTime = System.currentTimeMillis()
        )
        
        // Non-blocking send to next stage
        candidateChannel.trySend(candidateFrame)
    }
    
    // Update state manager
    stateManager.updateProcessingResult(ProcessingResult(
        stage1Passed = results.lightPassed && results.livenessPassed,
        stage2Passed = false, // Not processed yet
        stage3Passed = false,
        timestamp = System.currentTimeMillis()
    ))
}

// Stage 2: Segmentation
private suspend fun processStage2(candidate: CandidateFrame) {
    val result = stage2Processor.performSegmentation(candidate.originalFrame)
    
    val warnings = mutableListOf<Warning>()
    if (!result.passed) {
        warnings.add(Warning.SegmentationWarning)
    }
    
    warningManager.updateWarnings(
        stage = ProcessingStage.SEGMENTATION,
        newWarnings = warnings,
        passedChecks = if (result.passed) listOf(Warning.SegmentationWarning) else emptyList()
    )
    
    if (result.passed) {
        val segmentedFrame = SegmentedFrame(
            candidateFrame = candidate,
            detectionResults = result.detectionResults,
            segmentationConfidence = result.confidence,
            croppedBitmap = result.croppedBitmap,
            boundingBox = result.boundingBox
        )
        
        segmentedChannel.trySend(segmentedFrame)
    }
    
    // Update state with combined Stage 1 + Stage 2 results
    stateManager.updateProcessingResult(ProcessingResult(
        stage1Passed = true, // Already passed to get here
        stage2Passed = result.passed,
        stage3Passed = false,
        timestamp = System.currentTimeMillis()
    ))
}

// Stage 3: Quality Analysis
private suspend fun processStage3(segmented: SegmentedFrame) {
    val results = coroutineScope.async(Dispatchers.Default) {
        val blurResult = stage3Processor.checkBlur(segmented.croppedBitmap!!)
        val brightSpotResult = stage3Processor.checkBrightSpots(segmented.croppedBitmap)
        
        Stage3Result(
            blurPassed = blurResult.passed,
            brightSpotPassed = brightSpotResult.passed,
            blurScore = blurResult.confidence,
            brightSpotScore = brightSpotResult.confidence,
            qualityScore = qualityAnalyzer.calculateFinalScore(
                segmented.candidateFrame.combinedScore,
                segmented.segmentationConfidence,
                blurResult.confidence,
                brightSpotResult.confidence
            )
        )
    }.await()
    
    val warnings = mutableListOf<Warning>()
    if (!results.blurPassed) warnings.add(Warning.BlurWarning)
    if (!results.brightSpotPassed) warnings.add(Warning.BrightSpotWarning)
    
    warningManager.updateWarnings(
        stage = ProcessingStage.QUALITY_CHECKS,
        newWarnings = warnings,
        passedChecks = listOfNotNull(
            if (results.blurPassed) Warning.BlurWarning else null,
            if (results.brightSpotPassed) Warning.BrightSpotWarning else null
        )
    )
    
    // Add to final buffer if quality is sufficient
    if (results.blurPassed && results.brightSpotPassed && results.qualityScore > 0.7f) {
        val processedImage = ProcessedImage(
            segmentedFrame = segmented,
            blurScore = results.blurScore,
            brightSpotScore = results.brightSpotScore,
            qualityScore = results.qualityScore,
            processingStage = ProcessingStage.COMPLETED,
            finalBitmap = segmented.croppedBitmap
        )
        
        addToFinalBuffer(processedImage)
    }
    
    stateManager.updateProcessingResult(ProcessingResult(
        stage1Passed = true,
        stage2Passed = true,
        stage3Passed = results.blurPassed && results.brightSpotPassed,
        timestamp = System.currentTimeMillis()
    ))
}
```

---

## Flow Diagrams

### Overall System Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CAMERA2 INPUT LAYER                             │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────┐    30fps    ┌──────────────────────────────────────┐   │
│  │ ImageReader │ ──────────► │         rawFrameFlow                 │   │
│  │ YUV_420_888 │             │    (DROP_OLDEST, capacity=1)         │   │
│  └─────────────┘             └──────────────────────────────────────┘   │
│                                                │                        │
└────────────────────────────────────────────────┼────────────────────────┘
                                                 │
┌────────────────────────────────────────────────▼────────────────────────┐
│                      PROCESSING PIPELINE LAYER                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      STAGE 1 PROCESSOR                          │   │
│  │                                                                  │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │  Light Check    │  │ Liveness Check  │  │ Parallel Async  │  │   │
│  │  │  Histogram      │  │  MediaPipe      │  │  Processing     │  │   │
│  │  │  Analysis       │  │  Face Detection │  │  (~15-20ms)     │  │   │
│  │  │  (~5-10ms)      │  │  (~10-15ms)     │  │                 │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │ ~20fps                            │
│                                     ▼                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                  candidateChannel (capacity=5)                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │ sampled(100ms) → ~10fps           │
│                                     ▼                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      STAGE 2 PROCESSOR                          │   │
│  │                                                                  │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │              YOLO SEGMENTATION                              │ │   │
│  │  │                                                             │ │   │
│  │  │  • TensorFlow Lite Model (43MB)                            │ │   │
│  │  │  • Input: 800x800 RGB                                      │ │   │
│  │  │  • Output: [8 x 13125] detection matrix                    │ │   │
│  │  │  • Confidence threshold: 0.7                               │ │   │
│  │  │  • NMS (IoU threshold: 0.3)                                │ │   │
│  │  │  • Processing time: ~50-100ms                              │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │ ~5-8fps                           │
│                                     ▼                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                  segmentedChannel (capacity=3)                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │ all frames                        │
│                                     ▼                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      STAGE 3 PROCESSOR                          │   │
│  │                                                                  │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │   Blur Check    │  │ Bright Spot     │  │ Parallel Async  │  │   │
│  │  │   Laplacian     │  │ Detection       │  │  Processing     │  │   │
│  │  │   Variance      │  │ Histogram       │  │  (~30-50ms)     │  │   │
│  │  │   (~20ms)       │  │ Analysis        │  │                 │  │   │
│  │  │                 │  │ (~15ms)         │  │                 │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │ ~3-5fps                           │
│                                     ▼                                   │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                 finalBuffer (best 5 images)                     │   │
│  │                      Thread-safe Collection                     │   │
│  └──────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                        STATE MANAGEMENT LAYER                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────┐                    ┌─────────────────────────┐ │
│  │   WarningManager    │                    │     StateManager        │ │
│  │                     │                    │                         │ │
│  │ • Multi-warning     │                    │ • Sliding window        │ │
│  │   coordination      │                    │   analysis (20 frames)  │ │
│  │ • Priority          │                    │ • Debounced transitions │ │
│  │   resolution        │                    │ • State persistence     │ │
│  │ • Temporal          │                    │ • Condition evaluation  │ │
│  │   persistence       │                    │                         │ │
│  └─────────────────────┘                    └─────────────────────────┘ │
│           │                                               │             │
│           ▼                                               ▼             │
│  ┌─────────────────────┐                    ┌─────────────────────────┐ │
│  │  Active Warnings    │                    │      UI State           │ │
│  │  Set<ActiveWarning> │                    │   INITIAL/VALIDATION/   │ │
│  │                     │                    │      SUCCESS            │ │
│  └─────────────────────┘                    └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
                                     │
┌────────────────────────────────────▼────────────────────────────────────┐
│                           UI PRESENTATION LAYER                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌─────────────────────┐                    ┌─────────────────────────┐ │
│  │  CameraViewModel    │                    │    UI Components        │ │
│  │                     │                    │                         │ │
│  │ • StateFlow         │                    │ • Warning Display       │ │
│  │   observers         │                    │ • Progress Indicators   │ │
│  │ • UI state updates  │                    │ • Capture Button State  │ │
│  │ • Warning display   │                    │ • Biometric Overlay     │ │
│  │   coordination      │                    │                         │ │
│  └─────────────────────┘                    └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

### Warning Priority Resolution Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    WARNING PROCESSING FLOW                              │
└─────────────────────────────────────────────────────────────────────────┘

Input: Processing Results from All Stages
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    COLLECT STAGE WARNINGS                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Stage 1: [LowLightWarning, LivenessWarning]                           │
│  Stage 2: [SegmentationWarning]                                        │
│  Stage 3: [BlurWarning, BrightSpotWarning, DistanceRelatedWarning]     │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    UPDATE ACTIVE WARNINGS                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  For each new warning:                                                  │
│    ├─ If warning exists: Update lastSeenAt, increment failures         │
│    └─ If new warning: Create ActiveWarning entry                       │
│                                                                         │
│  For each resolved warning:                                             │
│    ├─ If within minWarningDuration: Keep active                        │
│    └─ If expired: Remove from active set                               │
│                                                                         │
│  Remove expired warnings (lastSeenAt + timeout < currentTime)          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    PRIORITY RESOLUTION                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  Sort activeWarnings by:                                                │
│    1. Priority (DESC): Higher priority number = higher importance      │
│    2. LastSeenAt (DESC): More recent = higher importance               │
│                                                                         │
│  Selection Logic:                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  activeWarnings.sortedWith(                                     │   │
│  │    compareByDescending<ActiveWarning> { it.warning.priority }   │   │
│  │      .thenByDescending { it.lastSeenAt }                        │   │
│  │  ).firstOrNull()                                                │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    EMIT WARNING STATE                                   │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  If no active warnings:                                                 │
│    └─ Emit WarningState.None                                            │
│                                                                         │
│  If active warnings exist:                                              │
│    └─ Emit WarningState.Active(                                         │
│         warnings = allActiveWarnings,                                   │
│         displayWarning = highestPriorityWarning,                       │
│         lastUpdated = currentTime                                       │
│       )                                                                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

Example Scenarios:
─────────────────────────────────────────────────────────────────────────

Scenario 1: Single Stage, Multiple Warnings
Input: Stage3 → [BlurWarning(priority=3), BrightSpotWarning(priority=3)]
Resolution: Display most recent warning (same priority)

Scenario 2: Multi-Stage, Different Priorities  
Input: Stage1 → [LowLightWarning(priority=1)]
       Stage3 → [BlurWarning(priority=3)]
Resolution: Display BlurWarning (higher priority: 3 > 1)

Scenario 3: Warning Persistence
Frame N: LowLightWarning active
Frame N+1: Light check passes, but warning within minWarningDuration
Frame N+2: Warning duration exceeded
Resolution: Remove LowLightWarning from active set

Scenario 4: Temporal Tie-Breaking
Input: [BlurWarning(priority=3, lastSeen=100ms ago)]
       [BrightSpotWarning(priority=3, lastSeen=50ms ago)]  
Resolution: Display BrightSpotWarning (more recent)
```

### UI State Transition Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      UI STATE MANAGEMENT FLOW                           │
└─────────────────────────────────────────────────────────────────────────┘

Input: ProcessingResult from Pipeline
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    UPDATE STATE HISTORY                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  stateHistory.add(ProcessingResult {                                    │
│    stage1Passed: Boolean,                                               │
│    stage2Passed: Boolean,                                               │
│    stage3Passed: Boolean,                                               │
│    timestamp: Long                                                      │
│  })                                                                     │
│                                                                         │
│  Maintain sliding window of last 20 processing results                  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    CALCULATE SUCCESS RATES                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  recentResults = stateHistory.getLast(10)                              │
│                                                                         │
│  stage1SuccessRate = recentResults.count { it.stage1Passed } / 10.0f    │
│  stage2SuccessRate = recentResults.count { it.stage2Passed } / 10.0f    │
│  stage3SuccessRate = recentResults.count { it.stage3Passed } / 10.0f    │
│  bufferSize = finalBuffer.size                                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    DETERMINE SUGGESTED STATE                            │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  suggestedState = when {                                                │
│    stage1SuccessRate < 0.3 → UIState.INITIAL                          │
│    stage1SuccessRate > 0.7 &&                                          │
│    stage3SuccessRate > 0.6 &&                                          │
│    bufferSize >= 3 → UIState.SUCCESS                                   │
│    stage1SuccessRate > 0.7 → UIState.VALIDATION                       │
│    else → currentState // No clear direction                            │
│  }                                                                      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    STATE TRANSITION LOGIC                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  If suggestedState == currentState:                                     │
│    └─ Cancel any pending state changes                                  │
│                                                                         │
│  If suggestedState != currentState:                                     │
│    ├─ Find required transition duration                                 │
│    ├─ If same as pendingState:                                          │
│    │   └─ Check if duration elapsed → commit change                     │
│    └─ If different pendingState:                                        │
│        └─ Start new transition timer                                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    DEBOUNCING MECHANISM                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  State Transition Requirements:                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ INITIAL → VALIDATION    : 1000ms consistent                    │   │
│  │ INITIAL → SUCCESS       : 2000ms consistent                    │   │
│  │ VALIDATION → SUCCESS    : 500ms consistent                     │   │
│  │ VALIDATION → INITIAL    : 2000ms consistent                    │   │
│  │ SUCCESS → VALIDATION    : 1500ms consistent                    │   │
│  │ SUCCESS → INITIAL       : 3000ms consistent                    │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│  Debouncing prevents rapid UI state changes from noisy processing      │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    EMIT UI STATE UPDATE                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  _uiState.value = newState                                              │
│                                                                         │
│  UI Components observe this StateFlow and update:                       │
│  ├─ Progress indicators                                                  │
│  ├─ Capture button state                                                │
│  ├─ Biometric overlay animations                                        │
│  └─ Warning display coordination                                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

State Transition Examples:
─────────────────────────────────────────────────────────────────────────

Example 1: Stable Operation
Frames 1-10: All pass Stage 1, fail Stage 2 → VALIDATION (consistent)
Frames 11-15: Pass all stages, buffer=3 → SUCCESS after 500ms

Example 2: Oscillating Conditions  
Frame N: Pass Stage 1 → Suggest VALIDATION, start timer (1000ms needed)
Frame N+1: Fail Stage 1 → Suggest INITIAL, cancel VALIDATION timer
Frame N+2: Pass Stage 1 → Suggest VALIDATION, restart timer
... continues until 1000ms of consistent VALIDATION suggestion

Example 3: Rapid Recovery
Current: INITIAL state
Frames 1-5: Pass all stages quickly → Suggest SUCCESS
Timer: Need 2000ms consistency for INITIAL→SUCCESS
Result: Wait for sustained good performance before changing UI
```

---

## Performance Considerations

### Processing Load Distribution

```kotlin
// CPU/Memory/GPU usage optimization
class ProcessingLoadBalancer {
    private val stage1Executor = Executors.newFixedThreadPool(2) // Light CPU work
    private val stage2Executor = Executors.newSingleThreadExecutor() // Heavy GPU work  
    private val stage3Executor = Executors.newFixedThreadPool(2) // Medium CPU work
    
    private val memoryMonitor = MemoryMonitor()
    private val thermalMonitor = ThermalMonitor()
    
    fun adaptProcessingRate() {
        val memoryPressure = memoryMonitor.getCurrentPressure()
        val thermalState = thermalMonitor.getCurrentState()
        
        when {
            memoryPressure > 0.8f || thermalState == SEVERE -> {
                // Reduce processing rate dramatically
                candidateSamplingInterval = 200L // 5fps → 5fps
                stage2ProcessingEnabled = false
            }
            memoryPressure > 0.6f || thermalState == MODERATE -> {
                // Moderate reduction
                candidateSamplingInterval = 150L // 10fps → 6.7fps
                stage2ProcessingEnabled = true
            }
            else -> {
                // Normal operation
                candidateSamplingInterval = 100L // 10fps
                stage2ProcessingEnabled = true
            }
        }
    }
}
```

### Memory Management

```kotlin
class MemoryEfficientImageProcessor {
    private val bitmapPool = BitmapPool(maxSize = 50) // Reuse bitmaps
    private val byteArrayPool = ByteArrayPool(maxSize = 20) // Reuse byte arrays
    
    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        
        try {
            // Reuse byte array from pool
            val byteArray = byteArrayPool.acquire(image.width * image.height * 3)
            image.toByteArray(byteArray) // In-place conversion
            
            // Process with pooled resources
            frameFlow.tryEmit(CameraFrame(byteArray, image.width, image.height))
            
        } finally {
            image.close() // Close Image ASAP
        }
    }
    
    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap.byteCount <= MAX_BITMAP_SIZE) {
            bitmapPool.release(bitmap)
        } else {
            bitmap.recycle()
        }
    }
}
```

### Benchmarking and Monitoring

```kotlin
class PerformanceMonitor {
    data class StageMetrics(
        val avgProcessingTime: Float,
        val successRate: Float,
        val frameDropRate: Float,
        val queueDepth: Int
    )
    
    private val stage1Metrics = MetricsCollector("Stage1")
    private val stage2Metrics = MetricsCollector("Stage2") 
    private val stage3Metrics = MetricsCollector("Stage3")
    
    fun reportStageMetrics(): Map<String, StageMetrics> {
        return mapOf(
            "stage1" to stage1Metrics.getMetrics(),
            "stage2" to stage2Metrics.getMetrics(), 
            "stage3" to stage3Metrics.getMetrics()
        )
    }
    
    // Real-time performance dashboard for debugging
    fun logPerformanceStats() {
        val metrics = reportStageMetrics()
        Log.d("Performance", """
            Stage 1: ${metrics["stage1"]?.avgProcessingTime}ms avg, ${metrics["stage1"]?.successRate}% success
            Stage 2: ${metrics["stage2"]?.avgProcessingTime}ms avg, ${metrics["stage2"]?.successRate}% success  
            Stage 3: ${metrics["stage3"]?.avgProcessingTime}ms avg, ${metrics["stage3"]?.successRate}% success
            Buffer: ${finalBuffer.size}/5 images collected
        """.trimIndent())
    }
}
```

---

## Testing Strategy

### Unit Testing

```kotlin
@Test
fun `warning manager resolves priorities correctly`() {
    val warningManager = WarningManager()
    
    // Add multiple warnings with different priorities
    warningManager.updateWarnings(
        stage = ProcessingStage.INITIAL_CHECKS,
        newWarnings = listOf(Warning.LowLightWarning), // priority = 1
        passedChecks = emptyList()
    )
    
    warningManager.updateWarnings(
        stage = ProcessingStage.QUALITY_CHECKS,  
        newWarnings = listOf(Warning.BlurWarning), // priority = 3
        passedChecks = emptyList()
    )
    
    val state = warningManager.warningState.value as WarningState.Active
    assertEquals(Warning.BlurWarning.id, state.displayWarning.id) // Higher priority
}

@Test 
fun `state manager debounces rapid changes`() = runTest {
    val stateManager = StateManager()
    
    // Simulate rapid state suggestions
    repeat(5) {
        stateManager.updateProcessingResult(ProcessingResult(
            stage1Passed = true,
            stage2Passed = false, 
            stage3Passed = false,
            timestamp = System.currentTimeMillis()
        ))
    }
    
    // Should remain in INITIAL state (not enough consistency for VALIDATION)
    assertEquals(UIState.INITIAL, stateManager.currentState)
    
    // Fast-forward time and add consistent results
    advanceTimeBy(1500L)
    repeat(10) {
        stateManager.updateProcessingResult(ProcessingResult(
            stage1Passed = true,
            stage2Passed = false,
            stage3Passed = false, 
            timestamp = System.currentTimeMillis()
        ))
    }
    
    // Now should transition to VALIDATION
    assertEquals(UIState.VALIDATION, stateManager.currentState)
}
```

### Integration Testing

```kotlin
@Test
fun `end to end pipeline processes frames correctly`() = runTest {
    val mockImageReader = mockk<ImageReader>()
    val processor = ImageProcessor(mockViewModel, this)
    
    // Simulate camera frames
    val testFrames = generateTestFrames(count = 100)
    
    testFrames.forEach { frame ->
        processor.onImageAvailable(mockImageReader)
    }
    
    // Allow processing time
    advanceTimeBy(5000L)
    
    // Verify results
    val finalImages = processor.getProcessedImages()
    assertTrue("Should collect some high-quality images", finalImages.isNotEmpty())
    assertTrue("Should not exceed buffer limit", finalImages.size <= 5)
    
    finalImages.forEach { image ->
        assertTrue("Quality score should be high", image.qualityScore > 0.7f)
    }
}
```

### Performance Testing

```kotlin
@Test
fun `pipeline maintains target frame rates under load`() = runTest {
    val processor = ImageProcessor(mockViewModel, this)
    val performanceMonitor = PerformanceMonitor()
    
    // Simulate high frame rate input
    val startTime = System.currentTimeMillis()
    repeat(300) { // 10 seconds at 30fps
        processor.onImageAvailable(mockImageReader) 
        delay(33) // ~30fps
    }
    
    val endTime = System.currentTimeMillis()
    val metrics = performanceMonitor.reportStageMetrics()
    
    // Verify performance targets
    assertTrue("Stage 1 should process >20fps", 
        metrics["stage1"]?.avgProcessingTime ?: 0f < 50f)
    assertTrue("Stage 2 should process >5fps",
        metrics["stage2"]?.avgProcessingTime ?: 0f < 200f)
    assertTrue("Stage 3 should process >3fps", 
        metrics["stage3"]?.avgProcessingTime ?: 0f < 333f)
        
    assertTrue("Overall processing should complete within reasonable time",
        endTime - startTime < 12000) // Allow 2 extra seconds
}
```

---

## Summary

This architecture provides:

1. **High Performance**: Multi-stage pipeline with intelligent sampling prevents frame drops
2. **Robust Warning Management**: Priority-based, temporally-aware warning display
3. **Stable UI Experience**: Debounced state transitions prevent UI flickering
4. **Scalable Design**: Modular components allow for easy testing and optimization
5. **Memory Efficient**: Object pooling and careful resource management
6. **Adaptive Processing**: Performance monitoring and automatic rate adjustment

The implementation balances real-time processing requirements with user experience stability, ensuring both technical performance and usable interface behavior. 