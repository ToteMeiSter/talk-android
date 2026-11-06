/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils.message

/**
 * CommonMark lets a single `*` inside a word open and close emphasis, so a product like `11.5*3.3*3.3` is rendered as
 * "11.5" + italic "3.3" + "3.3" and both asterisks disappear. People type `*` between numbers as a multiplication
 * sign, so a `*` with a digit on both sides is escaped (`\*`) and stays visible. Fenced code, indented code and inline
 * code spans are left untouched, because a backslash is not an escape there.
 *
 * Not handled: a code span that is split over two paragraphs is not a code span in CommonMark either.
 */
object MarkdownAsterisks {
    private const val ASTERISK = '*'
    private const val BACKTICK = '`'

    fun escapeBetweenDigits(markdown: String): String {
        if (!markdown.contains(ASTERISK)) return markdown

        val lines = markdown.split("\n")
        val inFence = MarkdownTables.fencedLines(lines.map { it.trimEnd('\r') })
        val result = StringBuilder(markdown.length + 1)
        val paragraph = StringBuilder()
        var afterBlankOrCode = true

        for ((index, line) in lines.withIndex()) {
            val isCode = inFence[index] || (afterBlankOrCode && MarkdownTables.isIndentedCode(line))
            if (isCode && paragraph.isNotEmpty()) {
                result.append(escapeOutsideInlineCode(paragraph.toString()))
                paragraph.setLength(0)
            }
            (if (isCode) result else paragraph).append(line)
            if (index < lines.lastIndex) (if (isCode) result else paragraph).append('\n')
            afterBlankOrCode = isCode || line.isBlank()
        }
        result.append(escapeOutsideInlineCode(paragraph.toString()))
        return result.toString()
    }

    private fun escapeOutsideInlineCode(text: String): String {
        val out = StringBuilder(text.length + 1)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == BACKTICK) {
                val runEnd = backtickRunEnd(text, i)
                val close = findClosingRun(text, runEnd, runEnd - i)
                val end = if (close < 0) runEnd else close + (runEnd - i)
                out.append(text, i, end)
                i = end
            } else {
                if (c == ASTERISK && i > 0 && text[i - 1].isDigit() && text.getOrNull(i + 1)?.isDigit() == true) {
                    out.append('\\')
                }
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun backtickRunEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] == BACKTICK) end++
        return end
    }

    /** Start index of the next backtick run of exactly [length] characters at or after [from], or -1. */
    private fun findClosingRun(text: String, from: Int, length: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == BACKTICK) {
                val end = backtickRunEnd(text, i)
                if (end - i == length) return i
                i = end
            } else {
                i++
            }
        }
        return -1
    }
}
