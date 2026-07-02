package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The phone's own X25519 identity keypair, generated once on first use and
 * persisted thereafter. Encrypted at rest, matching [com.sodre90.cmuxremote.data.Settings].
 */
class Identity(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val privateKey: ByteArray
    val publicKey: ByteArray

    init {
        val existingPriv = prefs.getString(KEY_PRIVATE, null)
        if (existingPriv != null) {
            privateKey = Base64.decode(existingPriv, Base64.NO_WRAP)
            publicKey = Base64.decode(prefs.getString(KEY_PUBLIC, null), Base64.NO_WRAP)
        } else {
            val (priv, pub) = generateX25519KeyPair()
            prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(priv, Base64.NO_WRAP))
                .putString(KEY_PUBLIC, Base64.encodeToString(pub, Base64.NO_WRAP))
                .apply()
            privateKey = priv
            publicKey = pub
        }
    }

    private companion object {
        const val PREFS_NAME = "cmux_e2e_identity"
        const val KEY_PRIVATE = "private_key_b64"
        const val KEY_PUBLIC = "public_key_b64"
    }
}
