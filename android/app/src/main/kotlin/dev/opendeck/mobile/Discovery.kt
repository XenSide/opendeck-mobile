package dev.opendeck.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.opendeck.core.Endpoint
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class DiscoveredHost(val workstationID: String, val name: String, val endpoints: List<Endpoint>)

/** mDNS discovery of `_vstreamdeck2._tcp` (spec §7.1). All state is touched on the main thread. */
class Discovery(
    context: Context,
    private val onHosts: (List<DiscoveredHost>) -> Unit,
    private val onScanning: (Boolean) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val found = LinkedHashMap<String, DiscoveredHost>() // by service instance name
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val timeout = Runnable { stop() }

    fun start() {
        stop()
        found.clear()
        publish()
        multicastLock = wifi?.createMulticastLock("opendeck-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
                main.post { if (listener === this) stop() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (listener === this) enqueue(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        onScanning(true)
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices", e)
            stop()
            return
        }
        main.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        listener?.let { l -> runCatching { nsd.stopServiceDiscovery(l) } }
        listener = null
        resolveQueue.clear()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        onScanning(false)
    }

    private fun enqueue(info: NsdServiceInfo) {
        resolveQueue.addLast(info)
        pump()
    }

    // Older platforms allow only one resolve at a time, so resolve serially.
    @Suppress("DEPRECATION")
    private fun pump() {
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; pump() }
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post { resolving = false; add(info); pump() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService", e)
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun add(info: NsdServiceInfo) {
        val ws = info.attributes[TXT_WORKSTATION]?.toString(Charsets.UTF_8)?.trim()
        if (ws.isNullOrEmpty()) return // not a VSD2 server
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val endpoints = addresses
            .filterNot { it is Inet6Address && it.isLinkLocalAddress } // zone IDs don't survive URLs
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { a -> a.hostAddress?.let { Endpoint(it, info.port) } }
        if (endpoints.isEmpty() || info.port !in 1..65535) return
        found[info.serviceName] = DiscoveredHost(ws, info.serviceName, endpoints)
        publish()
    }

    private fun publish() {
        val merged = found.values.groupBy { it.workstationID }.map { (_, list) ->
            list.first().copy(endpoints = list.flatMap { it.endpoints }.distinct())
        }
        onHosts(merged)
    }

    companion object {
        const val SERVICE_TYPE = "_vstreamdeck2._tcp"
        const val TXT_WORKSTATION = "VSDSV"
        const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val TAG = "OpenDeckDiscovery"
    }
}
