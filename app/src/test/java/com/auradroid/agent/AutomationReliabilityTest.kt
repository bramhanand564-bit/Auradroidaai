package com.auradroid.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationReliabilityTest {
    @Test
    fun uploadToYoutubeRoutesToStudioWebWorkflow() {
        val route = AppAutomationRouter.chooseRoute("Upload my latest video to YouTube", suggestedMode = "PHONE")
        assertEquals(AutomationMode.BACKGROUND_WEB, route.mode)
        assertEquals("https://studio.youtube.com/", route.startUrl)
        assertEquals("YOUTUBE_STUDIO", route.profile?.id)
    }

    @Test
    fun explicitOpenAppUsesPhoneUi() {
        val route = AppAutomationRouter.chooseRoute("Open WhatsApp", suggestedMode = "WEB")
        assertEquals(AutomationMode.PHONE_UI, route.mode)
        assertEquals("WHATSAPP", route.profile?.id)
    }

    @Test
    fun explicitRealPhoneRequestOverridesWebSuggestion() {
        val route = AppAutomationRouter.chooseRoute(
            goal = "Control my phone and open Chrome",
            suggestedMode = "WEB",
            forcePhoneUi = true
        )
        assertEquals(AutomationMode.PHONE_UI, route.mode)
    }

    @Test
    fun urlInGoalWinsOverModelSuggestedUrl() {
        val route = AppAutomationRouter.chooseRoute(
            goal = "Visit https://example.com/dashboard",
            suggestedMode = "PHONE",
            suggestedUrl = "https://labs.google/fx/tools/flow",
            pinnedFlowUrl = "https://labs.google/fx/tools/flow"
        )
        assertEquals(AutomationMode.BACKGROUND_WEB, route.mode)
        assertEquals("https://example.com/dashboard", route.startUrl)
    }

    @Test
    fun unrelatedTaskNeverFallsBackToPinnedFlowPage() {
        val resolved = AppAutomationRouter.resolveStartUrl(
            goal = "Search for weather",
            suggestedUrl = "https://labs.google/fx/tools/flow",
            pinnedFlowUrl = "https://labs.google/fx/tools/flow"
        )
        assertNotEquals("https://labs.google/fx/tools/flow", resolved)
        assertEquals("https://www.google.com/", resolved)
    }

    @Test
    fun unsafeExplicitUrlIsNotReplacedBySuggestedDestination() {
        val resolved = AppAutomationRouter.resolveStartUrl(
            goal = "Open https://172.16.1.9/admin",
            suggestedUrl = "https://example.com/",
            pinnedFlowUrl = ""
        )
        assertEquals("https://www.google.com/", resolved)
    }

    @Test
    fun youtubeUploadDoesNotGetMistakenForNativeLaunch() {
        val route = AppAutomationRouter.chooseRoute("Open YouTube and upload my newest video")
        assertEquals(AutomationMode.BACKGROUND_WEB, route.mode)
        assertEquals("https://studio.youtube.com/", route.startUrl)
    }

    @Test
    fun explicitWebsiteRequestUsesWebMode() {
        val route = AppAutomationRouter.chooseRoute("Open the WhatsApp website in a browser")
        assertEquals(AutomationMode.BACKGROUND_WEB, route.mode)
        assertEquals("https://web.whatsapp.com/", route.startUrl)
    }

    @Test
    fun ordinaryDriveVerbDoesNotSelectGoogleDriveAdapter() {
        assertNull(AppAutomationRouter.profileForGoal("I need to drive to work"))
    }

    @Test
    fun dangerousSchemesAndLocalHostsAreRejected() {
        assertNull(AppAutomationRouter.normalizeHttpUrl("javascript:alert(1)"))
        assertNull(AppAutomationRouter.normalizeHttpUrl("http://localhost/admin"))
        assertNull(AppAutomationRouter.normalizeHttpUrl("https://192.168.1.2/"))
        assertNull(AppAutomationRouter.normalizeHttpUrl("https://172.16.2.3/"))
        assertNull(AppAutomationRouter.normalizeHttpUrl("https://[::1]/"))
    }

    @Test
    fun plainHttpIsUpgradedToHttps() {
        assertEquals("https://example.com/path", AppAutomationRouter.normalizeHttpUrl("http://example.com/path"))
    }

    @Test
    fun urlAwareProfilesIdentifyPopularWebDestinations() {
        assertEquals("YOUTUBE_STUDIO", AppAutomationRouter.profileForUrl("https://studio.youtube.com/channel/abc")?.id)
        assertEquals("GMAIL", AppAutomationRouter.memoryDomainForWebGoal("Click the next button", "https://mail.google.com/mail/u/0/"))
        assertEquals("WHATSAPP", AppAutomationRouter.profileForUrl("https://web.whatsapp.com/")?.id)
    }

    @Test
    fun popularAppProfilesAreRegistered() {
        val ids = AppAutomationRouter.allProfiles().map { it.id }
        assertTrue(ids.containsAll(listOf("CHROME", "YOUTUBE", "YOUTUBE_STUDIO", "WHATSAPP", "INSTAGRAM", "GMAIL", "GOOGLE_DRIVE")))
        assertNotNull(AppAutomationRouter.profileForPackage("com.android.chrome"))
    }
}
