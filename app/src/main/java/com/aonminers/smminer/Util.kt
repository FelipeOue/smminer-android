package com.aonminers.smminer

import java.math.BigInteger
import java.security.MessageDigest

// Target value that a bitcoin hash must be below to have at least 1 of difficulty.
// 0x00000000FFFF0000000000000000000000000000000000000000000000000000
val TARGET_DIFF_ONE_HEX = "00000000FFFF0000000000000000000000000000000000000000000000000000"
val TARGET_DIFF_ONE_BI = BigInteger(TARGET_DIFF_ONE_HEX, 16)

// Default version mask of the stratum protocol.
// The miner will roll non-0 bits from this mask.
const val DEFAULT_VERSION_MASK = 0x1FFFE000L

// ── SHA-256 ──────────────────────────────────────────────────

fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

fun sha256d(data: ByteArray): ByteArray =
    sha256(sha256(data))

// ── Merkle Root ──────────────────────────────────────────────

fun computeMerkleRoot(
    coinbasePrefix: String,
    extraNonce1: String,
    extraNonce2: String,
    coinbaseSuffix: String,
    merkleBranches: List<String>
): String {
    val coinbaseHex = coinbasePrefix + extraNonce1 + extraNonce2 + coinbaseSuffix
    var left = sha256d(stringToHex(coinbaseHex))
    for (branch in merkleBranches) {
        left = sha256d(left + stringToHex(branch))
    }
    return bytesToHex(left)
}

// ── Endian / Byte utilities ──────────────────────────────────

/** Reverse byte order of a byte array. */
fun reverseBytes(b: ByteArray): ByteArray = ByteArray(b.size) { b[b.size - 1 - it] }

/** Reverse byte order of a uint32. */
fun reverseUint32(x: Int): Int =
    ((x shl 24)) or ((x shl 8) and 0x00FF0000) or ((x ushr 8) and 0x0000FF00) or (x ushr 24)

/** Reverse each 4-byte word in a 64-char hex string. */
fun reverseWord32(hash: String): String {
    require(hash.length == 64) { "hash must be 64 hex chars, got ${hash.length}" }
    val sb = StringBuilder(64)
    for (i in 0 until 64 step 8) {
        val bytes = stringToHex(hash.substring(i, i + 8))
        for (j in 3 downTo 0) sb.append((bytes[j].toInt() and 0xFF).toString(16).padStart(2, '0'))
    }
    return sb.toString()
}

/** Reverse hex string (treating as hex bytes). */
fun reverseHexString(s: String): String = bytesToHex(reverseBytes(stringToHex(s)))

/** Pad a uint32 to `length` bytes (big-endian). */
fun padLeft(value: Int, length: Int): ByteArray {
    val b = ByteArray(length)
    var v = value
    for (i in (length - 1) downTo 0) {
        b[i] = v.toByte()
        v = v ushr 8
    }
    return b
}

/** Pad a uint32 to `length` bytes with unsigned support. */
fun padLeftU32(value: Long, length: Int): ByteArray {
    val b = ByteArray(length)
    var v = value
    for (i in (length - 1) downTo 0) {
        b[i] = v.toByte()
        v = v ushr 8
    }
    return b
}

/** Decode hex string to bytes. */
fun stringToHex(s: String): ByteArray {
    val len = s.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        i += 2
    }
    return data
}

/** Encode bytes to hex string. */
fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) sb.append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
    return sb.toString()
}

/** Convert big-endian bytes to Double via BigInteger. */
fun bytesToFloat64(b: ByteArray): Double =
    BigInteger(1, b).toDouble()

/** Convert uint32 to 4 bytes (big-endian). */
fun uint32ToBytes(n: Long): ByteArray = byteArrayOf(
    ((n shr 24) and 0xFF).toByte(),
    ((n shr 16) and 0xFF).toByte(),
    ((n shr 8) and 0xFF).toByte(),
    (n and 0xFF).toByte()
)

// ── Difficulty / Validation ──────────────────────────────────

/** Convert difficulty to target value. */
fun diffToTarget(diff: Double): Double =
    TARGET_DIFF_ONE_BI.toDouble() / diff

