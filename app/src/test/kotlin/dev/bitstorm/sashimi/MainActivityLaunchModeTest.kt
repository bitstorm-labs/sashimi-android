package dev.bitstorm.sashimi

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * There must only ever be one MainActivity. With the default launch mode a
 * sashimi:// deep link while the app was open stacked a second one: two app
 * shells both navigating to the link, and the covered one's onStop stopping
 * the hero trailer the new detail screen had just scheduled.
 */
class MainActivityLaunchModeTest {
    @Test
    fun `MainActivity is singleTask so deep links reach onNewIntent`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        val main =
            (0 until activities.length)
                .map { activities.item(it) as Element }
                .single { it.getAttributeNS(ANDROID_NS, "name") == ".MainActivity" }
        assertEquals("singleTask", main.getAttributeNS(ANDROID_NS, "launchMode"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
