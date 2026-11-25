package com.morph.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

class NavigationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsRestoreVaultSearch() {
        fun tab(name:String) = compose.onNodeWithTag("tab-$name")
        val tabs = listOf("Settings", "Queue", "Convert", "Vault")
        tab("Vault").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("persistent")
        repeat(3) {
            for (name in tabs) {
                tab(name).performClick()
                compose.onNodeWithTag("panel-$name").assertIsDisplayed()
                tabs.filter { it != name }.forEach { other ->
                    compose.onNodeWithTag("panel-$other").assertDoesNotExist()
                }
            }
        }
        compose.onNode(hasSetTextAction() and hasText("persistent")).assertExists()
    }

    @Test fun shareIntentImportsWhileVaultIsOpenAndSurvivesRecreation() {
        val activity = compose.activity
        val directory = File(activity.filesDir, "exports/share-test").apply { mkdirs() }
        val photo = File(directory, "shared-report.json").apply { writeText("""{"shared":true}""") }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", photo)
        try {
            compose.onNodeWithTag("tab-Vault").performClick()
            compose.runOnUiThread {
                activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("report", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(photo.name).fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(photo.name).assertExists()
        } finally { directory.deleteRecursively() }
    }
}
