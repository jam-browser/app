package io.github.jambrowser.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 데스크톱 Jam Browser 의 shared/jam-crypto.js 와 바이트 단위로 같은 규칙.
 *
 *  - 초대 코드의 비밀키(32바이트, base64url)는 네트워크로 보내지 않는다
 *  - HKDF-SHA256(salt 없음)으로 암호화 키(enc)와 인증 키(auth)를 만든다
 *      enc  = HKDF(secret, info = "jam-browser v1 enc")
 *      auth = HKDF(secret, info = "jam-browser v1 auth")
 *  - 참여 증명: HMAC-SHA256(auth, "<nonce>:<방코드>") → base64url
 *  - 메시지: AES-256-GCM, 프레임 = iv(12) ‖ 암호문 ‖ 태그(16), AAD = 보내는 방향("c2s" / "s2c")
 */
object JamCrypto {
    private val INFO_ENC = "jam-browser v1 enc".toByteArray(Charsets.UTF_8)
    private val INFO_AUTH = "jam-browser v1 auth".toByteArray(Charsets.UTF_8)
    val C2S: ByteArray = "c2s".toByteArray(Charsets.UTF_8)
    val S2C: ByteArray = "s2c".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    class Keys(val enc: ByteArray, val auth: ByteArray)

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** 패딩 유무와 관계없이 base64url 을 푼다. 잘못된 글자가 있으면 IllegalArgumentException */
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

    fun newSecret(): String = b64u(ByteArray(32).also(random::nextBytes))

    /** RFC 5869 HKDF-SHA256. salt 가 비어 있으면 0 으로 채운 32바이트 (Node 의 hkdfSync 와 같음) */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val realSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(realSalt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun deriveKeys(secretB64u: String): Keys {
        val secret = try {
            unb64u(secretB64u)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad secret")
        }
        require(secret.size == 32) { "bad secret" }
        return Keys(
            enc = hkdf(secret, ByteArray(0), INFO_ENC, 32),
            auth = hkdf(secret, ByteArray(0), INFO_AUTH, 32),
        )
    }

    fun makeProof(keys: Keys, nonce: String, code: String): String =
        b64u(hmac(keys.auth, "$nonce:$code".toByteArray(Charsets.UTF_8)))

    fun checkProof(keys: Keys, nonce: String, code: String, proof: String): Boolean {
        val got = try { unb64u(proof) } catch (e: IllegalArgumentException) { return false }
        return MessageDigest.isEqual(got, unb64u(makeProof(keys, nonce, code)))
    }

    fun seal(keys: Keys, json: String, aad: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        val ctAndTag = c.doFinal(json.toByteArray(Charsets.UTF_8)) // Java 는 암호문 뒤에 태그를 붙여 준다
        return iv + ctAndTag
    }

    /** 위조 · 다른 키 · 다른 방향이면 예외 */
    fun open(keys: Keys, frame: ByteArray, aad: ByteArray): String {
        require(frame.size >= 12 + 16 + 2) { "short frame" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.enc, "AES"), GCMParameterSpec(128, frame, 0, 12))
        c.updateAAD(aad)
        val pt = c.doFinal(frame, 12, frame.size - 12)
        return String(pt, Charsets.UTF_8)
    }
}
