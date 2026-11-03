package moe.n4tsu.dextop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceMatchTest {
    private val xiaomiAurora = DeviceIdentity(
        "Xiaomi", "Xiaomi", "24030PN60G", "aurora", "aurora_global", "Xiaomi/aurora_global/aurora:16/build", 36,
    )

    @Test fun everyXiaomiDeviceUsesTheHyperosProfileAndStartsOnTheDextopWindowManager() {
        for (identity in listOf(
            xiaomiAurora,
            xiaomiAurora.copy(model = "other", device = "other"),
            xiaomiAurora.copy(sdk = 33), xiaomiAurora.copy(sdk = 37),
            xiaomiAurora.copy(manufacturer = "Redmi", brand = "Redmi"),
            xiaomiAurora.copy(manufacturer = "POCO", brand = "POCO"),
        )) {
            val profile = DesktopEnvironmentRegistry.resolve(identity)
            assertEquals("xiaomi_hyperos", profile.id)
            assertTrue(profile.directPlasmaDisplay)
            assertTrue(profile.untrustedMirrorDisplay)
            assertTrue(profile.fullscreenApplicationDisplays)
            assertFalse(profile.privateApplicationDisplays) // Public displays allow Gboard candidate windows.
            assertTrue(profile.passivePlasmaShellFocus)
            assertEquals("dextop_book", profile.defaultWindowManager)
        }
    }

    @Test fun bookIsTheDefaultOnXperiaAndOtherDevices() {
        val xperia = DeviceIdentity("Sony", "Sony", "A401SO", "A401SO", "A401SO", "Sony/A401SO/build", 36)
        assertEquals("dextop_book", DesktopEnvironmentRegistry.resolve(xperia).defaultWindowManager)
        assertEquals("dextop_book", DesktopEnvironmentRegistry.resolve(pixel).defaultWindowManager)
    }

    private val pixel = DeviceIdentity("Google", "google", "Pixel 9", "tokay", "tokay", "google/tokay/build", 35)

    @Test fun normalizesAndMatchesAllSpecifiedFields() {
        val match = DeviceMatch(
            manufacturers = setOf("google"), models = setOf("pixel 9"),
            devices = setOf("tokay"), fingerprintPrefixes = setOf("google/tokay"),
            minSdk = 35, maxSdk = 35
        )
        assertTrue(match.matches(pixel))
    }

    @Test fun modelOverrideDoesNotLeakToOtherModels() {
        val match = DeviceMatch(manufacturers = setOf("google"), models = setOf("pixel 9"))
        assertFalse(match.matches(pixel.copy(model = "Pixel 8")))
        assertFalse(match.matches(pixel.copy(manufacturer = "Samsung", model = "Pixel 9")))
    }

    @Test fun sdkRangeIsIsolated() {
        val match = DeviceMatch(manufacturers = setOf("google"), minSdk = 35, maxSdk = 35)
        assertFalse(match.matches(pixel.copy(sdk = 34)))
        assertFalse(match.matches(pixel.copy(sdk = 36)))
    }

    @Test fun legacyPixelProfileRestoresAospFlagsAndOlderMirrorOrder() {
        val environment = DesktopEnvironmentRegistry.legacyPixelAosp(pixel.copy(sdk = 36))
        assertEquals(DesktopStartupMode.COMPAT_WINDOWING, environment.startupMode)
        assertTrue(environment.configureFreeformWindowing)
        assertEquals("1", environment.temporaryGlobalSettings["enable_freeform_support"])
        assertEquals("1", environment.temporaryGlobalSettings["force_resizable_activities"])
        assertEquals("window_manager", environment.mirrorStrategies.first())
    }

    @Test fun modernPixelProfileForcesWindowingFlagsOnVirtualDisplay() {
        val environment = DesktopEnvironmentRegistry.resolve(pixel.copy(sdk = 37))
        assertEquals(DesktopStartupMode.SYSTEM_MANAGED, environment.startupMode)
        assertFalse(environment.configureFreeformWindowing)
        assertEquals("virtual_display", environment.mirrorStrategies.first())
        assertEquals("1", environment.temporaryGlobalSettings["force_resizable_activities"])
        assertEquals("1", environment.temporaryGlobalSettings["force_desktop_mode_on_external_displays"])
        assertEquals(null, environment.temporaryGlobalSettings["override_desktop_experience_features"])
        assertEquals(null, environment.temporaryGlobalSettings["override_desktop_mode_features"])
        assertEquals(null, environment.temporaryGlobalSettings["enable_non_resizable_multi_window"])
        assertEquals(null, environment.temporaryGlobalSettings["force_desktop_mode_on_secondary_displays"])
        assertEquals(null, environment.temporaryGlobalSettings["enable_freeform_support"])
    }

    @Test fun galaxyTriFoldUsesNarrowAutoResizeProfile() {
        val triFold = DeviceIdentity(
            "samsung", "samsung", "SM-F968N", "q7mq", "q7mqksx",
            "samsung/q7mqksx/q7mq:16/build", 36
        )
        val environment = DesktopEnvironmentRegistry.resolve(triFold)
        assertEquals("samsung_dex", environment.id)
        assertTrue(environment.autoResizeWithHostDisplay)

        val fold7 = triFold.copy(model = "SM-F966Q", device = "q7q", product = "q7qjpnw")
        assertFalse(DesktopEnvironmentRegistry.resolve(fold7).autoResizeWithHostDisplay)
    }

    /**
     * Galaxy Tab A9 Wi-Fi, SM-X110, One UI 7 / Android 15: Samsung firmware
     * with no DeX service. Before this patch the manufacturer-wide Samsung
     * rule selected the DeX profile, which configures nothing because it
     * expects DeX to own windowing - so every activity launched on the Dextop
     * display stayed fullscreen.
     */
    private val galaxyTabA9 = DeviceIdentity(
        "samsung", "samsung", "SM-X110", "gta9wifi", "gta9wifixx",
        "samsung/gta9wifixx/gta9wifi:15/AP3A.240905.015/XXU1:user/release-keys", 35,
        platformDesktopAvailable = false
    )

    @Test fun samsungFirmwareWithoutDexConfiguresFreeformItself() {
        val environment = DesktopEnvironmentRegistry.resolve(galaxyTabA9)
        assertEquals("samsung_windowed", environment.id)
        assertFalse(environment.platformManaged)
        assertTrue(environment.requiresFreeformDisplay)
        assertTrue(environment.configureFreeformWindowing)
        assertEquals("dextop_book", environment.defaultWindowManager)
        assertEquals(DesktopStartupMode.COMPAT_WINDOWING, environment.startupMode)
        // Decorations provide the platform caption a freeform task is dragged
        // and sized by; there is no DeX taskbar on this firmware.
        assertEquals(DisplayChromeMode.VISIBLE, environment.displayChromeMode)
        assertEquals("1", environment.freeformFallbackSettings["enable_freeform_support"])
        assertEquals("1", environment.freeformFallbackSettings["force_resizable_activities"])
        assertEquals("1", environment.freeformFallbackSettings["enable_non_resizable_multi_window"])
        // Forcing desktop mode on every public secondary display would also
        // turn the compositing shell's application displays into freeform
        // windows; the Dextop display is switched per display instead.
        assertFalse(
            environment.freeformFallbackSettings.containsKey(
                "force_desktop_mode_on_external_displays"
            )
        )
        // The session-start settings must not force desktop mode either, or
        // every public application display of the compositing shell becomes
        // a floating window.
        assertFalse(
            environment.temporaryGlobalSettings.containsKey(
                "force_desktop_mode_on_external_displays"
            )
        )
    }

    @Test fun samsungFirmwareWithDexKeepsPlatformWindowing() {
        val dexTablet = galaxyTabA9.copy(
            model = "SM-X710", device = "gts9wifi", product = "gts9wifixx",
            platformDesktopAvailable = true
        )
        val environment = DesktopEnvironmentRegistry.resolve(dexTablet)
        assertEquals("samsung_dex", environment.id)
        assertTrue(environment.platformManaged)
        assertTrue(environment.requiresFreeformDisplay)
        assertTrue(environment.freeformFallbackSettings.isNotEmpty())
    }

    @Test fun anUnprobedIdentityKeepsTheDexAssumption() {
        // Callers that only have a Build snapshot (and every existing test)
        // keep the previous behaviour; production always probes.
        val environment = DesktopEnvironmentRegistry.resolve(
            galaxyTabA9.copy(platformDesktopAvailable = null)
        )
        assertEquals("samsung_dex", environment.id)
        assertTrue(environment.platformManaged)
    }

    @Test fun desktopStackOverrideParsesOperatorsAndUnknownValues() {
        assertEquals(DesktopStackOverride.WINDOWED, DesktopStackOverride.parse("freeform"))
        assertEquals(DesktopStackOverride.WINDOWED, DesktopStackOverride.parse(" DEXTOP "))
        assertEquals(DesktopStackOverride.PLATFORM, DesktopStackOverride.parse("dex"))
        assertEquals(DesktopStackOverride.PLATFORM, DesktopStackOverride.parse("platform"))
        assertEquals(DesktopStackOverride.AUTO, DesktopStackOverride.parse(null))
        assertEquals(DesktopStackOverride.AUTO, DesktopStackOverride.parse("something-else"))
    }

    @Test fun regionalSamsungCarrierModelPrefixesUseSamsungProfile() {
        listOf("SC-56F", "SCG39", "SCV46").forEach { model ->
            val identity = DeviceIdentity(
                "unknown", "unknown", model, model, model,
                "unknown/$model/build", 36
            )
            val environment = DesktopEnvironmentRegistry.resolve(identity)
            assertEquals("samsung_dex", environment.id)
            assertTrue(environment.platformManaged)
        }
    }
}
