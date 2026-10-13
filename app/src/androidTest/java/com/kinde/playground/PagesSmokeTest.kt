package com.kinde.playground

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kinde.playground.ui.MainActivity
import com.kinde.playground.ui.Page
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every page opens from the home list and renders, signed in or out, configured or not. */
@RunWith(AndroidJUnit4::class)
class PagesSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyPageOpensAndGoesBack() {
        Page.menu.forEach { page ->
            compose.onNodeWithTag("page:HOME").performScrollToNode(hasTestTag("nav:${page.name}"))
            compose.onNodeWithTag("nav:${page.name}").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("page:${page.name}").assertExists("${page.title} did not render")
            compose.onNodeWithTag("title").assertExists()
            compose.onNodeWithText(page.title, useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun sessionChipIsShown() {
        compose.onNodeWithTag("session").assertExists()
    }

    @Test
    fun dashboardRunsSetupChecks() {
        compose.onNodeWithTag("nav:DASHBOARD").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag("summary:Setup")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
