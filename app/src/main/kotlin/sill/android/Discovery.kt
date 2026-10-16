package sill.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** How a Mac's home door speaks, from its TXT record's `p` (Pairing.swift's HomeDoorTXT). */
enum class Door { PLAIN, PAIRING_REQUIRED, OPEN }

/** A Mac the network lists: its Bonjour service, resolved. */
data class FoundMac(
    val serviceName: String,
    val host: String,
    val port: Int,
    val door: Door,
    /** The recognition tag, `r`. */
    val tag: String?,
)

/**
 * Browses `_sill._tcp` with NsdManager and resolves each service. Callbacks arrive on the main
 * thread. Services come and go as Macs register; a resolve follows the TXT record, whose tag
 * changes at each registration.
 */
class Discovery(context: Context, private val onChange: (List<FoundMac>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, FoundMac>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = HashMap<String, Any>()
    private val executor = Executors.newSingleThreadExecutor()

    var failed: String? = null
        private set

    fun start() {
        if (listener != null) return
        failed = null
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                main.post { failed = "Couldn't look for Macs (error $errorCode)."; listener = null; publish() }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { resolve(info) } }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    found.remove(info.serviceName)
                    unregisterInfo(info.serviceName)
                    publish()
                }
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        for (name in infoCallbacks.keys.toList()) unregisterInfo(name)
        pending.clear()
        resolving = false
        found.clear()
    }

    /** Looks again: what was found is forgotten and every service resolved afresh. */
    fun restart() { stop(); publish(); start() }

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            if (infoCallbacks.containsKey(info.serviceName)) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    main.post { infoCallbacks.remove(info.serviceName) }
                }
                override fun onServiceUpdated(updated: NsdServiceInfo) { main.post { record(updated) } }
                override fun onServiceLost() { main.post { found.remove(info.serviceName); publish() } }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            infoCallbacks[info.serviceName] = cb
            try { nsd.registerServiceInfoCallback(info, executor, cb) } catch (e: Exception) {
                infoCallbacks.remove(info.serviceName)
                Log.w(TAG, "resolve ${info.serviceName}: $e")
            }
        } else {
            pending.addLast(info)
            next()
        }
    }

    private fun unregisterInfo(name: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = infoCallbacks.remove(name) as? NsdManager.ServiceInfoCallback ?: return
            try { nsd.unregisterServiceInfoCallback(cb) } catch (_: Exception) {}
        }
    }

    // Before Android 14, one resolve at a time (a second fails with FAILURE_ALREADY_ACTIVE).
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                main.post { resolving = false; next() }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post { record(resolved); resolving = false; next() }
            }
        })
    }

    private fun record(info: NsdServiceInfo) {
        val address = address(info) ?: return
        if (info.port <= 0) return
        val txt = info.attributes ?: emptyMap()
        fun value(k: String): ByteArray? = txt[k] ?: txt[k.uppercase()]
        val door = when {
            !txt.containsKey("p") && !txt.containsKey("P") -> Door.PLAIN
            value("p")?.let { String(it) } == "0" -> Door.OPEN
            else -> Door.PAIRING_REQUIRED
        }
        val tag = value("r")?.let { String(it) }
        found[info.serviceName] = FoundMac(info.serviceName, address, info.port, door, tag)
        publish()
    }

    @Suppress("DEPRECATION")
    private fun address(info: NsdServiceInfo): String? {
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val pick = all.firstOrNull { it is Inet4Address } ?: all.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }
            ?: all.firstOrNull() ?: return null
        return pick.hostAddress?.let { a ->
            // A link-local IPv6 address needs its scope; the platform gives it as %iface.
            if (pick is Inet6Address && pick.isLinkLocalAddress && pick.scopedInterface != null && !a.contains('%'))
                "$a%${pick.scopedInterface!!.name}" else a
        }
    }

    private fun publish() = onChange(found.values.sortedBy { it.serviceName.lowercase() })

    companion object {
        const val SERVICE_TYPE = "_sill._tcp"
        private const val TAG = "SillDiscovery"
    }
}
