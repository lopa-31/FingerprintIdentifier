package com.example.security

import android.os.Build
import androidx.annotation.RequiresApi
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RequiresApi(Build.VERSION_CODES.M)
class DecryptionManager {

    fun decryptData(encryptedData: ByteArray, encryptedAesKey: ByteArray, rsaPrivateKey: PrivateKey, iv: ByteArray): ByteArray {
        // 1. Decrypt AES key with RSA
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey)
        val decryptedAesKeyBytes = rsaCipher.doFinal(encryptedAesKey)
        val aesKey: SecretKey = SecretKeySpec(decryptedAesKeyBytes, "AES")

        // 2. Decrypt data with AES
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmParameterSpec = GCMParameterSpec(128, iv)
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmParameterSpec)
        return aesCipher.doFinal(encryptedData)
    }
}