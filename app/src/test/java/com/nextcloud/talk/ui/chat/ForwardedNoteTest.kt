/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.nextcloud.talk.ui.chat

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForwardedNoteTest {

    private val registerActivity = object : ExternalResource() {
        override fun before() {
            val application = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(application.packageManager)
                .addActivityIfNotPresent(ComponentName(application.packageName, ComponentActivity::class.java.name))
        }
    }
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(composeRule)

    private val templates = listOf("Forwarded from %1\$s", "Переслано от %1\$s")

    @Test
    fun splitsTheFirstLineInEnglishAndRussian() {
        assertEquals(
            ForwardedNote("Forwarded from Anna", "hello\nworld"),
            splitForwardedNote("Forwarded from Anna\nhello\nworld", templates)
        )
        assertEquals(
            ForwardedNote("Переслано от Анна Б.", "привет"),
            splitForwardedNote("Переслано от Анна Б.\nпривет", templates)
        )
    }

    @Test
    fun noteWithoutBodyHasEmptyBody() {
        assertEquals(
            ForwardedNote("Forwarded from Anna", ""),
            splitForwardedNote("Forwarded from Anna", templates)
        )
    }

    @Test
    fun textWithoutTheNoteIsNotTouched() {
        assertNull(splitForwardedNote("hello\nForwarded from Anna", templates))
        assertNull(splitForwardedNote("I Forwarded from Anna", templates))
        assertNull(splitForwardedNote("Forwarded from ", templates))
        assertNull(splitForwardedNote("Forwarded from", templates))
        assertNull(splitForwardedNote("", templates))
    }

    @Test
    fun theStringResourceFitsTheTemplatePattern() {
        assertEquals("Forwarded from %1\$s", resourceTemplate("values"))
    }

    private fun resourceTemplate(folder: String): String {
        val xml = File(System.getProperty("user.dir"), "src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="nc_chat_forwarded_from">(.*?)</string>""").find(xml)!!.groupValues[1]
    }

    @Test
    fun noteIsDrawnApartFromTheBody_andLeavesPlainTextAlone() {
        val messages = listOf(
            "Переслано от Анна Б.\nТекст пересланного сообщения",
            "Forwarded from Anna\nSample forwarded body",
            "Message without the note"
        ).map { createBaseMessage(null).copy(message = it, plainMessage = it) }
        composeRule.setContent {
            MaterialTheme {
                // inspection mode: the emoji check of the text needs EmojiCompat, which a unit test does not start
                CompositionLocalProvider(
                    LocalForwardedNoteTemplates provides templates,
                    LocalInspectionMode provides true
                ) {
                    Column(Modifier.background(Color.White).padding(12.dp)) {
                        messages.forEach {
                            EnrichedText(it, Modifier.padding(bottom = 10.dp))
                        }
                    }
                }
            }
        }
        composeRule.onRoot().assertExists()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("user.dir"), "build/forwarded-note").apply { mkdirs() }
        File(dir, "forwarded-note.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 0)
    }
}
