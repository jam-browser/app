package io.github.jambrowser.core

/**
 * 초대 코드 (데스크톱 src/renderer/app.js 의 parseInvite 와 같은 규칙)
 *
 *  같은 네트워크 : jam://호스트:포트/방코드/비밀키            → ws://호스트:포트
 *  중계 서버     : jamr://중계주소:포트/경로/방코드/비밀키     → ws://중계주소:포트/join/경로
 *  중계(암호화)  : jamrs://중계주소/경로/방코드/비밀키        → wss://중계주소/join/경로
 */
data class Invite(
    val server: String,
    val code: String,
    val secret: String,
    val relay: Boolean,
    /** 근처 페어링으로 받은 일회용 입장권 (호스트가 이미 수락함 → 다시 승인받지 않음) */
    val ticket: String? = null,
) {
    companion object {
        const val DEFAULT_PORT = 3030
        const val RELAY_PORT = 3031

        private val RELAY_RE = Regex(
            "^(jamrs?)://([^/\\s]+)/([a-z0-9]{6,16})/([A-Za-z0-9]{4,8})/([A-Za-z0-9_-]{43})/?$",
            RegexOption.IGNORE_CASE,
        )
        private val DIRECT_RE = Regex(
            "^(?:jam://)?([^/\\s]+)/([A-Za-z0-9]{4,8})/([A-Za-z0-9_-]{43})/?$",
            RegexOption.IGNORE_CASE,
        )
        private val HAS_PORT = Regex(":\\d+$")

        /** 메신저로 받은 글에 초대 코드가 섞여 있어도 찾아낸다 */
        fun find(text: String): Invite? {
            parse(text)?.let { return it }
            for (token in text.split(Regex("\\s+"))) parse(token.trim('<', '>', '"', '\'', '(', ')', ','))?.let { return it }
            return null
        }

        fun parse(text: String): Invite? {
            val t = text.trim()
            RELAY_RE.matchEntire(t)?.let { r ->
                val (schemeRaw, hostRaw, route, code, secret) = r.destructured
                val scheme = if (schemeRaw.equals("jamrs", ignoreCase = true)) "wss" else "ws"
                var host = hostRaw
                if (scheme == "ws" && !HAS_PORT.containsMatchIn(host)) host += ":$RELAY_PORT"
                return Invite("$scheme://$host/join/$route", code.uppercase(), secret, relay = true)
            }
            val m = DIRECT_RE.matchEntire(t) ?: return null
            var host = m.groupValues[1]
            if (host.contains("://")) return null // http:// 같은 다른 주소
            if (!HAS_PORT.containsMatchIn(host)) host += ":$DEFAULT_PORT"
            return Invite("ws://$host", m.groupValues[2].uppercase(), m.groupValues[3], relay = false)
        }
    }
}
