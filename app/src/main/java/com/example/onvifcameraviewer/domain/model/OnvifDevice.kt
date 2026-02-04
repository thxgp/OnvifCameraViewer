package com.example.onvifcameraviewer.domain.model

/**
 * Represents an ONVIF-compliant device discovered on the network.
 * 
 * @property id Unique identifier (usually derived from MAC address or service URL)
 * @property name Human-readable name of the device
 * @property manufacturer Device manufacturer if available
 * @property model Device model if available
 * @property serviceUrl The XAddrs URL used for ONVIF communication
 * @property ipAddress IP address of the device
 * @property macAddress MAC address if available
 */
data class OnvifDevice(
    val id: String,
    val name: String,
    val manufacturer: String = "",
    val model: String = "",
    val serviceUrl: String,
    val ipAddress: String,
    val macAddress: String = ""
) {
    companion object {
        /**
         * Extracts IP address from a service URL.
         * Example: "http://192.168.1.50:80/onvif/device_service" -> "192.168.1.50"
         */
        fun extractIpFromUrl(url: String): String {
            val regex = Regex("""://([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)""")
            return regex.find(url)?.groupValues?.getOrNull(1) ?: ""
        }
    }
}
