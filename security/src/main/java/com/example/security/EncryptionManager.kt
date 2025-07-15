package com.example.security

import android.os.Build
import androidx.annotation.RequiresApi
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RequiresApi(Build.VERSION_CODES.M)
class EncryptionManager {

    fun encryptData(data: ByteArray, aesKey: SecretKey, rsaPublicKey: PublicKey): Pair<ByteArray, ByteArray> {
        // 1. Encrypt data with AES
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val encryptedData = aesCipher.doFinal(data)
        val iv = aesCipher.iv

        // 2. Encrypt AES key with RSA
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaPublicKey)
        val encryptedAesKey = rsaCipher.doFinal(aesKey.encoded)

        return Pair(encryptedData, encryptedAesKey)
    }
}