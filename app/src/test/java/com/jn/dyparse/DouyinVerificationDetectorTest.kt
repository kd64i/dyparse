package com.jn.dyparse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinVerificationDetectorTest {
    @Test
    fun normalSliderMarkupIsNotVerification() {
        val html = """
            <html>
              <body>
                <div class="slider carousel-slider"></div>
                <script>window._ROUTER_DATA = {"loaderData":{"video":{"aweme_id":"1234567890123456789"}}}</script>
              </body>
            </html>
        """.trimIndent()

        assertFalse(DouyinVerificationDetector.isVerificationBody(html))
    }

    @Test
    fun automaticAcrawlerChallengeIsNotManualVerification() {
        val html = """
            <html>
              <script>
                window.byted_acrawler.init({aid:99999999});
                window.location.reload();
                document.cookie = "__ac_signature=sample";
              </script>
            </html>
        """.trimIndent()

        assertFalse(DouyinVerificationDetector.isVerificationBody(html))
    }

    @Test
    fun explicitCaptchaPageIsVerification() {
        val html = """
            <html>
              <body>
                <div id="captcha-verify-container"></div>
                <script src="/verifycenter/main.js"></script>
              </body>
            </html>
        """.trimIndent()

        assertTrue(DouyinVerificationDetector.isVerificationBody(html))
    }

    @Test
    fun forbiddenWithoutCaptchaBodyIsNotVerification() {
        assertFalse(DouyinVerificationDetector.isVerificationHttpResponse(403, """{"status_code":0}"""))
    }

    @Test
    fun forbiddenWithCaptchaBodyIsVerification() {
        val html = """<html><body><div class="captcha-verify-container"></div></body></html>"""

        assertTrue(DouyinVerificationDetector.isVerificationHttpResponse(403, html))
    }

    @Test
    fun rateLimitStatusIsVerification() {
        assertTrue(DouyinVerificationDetector.isVerificationHttpResponse(429, null))
    }
}
