package io.github.jambrowser.core

import java.net.URI
import java.net.URLDecoder

/**
 * 데스크톱 shared/site.js 와 같은 규칙
 *  - siteOf / sameSite : "같은 사이트" 판단 (m.youtube.com 과 www.youtube.com 은 같은 사이트)
 *  - isSensitiveUrl    : 로그인·인증·결제처럼 다른 사람에게 주소를 공유하면 안 되는 페이지
 */
object Site {
    private val SECOND_LEVEL = setOf("co", "or", "go", "ac", "ne", "re", "pe", "com", "net", "org", "gov", "edu", "mil")
    private val AUTH_HOST = Regex("^(accounts?|login|logins|signin|auth|sso|oauth|kauth|nid|appleid|secure|id)\\.", RegexOption.IGNORE_CASE)
    private val AUTH_PATH = Regex(
        "/(login|log-in|signin|sign-in|signup|sign-up|oauth2?|auth|authorize|sso|saml|logout|password|2fa|mfa|verify|checkout|payment|billing)(/|$|\\.|\\?)",
        RegexOption.IGNORE_CASE,
    )
    private val SENSITIVE_PARAMS = setOf(
        "code", "token", "access_token", "id_token", "refresh_token", "auth", "ticket", "session", "sid",
        "password", "otp", "api_key", "apikey", "signature", "sig", "jwt", "saml", "samlresponse",
    )
    private val SENSITIVE_HASH = Regex("(^|[#&])(access_token|id_token|code|token)=", RegexOption.IGNORE_CASE)
    private val IPV4ISH = Regex("^[\\d.]+$")
    private val LOCAL_HOST_INPUT = Regex("""^(localhost|127\.0\.0\.1|\d{1,3}(\.\d{1,3}){3})(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)
    private val DOMAIN_INPUT = Regex("""^[^/]+\.[a-z]{2,}(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)

    private fun parse(u: String?): URI? {
        if (u == null || u.length > 4096) return null
        return try {
            URI(u.trim())
        } catch (e: Exception) {
            null
        }
    }

    fun isWebUrl(u: String?): Boolean {
        val p = parse(u) ?: return false
        val s = p.scheme?.lowercase()
        return (s == "http" || s == "https") && !p.host.isNullOrEmpty()
    }

    fun siteOf(u: String?): String? {
        val p = parse(u) ?: return null
        val s = p.scheme?.lowercase()
        if (s != "http" && s != "https") return null
        val host = (p.host ?: return null).lowercase().trim('[', ']')
        if (host.isEmpty()) return null
        if (host == "localhost" || IPV4ISH.matches(host) || host.contains(':')) return host
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val n = parts.size
        val take = if (parts[n - 1].length == 2 && parts[n - 2] in SECOND_LEVEL) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    fun sameSite(a: String?, b: String?): Boolean {
        val x = siteOf(a) ?: return false
        return x == siteOf(b)
    }

    fun isSensitiveUrl(u: String?): Boolean {
        val p = parse(u) ?: return true
        if (AUTH_HOST.containsMatchIn(p.host ?: "")) return true
        if (AUTH_PATH.containsMatchIn(p.rawPath ?: "")) return true
        p.rawQuery?.split('&')?.forEach { kv ->
            val k = kv.substringBefore('=')
            val key = try { URLDecoder.decode(k, "UTF-8") } catch (e: Exception) { k }
            if (key.lowercase() in SENSITIVE_PARAMS) return true
        }
        val frag = p.rawFragment
        if (frag != null && SENSITIVE_HASH.containsMatchIn("#$frag")) return true
        return false
    }

    /** 다른 사람에게 공유해도 되는 주소 (서버의 isShareable 과 같음) */
    fun isShareable(u: String?): Boolean = isWebUrl(u) && !isSensitiveUrl(u)

    /** 같은 문서인지 (# 뒤와 끝의 / 는 무시) — 데스크톱 sameDoc */
    fun sameDoc(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.substringBefore('#').removeSuffix("/") == b.substringBefore('#').removeSuffix("/")
    }

    /** 주소창 입력 → 주소 (검색어면 검색) — 데스크톱 toUrl */
    fun toUrl(input: String, search: (String) -> String = ::googleSearch): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        if (LOCAL_HOST_INPUT.matches(t)) return "http://$t"
        if (!Regex("""\s""").containsMatchIn(t) && DOMAIN_INPUT.matches(t)) return "https://$t"
        return search(t)
    }

    fun googleSearch(q: String): String = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")

    /** 사람이 읽기 좋은 주소 (www. 와 끝의 / 생략) */
    fun pretty(u: String?): String {
        val p = parse(u) ?: return u ?: ""
        val host = p.host ?: return u ?: ""
        val path = p.rawPath ?: ""
        return host.removePrefix("www.") + if (path == "/" || path.isEmpty()) "" else path
    }
}
