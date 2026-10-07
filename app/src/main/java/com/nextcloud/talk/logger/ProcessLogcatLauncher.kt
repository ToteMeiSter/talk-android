/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Starts logcat through `sh`. The shell script runs logcat in the background and polls both the app process
 * (`$PPID`) and logcat: when the app dies or logcat ends, the script ends logcat / itself, and when the wrapper
 * gets SIGTERM (stop) the trap ends logcat. Without this, a logcat of a dead app would stay forever, because it
 * only notices a closed pipe when it writes the next line.
 */
class ProcessLogcatLauncher(private val shell: String = "sh", private val logcat: String = "logcat") :
    LogcatLauncher {

    companion object {
        const val WRAPPER_SCRIPT =
            "\"\$@\" & LP=\$!\n" +
                "trap 'kill \$LP 2>/dev/null; exit 0' TERM HUP INT\n" +
                "while kill -0 \$PPID 2>/dev/null && kill -0 \$LP 2>/dev/null; do sleep 2 & wait \$!; done\n" +
                "kill \$LP 2>/dev/null\n"
    }

    override fun launch(args: List<String>): LogcatStream {
        val command = listOf(shell, "-c", WRAPPER_SCRIPT, "sh", logcat) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        return object : LogcatStream {
            private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

            override fun readLine(): String? = reader.readLine()

            override fun close() {
                // destroy() first: BufferedReader.close() would wait for a readLine() that is blocked.
                process.destroy()
                runCatching { process.inputStream.close() }
            }
        }
    }
}
