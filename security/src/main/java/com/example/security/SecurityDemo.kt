package com.example.security

import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.charset.Charset
import javax.crypto.Cipher

@RequiresApi(Build.VERSION_CODES.M)
class SecurityDemo {

    private val keyManager = KeyManager()
    private val encryptionManager = EncryptionManager()
    private val decryptionManager = DecryptionManager()

    fun demonstrateEncryption() {
        val originalText = "This is a secret message."
        val dataToEncrypt = originalText.toByteArray(Charset.defaultCharset())

        // 1. Get or create the keys
        val aesKey = keyManager.getOrCreateAesKey()
        val rsaKeyPair = keyManager.getOrCreateRsaKeyPair()

        // 2. Encrypt the data
        val (encryptedData, encryptedAesKey) = encryptionManager.encryptData(dataToEncrypt, aesKey, rsaKeyPair.public)

        // For demonstration, let's get the IV. In a real app, you'd store this with the encrypted data.
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey)
        val iv = aesCipher.iv

        // 3. Decrypt the data
        val decryptedData = decryptionManager.decryptData(encryptedData, encryptedAesKey, rsaKeyPair.private, iv)

        val decryptedText = String(decryptedData, Charset.defaultCharset())

        println("Original Text: $originalText")
        println("Decrypted Text: $decryptedText")
    }
}