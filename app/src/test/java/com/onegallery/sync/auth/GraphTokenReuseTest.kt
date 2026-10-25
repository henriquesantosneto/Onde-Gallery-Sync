package com.onegallery.sync.auth

import com.onegallery.sync.TestApplication
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GraphTokenReuseTest {

    @Test
    fun jwtExp_isReadInMillis() {
        val expSeconds = 1_800_000_000L
        val token = unsignedJwt("""{"exp":$expSeconds}""")
        assertEquals(expSeconds * 1000L, jwtExpiresAtEpochMs(token))
    }

    @Test
    fun jwtWithoutExp_isIgnored() {
        assertNull(jwtExpiresAtEpochMs(unsignedJwt("""{"aud":"graph"}""")))
        assertNull(jwtExpiresAtEpochMs("not-a-jwt"))
    }

    private fun unsignedJwt(payloadJson: String): String {
        val header = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }
}
