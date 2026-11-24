package com.morph.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsRestoreVaultSearch() {
        fun tab(name:String) = compose.onNodeWithTag("tab-$name")
        tab("Vault").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("persistent")
        repeat(3) {
            tab("Settings").performClick()
            tab("Queue").performClick()
            tab("Convert").performClick()
            tab("Vault").performClick()
        }
        compose.onNode(hasSetTextAction() and hasText("persistent")).assertExists()
    }
}
