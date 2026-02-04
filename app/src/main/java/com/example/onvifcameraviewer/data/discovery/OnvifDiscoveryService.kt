package com.example.onvifcameraviewer.data.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.example.onvifcameraviewer.domain.model.OnvifDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
        private const val WS_DISCOVERY_PORT = 3702
        private const val SOCKET_TIMEOUT_MS = 3000
        private const val DISCOVERY_TIMEOUT_MS = 8000L
        private const val BUFFER_SIZE = 65535
    }
    
    private val wifiManager: WifiManager? by lazy {
        try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Discovers ONVIF devices on the network.
     * Emits devices as they are found via Flow.
     * 
     * @param timeoutMs Total timeout for the discovery process
     * @return Flow of discovered OnvifDevice objects
     */
    fun discoverDevices(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): Flow<OnvifDevice> = flow {
        val manager = wifiManager
        var multicastLock: WifiManager.MulticastLock? = null
        
        val discoveredDevices = mutableSetOf<String>()
        var socket: DatagramSocket? = null
        
        try {
            // Try to acquire multicast lock if WiFi is available
            multicastLock = try {
                manager?.createMulticastLock("onvif_discovery")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (e: Exception) {
                // Multicast lock failed, continue without it
                null
            }
            
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
            socket.send(outPacket)
            
            // Listen for responses
            val buffer = ByteArray(BUFFER_SIZE)
            val inPacket = DatagramPacket(buffer, buffer.size)
            
            withTimeout(timeoutMs) {
                while (true) {
                    try {
                        socket.receive(inPacket)
                        val response = String(inPacket.data, 0, inPacket.length)
                        
                        parseProbeMatch(response, inPacket.address?.hostAddress ?: "")?.let { device ->
                            if (device.serviceUrl !in discoveredDevices) {
                                discoveredDevices.add(device.serviceUrl)
                                emit(device)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // Continue listening until timeout
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Normal timeout - discovery complete
        } catch (e: Exception) {
            // Log error but don't crash
            android.util.Log.e("OnvifDiscovery", "Discovery error: ${e.message}", e)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }.flowOn(Dispatchers.IO)
    
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
        
        return OnvifDevice(
            id = endpointRef.hashCode().toString(),
            name = name,
            manufacturer = manufacturer,
            model = model,
            serviceUrl = serviceUrl,
            ipAddress = ipAddress
        )
    }
    
    /**
     * Extracts a value from ONVIF scopes string.
     * Scopes format: "onvif://www.onvif.org/type/value"
     */
    private fun extractScopeValue(scopes: String, key: String): String? {
        val regex = Regex("""onvif://www\.onvif\.org/$key/([^\s]+)""", RegexOption.IGNORE_CASE)
        return regex.find(scopes)?.groupValues?.get(1)?.let { 
            java.net.URLDecoder.decode(it, "UTF-8") 
        }
    }
}
