/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.logger

/**
 * Hides secrets in log lines. It is the only place that does this: [FileLogHandler.write] calls it for every line
 * that goes into the log file (own entries and captured logcat lines alike) and the log export calls it again for
 * files written by older versions. Masking is idempotent.
 *
 * The rules follow the formats that the app really logs:
 *  - HTTP debug log (`LoggingHttpInterceptor`): header lines (`Authorization`, `Cookie`, `Set-Cookie`) and bodies
 *    with `appPassword`, `apppassword`, `password`, `token=`, `pushTokenHash`, form fields `password=...&...`;
 *  - signaling (`WebSocketInstance` "Receiving"): `"resumeid"`, `"ticket"`, TURN servers with `"username"` and
 *    `"credential"`, SDP `a=ice-pwd`;
 *  - the login flow URL `nc://login/user:...&password:...&server:...` and `https://user:password@host`;
 *  - `IceServerDto(..., username=..., credential=...)` and `pushToken: ...` in `toString()`/interpolated texts.
 *
 * The room token (`"token"` in room JSON, `roomToken=`) is NOT masked: it is not a secret and the log is useless
 * without it. A bare `token=` is masked only in the form `token=value` (query, form, `toString()`).
 */
object LogMasker {
    const val MASK = "***"

    private const val SECRET_KEYS = "app[_-]?password|apppassword|password|passwd|pwd|passphrase|" +
        "access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|push[_-]?token(?:[_-]?hash)?|" +
        "ticket|credential|secret|client[_-]?secret|shared[_-]?secret|api[_-]?key|resume[_-]?id|" +
        "private[_-]?key|authorization|cookie|oc_sessionPassphrase|nc_session_id|nc_token"

    // `username` is a secret only in a TURN server description (the TURN username is a temporary credential).
    private const val TURN_KEYS = "username"
    private val TURN_HINT = Regex("(?i)credential|turnserver|ice[_ -]?server|turns?:")

    private val PEM = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----.*")
    private val HEADER = Regex(
        "(?i)\\b((?:Proxy-)?Authorization|Set-Cookie|Cookie|X-Auth-Token|X-Api-Key)\\s*:\\s*.*$"
    )
    private val BEARER = Regex("\\b(Bearer)\\s+[A-Za-z0-9\\-._~+/]{8,}=*", RegexOption.IGNORE_CASE)

    // "Basic" is also an ordinary word, so only a value that looks like base64 of `user:password` counts.
    private val BASIC = Regex("\\b(Basic)\\s+(?=[A-Za-z0-9+/]*[0-9+/A-Z])[A-Za-z0-9+/]{12,}=*")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
    private val URL_USERINFO = Regex("(?<=://)[^/\\s:@]+:[^/\\s@]+@")
    private val ICE_PWD = Regex("(?i)(a=ice-pwd:|ice-pwd:)[^\\s\\\\\"]+")

    private val IGNORE_CASE = RegexOption.IGNORE_CASE

    private fun jsonField(keys: String) = Regex("(\"(?:$keys)\"\\s*:\\s*\")(?:[^\"\\\\]|\\\\.)*(\")", IGNORE_CASE)

    // The same JSON inside a JSON string: \"key\":\"value\"
    private fun escapedJsonField(keys: String) =
        Regex("(\\\\\"(?:$keys)\\\\\"\\s*:\\s*\\\\\")(?:[^\"\\\\]|\\\\(?!\"))*(\\\\\")", IGNORE_CASE)

    private fun keyValue(keys: String) = Regex("(?i)(?<![A-Za-z0-9_])($keys)(\\s*[:=]\\s*)(?!\\*\\*\\*)[^\\s\"&]+")

    private val SECRET_JSON = jsonField(SECRET_KEYS)
    private val SECRET_JSON_ESCAPED = escapedJsonField(SECRET_KEYS)
    private val TURN_JSON = jsonField(TURN_KEYS)
    private val TURN_JSON_ESCAPED = escapedJsonField(TURN_KEYS)
    private val SECRET_KV = keyValue(SECRET_KEYS)
    private val TURN_KV = keyValue(TURN_KEYS)
    private val BARE_TOKEN = Regex("(?<![A-Za-z0-9_])(token)(=)(?!\\*\\*\\*)[^\\s\"&]+", IGNORE_CASE)
    private val RESUME_ID_WORD = Regex("(?i)(resume[_-]?id\\s+)(?!\\*\\*\\*)[A-Za-z0-9_+/=-]{8,}")

    private fun Regex.keepQuotes(s: String) = replace(s) { m -> m.groupValues[1] + MASK + m.groupValues[2] }

    private fun Regex.keepKey(s: String) = replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }

    private val steps: List<(String) -> String> = listOf(
        { s -> PEM.replace(s, "-----BEGIN PRIVATE KEY----- $MASK") },
        { s -> HEADER.replace(s) { m -> "${m.groupValues[1]}: $MASK" } },
        { s -> BEARER.replace(s) { m -> "${m.groupValues[1]} $MASK" } },
        { s -> BASIC.replace(s) { m -> "${m.groupValues[1]} $MASK" } },
        { s -> JWT.replace(s, MASK) },
        { s -> URL_USERINFO.replace(s, "$MASK@") },
        { s -> ICE_PWD.replace(s) { m -> m.groupValues[1] + MASK } },
        { s -> SECRET_JSON.keepQuotes(s) },
        { s -> SECRET_JSON_ESCAPED.keepQuotes(s) },
        { s ->
            if (TURN_HINT.containsMatchIn(s)) {
                TURN_KV.keepKey(TURN_JSON_ESCAPED.keepQuotes(TURN_JSON.keepQuotes(s)))
            } else {
                s
            }
        },
        { s -> SECRET_KV.keepKey(s) },
        { s -> BARE_TOKEN.keepKey(s) },
        { s -> RESUME_ID_WORD.replace(s) { m -> m.groupValues[1] + MASK } }
    )

    /** Masks every line of [text] (a single log line or a multi-line entry). */
    fun mask(text: String): String {
        if (text.indexOf('\n') < 0) return maskLine(text)
        return text.split('\n').joinToString("\n") { maskLine(it) }
    }

    private fun maskLine(line: String): String = if (line.isEmpty()) line else steps.fold(line) { s, step -> step(s) }
}
