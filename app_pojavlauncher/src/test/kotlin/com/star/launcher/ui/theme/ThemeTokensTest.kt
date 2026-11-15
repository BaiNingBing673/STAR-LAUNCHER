package com.star.launcher.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThemeTokensTest {

    @Test
    fun `every compatibility badge tier maps to distinct label and foreground color`() {
        val styles = CompatibilityBadgeTier.values().map { badgeStyleFor(it) }
        assertEquals(5, styles.map { it.label }.distinct().size)
        assertEquals(5, styles.map { it.shortLabel }.distinct().size)
        assertEquals(5, styles.map { it.foreground }.distinct().size)
    }

    @Test
    fun `dark surface hierarchy stays visually separated`() {
        assertNotEquals(AetherColors.BackgroundDark, AetherColors.SurfaceDark)
        assertNotEquals(AetherColors.SurfaceDark, AetherColors.SurfaceVariantDark)
    }
}
