package com.yair.printer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Finds the printer by name instead of by address. The address comes from DHCP
 * and moves whenever the router is restarted, which would otherwise break the
 * app silently; the mDNS name the printer advertises does not move.
 *
 * eSCL scanners advertise `_uscan._tcp`, so finding that service finds exactly
 * the thing this app needs rather than any printer on the network.
 */
object Discovery {

    private const val SERVICE_TYPE = "_uscan._tcp."

    data class Found(val host: String, val name: String)

    /** Returns the first scanner that answers, or null if none does in time. */
    suspend fun find(context: Context, timeoutMs: Long = 6_000): Found? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null

        // Discovery runs until it is stopped, and a resolved service does not
        // stop it, so the listener is held out here and shut down in `finally`
        // whichever way this returns: found, timed out, or failed.
        var started: NsdManager.DiscoveryListener? = null

        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val resolve = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, code: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val address = info.host?.hostAddress ?: return
                            if (cont.isActive) {
                                cont.resume(Found(address, info.serviceName ?: address))
                            }
                        }
                    }

                    val listener = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(type: String?) = Unit
                        override fun onDiscoveryStopped(type: String?) = Unit
                        override fun onStopDiscoveryFailed(type: String?, code: Int) = Unit
                        override fun onServiceLost(info: NsdServiceInfo?) = Unit

                        override fun onStartDiscoveryFailed(type: String?, code: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceFound(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(info, resolve)
                        }
                    }

                    started = listener

                    @Suppress("DEPRECATION")
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            started?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }
}
