package com.example.fingerprint_identifier.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionManager(
    private val context: Context,
    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>>,
    private val onPermissionsGranted: () -> Unit,
    private val onPermissionsDenied: () -> Unit
) {

    private val requiredPermissions = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    } else {
        arrayOf(android.Manifest.permission.CAMERA)
    }

    fun checkAndRequestPermissions() {
        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            onPermissionsGranted()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    fun handlePermissionsResult(grants: Map<String, Boolean>) {
        if (grants.all { it.value }) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied()
        }
    }
} 