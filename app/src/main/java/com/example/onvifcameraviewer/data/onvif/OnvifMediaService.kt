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
    /**
     * Retrieves available media profiles from the device.
     * Automatically handles clock skew by syncing with server time if initial auth fails.
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
            
            // First attempt with local time
            try {
                return@withContext fetchProfilesWithOffset(mediaUrl, credentials, 0)
            } catch (e: Exception) {
                // If auth failed (401 or SOAP Fault), try to sync time
                val errorMsg = e.message ?: ""
                if (errorMsg.contains("401") || errorMsg.contains("Failed") || errorMsg.contains("500")) {
                    try {
                        // Get server time
                        val timeOffset = calculateTimeOffset(deviceUrl)
                        // Retry with corrected time
                        return@withContext fetchProfilesWithOffset(mediaUrl, credentials, timeOffset)
                    } catch (syncError: Exception) {
                        // If sync fails, throw original error
                        throw e
                    }
                }
                throw e
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Helper to fetch profiles with a specific time offset
    private fun fetchProfilesWithOffset(
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
        val response = executeSoapRequest(mediaUrl, soapRequest)
        
        val profiles = parseProfilesResponse(response)
        return Result.success(profiles)
    }

    /**
     * Calculates time offset between device and local clock.
     * Offset = DeviceTime - LocalTime
     */
    private fun calculateTimeOffset(deviceUrl: String): Long {
        // Use device_service URL directly, usually available at the base URL or specified XAddr
        // But for GetSystemDateAndTime we should send to Device Service, NOT Media Service.
        // Assuming deviceUrl passed here IS the Device Service URL (XAddrs) 
        // If it was modified to be media service, we might need the original.
        // In this architecture, usually 'deviceUrl' passed to getProfiles comes from discovery XAddrs (Device Service).
        
        val soapRequest = """<?xml version="1.0" encoding="UTF-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
               xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
    <soap:Body>
        <tds:GetSystemDateAndTime/>
    </soap:Body>
</soap:Envelope>"""

        val response = executeSoapRequest(deviceUrl, soapRequest)
        return parseSystemDateAndTime(response)
    }

    private fun parseSystemDateAndTime(response: String): Long {
        // Extract UTC DateTime
        // <tt:UTCDateTime>
        //    <tt:Time> <tt:Hour>15</tt:Hour> <tt:Minute>30</tt:Minute> <tt:Second>45</tt:Second> </tt:Time>
        //    <tt:Date> <tt:Year>2024</tt:Year> <tt:Month>2</tt:Month> <tt:Day>4</tt:Day> </tt:Date>
        // </tt:UTCDateTime>
        
        val yearPattern = Regex("""<[^:]*:?Year>(\d+)</[^:]*:?Year>""")
        val monthPattern = Regex("""<[^:]*:?Month>(\d+)</[^:]*:?Month>""")
        val dayPattern = Regex("""<[^:]*:?Day>(\d+)</[^:]*:?Day>""")
        val hourPattern = Regex("""<[^:]*:?Hour>(\d+)</[^:]*:?Hour>""")
        val minutePattern = Regex("""<[^:]*:?Minute>(\d+)</[^:]*:?Minute>""")
        val secondPattern = Regex("""<[^:]*:?Second>(\d+)</[^:]*:?Second>""")

        val year = yearPattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val month = monthPattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val day = dayPattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val hour = hourPattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val minute = minutePattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0
        val second = secondPattern.find(response)?.groupValues?.get(1)?.toInt() ?: return 0

        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.set(year, month - 1, day, hour, minute, second)
        val serverTime = calendar.timeInMillis
        val localTime = System.currentTimeMillis()
        
        return serverTime - localTime
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
            
            // Ideally we should cache the offset, but for now let's just use 0 
            // If getProfiles succeeded, it implies 0 offset was fine OR we don't persist offset yet.
            // Improve: Pass offset or recalculate if fails. For simplicity, retry logic here too.
             try {
                return@withContext fetchStreamUriWithOffset(mediaUrl, credentials, profileToken, useUdp, 0)
            } catch (e: Exception) {
                 val errorMsg = e.message ?: ""
                if (errorMsg.contains("401") || errorMsg.contains("Failed")) {
                     val timeOffset = calculateTimeOffset(deviceUrl)
                     return@withContext fetchStreamUriWithOffset(mediaUrl, credentials, profileToken, useUdp, timeOffset)
                }
                throw e
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchStreamUriWithOffset(
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
        val response = executeSoapRequest(mediaUrl, soapRequest) // executeSoapRequest handles non-200 by throwing
        
        val uri = parseStreamUriResponse(response)
            ?: throw Exception("Failed to parse stream URI")
        
        // Embed credentials in RTSP URI for authentication
        val authenticatedUri = embedCredentialsInUri(uri, credentials)
        return Result.success(authenticatedUri)
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
            // Read error body for debugging
            val errorBody = response.body?.string() ?: ""
            throw Exception("SOAP request failed: ${response.code} $errorBody")
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
