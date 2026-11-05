/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.message

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownAsterisksTest {

    // Same parser as Markwon's: commonmark core. The extensions Talk adds do not touch emphasis.
    private val parser = Parser.builder().build()

    private class Collected {
        var emphasis = 0
        var strong = 0
        val text = StringBuilder()
        val code = StringBuilder()
    }

    /** Parses the text the way the chat does (shared preprocessing, then commonmark) and collects what is shown. */
    private fun render(markdown: String): Collected {
        val result = Collected()
        parser.parse(MessageUtils.prepareMarkdown(markdown)).accept(object : AbstractVisitor() {
            override fun visit(emphasis: Emphasis) {
                result.emphasis++
                super.visit(emphasis)
            }

            override fun visit(strongEmphasis: StrongEmphasis) {
                result.strong++
                super.visit(strongEmphasis)
            }

            override fun visit(text: Text) {
                result.text.append(text.literal)
            }

            override fun visit(code: Code) {
                result.code.append(code.literal)
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                result.code.append(fencedCodeBlock.literal)
            }

            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                result.code.append(indentedCodeBlock.literal)
            }
        })
        return result
    }

    @Test
    fun `asterisks between digits stay visible and make no emphasis`() {
        val shown = render("11.5*3.3*3.3")
        assertEquals("11.5*3.3*3.3", shown.text.toString())
        assertEquals(0, shown.emphasis)
    }

    @Test
    fun `plain multiplication of integers stays visible`() {
        val shown = render("2*3*4")
        assertEquals("2*3*4", shown.text.toString())
        assertEquals(0, shown.emphasis)
    }

    @Test
    fun `emphasis around words is kept`() {
        val shown = render("*italic*")
        assertEquals("italic", shown.text.toString())
        assertEquals(1, shown.emphasis)
    }

    @Test
    fun `strong emphasis is kept`() {
        val shown = render("**bold**")
        assertEquals("bold", shown.text.toString())
        assertEquals(1, shown.strong)
        assertEquals(0, shown.emphasis)
    }

    @Test
    fun `emphasis inside a sentence is kept`() {
        val shown = render("text *italic* text")
        assertEquals("text italic text", shown.text.toString())
        assertEquals(1, shown.emphasis)
    }

    @Test
    fun `strong italic is kept`() {
        val shown = render("***both***")
        assertEquals("both", shown.text.toString())
        assertEquals(1, shown.emphasis)
        assertEquals(1, shown.strong)
    }

    @Test
    fun `emphasis around numbers is kept`() {
        val shown = render("a *3* b")
        assertEquals("a 3 b", shown.text.toString())
        assertEquals(1, shown.emphasis)
    }

    @Test
    fun `asterisks in inline code are not changed`() {
        val text = "`2*3*4` and 5*6"
        val shown = render(text)
        assertEquals("2*3*4", shown.code.toString())
        assertEquals(" and 5*6", shown.text.toString())
        assertEquals("`2*3*4` and 5\\*6", MessageUtils.prepareMarkdown(text))
    }

    @Test
    fun `asterisks in fenced code are not changed`() {
        val text = "before 1*2\n```\n2*3*4\n```\nafter 3*4"
        val shown = render(text)
        assertEquals("2*3*4\n", shown.code.toString())
        assertEquals("before 1*2after 3*4", shown.text.toString().replace("\n", ""))
        assertEquals("before 1\\*2\n```\n2*3*4\n```\nafter 3\\*4", MessageUtils.prepareMarkdown(text))
    }

    @Test
    fun `asterisks in indented code are not changed`() {
        val text = "text\n\n    2*3*4\n"
        assertEquals("2*3*4\n", render(text).code.toString())
        assertEquals(text, MessageUtils.prepareMarkdown(text))
    }

    @Test
    fun `text without asterisks is returned as is`() {
        val text = "no stars 1 2 3"
        assertEquals(text, MarkdownAsterisks.escapeBetweenDigits(text))
    }

    @Test
    fun `line breaks are kept`() {
        assertEquals("1\\*2\r\n3\\*4\n", MarkdownAsterisks.escapeBetweenDigits("1*2\r\n3*4\n"))
    }
}
