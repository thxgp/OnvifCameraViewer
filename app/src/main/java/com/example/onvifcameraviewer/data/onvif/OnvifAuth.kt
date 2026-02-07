package com.example.onvifcameraviewer.data.onvif

import android.util.Base64
import com.example.onvifcameraviewer.domain.exception.OnvifException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ONVIF WS-UsernameToken Authentication helper.
 * 
 * Implements PasswordDigest authentication as required by ONVIF Profile S.
 * Formula: Base64(SHA-1(Nonce + Created + Password))
 * 
 * Most ONVIF devices reject plain-text passwords over HTTP, requiring this digest.
 */
object OnvifAuth {
    
    private const val NONCE_SIZE = 16
    
    /**
     * Generates the authentication components for a SOAP request.
     * 
     * @param username The camera username
     * @param password The camera password
     * @param timeOffsetMillis Offset to add to local time (ServerTime - LocalTime)
     * @return AuthComponents containing nonce, created timestamp, and password digest
     * @throws OnvifException.AuthenticationException if digest creation fails
     */
    @Throws(OnvifException.AuthenticationException::class)
    fun generateAuthComponents(
        username: String, 
        password: String,
        timeOffsetMillis: Long = 0
    ): AuthComponents {
        val nonce = generateNonce()
        val created = generateCreatedTimestamp(timeOffsetMillis)
        val digest = createPasswordDigest(password, nonce, created)
        
        return AuthComponents(
            username = username,
            nonceBase64 = Base64.encodeToString(nonce, Base64.NO_WRAP),
            created = created,
            passwordDigest = digest
        )
    }
    
    /**
     * Creates the PasswordDigest for WS-UsernameToken.
     * Formula: Base64(SHA-1(Nonce + Created + Password))
     * 
     * @throws OnvifException.AuthenticationException if SHA-1 algorithm is unavailable
     */
    @Throws(OnvifException.AuthenticationException::class)
    private fun createPasswordDigest(
        password: String,
        nonce: ByteArray,
        created: String
    ): String {
        return try {
            val combined = nonce + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(combined)
            Base64.encodeToString(sha1, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw OnvifException.AuthenticationException("Failed to create password digest", e)
        }
    }
    
    /**
     * Generates a cryptographically secure random nonce.
     */
    private fun generateNonce(): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)
        return nonce
    }
    
    /**
     * Generates the Created timestamp in ISO 8601 format (UTC).
     * Example: 2024-01-15T10:30:00Z
     */
    private fun generateCreatedTimestamp(offsetMillis: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val now = Date(System.currentTimeMillis() + offsetMillis)
        return dateFormat.format(now)
    }
    
    /**
     * Builds the WS-Security SOAP header with UsernameToken.
     */
    fun buildSecurityHeader(auth: AuthComponents): String {
        return """
            <wsse:Security xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                           xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
                <wsse:UsernameToken>
                    <wsse:Username>${auth.username}</wsse:Username>
                    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${auth.passwordDigest}</wsse:Password>
                    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${auth.nonceBase64}</wsse:Nonce>
                    <wsu:Created>${auth.created}</wsu:Created>
                </wsse:UsernameToken>
            </wsse:Security>
        """.trimIndent()
    }
}

/**
 * Container for authentication components.
 */
data class AuthComponents(
    val username: String,
    val nonceBase64: String,
    val created: String,
    val passwordDigest: String
)
