package com.jn.dyparse

import android.util.Base64
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.max

/**
 * Minimal Douyin web a_bogus signer adapted from the public Apache-licensed
 * douyin-downloader/f2 implementation. It lets author-list requests use the
 * signed web endpoint directly from the app.
 */
object DouyinABogusSigner {
    private const val AID = 6383
    private const val PAGE_ID = 0
    private const val SALT = "cus"
    private val secureRandom = SecureRandom()

    fun sign(url: HttpUrl, userAgent: String): HttpUrl {
        val query = url.encodedQuery.orEmpty()
        if (query.isBlank() || query.contains("a_bogus=", ignoreCase = true)) {
            return url
        }
        val abogus = ABogus(userAgent = userAgent).generate(query)
        return url.newBuilder()
            .encodedQuery("$query&a_bogus=$abogus")
            .build()
    }

    fun signXBogus(url: HttpUrl, userAgent: String): HttpUrl {
        val query = url.encodedQuery.orEmpty()
        if (query.isBlank() || query.contains("X-Bogus=", ignoreCase = true)) {
            return url
        }
        val token = XBogus(userAgent).generate(url.toString())
        return url.newBuilder()
            .encodedQuery("$query&X-Bogus=$token")
            .build()
    }

    fun generateMsToken(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(182) {
                append(alphabet[secureRandom.nextInt(alphabet.length)])
            }
            append("==")
        }
    }

    private class ABogus(
        private val userAgent: String,
        private val browserFp: String = BrowserFingerprintGenerator.generate()
    ) {
        private val crypto = CryptoUtility()
        private val options = intArrayOf(0, 1, 14)
        private val paths = listOf(
            "^/webcast/",
            "^/aweme/v1/",
            "^/aweme/v2/",
            "/v1/message/send",
            "^/live/",
            "^/captcha/",
            "^/ecom/"
        )
        private val sortIndex = intArrayOf(
            18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21, 27, 54, 55, 31, 35,
            57, 39, 41, 43, 22, 28, 32, 60, 36, 23, 29, 33, 37, 44, 45, 59,
            46, 47, 48, 49, 50, 24, 25, 65, 66, 70, 71
        )
        private val sortIndex2 = intArrayOf(
            18, 20, 26, 30, 34, 38, 40, 42, 21, 27, 31, 35, 39, 41, 43, 22, 28,
            32, 36, 23, 29, 33, 37, 44, 45, 46, 47, 48, 49, 50, 24, 25, 52,
            53, 54, 55, 57, 58, 59, 60, 65, 66, 70, 71
        )

        fun generate(params: String, body: String = ""): String {
            val ab = mutableMapOf(
                8 to 3,
                18 to 44,
                66 to 0,
                69 to 0,
                70 to 0,
                71 to 0
            )

            val start = System.currentTimeMillis()
            val array1 = crypto.paramsToArray(crypto.paramsToArray(params))
            val array2 = crypto.paramsToArray(crypto.paramsToArray(body))
            val uaEncrypted = crypto.rc4Encrypt(byteArrayOf(0x00, 0x01, 0x0E), userAgent)
            val array3 = crypto.paramsToArray(
                crypto.base64Encode(StringProcessor.toOrdString(uaEncrypted), 1),
                addSalt = false
            )
            val end = System.currentTimeMillis()

            ab[20] = byteAt(start, 24)
            ab[21] = byteAt(start, 16)
            ab[22] = byteAt(start, 8)
            ab[23] = byteAt(start, 0)
            ab[24] = byteAt(start, 32)
            ab[25] = byteAt(start, 40)

            ab[26] = byteAt(options[0], 24)
            ab[27] = byteAt(options[0], 16)
            ab[28] = byteAt(options[0], 8)
            ab[29] = byteAt(options[0], 0)

            ab[30] = (options[1] / 256) and 255
            ab[31] = (options[1] % 256) and 255
            ab[32] = byteAt(options[1], 24)
            ab[33] = byteAt(options[1], 16)

            ab[34] = byteAt(options[2], 24)
            ab[35] = byteAt(options[2], 16)
            ab[36] = byteAt(options[2], 8)
            ab[37] = byteAt(options[2], 0)

            ab[38] = array1.getOrElse(21) { 0 }
            ab[39] = array1.getOrElse(22) { 0 }
            ab[40] = array2.getOrElse(21) { 0 }
            ab[41] = array2.getOrElse(22) { 0 }
            ab[42] = array3.getOrElse(23) { 0 }
            ab[43] = array3.getOrElse(24) { 0 }

            ab[44] = byteAt(end, 24)
            ab[45] = byteAt(end, 16)
            ab[46] = byteAt(end, 8)
            ab[47] = byteAt(end, 0)
            ab[48] = ab[8] ?: 0
            ab[49] = byteAt(end, 32)
            ab[50] = byteAt(end, 40)

            ab[51] = byteAt(PAGE_ID, 24)
            ab[52] = byteAt(PAGE_ID, 16)
            ab[53] = byteAt(PAGE_ID, 8)
            ab[54] = byteAt(PAGE_ID, 0)
            ab[55] = PAGE_ID
            ab[56] = AID
            ab[57] = AID and 255
            ab[58] = (AID shr 8) and 255
            ab[59] = (AID shr 16) and 255
            ab[60] = (AID shr 24) and 255
            ab[64] = browserFp.length
            ab[65] = browserFp.length

            val sortedValues = sortIndex.map { ab[it] ?: 0 }.toMutableList()
            var abXor = ((browserFp.length and 255) shr 8) and 255
            for (index in 0 until max(0, sortIndex2.size - 1)) {
                if (index == 0) {
                    abXor = ab[sortIndex2[index]] ?: 0
                }
                abXor = abXor xor (ab[sortIndex2[index + 1]] ?: 0)
            }

            sortedValues.addAll(StringProcessor.toCharArray(browserFp))
            sortedValues.add(abXor)

            val abogusBytes = StringProcessor.generateRandomBytes() +
                crypto.transformBytes(sortedValues)
            return crypto.abogusEncode(abogusBytes, 0)
        }

        @Suppress("unused")
        private fun browserConfig(): Map<String, Any> {
            return mapOf(
                "aid" to AID,
                "pageId" to PAGE_ID,
                "boe" to false,
                "ddrt" to 8.5,
                "paths" to paths,
                "track" to mapOf("mode" to 0, "delay" to 300, "paths" to emptyList<String>()),
                "dump" to true,
                "rpU" to ""
            )
        }
    }

    private class CryptoUtility {
        private val base64Alphabet = listOf(
            "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe",
            "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
        )
        private val bigArray = mutableListOf(
            121, 243, 55, 234, 103, 36, 47, 228, 30, 231, 106, 6, 115, 95, 78,
            101, 250, 207, 198, 50, 139, 227, 220, 105, 97, 143, 34, 28, 194,
            215, 18, 100, 159, 160, 43, 8, 169, 217, 180, 120, 247, 45, 90, 11,
            27, 197, 46, 3, 84, 72, 5, 68, 62, 56, 221, 75, 144, 79, 73, 161,
            178, 81, 64, 187, 134, 117, 186, 118, 16, 241, 130, 71, 89, 147,
            122, 129, 65, 40, 88, 150, 110, 219, 199, 255, 181, 254, 48, 4,
            195, 248, 208, 32, 116, 167, 69, 201, 17, 124, 125, 104, 96, 83,
            80, 127, 236, 108, 154, 126, 204, 15, 20, 135, 112, 158, 13, 1,
            188, 164, 210, 237, 222, 98, 212, 77, 253, 42, 170, 202, 26, 22,
            29, 182, 251, 10, 173, 152, 58, 138, 54, 141, 185, 33, 157, 31,
            252, 132, 233, 235, 102, 196, 191, 223, 240, 148, 39, 123, 92, 82,
            128, 109, 57, 24, 38, 113, 209, 245, 2, 119, 153, 229, 189, 214,
            230, 174, 232, 63, 52, 205, 86, 140, 66, 175, 111, 171, 246, 133,
            238, 193, 99, 60, 74, 91, 225, 51, 76, 37, 145, 211, 166, 151,
            213, 206, 0, 200, 244, 176, 218, 44, 184, 172, 49, 216, 93, 168,
            53, 21, 183, 41, 67, 85, 224, 155, 226, 242, 87, 177, 146, 70,
            190, 12, 162, 19, 137, 114, 25, 165, 163, 192, 23, 59, 9, 94, 179,
            107, 35, 7, 142, 131, 239, 203, 149, 136, 61, 249, 14, 156
        )

        fun paramsToArray(param: String, addSalt: Boolean = true): List<Int> {
            val value = if (addSalt) param + SALT else param
            return Sm3.digest(value.toByteArray(Charsets.UTF_8)).map { it.toInt() and 255 }
        }

        fun paramsToArray(param: List<Int>, addSalt: Boolean = true): List<Int> {
            val value = if (addSalt) param else param
            return Sm3.digest(value.map { (it and 255).toByte() }.toByteArray())
                .map { it.toInt() and 255 }
        }

        fun transformBytes(bytesList: List<Int>): String {
            val bytesString = StringProcessor.toCharString(bytesList)
            val result = StringBuilder()
            var indexB = bigArray[1]
            var initialValue = 0
            var valueE = 0

            bytesString.forEachIndexed { index, char ->
                var sumInitial = if (index == 0) {
                    initialValue = bigArray[indexB]
                    val sum = indexB + initialValue
                    bigArray[1] = initialValue
                    bigArray[indexB] = indexB
                    sum
                } else {
                    initialValue + valueE
                }

                sumInitial %= bigArray.size
                val valueF = bigArray[sumInitial]
                result.append(((char.code and 255) xor valueF).toChar())

                val swapIndex = (index + 2) % bigArray.size
                valueE = bigArray[swapIndex]
                sumInitial = (indexB + valueE) % bigArray.size
                initialValue = bigArray[sumInitial]
                bigArray[sumInitial] = bigArray[swapIndex]
                bigArray[swapIndex] = initialValue
                indexB = sumInitial
            }

            return result.toString()
        }

        fun base64Encode(input: String, alphabetIndex: Int = 0): String {
            val binary = buildString {
                input.forEach { char ->
                    append(((char.code and 255).toString(2)).padStart(8, '0'))
                }
            }
            val paddingLength = (6 - binary.length % 6) % 6
            val padded = binary + "0".repeat(paddingLength)
            val alphabet = base64Alphabet[alphabetIndex]
            val output = StringBuilder()
            var index = 0
            while (index < padded.length) {
                output.append(alphabet[padded.substring(index, index + 6).toInt(2)])
                index += 6
            }
            output.append("=".repeat(paddingLength / 2))
            return output.toString()
        }

        fun abogusEncode(input: String, alphabetIndex: Int): String {
            val alphabet = base64Alphabet[alphabetIndex]
            val output = StringBuilder()
            var index = 0
            while (index < input.length) {
                val n = when {
                    index + 2 < input.length ->
                        ((input[index].code and 255) shl 16) or
                            ((input[index + 1].code and 255) shl 8) or
                            (input[index + 2].code and 255)
                    index + 1 < input.length ->
                        ((input[index].code and 255) shl 16) or
                            ((input[index + 1].code and 255) shl 8)
                    else -> (input[index].code and 255) shl 16
                }

                val shifts = intArrayOf(18, 12, 6, 0)
                val masks = intArrayOf(0xFC0000, 0x03F000, 0x0FC0, 0x3F)
                for (i in shifts.indices) {
                    if (shifts[i] == 6 && index + 1 >= input.length) break
                    if (shifts[i] == 0 && index + 2 >= input.length) break
                    output.append(alphabet[(n and masks[i]) shr shifts[i]])
                }
                index += 3
            }
            output.append("=".repeat((4 - output.length % 4) % 4))
            return output.toString()
        }

        fun rc4Encrypt(key: ByteArray, plaintext: String): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 255)) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
            }

            var i = 0
            j = 0
            val output = ByteArray(plaintext.length)
            plaintext.forEachIndexed { index, char ->
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                val k = s[(s[i] + s[j]) % 256]
                output[index] = ((char.code and 255) xor k).toByte()
            }
            return output
        }
    }

    private object BrowserFingerprintGenerator {
        fun generate(): String {
            val innerWidth = randomInt(1024, 1920)
            val innerHeight = randomInt(768, 1080)
            val outerWidth = innerWidth + randomInt(24, 32)
            val outerHeight = innerHeight + randomInt(75, 90)
            val screenY = if (secureRandom.nextBoolean()) 0 else 30
            val sizeWidth = randomInt(1024, 1920)
            val sizeHeight = randomInt(768, 1080)
            val availWidth = randomInt(1280, 1920)
            val availHeight = randomInt(800, 1080)
            return "$innerWidth|$innerHeight|$outerWidth|$outerHeight|" +
                "0|$screenY|0|0|$sizeWidth|$sizeHeight|" +
                "$availWidth|$availHeight|$innerWidth|$innerHeight|24|24|Win32"
        }

        private fun randomInt(min: Int, max: Int): Int {
            return min + secureRandom.nextInt(max - min + 1)
        }
    }

    private object StringProcessor {
        fun toOrdString(bytes: ByteArray): String {
            return buildString {
                bytes.forEach { append((it.toInt() and 255).toChar()) }
            }
        }

        fun toCharString(values: List<Int>): String {
            return buildString {
                values.forEach { append((it and 255).toChar()) }
            }
        }

        fun toCharArray(value: String): List<Int> {
            return value.map { it.code and 255 }
        }

        fun generateRandomBytes(length: Int = 3): String {
            return buildString {
                repeat(length) {
                    val randomValue = (secureRandom.nextDouble() * 10000).toInt()
                    append(((randomValue and 255) and 170 or 1).toChar())
                    append(((randomValue and 255) and 85 or 2).toChar())
                    append((((randomValue ushr 8) and 170) or 5).toChar())
                    append((((randomValue ushr 8) and 85) or 40).toChar())
                }
            }
        }
    }

    private object Sm3 {
        private val iv = intArrayOf(
            0x7380166f,
            0x4914b2b9,
            0x172442d7,
            0xda8a0600.toInt(),
            0xa96f30bc.toInt(),
            0x163138aa,
            0xe38dee4d.toInt(),
            0xb0fb0e4e.toInt()
        )

        fun digest(input: ByteArray): ByteArray {
            val padded = pad(input)
            val v = iv.copyOf()
            var offset = 0
            while (offset < padded.size) {
                compress(v, padded, offset)
                offset += 64
            }
            val output = ByteArray(32)
            for (i in v.indices) {
                writeInt(output, i * 4, v[i])
            }
            return output
        }

        private fun pad(input: ByteArray): ByteArray {
            val bitLength = input.size.toLong() * 8L
            var total = input.size + 1 + 8
            val remainder = total % 64
            if (remainder != 0) {
                total += 64 - remainder
            }
            val output = ByteArray(total)
            input.copyInto(output)
            output[input.size] = 0x80.toByte()
            for (i in 0 until 8) {
                output[total - 1 - i] = ((bitLength ushr (8 * i)) and 255).toByte()
            }
            return output
        }

        private fun compress(v: IntArray, block: ByteArray, offset: Int) {
            val w = IntArray(68)
            val w1 = IntArray(64)
            for (i in 0 until 16) {
                w[i] = readInt(block, offset + i * 4)
            }
            for (j in 16 until 68) {
                w[j] = p1(w[j - 16] xor w[j - 9] xor rotateLeft(w[j - 3], 15)) xor
                    rotateLeft(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) {
                w1[j] = w[j] xor w[j + 4]
            }

            var a = v[0]
            var b = v[1]
            var c = v[2]
            var d = v[3]
            var e = v[4]
            var f = v[5]
            var g = v[6]
            var h = v[7]

            for (j in 0 until 64) {
                val tj = if (j <= 15) 0x79cc4519 else 0x7a879d8a
                val ss1 = rotateLeft(rotateLeft(a, 12) + e + rotateLeft(tj, j), 7)
                val ss2 = ss1 xor rotateLeft(a, 12)
                val tt1 = ff(a, b, c, j) + d + ss2 + w1[j]
                val tt2 = gg(e, f, g, j) + h + ss1 + w[j]
                d = c
                c = rotateLeft(b, 9)
                b = a
                a = tt1
                h = g
                g = rotateLeft(f, 19)
                f = e
                e = p0(tt2)
            }

            v[0] = v[0] xor a
            v[1] = v[1] xor b
            v[2] = v[2] xor c
            v[3] = v[3] xor d
            v[4] = v[4] xor e
            v[5] = v[5] xor f
            v[6] = v[6] xor g
            v[7] = v[7] xor h
        }

        private fun ff(x: Int, y: Int, z: Int, j: Int): Int {
            return if (j <= 15) x xor y xor z else (x and y) or (x and z) or (y and z)
        }

        private fun gg(x: Int, y: Int, z: Int, j: Int): Int {
            return if (j <= 15) x xor y xor z else (x and y) or (x.inv() and z)
        }

        private fun p0(x: Int): Int = x xor rotateLeft(x, 9) xor rotateLeft(x, 17)

        private fun p1(x: Int): Int = x xor rotateLeft(x, 15) xor rotateLeft(x, 23)

        private fun rotateLeft(value: Int, bits: Int): Int {
            return Integer.rotateLeft(value, bits and 31)
        }

        private fun readInt(bytes: ByteArray, offset: Int): Int {
            return ((bytes[offset].toInt() and 255) shl 24) or
                ((bytes[offset + 1].toInt() and 255) shl 16) or
                ((bytes[offset + 2].toInt() and 255) shl 8) or
                (bytes[offset + 3].toInt() and 255)
        }

        private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }

    private class XBogus(private val userAgent: String) {
        private val hexArray = arrayOfNulls<Int>(128).apply {
            for (i in 0..9) this['0'.code + i] = i
            for (i in 0..5) this['a'.code + i] = 10 + i
        }
        private val character = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="
        private val uaKey = byteArrayOf(0x00, 0x01, 0x0C)

        fun generate(url: String): String {
            val uaMd5Array = md5StrToArray(
                md5(
                    Base64.encodeToString(
                        rc4Encrypt(uaKey, userAgent.toByteArray(Charsets.ISO_8859_1)),
                        Base64.NO_WRAP
                    )
                )
            )
            val emptyMd5Array = md5StrToArray(md5(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e")))
            val urlMd5Array = md5Encrypt(url)
            val timer = (System.currentTimeMillis() / 1000L).toInt()
            val ct = 536919696
            val values = mutableListOf(
                64,
                0,
                1,
                12,
                urlMd5Array[14],
                urlMd5Array[15],
                emptyMd5Array[14],
                emptyMd5Array[15],
                uaMd5Array[14],
                uaMd5Array[15],
                (timer shr 24) and 255,
                (timer shr 16) and 255,
                (timer shr 8) and 255,
                timer and 255,
                (ct shr 24) and 255,
                (ct shr 16) and 255,
                (ct shr 8) and 255,
                ct and 255
            )
            var xorResult = values[0]
            for (index in 1 until values.size) {
                xorResult = xorResult xor values[index]
            }
            values.add(xorResult)

            val odd = mutableListOf<Int>()
            val even = mutableListOf<Int>()
            var index = 0
            while (index < values.size) {
                odd.add(values[index])
                if (index + 1 < values.size) {
                    even.add(values[index + 1])
                }
                index += 2
            }

            val merged = odd + even
            val encoded = encodingConversion(merged)
            val garbledBytes = byteArrayOf(2, 255.toByte()) + rc4Encrypt(
                byteArrayOf(0xFF.toByte()),
                encoded.toByteArray(Charsets.ISO_8859_1)
            )

            val output = StringBuilder()
            var cursor = 0
            while (cursor + 2 < garbledBytes.size) {
                output.append(
                    calculation(
                        garbledBytes[cursor].toInt() and 255,
                        garbledBytes[cursor + 1].toInt() and 255,
                        garbledBytes[cursor + 2].toInt() and 255
                    )
                )
                cursor += 3
            }
            return output.toString()
        }

        private fun encodingConversion(values: List<Int>): String {
            val payload = mutableListOf(values[0], values[9])
            payload.addAll(
                listOf(
                    values[1], values[11], values[2], values[12], values[3], values[13],
                    values[4], values[14], values[5], values[15], values[6], values[16],
                    values[7], values[17], values[8], values[18], values[10]
                )
            )
            return payload.map { (it and 255).toChar() }.joinToString("")
        }

        private fun calculation(a1: Int, a2: Int, a3: Int): String {
            val x3 = ((a1 and 255) shl 16) or ((a2 and 255) shl 8) or (a3 and 255)
            return buildString {
                append(character[(x3 and 16515072) shr 18])
                append(character[(x3 and 258048) shr 12])
                append(character[(x3 and 4032) shr 6])
                append(character[x3 and 63])
            }
        }

        private fun md5Encrypt(value: String): List<Int> {
            return md5StrToArray(md5(md5StrToArray(md5(value))))
        }

        private fun md5(input: String): String {
            return md5(md5StrToArray(input))
        }

        private fun md5(input: List<Int>): String {
            val digest = MessageDigest.getInstance("MD5")
                .digest(input.map { (it and 255).toByte() }.toByteArray())
            return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun md5StrToArray(value: String): List<Int> {
            if (value.length > 32) {
                return value.map { it.code and 255 }
            }
            val output = mutableListOf<Int>()
            var index = 0
            while (index + 1 < value.length) {
                val high = hexArray.getOrNull(value[index].code) ?: 0
                val low = hexArray.getOrNull(value[index + 1].code) ?: 0
                output.add((high shl 4) or low)
                index += 2
            }
            return output
        }

        private fun rc4Encrypt(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            val encrypted = ByteArray(data.size)

            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 255)) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
            }

            var i = 0
            j = 0
            data.forEachIndexed { index, byte ->
                i = (i + 1) % 256
                j = (j + s[i]) % 256
                val tmp = s[i]
                s[i] = s[j]
                s[j] = tmp
                encrypted[index] = ((byte.toInt() and 255) xor s[(s[i] + s[j]) % 256]).toByte()
            }
            return encrypted
        }
    }

    private fun byteAt(value: Long, shift: Int): Int {
        return ((value ushr shift) and 255L).toInt()
    }

    private fun byteAt(value: Int, shift: Int): Int {
        return (value ushr shift) and 255
    }
}
