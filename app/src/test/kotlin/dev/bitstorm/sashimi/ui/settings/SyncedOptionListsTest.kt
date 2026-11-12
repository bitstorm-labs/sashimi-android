package dev.bitstorm.sashimi.ui.settings

import dev.bitstorm.sashimi.core.settingssync.SettingsSyncSchema
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every value another device can write is one Settings can show (settings sync, WORK-106). */
class SyncedOptionListsTest {
    @Test
    fun `resume threshold offers the shared list, 10 minutes included`() {
        assertEquals(SettingsSyncSchema.RESUME_THRESHOLDS, ResumeThresholdOptions.values.toList())
        assertEquals("10 minutes", ResumeThresholdOptions.entries.last().key)
    }

    @Test
    fun `languages are device default plus the 18 shared ones in order`() {
        assertEquals(listOf("") + SettingsSyncSchema.LANGUAGES, LanguageOptions.values.toList())
    }
}
