package com.example.onvifcameraviewer.data.onvif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for ONVIF authentication logic.
 */
class OnvifAuthTest {
    
    @Test
    fun `generateAuthComponents returns valid components`() {
        val username = "admin"
        val password = "password123"
        
        val auth = OnvifAuth.generateAuthComponents(username, password)
        
        assertEquals(username, auth.username)
        assertNotNull(auth.nonceBase64)
        assertNotNull(auth.created)
        assertNotNull(auth.passwordDigest)
        
        // Nonce should be Base64 encoded (at least 20 chars for 16 bytes)
        assert(auth.nonceBase64.length >= 20)
        
        // Created should be ISO 8601 format
        assert(auth.created.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")))
        
        // Digest should be Base64 encoded SHA-1 (28 chars)
        assertEquals(28, auth.passwordDigest.length)
    }
    
    @Test
    fun `buildSecurityHeader generates valid XML`() {
        val auth = AuthComponents(
            username = "admin",
            nonceBase64 = "dGVzdG5vbmNl",
            created = "2024-01-15T10:30:00Z",
            passwordDigest = "dGVzdGRpZ2VzdA=="
        )
        
        val header = OnvifAuth.buildSecurityHeader(auth)
        
        assert(header.contains("<wsse:Username>admin</wsse:Username>"))
        assert(header.contains("<wsse:Password"))
        assert(header.contains("PasswordDigest"))
        assert(header.contains("<wsse:Nonce"))
        assert(header.contains("<wsu:Created>2024-01-15T10:30:00Z</wsu:Created>"))
    }
}
