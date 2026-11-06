package app.aether.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The listener's signed-in YouTube Music session: the cookies Google's own sign-in page left (AETHER
 * never sees a password). Sealed at rest with a key that lives in the phone's Keystore and never
 * leaves it, so the session can't be read off the phone's storage. Signing out forgets it.
 */
class Account(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val _cookies = MutableStateFlow(open())

    /** The session's `Cookie` header, or null when signed out. */
    val cookies: StateFlow<String?> = _cookies.asStateFlow()

    fun signIn(cookieHeader: String) {
        prefs.edit().putString(SEALED, seal(cookieHeader)).apply()
        _cookies.value = cookieHeader
    }

    fun signOut() {
        prefs.edit().remove(SEALED).apply()
        _cookies.value = null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The stored session, or null if there's none or it can't be opened (a new phone, a reset Keystore). */
    private fun open(): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(SEALED, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV)) }
        String(cipher.doFinal(sealed, IV, sealed.size - IV), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aether.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
        const val SEALED = "sealed"
    }
}
