package com.example.onvifcameraviewer.domain.exception

/**
 * Sealed hierarchy of domain-specific exceptions for ONVIF operations.
 * Provides type-safe error handling throughout the application.
 */
sealed class OnvifException(
    message: String, 
    cause: Throwable? = null
) : Exception(message, cause) {
    
    /**
     * Authentication failed - wrong credentials or digest calculation error.
     */
    class AuthenticationException(
        message: String = "Authentication failed", 
        cause: Throwable? = null
    ) : OnvifException(message, cause)
    
    /**
     * Network-related errors - connection failures, timeouts, HTTP errors.
     */
    class NetworkException(
        message: String = "Network error", 
        cause: Throwable? = null
    ) : OnvifException(message, cause)
    
    /**
     * Camera returned no media profiles.
     */
    class NoProfilesException(
        message: String = "No media profiles available"
    ) : OnvifException(message)
    
    /**
     * Failed to retrieve or parse stream URI.
     */
    class StreamUriException(
        message: String = "Failed to get stream URI", 
        cause: Throwable? = null
    ) : OnvifException(message, cause)
    
    /**
     * SOAP response parsing failed.
     */
    class SoapParsingException(
        message: String = "Failed to parse SOAP response", 
        cause: Throwable? = null
    ) : OnvifException(message, cause)
    
    /**
     * Request timed out.
     */
    class TimeoutException(
        message: String = "Request timed out"
    ) : OnvifException(message)
}
