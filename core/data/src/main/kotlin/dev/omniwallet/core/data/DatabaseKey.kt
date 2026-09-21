package dev.omniwallet.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher passphrase for the library database.
 *
 * A random 32-byte passphrase is generated once and then stored wrapped: it is
 * encrypted with an AES-256-GCM key held in the Android Keystore, which never
 * leaves hardware-backed storage on devices that have it. Only the wrapped form
 * touches the filesystem.
 *
 * ## What this does and does not protect against
 *
 * Worth being exact, because "encrypted" invites more confidence than it earns.
 *
 * It protects the database **at rest, as a file**. Someone who obtains a copy
 * of the app's data directory -- a filesystem image, a misconfigured backup, a
 * stolen unlocked-bootloader device -- gets ciphertext and a wrapped key they
 * cannot unwrap off-device, because the Keystore key is not exportable.
 *
 * It does **not** protect against an attacker running code as this app on an
 * unlocked device, because such an attacker can simply ask the Keystore to
 * unwrap, exactly as the app does. Defeating that needs the passphrase derived
 * from something only the user knows, which means a master password and the
 * accompanying "forget it and the data is gone" contract. That is a product
 * decision, not an implementation detail, so it is not being made quietly here.
 *
 * The biometric lock is a separate, complementary control: it guards the UI,
 * not the bytes.
 */
@Singleton
class DatabaseKey @Inject constructor(
    private val context: Context,
) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "omniwallet.database.key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val PASSPHRASE_BYTES = 32
        private const val WRAPPED_KEY_FILE = "db.key"
    }

    /** The database passphrase, creating and storing one on first call. */
    fun getOrCreate(): ByteArray {
        val file = File(context.filesDir, WRAPPED_KEY_FILE)
        return if (file.exists()) {
            runCatching { unwrap(file.readText()) }.getOrElse {
                // The wrapped key is unreadable: the Keystore entry was lost,
                // which happens if the user removes their screen lock on some
                // devices. The database it opened is unrecoverable, so start
                // again rather than crash on every launch forever. The library
                // rebuilds from the device on the next scan; only local
                // metadata is lost, and losing it beats being permanently
                // unable to open the app.
                context.getDatabasePath(OmniWalletDatabase.NAME).delete()
                createAndStore(file)
            }
        } else {
            createAndStore(file)
        }
    }

    private fun createAndStore(file: File): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        file.writeText(wrap(passphrase))
        return passphrase
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication to unwrap.
                // The database has to be readable before the biometric gate can
                // render anything useful, and tying the key to biometrics would
                // also destroy the library whenever the user re-enrolls a
                // fingerprint. The lock guards the UI; this guards the file.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(passphrase: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(passphrase)
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun unwrap(stored: String): ByteArray {
        val (ivPart, cipherPart) = stored.split(":", limit = 2)
        val iv = Base64.decode(ivPart, Base64.NO_WRAP)
        require(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(Base64.decode(cipherPart, Base64.NO_WRAP))
    }
}
