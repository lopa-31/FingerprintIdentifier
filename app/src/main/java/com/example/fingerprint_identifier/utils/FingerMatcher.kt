package com.example.fingerprint_identifier.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File

data class MatchResult(
    val bestMatchPath: String,
    val bestMatchScore: Int
)

object FingerMatcher {

    private const val MATCH_THRESHOLD = 30 // Empirical value, may need tuning

    fun findBestMatch(context: Context, queryImageUri: Uri): MatchResult? {
        val orb = ORB.create()
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

        val queryImageBitmap = BitmapFactory.decodeStream(context.contentResolver.openInputStream(queryImageUri))
        val queryMat = Mat()
        org.opencv.android.Utils.bitmapToMat(queryImageBitmap, queryMat)
        Imgproc.cvtColor(queryMat, queryMat, Imgproc.COLOR_RGBA2GRAY)

        val queryKeyPoints = MatOfKeyPoint()
        val queryDescriptors = Mat()
        orb.detectAndCompute(queryMat, Mat(), queryKeyPoints, queryDescriptors)

        val imageDir = File(context.getExternalFilesDir(null)?.absolutePath?.replace("files", "Pictures"), "Finger Data")
        if (!imageDir.exists() || !imageDir.isDirectory) {
            return null
        }
        
        var bestMatchScore = 0
        var bestMatchPath: String? = null

        imageDir.listFiles { file -> file.isFile && file.extension == "jpg" && !file.name.contains("Palm") }?.forEach { file ->
            val trainImageBitmap = BitmapFactory.decodeFile(file.absolutePath)
            val trainMat = Mat()
            org.opencv.android.Utils.bitmapToMat(trainImageBitmap, trainMat)
            Imgproc.cvtColor(trainMat, trainMat, Imgproc.COLOR_RGBA2GRAY)
            
            val trainKeyPoints = MatOfKeyPoint()
            val trainDescriptors = Mat()
            orb.detectAndCompute(trainMat, Mat(), trainKeyPoints, trainDescriptors)
            
            val matches = MatOfDMatch()
            matcher.match(queryDescriptors, trainDescriptors, matches)
            
            val goodMatches = matches.toList().count { it.distance < 70 }

            if (goodMatches > bestMatchScore) {
                bestMatchScore = goodMatches
                bestMatchPath = file.name
            }
        }

        queryMat.release()
        queryDescriptors.release()
        
        return if (bestMatchScore > MATCH_THRESHOLD) {
            MatchResult(bestMatchPath!!, bestMatchScore)
        } else {
            null
        }
    }
} 