/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import com.nextcloud.talk.logger.FakeLogcat.Companion.eventually
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Runs the real shell wrapper on the host with a fake `logcat` script, to check quoting, stop and the end of logcat
 * when the parent dies. (On a device the program is the real logcat; that part is not covered here.)
 */
class ProcessLogcatLauncherTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var fakeLogcat: File

    @Before
    fun setUp() {
        assumeTrue(File("/proc/self").exists() && File("/bin/sh").exists())
        fakeLogcat = folder.newFile("fakelogcat")
        fakeLogcat.writeText(
            """
            #!/bin/sh
            echo "pid ${'$'}${'$'}"
            echo "args ${'$'}*"
            echo "--------- beginning of main"
            echo "10-07 12:00:00.000  1  2 I Tag: hello"
            exec sleep 120
            """.trimIndent() + "\n"
        )
        fakeLogcat.setExecutable(true)
    }

    private fun alive(pid: Int) = File("/proc/$pid").exists() && !File("/proc/$pid/stat").readText().contains(") Z ")

    @Test
    fun `the wrapper passes the arguments and stop ends logcat`() {
        val stream = ProcessLogcatLauncher(logcat = fakeLogcat.absolutePath)
            .launch(listOf("-v", "threadtime", "--pid=4242", "-T", "10-07 12:00:00.000", "*:D"))
        val pid = stream.readLine()!!.removePrefix("pid ").toInt()
        assertEquals("args -v threadtime --pid=4242 -T 10-07 12:00:00.000 *:D", stream.readLine())
        assertEquals("--------- beginning of main", stream.readLine())
        assertEquals("10-07 12:00:00.000  1  2 I Tag: hello", stream.readLine())
        assertTrue(alive(pid))

        stream.close()
        assertTrue("logcat must be gone after stop", eventually(6000) { !alive(pid) })
    }

    @Test
    fun `logcat ends when the process that started the wrapper dies`() {
        val script = ProcessLogcatLauncher.WRAPPER_SCRIPT
        // the parent shell stands for the app: the wrapper's $PPID is this shell
        val parent = ProcessBuilder("sh", "-c", "sh -c \"${'$'}WRAPPER\" sh \"${'$'}FAKE\" & wait")
            .also {
                it.environment()["WRAPPER"] = script
                it.environment()["FAKE"] = fakeLogcat.absolutePath
            }
            .redirectErrorStream(true)
            .start()
        val reader = BufferedReader(InputStreamReader(parent.inputStream))
        val pid = reader.readLine().removePrefix("pid ").toInt()
        assertTrue(alive(pid))

        parent.destroyForcibly().waitFor()
        assertTrue("logcat must end after the app died", eventually(10_000) { !alive(pid) })
    }

    @Test
    fun `a missing logcat program ends the stream with the shell message`() {
        val stream = ProcessLogcatLauncher(logcat = "/nonexistent/logcat").launch(listOf("-v", "threadtime"))
        val lines = generateSequence { stream.readLine() }.toList()
        assertTrue(lines.toString(), lines.any { it.contains("nonexistent") })
        stream.close()
    }
}
