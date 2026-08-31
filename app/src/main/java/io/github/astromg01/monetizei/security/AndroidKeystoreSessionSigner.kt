package io.github.astromg01.monetizei.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.astromg01.monetizei.protocol.KeyIds
import io.github.astromg01.monetizei.protocol.SessionProtocol
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AndroidKeystoreSessionSigner {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKeyPair()
    }

    fun keyId(): String = KeyIds.fromEncodedPublicKey(publicKeyEncoded())

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKeyEncoded())

    fun signBase64(payload: ByteArray): String {
        val entry = privateKeyEntry()
        val signature = Signature.getInstance(SessionProtocol.SIGNATURE_ALGORITHM).apply {
            initSign(entry.privateKey)
            update(payload)
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun publicKeyEncoded(): ByteArray = privateKeyEntry().certificate.publicKey.encoded

    private fun privateKeyEntry(): KeyStore.PrivateKeyEntry =
        keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Monetizei session signing key is unavailable")

    private fun ensureKeyPair() {
        if (keyStore.containsAlias(KEY_ALIAS)) return

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generateKeyPair()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "monetizei_session_signing_v1"
    }
}
