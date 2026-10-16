package sill.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import sill.protocol.DeviceIdentity
import sill.protocol.Json
import sill.protocol.PairedMac
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * What this device keeps: its own key (made once in Android Keystore, which never lets it out; the
 * Macs it paired with pin it) and the Macs it paired with (app-private storage, no backups).
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sill", Context.MODE_PRIVATE)

    val identity: DeviceIdentity by lazy { loadIdentity() }

    @Synchronized
    private fun loadIdentity(): DeviceIdentity {
        // Android Keystore first: the key is made there and never leaves it.
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(KEY_ALIAS) && prefs.getString("deviceKey", null) == null) {
                val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                g.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .build())
                g.generateKeyPair()
            }
            if (ks.containsAlias(KEY_ALIAS)) {
                val priv = ks.getKey(KEY_ALIAS, null) as PrivateKey
                val pub = ks.getCertificate(KEY_ALIAS).publicKey
                return DeviceIdentity.fromKeys(priv, pub)
            }
        } catch (e: Exception) {
            android.util.Log.w("SillStore", "Keystore unavailable, using app storage: $e")
        }
        val priv = prefs.getString("deviceKey", null)
        val pub = prefs.getString("devicePublicKey", null)
        if (priv != null && pub != null) {
            try {
                return DeviceIdentity.load(Base64.decode(priv, Base64.NO_WRAP), Base64.decode(pub, Base64.NO_WRAP))
            } catch (_: Exception) {
                // Unreadable: make a new one (every Mac will ask to pair again).
            }
        }
        val id = DeviceIdentity.generate()
        prefs.edit()
            .putString("deviceKey", Base64.encodeToString(id.privateKeyBytes(), Base64.NO_WRAP))
            .putString("devicePublicKey", Base64.encodeToString(id.publicKeyBytes(), Base64.NO_WRAP))
            .apply()
        return id
    }

    /** The name a Mac shows for this device (its Devices list, its log). */
    val deviceName: String
        get() = prefs.getString("deviceName", null)
            ?: Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)

    @Synchronized
    fun pairedMacs(): List<PairedMac> {
        val text = prefs.getString("pairedMacs", null) ?: return emptyList()
        val list = try { Json.parse(text) as? List<*> } catch (_: Exception) { null } ?: return emptyList()
        return list.mapNotNull { e ->
            @Suppress("UNCHECKED_CAST")
            (e as? Map<String, Any?>)?.let { PairedMac.fromJson(it) }
        }
    }

    @Synchronized
    fun save(mac: PairedMac) {
        val others = pairedMacs().filter { it.macID != mac.macID }
        write(others + mac)
    }

    @Synchronized
    fun forget(macID: String) = write(pairedMacs().filter { it.macID != macID })

    @Synchronized
    fun forgetAll() = write(emptyList())

    companion object { private const val KEY_ALIAS = "sill-device" }

    private fun write(list: List<PairedMac>) {
        prefs.edit().putString("pairedMacs", Json.write(list.map { it.toJson() })).apply()
    }

    /** Addresses typed in "Connect by address", newest first. */
    fun recentAddresses(): List<String> =
        prefs.getString("recentAddresses", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberAddress(a: String) {
        val list = (listOf(a) + recentAddresses().filter { it != a }).take(5)
        prefs.edit().putString("recentAddresses", list.joinToString("\n")).apply()
    }
}
