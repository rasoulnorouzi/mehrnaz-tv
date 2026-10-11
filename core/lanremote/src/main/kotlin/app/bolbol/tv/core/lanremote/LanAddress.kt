package app.bolbol.tv.core.lanremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4 address of this device, with the name of the interface that carries it (may be unknown). */
internal data class LanCandidate(val interfaceName: String?, val address: String)

/**
 * Chooses the address the phone should connect to. Pure: the caller injects the candidate list, best-guess first
 * (the default network's addresses before the others).
 *
 * Rejected: anything that is not a dotted-quad IPv4 (IPv6 included), loopback 127/8, unspecified 0/8, link-local
 * 169.254/16, multicast and reserved 224/3, and interfaces that cannot be the home LAN (loopback, tunnels, VPN,
 * cellular, USB tethering, containers). Among the rest, private ranges (192.168/16, 10/8, 172.16/12) win over other
 * addresses (e.g. carrier-grade NAT 100.64/10); the first candidate wins within a class.
 */
internal object LanAddressSelector {
    private val rejectedInterfacePrefixes = listOf(
        "lo", "tun", "tap", "ppp", "pptp", "rmnet", "ccmni", "dummy", "p2p", "rndis", "docker", "veth", "ipsec",
        "wg", "clat", "v4-",
    )

    fun select(candidates: List<LanCandidate>): String? {
        var fallback: String? = null
        for (candidate in candidates) {
            if (isRejectedInterface(candidate.interfaceName)) continue
            val octets = parseIpv4(candidate.address) ?: continue
            if (isUnusable(octets)) continue
            if (isPrivate(octets)) return candidate.address
            if (fallback == null) fallback = candidate.address
        }
        return fallback
    }

    internal fun parseIpv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isRejectedInterface(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return rejectedInterfacePrefixes.any { lower.startsWith(it) }
    }

    private fun isUnusable(o: IntArray): Boolean = o[0] == 0 || o[0] == 127 || o[0] >= 224 ||
        (o[0] == 169 && o[1] == 254)

    private fun isPrivate(o: IntArray): Boolean = o[0] == 10 ||
        (o[0] == 192 && o[1] == 168) ||
        (o[0] == 172 && o[1] in 16..31)
}

/** Reads this device's addresses from Android, then lets [LanAddressSelector] pick one. `null` when offline. */
internal class AndroidLanAddress(private val context: Context) : () -> String? {
    override fun invoke(): String? = LanAddressSelector.select(collect())

    private fun collect(): List<LanCandidate> {
        val out = ArrayList<LanCandidate>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                @Suppress("DEPRECATION") // allNetworks is deprecated on API 31+ but still the only way to list them on API 29.
                val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.filter { it != cm.activeNetwork }
                for (network in networks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    ) {
                        continue
                    }
                    val link = cm.getLinkProperties(network) ?: continue
                    for (linkAddress in link.linkAddresses) {
                        val address = linkAddress.address
                        if (address is Inet4Address) out += LanCandidate(link.interfaceName, address.hostAddress.orEmpty())
                    }
                }
            }
        } catch (_: RuntimeException) {
            // Missing permission or a dead service: fall through to the interface scan.
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (address in ni.inetAddresses) {
                    if (address is Inet4Address) out += LanCandidate(ni.name, address.hostAddress.orEmpty())
                }
            }
        } catch (_: java.io.IOException) {
            // No interface list: whatever ConnectivityManager gave us is all we have.
        }
        return out
    }
}
