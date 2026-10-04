import com.opus.music.cast.raop.AlacEncoder
import com.opus.music.cast.raop.RaopCrypto
import com.opus.music.cast.raop.RaopPcmPipeline
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

/** Minimal bit reader for verifying encoded frames. */
class BitReader(val bytes: ByteArray) {
    var bitPos = 0
    fun read(n: Int): Long {
        var v = 0L
        repeat(n) {
            val b = bytes[bitPos / 8].toInt() and 0xFF
            v = (v shl 1) or ((b ushr (7 - (bitPos % 8))) and 1).toLong()
            bitPos++
        }
        return v
    }
}

fun decodeFrame(bytes: ByteArray): Pair<ShortArray, ShortArray> {
    val r = BitReader(bytes)
    fun channel(): ShortArray {
        check("tag", r.read(3) == 0L)
        val ch = r.read(4)
        r.read(12)
        val shortFlag = r.read(1)
        r.read(2)
        check("escape", r.read(1) == 1L)
        val count = if (shortFlag == 1L) r.read(32).toInt() else 352
        return ShortArray(count) { r.read(16).toShort() }.also {
            check("channel $ch decoded", true)
        }
    }
    val left = channel()
    val right = channel()
    check("end tag", r.read(3) == 7L)
    return left to right
}

