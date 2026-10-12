/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.attachmentpreview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** With the Russian UI language no English string may show up on the preview screen, the sheet or the camera. */
class AttachmentStringsRussianTest {

    private val main = File("src/main")

    @Test
    fun everyStringOfThePreviewTheSheetAndTheCameraHasARussianTranslation() {
        val used = SCREEN_PACKAGES
            .flatMap { File(main, "java/com/nextcloud/talk/$it").walkTopDown().filter { f -> f.extension == "kt" } }
            .flatMap { STRING_REFERENCE.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()
        val russian = declaredNames(File(main, "res/values-ru/strings.xml"))
        val notTranslatable = NOT_TRANSLATABLE.findAll(File(main, "res/values/strings.xml").readText())
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(emptyList<String>(), (used - russian - notTranslatable).sorted())
    }

    private fun declaredNames(file: File): Set<String> =
        DECLARATION.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    companion object {
        private val SCREEN_PACKAGES = listOf("attachmentpreview", "attachmentsheet", "camera")
        private val STRING_REFERENCE = Regex("""R\.(?:string|plurals)\.([A-Za-z0-9_]+)""")
        private val DECLARATION = Regex("""<(?:string|plurals)\s+name="([^"]+)"""")
        private val NOT_TRANSLATABLE = Regex("""<string\s+name="([^"]+)"\s+translatable="false"""")
    }
}
