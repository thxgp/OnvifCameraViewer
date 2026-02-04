package com.example.onvifcameraviewer.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for OnvifDevice model.
 */
class OnvifDeviceTest {
    
    @Test
    fun `extractIpFromUrl extracts IP from standard URL`() {
        val url = "http://192.168.1.50:80/onvif/device_service"
        val ip = OnvifDevice.extractIpFromUrl(url)
        assertEquals("192.168.1.50", ip)
    }
    
    @Test
    fun `extractIpFromUrl extracts IP from HTTPS URL`() {
        val url = "https://10.0.0.100:443/onvif/device_service"
        val ip = OnvifDevice.extractIpFromUrl(url)
        assertEquals("10.0.0.100", ip)
    }
    
    @Test
    fun `extractIpFromUrl returns empty for invalid URL`() {
        val url = "not-a-valid-url"
        val ip = OnvifDevice.extractIpFromUrl(url)
        assertEquals("", ip)
    }
    
    @Test
    fun `extractIpFromUrl works with different ports`() {
        val url = "http://172.16.0.1:8080/onvif/device_service"
        val ip = OnvifDevice.extractIpFromUrl(url)
        assertEquals("172.16.0.1", ip)
    }
}