// ── SHA256d Validator (replicates Go's util.SHA256dValidator) ─

/**
 * Validates a block header with the given nonce and version.
 * Returns the share difficulty (how hard the hash was to find).
 *
 * Block header layout (80 bytes):
 *   [0..3]   version        LE bytes (4B)
 *   [4..35]  prevBlockHash  word32-reversed bytes (32B)
 *   [36..67] merkleRoot     BE bytes (32B)
 *   [68..71] blockTimestamp LE bytes (4B)
 *   [72..75] networkTarget  LE bytes (4B)
 *   [76..79] nonce          LE bytes (4B)
 */
fun sha256dValidator(
    job: StratumJob,
    nonce: ByteArray,
    version: ByteArray
): Double {
    val prevBlockReversed = reverseWord32(job.prevBlockHash)

    val header = ByteArray(80)
    // version (LE)
    val verBytes = reverseBytes(version)
    System.arraycopy(verBytes, 0, header, 0, 4)
    // prevBlockHash (word32-reversed)
    val prevBytes = stringToHex(prevBlockReversed)
    System.arraycopy(prevBytes, 0, header, 4, 32)
    // merkleRoot (BE)
    val mrBytes = stringToHex(job.merkleRoot)
    System.arraycopy(mrBytes, 0, header, 36, 32)
    // blockTimestamp (LE)
    val tsBytes = reverseBytes(stringToHex(job.blockTimestamp))
    System.arraycopy(tsBytes, 0, header, 68, 4)
    // networkTarget (LE)
    val ntBytes = reverseBytes(stringToHex(job.networkTarget))
    System.arraycopy(ntBytes, 0, header, 72, 4)
    // nonce (LE)
    val ncBytes = reverseBytes(nonce)
    System.arraycopy(ncBytes, 0, header, 76, 4)

    val hash = reverseBytes(sha256d(header))
    val hashValue = bytesToFloat64(hash)

    return TARGET_DIFF_ONE_BI.toDouble() / hashValue
}

// ── Formatting ────────────────────────────────────────────────

/** Format a difficulty number to human-readable scale (K, M, G, T...). */
fun formatDiff(diff: Double): String {
    if (diff < 1000) return diff.toLong().toString()
    val units = arrayOf("K", "M", "G", "T", "P", "E", "Z", "Y")
    var value = diff
    var idx = -1
    while (value >= 1000 && idx < units.size - 1) {
        value /= 1000
        idx++
    }
    return if (value == value.toLong().toDouble())
        "${value.toLong()}${units[idx]}"
    else
        String.format("%.2f%s", value, units[idx])
}

// ── CRC ───────────────────────────────────────────────────────

/** CRC-5/USB with 0x13 polynomial (Bitmain). */
fun crc5Bm(data: ByteArray): Byte {
    var crc = 0x1F.toByte()
    for (b in data) {
        var byte = b
        for (j in 0 until 8) {
            val bit = ((byte.toInt() shr 7) and 1)
            byte = (byte.toInt() shl 1).toByte()
            var newBit = ((crc.toInt() shr 4) xor bit) and 1
            crc = ((crc.toInt() shl 1) or newBit xor (newBit shl 2)).toByte()
            crc = (crc.toInt() and 0x1F).toByte()
        }
    }
    return crc
}

