package com.jn.dyparse

object DouyinVerificationDetector {
    private val verificationMarkers = listOf(
        "captcha-verify-container",
        "captcha_verify_container",
        "verifycenter",
        "sec_did captcha",
        "验证码",
        "人机验证"
    )

    fun isVerificationBody(body: String?): Boolean {
        val normalized = body.orEmpty()
        if (normalized.isBlank()) {
            return false
        }

        val lowercaseBody = normalized.lowercase()
        return verificationMarkers.any { marker ->
            lowercaseBody.contains(marker.lowercase())
        }
    }

    fun isVerificationHttpResponse(statusCode: Int, body: String?): Boolean {
        if (statusCode == 429) {
            return true
        }

        if (statusCode !in setOf(403, 412)) {
            return false
        }

        return isVerificationBody(body)
    }
}
