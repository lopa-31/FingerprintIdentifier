package com.example.fingerprint_identifier.utils

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmark

object LandmarkUtils {
    private val fingerLandmarkIndexes = listOf(
        // Thumb
        listOf(
            HandLandmark.THUMB_MCP,
            HandLandmark.THUMB_IP,
            HandLandmark.THUMB_TIP,
            HandLandmark.WRIST // Include wrist to get a better center
        ),
        // Index
        listOf(
            HandLandmark.INDEX_FINGER_MCP,
            HandLandmark.INDEX_FINGER_PIP,
            HandLandmark.INDEX_FINGER_DIP,
            HandLandmark.INDEX_FINGER_TIP
        ),
        // Middle
        listOf(
            HandLandmark.MIDDLE_FINGER_MCP,
            HandLandmark.MIDDLE_FINGER_PIP,
            HandLandmark.MIDDLE_FINGER_DIP,
            HandLandmark.MIDDLE_FINGER_TIP
        ),
        // Ring
        listOf(
            HandLandmark.RING_FINGER_MCP,
            HandLandmark.RING_FINGER_PIP,
            HandLandmark.RING_FINGER_DIP,
            HandLandmark.RING_FINGER_TIP
        ),
        // Pinky
        listOf(
            HandLandmark.PINKY_MCP,
            HandLandmark.PINKY_PIP,
            HandLandmark.PINKY_DIP,
            HandLandmark.PINKY_TIP
        )
    )

    fun getFingerLandmarks(allLandmarks: List<NormalizedLandmark>, fingerIndex: Int): List<NormalizedLandmark> {
        if (fingerIndex < 0 || fingerIndex >= fingerLandmarkIndexes.size || allLandmarks.isEmpty()) {
            return emptyList()
        }
        return fingerLandmarkIndexes[fingerIndex].map { index -> allLandmarks[index] }
    }
} 