// CRC16 table from cgminer
private val CRC16_TABLE = intArrayOf(
    0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50A5, 0x60C6, 0x70E7,
    0x8108, 0x9129, 0xA14A, 0xB16B, 0xC18C, 0xD1AD, 0xE1CE, 0xF1EF,
    0x1231, 0x0210, 0x3273, 0x2252, 0x52B5, 0x4294, 0x72F7, 0x62D6,
    0x9339, 0x8318, 0xB37B, 0xA35A, 0xD3BD, 0xC39C, 0xF3FF, 0xE3DE,
    0x2462, 0x3443, 0x0420, 0x1401, 0x64E6, 0x74C7, 0x44A4, 0x5485,
    0xA56A, 0xB54B, 0x8528, 0x9509, 0xE5EE, 0xF5CF, 0xC5AC, 0xD58D,
    0x3653, 0x2672, 0x1611, 0x0630, 0x76D7, 0x66F6, 0x5695, 0x46B4,
    0xB75B, 0xA77A, 0x9719, 0x8738, 0xF7DF, 0xE7FE, 0xD79D, 0xC7BC,
    0x48C4, 0x58E5, 0x6886, 0x78A7, 0x0840, 0x1861, 0x2802, 0x3823,
    0xC9CC, 0xD9ED, 0xE98E, 0xF9AF, 0x8948, 0x9969, 0xA90A, 0xB92B,
    0x5AF5, 0x4AD4, 0x7AB7, 0x6A96, 0x1A71, 0x0A50, 0x3A33, 0x2A12,
    0xDBFD, 0xCBDC, 0xFBBF, 0xEB9E, 0x9B79, 0x8B58, 0xBB3B, 0xAB1A,
    0x6CA6, 0x7C87, 0x4CE4, 0x5CC5, 0x2C22, 0x3C03, 0x0C60, 0x1C41,
    0xEDAE, 0xFD8F, 0xCDEC, 0xDDCD, 0xAD2A, 0xBD0B, 0x8D68, 0x9D49,
    0x7E97, 0x6EB6, 0x5ED5, 0x4EF4, 0x3E13, 0x2E32, 0x1E51, 0x0E70,
    0xFF9F, 0xEFBE, 0xDFDD, 0xCFFC, 0xBF1B, 0xAF3A, 0x9F59, 0x8F78,
    0x9188, 0x81A9, 0xB1CA, 0xA1EB, 0xD10C, 0xC12D, 0xF14E, 0xE16F,
    0x1080, 0x00A1, 0x30C2, 0x20E3, 0x5004, 0x4025, 0x7046, 0x6067,
    0x83B9, 0x9398, 0xA3FB, 0xB3DA, 0xC33D, 0xD31C, 0xE37F, 0xF35E,
    0x02B1, 0x1290, 0x22F3, 0x32D2, 0x4235, 0x5214, 0x6277, 0x7256,
    0xB5EA, 0xA5CB, 0x95A8, 0x8589, 0xF56E, 0xE54F, 0xD52C, 0xC50D,
    0x34E2, 0x24C3, 0x14A0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
    0xA7DB, 0xB7FA, 0x8799, 0x97B8, 0xE75F, 0xF77E, 0xC71D, 0xD73C,
    0x26D3, 0x36F2, 0x0691, 0x16B0, 0x6657, 0x7676, 0x4615, 0x5634,
    0xD94C, 0xC96D, 0xF90E, 0xE92F, 0x99C8, 0x89E9, 0xB98A, 0xA9AB,
    0x5844, 0x4865, 0x7806, 0x6827, 0x18C0, 0x08E1, 0x3882, 0x28A3,
    0xCB7D, 0xDB5C, 0xEB3F, 0xFB1E, 0x8BF9, 0x9BD8, 0xABBB, 0xBB9A,
    0x4A75, 0x5A54, 0x6A37, 0x7A16, 0x0AF1, 0x1AD0, 0x2AB3, 0x3A92,
    0xFD2E, 0xED0F, 0xDD6C, 0xCD4D, 0xBDAA, 0xAD8B, 0x9DE8, 0x8DC9,
    0x7C26, 0x6C07, 0x5C64, 0x4C45, 0x3CA2, 0x2C83, 0x1CE0, 0x0CC1,
    0xEF1F, 0xFF3E, 0xCF5D, 0xDF7C, 0xAF9B, 0xBFBA, 0x8FD9, 0x9FF8,
    0x6E17, 0x7E36, 0x4E55, 0x5E74, 0x2E93, 0x3EB2, 0x0ED1, 0x1EF0
)

fun crc16False(buffer: ByteArray): Int {
    var crc = 0xFFFF
    for (b in buffer) {
        // and 0xFFFF inside the loop emulates Go's uint16 overflow;
        // without it the Int accumulates bits beyond 2 bytes and the
        // table index (crc shr 8) & 0xFF diverges.
        crc = (CRC16_TABLE[((crc shr 8) xor (b.toInt() and 0xFF)) and 0xFF] xor (crc shl 8)) and 0xFFFF
    }
    return crc
}
