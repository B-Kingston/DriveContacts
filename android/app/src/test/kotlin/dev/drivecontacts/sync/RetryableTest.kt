package dev.drivecontacts.sync

import me.proton.drive.sdk.ProtonDriveSdkException
import me.proton.drive.sdk.ProtonSdkError
import me.proton.drive.sdk.ProtonSdkError.ErrorDomain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RetryableTest {
    private fun sdk(domain: ErrorDomain, secondaryCode: Long? = null, inner: ProtonSdkError? = null) =
        ProtonDriveSdkException(
            "failed",
            error = ProtonSdkError("failed", "Test", domain, secondaryCode = secondaryCode, innerError = inner),
        )

    @Test
    fun ioErrorInTheCauseChainIsRetryable() {
        assertTrue(RuntimeException("outer", IOException("socket")).isRetryable())
    }

    @Test
    fun sdkNetworkTransportAndIoDomainsAreRetryable() {
        assertTrue(sdk(ErrorDomain.Network).isRetryable())
        assertTrue(sdk(ErrorDomain.Transport).isRetryable())
        assertTrue(sdk(ErrorDomain.UnknownIO).isRetryable())
    }

    @Test
    fun retryableInnerSdkErrorIsFound() {
        val inner = ProtonSdkError("socket", "Inner", ErrorDomain.Network)
        assertTrue(sdk(ErrorDomain.BusinessLogic, inner = inner).isRetryable())
    }

    @Test
    fun apiErrorIsRetryableOnlyForRateLimitAndServerErrors() {
        assertTrue(sdk(ErrorDomain.Api, secondaryCode = 429).isRetryable())
        assertTrue(sdk(ErrorDomain.Api, secondaryCode = 500).isRetryable())
        assertTrue(sdk(ErrorDomain.Api, secondaryCode = 503).isRetryable())
        assertFalse(sdk(ErrorDomain.Api, secondaryCode = 409).isRetryable())
        assertFalse(sdk(ErrorDomain.Api, secondaryCode = 422).isRetryable())
        assertFalse(sdk(ErrorDomain.Api).isRetryable())
    }

    @Test
    fun sdkWrappedByAnotherExceptionIsFound() {
        assertTrue(IllegalStateException("wrapped", sdk(ErrorDomain.Transport)).isRetryable())
    }

    @Test
    fun otherErrorsAreNotRetryable() {
        assertFalse(sdk(ErrorDomain.Cryptography).isRetryable())
        assertFalse(sdk(ErrorDomain.DataIntegrity).isRetryable())
        assertFalse(IllegalArgumentException("bad").isRetryable())
        assertFalse(ProtonDriveSdkException("no error data").isRetryable())
    }
}
