package com.example.onvifcameraviewer.data.onvif

import android.util.Log
import com.burgstaller.okhttp.digest.DigestAuthenticator
import com.burgstaller.okhttp.digest.Credentials as DigestCredentials
import com.example.onvifcameraviewer.domain.exception.OnvifException
import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.MediaProfile
import com.example.onvifcameraviewer.domain.model.VideoEncoderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONVIF Media Service client for retrieving profiles and stream URIs.
 * 
 * Implements SOAP 1.2 communication with WS-UsernameToken authentication.
 */
@Singleton
class OnvifMediaService @Inject constructor() {
    
    companion object {
        private const val TAG = "OnvifMediaService"
        private const val SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 15L
    }
    
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()
    
    // Cache for media service URLs to avoid repeated GetCapabilities calls
    private val mediaUrlCache = mutableMapOf<String, String>()
    
    /**
     * Retrieves available media profiles from the device.
     */
    suspend fun getProfiles(
        deviceUrl: String,
        credentials: Credentials
    ): Result<List<MediaProfile>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getProfiles: Starting for $deviceUrl")
            val mediaUrl = resolveMediaServiceUrl(deviceUrl, credentials)
            Log.d(TAG, "Resolved media URL: $mediaUrl")
            
            // First attempt with local time
            try {
                return@withContext fetchProfilesWithOffset(mediaUrl, credentials, 0)
            } catch (e: Exception) {
                // Retry with time sync for auth failures
                if (e is OnvifException.AuthenticationException || 
                    e.message?.contains("401") == true ||
                    e.message?.contains("Authentication") == true) {
                    Log.d(TAG, "Auth failed, syncing time...")
                    val timeOffset = calculateTimeOffset(deviceUrl, credentials)
                    return@withContext fetchProfilesWithOffset(mediaUrl, credentials, timeOffset)
                }
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "getProfiles failed", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves the actual Media Service URL using GetCapabilities.
     */
    private suspend fun resolveMediaServiceUrl(deviceUrl: String, credentials: Credentials): String {
        // Check cache first
        mediaUrlCache[deviceUrl]?.let { return it }
        
        return try {
            val soapRequest = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(OnvifAuth.generateAuthComponents(credentials.username, credentials.password))}
    </soap:Header>
    <soap:Body>
        <tds:GetCapabilities>
            <tds:Category>Media</tds:Category>
        </tds:GetCapabilities>
    </soap:Body>
</soap:Envelope>"""

            val response = executeSoapRequest(deviceUrl, soapRequest, credentials)
            val mediaUrl = parseMediaUrlFromCapabilities(response)
            
            if (mediaUrl != null) {
                mediaUrlCache[deviceUrl] = mediaUrl
                mediaUrl
            } else {
                // Fallback to heuristic if parsing fails
                heuristicMediaUrl(deviceUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GetCapabilities failed, using heuristic: ${e.message}")
            heuristicMediaUrl(deviceUrl)
        }
    }

    private fun parseMediaUrlFromCapabilities(response: String): String? {
        val mediaUrlRegex = Regex("""<[^:]*:?Media[^>]*>\s*<[^:]*:?XAddr>([^<]+)</[^:]*:?XAddr>""", RegexOption.IGNORE_CASE)
        return mediaUrlRegex.find(response)?.groupValues?.get(1)?.trim()
    }

    private fun heuristicMediaUrl(deviceUrl: String): String {
        return deviceUrl.replace("device_service", "media_service")
            .replace("/onvif/device", "/onvif/media")
    }

    // Helper to fetch profiles with a specific time offset
    private suspend fun fetchProfilesWithOffset(
        mediaUrl: String, 
        credentials: Credentials, 
        offset: Long
    ): Result<List<MediaProfile>> {
        val auth = OnvifAuth.generateAuthComponents(
            credentials.username, 
            credentials.password,
            offset
        )
        
        val soapRequest = buildGetProfilesRequest(auth)
        val response = executeSoapRequest(mediaUrl, soapRequest, credentials)
        
        val profiles = parseProfilesResponse(response)
        return Result.success(profiles)
    }

    /**
     * Calculates time offset between device and local clock.
     */
    private suspend fun calculateTimeOffset(deviceUrl: String, credentials: Credentials? = null): Long {
        val soapRequest = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetSystemDateAndTime/>
    </soap:Body>
</soap:Envelope>"""

        val response = executeSoapRequest(deviceUrl, soapRequest, credentials)
        return parseSystemDateAndTime(response)
    }

    private fun parseSystemDateAndTime(response: String): Long {
        val year = Regex("""<[^:]*:?Year>(\d+)</[^:]*:?Year>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val month = Regex("""<[^:]*:?Month>(\d+)</[^:]*:?Month>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val day = Regex("""<[^:]*:?Day>(\d+)</[^:]*:?Day>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val hour = Regex("""<[^:]*:?Hour>(\d+)</[^:]*:?Hour>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val minute = Regex("""<[^:]*:?Minute>(\d+)</[^:]*:?Minute>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val second = Regex("""<[^:]*:?Second>(\d+)</[^:]*:?Second>""").find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, hour, minute, second)
        val serverTime = calendar.timeInMillis
        val localTime = System.currentTimeMillis()
        
        return serverTime - localTime
    }

    /**
     * Retrieves the RTSP stream URI for a specific profile.
     */
    suspend fun getStreamUri(
        deviceUrl: String,
        credentials: Credentials,
        profileToken: String,
        useUdp: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mediaUrl = resolveMediaServiceUrl(deviceUrl, credentials)
            
             try {
                return@withContext fetchStreamUriWithOffset(mediaUrl, credentials, profileToken, useUdp, 0)
            } catch (e: Exception) {
                 val errorMsg = e.message ?: ""
                if (errorMsg.contains("401") || errorMsg.contains("Failed")) {
                     val timeOffset = calculateTimeOffset(deviceUrl, credentials)
                     return@withContext fetchStreamUriWithOffset(mediaUrl, credentials, profileToken, useUdp, timeOffset)
                }
                throw e
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchStreamUriWithOffset(
        mediaUrl: String,
        credentials: Credentials,
        profileToken: String,
        useUdp: Boolean,
        offset: Long
    ): Result<String> {
        val auth = OnvifAuth.generateAuthComponents(
            credentials.username, 
            credentials.password,
            offset
        )
        
        val transportProtocol = if (useUdp) "UDP" else "TCP" 
        val soapRequest = buildGetStreamUriRequest(auth, profileToken, transportProtocol)
        val response = executeSoapRequest(mediaUrl, soapRequest, credentials) 
        
        val uri = parseStreamUriResponse(response)
            ?: throw OnvifException.StreamUriException("Failed to parse stream URI from SOAP response")
        
        // Clean up URI and embed credentials safely
        val cleanUri = uri.trim().replace(Regex("^rtsp:///+"), "rtsp://")
        val authenticatedUri = embedCredentialsInUri(cleanUri, credentials)
        
        Log.d(TAG, "Stream URI: $authenticatedUri")
        return Result.success(authenticatedUri)
    }

    /**
     * Executes a SOAP request and returns the response body.
     */
    private suspend fun executeSoapRequest(url: String, soapBody: String, credentials: Credentials? = null): String {
        return withContext(Dispatchers.IO) { 
            val requestBody = soapBody.toRequestBody(SOAP_CONTENT_TYPE.toMediaType())
            
            val clientToUse = if (credentials != null) {
                val authenticator = DigestAuthenticator(DigestCredentials(credentials.username, credentials.password))
                httpClient.newBuilder()
                    .authenticator(authenticator)
                    .build()
            } else {
                httpClient
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", SOAP_CONTENT_TYPE)
                .build()
            
            val response = try {
                clientToUse.newCall(request).execute()
            } catch (e: SocketTimeoutException) {
                throw OnvifException.TimeoutException("Connection timed out - check camera IP")
            } catch (e: java.net.ConnectException) {
                throw OnvifException.NetworkException("Cannot connect to camera - check network")
            }
            
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                val errorMessage = when (response.code) {
                    401 -> "Authentication failed - check username/password"
                    403 -> "Access forbidden - insufficient permissions"
                    404 -> "Service not found at this URL"
                    500 -> "Camera internal error"
                    503 -> "Camera service unavailable"
                    else -> "Request failed: ${response.code}"
                }
                when {
                    response.code == 401 -> throw OnvifException.AuthenticationException(errorMessage)
                    response.code in 500..599 -> throw OnvifException.NetworkException("$errorMessage: $responseBody")
                    else -> throw OnvifException.NetworkException(errorMessage)
                }
            }
            responseBody
        }
    }
    
    /**
     * Builds GetProfiles SOAP request.
     */
    private fun buildGetProfilesRequest(auth: AuthComponents): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(auth)}
    </soap:Header>
    <soap:Body>
        <trt:GetProfiles/>
    </soap:Body>
</soap:Envelope>"""
    }
    
    /**
     * Builds GetStreamUri SOAP request.
     */
    private fun buildGetStreamUriRequest(
        auth: AuthComponents,
        profileToken: String,
        @Suppress("UNUSED_PARAMETER") transport: String
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
               xmlns:tt="http://www.onvif.org/ver10/schema">
    <soap:Header>
        ${OnvifAuth.buildSecurityHeader(auth)}
    </soap:Header>
    <soap:Body>
        <trt:GetStreamUri>
            <trt:StreamSetup>
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport>
                    <tt:Protocol>RTSP</tt:Protocol>
                </tt:Transport>
            </trt:StreamSetup>
            <trt:ProfileToken>$profileToken</trt:ProfileToken>
        </trt:GetStreamUri>
    </soap:Body>
</soap:Envelope>"""
    }
    
    /**
     * Parses GetProfiles response.
     */
    private fun parseProfilesResponse(response: String): List<MediaProfile> {
        val profiles = mutableListOf<MediaProfile>()
        
        val profileRegex = Regex("""<[^:]*:?Profiles[^>]*token="([^"]+)"[^>]*>.*?</[^:]*:?Profiles>""", RegexOption.DOT_MATCHES_ALL)
        val nameRegex = Regex("""<[^:]*:?Name>([^<]+)</[^:]*:?Name>""")
        val encodingRegex = Regex("""<[^:]*:?Encoding>([^<]+)</[^:]*:?Encoding>""")
        val widthRegex = Regex("""<[^:]*:?Width>([^<]+)</[^:]*:?Width>""")
        val heightRegex = Regex("""<[^:]*:?Height>([^<]+)</[^:]*:?Height>""")
        
        profileRegex.findAll(response).forEach { match ->
            val profileXml = match.value
            val token = match.groupValues[1]
            val name = nameRegex.find(profileXml)?.groupValues?.get(1) ?: token
            
            val encoding = encodingRegex.find(profileXml)?.groupValues?.get(1) ?: "H264"
            val width = widthRegex.find(profileXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = heightRegex.find(profileXml)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            profiles.add(MediaProfile(
                token = token,
                name = name,
                videoEncoderConfig = VideoEncoderConfig(
                    encoding = encoding,
                    width = width,
                    height = height
                )
            ))
        }
        
        return profiles
    }
    
    /**
     * Parses GetStreamUri response.
     */
    private fun parseStreamUriResponse(response: String): String? {
        val uriRegex = Regex("""<[^:]*:?Uri>([^<]+)</[^:]*:?Uri>""", RegexOption.DOT_MATCHES_ALL)
        return uriRegex.find(response)?.groupValues?.get(1)?.trim()
    }

    /**
     * Embeds credentials into RTSP URI for authentication.
     * Uses URL-encoding for safety.
     */
    private fun embedCredentialsInUri(uri: String, credentials: Credentials): String {
        return try {
            val encodedUser = URLEncoder.encode(credentials.username, "UTF-8")
            val encodedPass = URLEncoder.encode(credentials.password, "UTF-8")
            uri.replace("rtsp://", "rtsp://$encodedUser:$encodedPass@")
        } catch (e: Exception) {
            uri.replace("rtsp://", "rtsp://${credentials.username}:${credentials.password}@")
        }
    }
}
