package com.sodre90.cmuxremote.data

/**
 * The client half of a Firebase configuration, handed over by the bridge at
 * pairing (mirrors wire.FCMClientConfig) so this app can initialise FCM at
 * runtime instead of being rebuilt with a google-services.json baked into its
 * resources.
 *
 * None of these four values is a secret -- Firebase ships all of them in the
 * clear inside every push-enabled APK, and none of them authorises sending.
 * They are stored with everything else in Settings' EncryptedSharedPreferences
 * only because that is where this app keeps connection state, not because they
 * need protecting.
 *
 * Not slot-scoped: Firebase initialises once per process, so a single config
 * has to serve both connection slots. The most recent pairing wins.
 */
data class FcmClientConfig(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val senderId: String,
)
