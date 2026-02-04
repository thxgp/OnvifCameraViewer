package com.example.onvifcameraviewer.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Service for discovering ONVIF-compliant devices on the local network
 * using WS-Discovery protocol (UDP Multicast).
 * 
 * Protocol Details:
 * - Multicast Address: 239.255.255.250
 * - Port: 3702
 * - Message Type: SOAP Probe for NetworkVideoTransmitter
 */
@Singleton
class OnvifDiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OnvifDiscovery"
        private const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        private const val SOCKET_TIMEOUT_MS = 2000
        private const val DISCOVERY_TIMEOUT_MS = 8000L
        private const val BUFFER_SIZE = 65535
    }
    
    /**
     * Discovers ONVIF devices on the network.
     * Emits devices as they are found via Flow.
     * 
     * @param timeoutMs Total timeout for the discovery process
     * @return Flow of discovered OnvifDevice objects
     */
    fun discoverDevices(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): Flow<OnvifDevice> = flow {
        Log.d(TAG, "Starting ONVIF discovery...")
        
        val discoveredDevices = mutableSetOf<String>()
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null
        
        try {
            // Try to acquire multicast lock
            multicastLock = acquireMulticastLock()
            
            // Create UDP socket
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                reuseAddress = true
            }
            
            // Send probe message
            val probeMessage = buildProbeMessage()
            val multicastAddress = InetAddress.getByName(WS_DISCOVERY_ADDRESS)
            val outPacket = DatagramPacket(
                probeMessage.toByteArray(),
                probeMessage.length,
                multicastAddress,
                WS_DISCOVERY_PORT
            )
            
            Log.d(TAG, "Sending WS-Discovery probe...")
            socket.send(outPacket)
            
            // Listen for responses with timeout
            val buffer = ByteArray(BUFFER_SIZE)
            val inPacket = DatagramPacket(buffer, buffer.size)
            val startTime = System.currentTimeMillis()
            
            while (coroutineContext.isActive && 
                   (System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    socket.receive(inPacket)
                    val response = String(inPacket.data, 0, inPacket.length)
                    val senderIp = inPacket.address?.hostAddress ?: ""
                    
                    parseProbeMatch(response, senderIp)?.let { device ->
                        if (device.serviceUrl !in discoveredDevices) {
                            Log.d(TAG, "Discovered device: ${device.name} at ${device.ipAddress}")
                            discoveredDevices.add(device.serviceUrl)
                            emit(device)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Normal timeout, continue listening
                }
            }
            
            Log.d(TAG, "Discovery complete. Found ${discoveredDevices.size} devices.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error: ${e.message}", e)
            // Don't rethrow - just end the flow gracefully
        } finally {
            closeSocketSafely(socket)
            releaseMulticastLockSafely(multicastLock)
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Safely acquires a multicast lock if WiFi is available.
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock("onvif_discovery")?.apply {
                setReferenceCounted(true)
                acquire()
                Log.d(TAG, "Multicast lock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
            null
        }
    }
    
    /**
     * Safely closes the UDP socket.
     */
    private fun closeSocketSafely(socket: DatagramSocket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket: ${e.message}")
        }
    }
    
    /**
     * Safely releases the multicast lock.
     */
    private fun releaseMulticastLockSafely(lock: WifiManager.MulticastLock?) {
        try {
            if (lock?.isHeld == true) {
                lock.release()
                Log.d(TAG, "Multicast lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock: ${e.message}")
        }
    }
    
    /**
     * Builds the WS-Discovery SOAP Probe message.
     * Targets ONVIF NetworkVideoTransmitter devices.
     */
    private fun buildProbeMessage(): String {
        val messageId = UUID.randomUUID().toString()
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope 
    xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
    xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
    xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
    <soap:Header>
        <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
        <wsa:MessageID>uuid:$messageId</wsa:MessageID>
        <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
    </soap:Header>
    <soap:Body>
        <wsd:Probe>
            <wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>
        </wsd:Probe>
    </soap:Body>
</soap:Envelope>""".trimIndent()
    }
    
    /**
     * Parses a ProbeMatch response to extract device information.
     * Uses regex for performance (faster than full XML parsing).
     * 
     * @param response The raw XML response
     * @param senderIp The IP address of the responding device
     * @return OnvifDevice if parsing succeeds, null otherwise
     */
    private fun parseProbeMatch(response: String, senderIp: String): OnvifDevice? {
        // Must be a ProbeMatch response
        if (!response.contains("ProbeMatch")) return null
        
        return try {
            // Extract XAddrs (service URL)
            val xAddrsRegex = Regex("""<[^:]*:?XAddrs>([^<]+)</[^:]*:?XAddrs>""")
            val xAddrsMatch = xAddrsRegex.find(response) ?: return null
            val xAddrs = xAddrsMatch.groupValues[1].trim()
            
            // Take first URL if multiple are provided (space-separated)
            val serviceUrl = xAddrs.split(" ").firstOrNull()?.trim() ?: return null
            
            // Extract IP from service URL
            val ipAddress = OnvifDevice.extractIpFromUrl(serviceUrl).ifEmpty { senderIp }
            
            // Extract endpoint reference (acts as unique ID)
            val addressRegex = Regex("""<[^:]*:?Address>([^<]+)</[^:]*:?Address>""")
            val addressMatch = addressRegex.find(response)
            val endpointRef = addressMatch?.groupValues?.get(1)?.trim() ?: serviceUrl
            
            // Extract scopes for device info
            val scopesRegex = Regex("""<[^:]*:?Scopes>([^<]+)</[^:]*:?Scopes>""")
            val scopesMatch = scopesRegex.find(response)
            val scopes = scopesMatch?.groupValues?.get(1) ?: ""
            
            // Parse scopes for manufacturer, model, name
            val manufacturer = extractScopeValue(scopes, "hardware") 
                ?: extractScopeValue(scopes, "mfr") 
                ?: ""
            val model = extractScopeValue(scopes, "name") ?: ""
            val name = extractScopeValue(scopes, "location") 
                ?: model.ifEmpty { "Camera @ $ipAddress" }
            
            OnvifDevice(
                id = endpointRef.hashCode().toString(),
                name = name,
                manufacturer = manufacturer,
                model = model,
                serviceUrl = serviceUrl,
                ipAddress = ipAddress
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse probe match: ${e.message}")
            null
        }
    }
    
    /**
     * Extracts a value from ONVIF scopes string.
     * Scopes format: "onvif://www.onvif.org/type/value"
     */
    private fun extractScopeValue(scopes: String, key: String): String? {
        return try {
            val regex = Regex("""onvif://www\.onvif\.org/$key/([^\s]+)""", RegexOption.IGNORE_CASE)
            regex.find(scopes)?.groupValues?.get(1)?.let { 
                java.net.URLDecoder.decode(it, "UTF-8") 
            }
        } catch (e: Exception) {
            null
        }
    }
}