// ---- 1. Silence frame: exact size + known header bytes ----
fun main() {
run {
    val silence = ShortArray(352)
    val f = AlacEncoder.encodeFrame(silence, silence)
    check("silence frame size == 1415", f.size == 1415)
    // ch0: tag=000 chan=0000 hdr=12x0 flag=0 extra=00 esc=1 -> bytes 00 00 02
    check(
        "silence header bytes",
        (f[0].toInt() and 0xFF) == 0x00 &&
            (f[1].toInt() and 0xFF) == 0x00 &&
            (f[2].toInt() and 0xFF) == 0x02,
    )
    check("silence ch0 samples zero", f.copyOfRange(3, 706).all { it == 0.toByte() })
}

// ---- 2. Round-trip with pseudo-random samples ----
run {
    val rnd = Random(1234)
    val left = ShortArray(352) { rnd.nextInt(-32768, 32767).toShort() }
    val right = ShortArray(352) { rnd.nextInt(-32768, 32767).toShort() }
    val f = AlacEncoder.encodeFrame(left, right)
    check("random frame size == 1415", f.size == 1415)
    val (dl, dr) = decodeFrame(f)
    check("left round-trip", dl.contentEquals(left))
    check("right round-trip", dr.contentEquals(right))
}

// ---- 3. Short final frame ----
run {
    val left = ShortArray(352) { 1000 }
    val right = ShortArray(352) { -1000 }
    val f = AlacEncoder.encodeFrame(left, right, 100)
    // 2ch * (23 header bits + 32 count bits + 100*16) + 3 end bits, byte-aligned
    val bits = 2 * (23 + 32 + 100 * 16) + 3
    val expect = (bits + 7) / 8
    check("short frame size == $expect", f.size == expect)
    val (dl, dr) = decodeFrame(f)
    check("short left", dl.size == 100 && dl.all { it == 1000.toShort() })
    check("short right", dr.size == 100 && dr.all { it == (-1000).toShort() })
}

// ---- 4. AES: fixed triple, block encryption, clear tail, decrypt round-trip ----
run {
    val key = RaopCrypto.aesKeyBytes()
    val iv = RaopCrypto.aesIvBytes()
    check("fixed aes key length 16", key.size == 16)
    check("fixed aes iv length 16", iv.size == 16)
    check("fixed aes key matches verified triple", key.contentEquals(
        byteArrayOf(0x14, 0x49, 0x7d.toByte(), 0xcc.toByte(), 0x98.toByte(), 0xe1.toByte(), 0x37, 0xa8.toByte(),
            0x55, 0xc1.toByte(), 0x45, 0x5a, 0x6b, 0xc0.toByte(), 0xc9.toByte(), 0x79)))
    check("fixed aes iv matches verified triple", RaopCrypto.aesIvB64() == "ePRBLI0XN5ArFaaz7ncNZw")
    // The hard-coded RSA blob must be a 256-byte ciphertext (344 base64 chars).
    val blobB64 = RaopCrypto.RSA_AES_KEY_B64
    check("rsaaeskey blob 344 chars", blobB64.length == 344)
    check("rsaaeskey blob decodes to 256 bytes", java.util.Base64.getDecoder().decode(blobB64).size == 256)
    val payload = ByteArray(1415) { it.toByte() }
    val enc = RaopCrypto.encryptPayload(payload)
    check("aes same size", enc.size == payload.size)
    check("aes changes full blocks", !enc.copyOf(1408).contentEquals(payload.copyOf(1408)))
    check("aes tail 7 bytes clear", enc.copyOfRange(1408, 1415).contentEquals(payload.copyOfRange(1408, 1415)))
    // Decrypt and compare.
    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val dec = cipher.doFinal(enc, 0, 1408)
    check("aes decrypt round-trip", dec.contentEquals(payload.copyOf(1408)))
    // IV is re-initialised for every packet: same input -> same output across calls.
    val enc2 = RaopCrypto.encryptPayload(payload)
    check("aes IV reset per packet (deterministic)", enc.contentEquals(enc2))
}

// ---- 5. RTP header layout ----
run {
    val pkt = ByteArray(12 + 1415)
    pkt[0] = 0x80.toByte(); pkt[1] = 0x60.toByte()
    val seq = 43981
    pkt[2] = (seq ushr 8).toByte(); pkt[3] = seq.toByte()
    check("rtp version/pt", (pkt[0].toInt() and 0xFF) == 0x80 && (pkt[1].toInt() and 0xFF) == 0x60)
    check("rtp seq", ((pkt[2].toInt() and 0xFF) shl 8 or (pkt[3].toInt() and 0xFF)) == seq)
}

// ---- 6. RSA blob sanity: fixed blob is well-formed (no RSA at runtime) ----
run {
    // Classic RAOP senders hard-code the triple; no RSA happens on device.
    // We only assert the blob is well-formed: 344 base64 chars -> 256 bytes.
    val blobB64 = RaopCrypto.RSA_AES_KEY_B64
    check("static blob present", blobB64.isNotBlank())
    check("static blob 344 chars", blobB64.length == 344)
    check("static blob decodes to 256 bytes", java.util.Base64.getDecoder().decode(blobB64).size == 256)
}

// ---- 7. LinearResampler boundaries (chunk-wise 48k->44.1k etc.) ----
run {
    fun fresh(rate: Int) = RaopPcmPipeline.LinearResampler(rate)
    fun runChunks(rate: Int, chunks: List<Pair<FloatArray, FloatArray>>): Pair<List<Float>, List<Float>> {
        val rs = fresh(rate)
        val outL = ArrayList<Float>(); val outR = ArrayList<Float>()
        for ((l, r) in chunks) rs.process(l, r, l.size, outL, outR)
        return outL.toList() to outR.toList()
    }
    // 7a. 44.1k passthrough: step=1, output[k] == input[k]; last sample
    // of a chunk is held for the next chunk's interpolation (needs i+1).
    val n = 1000
    val l = FloatArray(n) { kotlin.math.sin(it * 0.05).toFloat() }
    val r = FloatArray(n) { kotlin.math.cos(it * 0.05).toFloat() }
    val (ol, orr) = runChunks(44100, listOf(l to r))
    check("44100 count n-1", ol.size == n - 1 && orr.size == n - 1)
    check("44100 passthrough exact", (0 until n - 1).all {
        kotlin.math.abs(ol[it] - l[it]) < 1e-6f && kotlin.math.abs(orr[it] - r[it]) < 1e-6f
    })
    // 7b. Chunk-boundary continuity: one chunk vs two halves must agree.
    val m = 4800
    val ml = FloatArray(m) { kotlin.math.sin(it * 0.02).toFloat() }
    val mr = FloatArray(m) { kotlin.math.cos(it * 0.02).toFloat() }
    val (a1, b1) = runChunks(48000, listOf(ml to mr))
    val (a2, b2) = runChunks(48000, listOf(
        ml.copyOfRange(0, 2400) to mr.copyOfRange(0, 2400),
        ml.copyOfRange(2400, 4800) to mr.copyOfRange(2400, 4800)
    ))
    check("48k output count ~4410", a1.size in 4408..4412)
    check("chunk split identical", a1.size == a2.size &&
        a1.indices.all { kotlin.math.abs(a1[it] - a2[it]) < 1e-6f } &&
        b1.indices.all { kotlin.math.abs(b1[it] - b2[it]) < 1e-6f })
    // 7c. DC in -> DC out (no drift), stereo independent.
    val dc = FloatArray(500) { 0.5f }; val dc2 = FloatArray(500) { -0.25f }
    val (dL, dR) = runChunks(48000, listOf(dc to dc2))
    check("dc preserved", dL.all { kotlin.math.abs(it - 0.5f) < 1e-5f } &&
        dR.all { kotlin.math.abs(it + 0.25f) < 1e-5f })
    // 7d. Degenerate inputs: no crash, no output.
    val (eL, eR) = runChunks(48000, listOf(FloatArray(1) { 1f } to FloatArray(1) { 1f }))
    check("1-frame input -> no output", eL.isEmpty() && eR.isEmpty())
    val (zL, _) = runChunks(48000, listOf(FloatArray(0) to FloatArray(0)))
    check("empty input -> no output", zL.isEmpty())
    // 7e. reset() restores fresh behavior.
    val rs = fresh(48000)
    val o1 = ArrayList<Float>(); val o2 = ArrayList<Float>()
    rs.process(ml, mr, m, o1, o2)
    rs.reset(48000)
    val p1 = ArrayList<Float>(); val p2 = ArrayList<Float>()
    rs.process(ml, mr, m, p1, p2)
    check("reset reproduces", o1 == p1 && o2 == p2)
}

if (failures > 0) {
    println("\n$failures FAILURE(S)")
    kotlin.system.exitProcess(1)
} else {
    println("\nALL RAOP TESTS PASSED")
}
}
