package dev.bitstorm.sashimi.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive Auto (#155), ported from Apple's PlaybackSelectionTests for sustainedUpgrade / wirelessCeiling. */
class AdaptiveAutoTest {
    // MARK: Link kind

    @Test
    fun `ethernet is wired, metered wins over ethernet, anything else is wireless`() {
        assertEquals(LinkKind.WIRED, LinkKind.classify(isEthernet = true, isMetered = false))
        assertEquals(LinkKind.METERED, LinkKind.classify(isEthernet = true, isMetered = true))
        assertEquals(LinkKind.METERED, LinkKind.classify(isEthernet = false, isMetered = true))
        assertEquals(LinkKind.WIRELESS, LinkKind.classify(isEthernet = false, isMetered = false))
    }

    // MARK: Ceiling

    @Test
    fun `an unremembered wireless link is held to the smooth 4K bitrate`() {
        assertEquals(24_000_000, AdaptiveAuto.wirelessCeiling(cap = 100_000_000, sustainedBitrate = null))
        // Never above the cap.
        assertEquals(8_500_000, AdaptiveAuto.wirelessCeiling(cap = 8_500_000, sustainedBitrate = null))
    }

    @Test
    fun `a remembered wireless link is trusted with half its sustained rate, capped by Auto`() {
        // ~378 Mbps measured: a 44 Mbps film fits under the 100 Mbps cap.
        assertEquals(100_000_000, AdaptiveAuto.wirelessCeiling(cap = 100_000_000, sustainedBitrate = 378_000_000))
        assertEquals(60_000_000, AdaptiveAuto.wirelessCeiling(cap = 100_000_000, sustainedBitrate = 120_000_000))
        // A slow memory lowers it below the smooth-4K clamp.
        assertEquals(5_000_000, AdaptiveAuto.wirelessCeiling(cap = 100_000_000, sustainedBitrate = 10_000_000))
    }

    @Test
    fun `the wireless ceiling never drops below the lowest tier`() {
        assertEquals(720_000, AdaptiveAuto.wirelessCeiling(cap = 4_000_000, sustainedBitrate = 300_000))
    }

    @Test
    fun `wired keeps the probe cap, wireless and metered use the remembered rate`() {
        assertEquals(100_000_000, AdaptiveAuto.linkCeiling(100_000_000, LinkKind.WIRED, sustainedBitrate = 10_000_000))
        assertEquals(5_000_000, AdaptiveAuto.linkCeiling(100_000_000, LinkKind.WIRELESS, sustainedBitrate = 10_000_000))
        assertEquals(24_000_000, AdaptiveAuto.linkCeiling(100_000_000, LinkKind.METERED, sustainedBitrate = null))
    }

    // MARK: Cool-down

    @Test
    fun `the cool-down is two minutes, doubling per failed upgrade up to 32x`() {
        assertEquals(120_000L, AdaptiveAuto.cooldownMs(0))
        assertEquals(240_000L, AdaptiveAuto.cooldownMs(1))
        assertEquals(120_000L * 32, AdaptiveAuto.cooldownMs(5))
        assertEquals(120_000L * 32, AdaptiveAuto.cooldownMs(9))
        assertEquals(120_000L, AdaptiveAuto.cooldownMs(-1))
    }

    // MARK: Sustained upgrade

    private val heldDown =
        SustainedUpgradeContext(
            isAuto = true,
            linkKind = LinkKind.WIRELESS,
            autoCap = 100_000_000,
            activeCap = 24_000_000,
            sourceBitrate = 44_000_000,
            sustainedBitrate = 120_000_000,
            isTranscoding = true,
            isBusy = false,
            msSinceRecovery = null,
            msSinceQualityChange = null,
            failedUpgrades = 0,
        )

    @Test
    fun `a link that now carries the source rebuilds on Auto`() {
        assertEquals(UpgradeDecision.Now, AdaptiveAuto.sustainedUpgrade(heldDown))
    }

    @Test
    fun `a link at twice the current cap rebuilds even below the source`() {
        // A 4 Mbps step-down, a 60 Mbps source, a link that sustains 20 Mbps:
        // ceiling 10 Mbps >= 2 x 4.
        val c = heldDown.copy(activeCap = 4_000_000, sourceBitrate = 60_000_000, sustainedBitrate = 20_000_000)
        assertEquals(UpgradeDecision.Now, AdaptiveAuto.sustainedUpgrade(c))
    }

    @Test
    fun `not enough headroom stays put`() {
        // Ceiling 30 Mbps: under the 44 Mbps source and under 2 x 24.
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(sustainedBitrate = 60_000_000)))
    }

    @Test
    fun `wired trusts the sustained rate itself, not half`() {
        val c = heldDown.copy(linkKind = LinkKind.WIRED, sustainedBitrate = 50_000_000)
        assertEquals(UpgradeDecision.Now, AdaptiveAuto.sustainedUpgrade(c))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(c.copy(linkKind = LinkKind.WIRELESS)))
    }

    @Test
    fun `the Auto cap still bounds an upgrade`() {
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(autoCap = 30_000_000)))
    }

    @Test
    fun `never on a viewer's pick, a direct play, a stream already at its source, metered data, or no reading`() {
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(isAuto = false)))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(isTranscoding = false)))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(activeCap = 44_000_000)))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(linkKind = LinkKind.METERED)))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(sustainedBitrate = null)))
        assertEquals(UpgradeDecision.None, AdaptiveAuto.sustainedUpgrade(heldDown.copy(sourceBitrate = null)))
    }

    @Test
    fun `waits out the cool-down since the last automatic change`() {
        assertEquals(UpgradeDecision.Later(20_000), AdaptiveAuto.sustainedUpgrade(heldDown.copy(msSinceQualityChange = 100_000)))
        assertEquals(UpgradeDecision.Now, AdaptiveAuto.sustainedUpgrade(heldDown.copy(msSinceQualityChange = 120_000)))
        // Two failed upgrades: 8 minutes.
        assertEquals(
            UpgradeDecision.Later(480_000L - 200_000L),
            AdaptiveAuto.sustainedUpgrade(heldDown.copy(msSinceQualityChange = 200_000, failedUpgrades = 2)),
        )
    }

    @Test
    fun `waits while busy and just after a recovery`() {
        assertEquals(UpgradeDecision.Later(15_000), AdaptiveAuto.sustainedUpgrade(heldDown.copy(isBusy = true)))
        assertEquals(UpgradeDecision.Later(10_000), AdaptiveAuto.sustainedUpgrade(heldDown.copy(msSinceRecovery = 5_000)))
    }

    // MARK: Session bookkeeping

    private var now = 1_000_000L
    private val state = AdaptiveAutoState { now }

    @Test
    fun `a stall step-down from Auto stays Auto's, a viewer pick does not`() {
        assertTrue(state.isViewerOnAuto(0, QualityOption.AUTO))
        assertFalse(state.isViewerOnAuto(0, QualityOption.P720))
        state.onStepDown(fromViewerAuto = true)
        assertTrue(state.isViewerOnAuto(0, QualityOption.P720))
        state.onViewerPick()
        assertFalse(state.isViewerOnAuto(0, QualityOption.P720))
    }

    @Test
    fun `a Settings cap is never Auto`() {
        state.onStepDown(fromViewerAuto = true)
        assertFalse(state.isViewerOnAuto(8_000_000, QualityOption.AUTO))
        assertFalse(state.isViewerOnAuto(-1, QualityOption.P720))
    }

    @Test
    fun `a step-down from a viewer's pick is not Auto's`() {
        state.onStepDown(fromViewerAuto = false)
        assertFalse(state.isViewerOnAuto(0, QualityOption.P480))
    }

    @Test
    fun `an upgrade that stalls inside its cool-down counts as failed, a later stall does not`() {
        assertNull(state.msSinceQualityChange())
        state.onUpgrade()
        now += 60_000
        state.onStepDown(fromViewerAuto = true)
        assertEquals(1, state.failedUpgrades)
        assertEquals(0L, state.msSinceQualityChange())

        state.onUpgrade()
        // The cool-down is now 4 minutes; a stall 5 minutes on is the link, not the upgrade.
        now += 300_000
        state.onStepDown(fromViewerAuto = true)
        assertEquals(1, state.failedUpgrades)
    }

    @Test
    fun `an upgrade clears the step-down mark`() {
        state.onStepDown(fromViewerAuto = true)
        state.onUpgrade()
        assertFalse(state.isViewerOnAuto(0, QualityOption.P720))
        assertTrue(state.isViewerOnAuto(0, QualityOption.AUTO))
    }
}
