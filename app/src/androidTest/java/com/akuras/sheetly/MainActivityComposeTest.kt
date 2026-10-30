package com.akuras.pdfscanner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivityComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanButtonIsVisible() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.scan_document)).assertIsDisplayed()
    }

    @Test
    fun historyPanelHeaderIsVisible() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.saved_pdfs)).assertIsDisplayed()
    }

    @Test
    fun emptyStateMessageIsVisible() {
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.no_scanned_documents)).assertIsDisplayed()
    }
}
