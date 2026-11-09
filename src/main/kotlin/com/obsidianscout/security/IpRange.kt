package com.obsidianscout.security

import java.math.BigInteger
import java.net.InetAddress

/** A single IP address or a CIDR range, IPv4 or IPv6. Built only from literal addresses (never resolves DNS). */
class IpRange private constructor(val text: String, private val network: BigInteger, private val prefix: Int, private val bits: Int) {

    fun contains(ip: String): Boolean {
        val addr = toBigInteger(ip) ?: return false
        if (addr.second != bits) return false
        if (prefix == 0) return true
        val shift = bits - prefix
        return addr.first.shiftRight(shift) == network.shiftRight(shift)
    }

    val isSingleAddress: Boolean get() = prefix == bits

    companion object {
        private fun isLiteral(s: String) = s.isNotEmpty() && s.length <= 45 &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '.' || it == ':' }

        /** Address bytes as a number, and the address width (32 or 128). IPv4-mapped IPv6 is treated as IPv4. */
        fun toBigInteger(ip: String): Pair<BigInteger, Int>? {
            val clean = ip.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
            if (!isLiteral(clean)) return null
            val bytes = runCatching { InetAddress.getByName(clean).address }.getOrNull() ?: return null
            return BigInteger(1, bytes) to bytes.size * 8
        }

        /** Parses "203.0.113.7", "203.0.113.0/24", "2001:db8::/32". Null when the text isn't a valid literal range. */
        fun parse(text: String): IpRange? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 64) return null
            val parts = trimmed.split('/')
            if (parts.size > 2) return null
            val (addr, bits) = toBigInteger(parts[0]) ?: return null
            val prefix = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else bits
            if (prefix < 0 || prefix > bits) return null
            // Refuse ranges so wide a typo could block everyone: at least a /8 for IPv4, /16 for IPv6.
            if (prefix < (if (bits == 32) 8 else 16)) return null
            val shift = bits - prefix
            val network = addr.shiftRight(shift).shiftLeft(shift)
            val canonicalAddr = runCatching {
                val raw = network.toByteArray().let { b ->
                    val width = bits / 8
                    when {
                        b.size == width -> b
                        b.size > width -> b.copyOfRange(b.size - width, b.size)
                        else -> ByteArray(width - b.size) + b
                    }
                }
                InetAddress.getByAddress(raw).hostAddress
            }.getOrDefault(parts[0])
            val canonical = if (prefix == bits) canonicalAddr else "$canonicalAddr/$prefix"
            return IpRange(canonical, network, prefix, bits)
        }
    }

    override fun toString(): String = text
}
