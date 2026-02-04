package com.example.onvifcameraviewer.data.onvif

import com.example.onvifcameraviewer.domain.model.Credentials
import com.example.onvifcameraviewer.domain.model.MediaProfile
import com.example.onvifcameraviewer.domain.model.VideoEncoderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        private const val SOAP_CONTENT_TYPE = "application/soap+xml; charset=utf-8"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 15L
    }
    
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()
    
    /**
     * Retrieves available media profiles from the device.
     * 
     * @param deviceUrl The ONVIF service URL (XAddrs)
     * @param credentials Camera login credentials
     * @return List of MediaProfile objects
     */
    suspend fun getProfiles(
        deviceUrl: String,
        credentials: Credentials
    ): Result<List<MediaProfile>> = withContext(Dispatchers.IO) {
        try {
            val mediaUrl = getMediaServiceUrl(deviceUrl)
            val auth = OnvifAuth.generateAuthComponents(credentials.username, credentials.password)
            
            val soapRequest = buildGetProfilesRequest(auth)
            val response = executeSoapRequest(mediaUrl, soapRequest)
            
            val profiles = parseProfilesResponse(response)
            Result.success(profiles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Retrieves the RTSP stream URI for a specific profile.
     * 
     * @param deviceUrl The ONVIF service URL
     * @param credentials Camera login credentials
     * @param profileToken The profile token from GetProfiles
     * @param useUdp Whether to use UDP (false = TCP, more reliable on Wi-Fi)
     * @return RTSP URI string
     */
    suspend fun getStreamUri(
        deviceUrl: String,
        credentials: Credentials,
        profileToken: String,
        useUdp: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mediaUrl = getMediaServiceUrl(deviceUrl)
            val auth = OnvifAuth.generateAuthComponents(credentials.username, credentials.password)
            
            val transportProtocol = if (useUdp) "UDP" else "TCP" 
            val soapRequest = buildGetStreamUriRequest(auth, profileToken, transportProtocol)
            val response = executeSoapRequest(mediaUrl, soapRequest)
            
            val uri = parseStreamUriResponse(response)
                ?: return@withContext Result.failure(Exception("Failed to parse stream URI"))
            
            // Embed credentials in RTSP URI for authentication
            val authenticatedUri = embedCredentialsInUri(uri, credentials)
            Result.success(authenticatedUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Converts device service URL to media service URL.
     */
    private fun getMediaServiceUrl(deviceUrl: String): String {
        // Most devices use /onvif/media_service or the path is in XAddrs
        return deviceUrl.replace("device_service", "media_service")
            .replace("/onvif/device", "/onvif/media")
    }
    
    /**
     * Executes a SOAP request and returns the response body.
     */
    private fun executeSoapRequest(url: String, soapBody: String): String {
        val requestBody = soapBody.toRequestBody(SOAP_CONTENT_TYPE.toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", SOAP_CONTENT_TYPE)
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("SOAP request failed: ${response.code}")
        }
        return response.body?.string() ?: throw Exception("Empty response")
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
        transport: String
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
        
        // Extract each Profiles element
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
        val uriRegex = Regex("""<[^:]*:?Uri>([^<]+)</[^:]*:?Uri>""")
        return uriRegex.find(response)?.groupValues?.get(1)
    }
    
    /**
     * Embeds credentials into RTSP URI for authentication.
     * Example: rtsp://192.168.1.50/stream -> rtsp://user:pass@192.168.1.50/stream
     */
    private fun embedCredentialsInUri(uri: String, credentials: Credentials): String {
        return uri.replace("rtsp://", "rtsp://${credentials.username}:${credentials.password}@")
    }
}
