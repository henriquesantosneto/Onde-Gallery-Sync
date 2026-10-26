package com.onegallery.sync.auth

import com.onegallery.sync.TestApplication
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class MsalConfigFactoryTest {

    @Test
    fun writeConfigFile_keepsSingleAccountRedirectWithoutTruncating() {
        val context = RuntimeEnvironment.getApplication()
        val dest = File(context.filesDir, "msal_auth_config.json")
        dest.writeText("")
        val clientId = "11111111-1111-1111-1111-111111111111"
        val file = MsalConfigFactory.writeConfigFile(context, clientId)
        val text = file.readText()
        assertTrue(text.contains("\"redirect_uri\": \"msauth://"))
        assertTrue(text.contains("\"account_mode\": \"SINGLE\""))
        assertTrue(text.contains("\"broker_redirect_uri_registered\": false"))
        assertTrue(text.contains(clientId))
        assertTrue(file.length() > 50)
        assertFalse(File(context.filesDir, "msal_auth_config.json.tmp").exists())
        val again = MsalConfigFactory.writeConfigFile(context, clientId)
        assertTrue(again.readBytes().contentEquals(file.readBytes()))
    }
}
