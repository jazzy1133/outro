import com.opus.music.cast.CastEndAction
import com.opus.music.cast.SonosDlna
import com.opus.music.cast.castEndAction

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("PASS: $name") else { println("FAIL: $name"); failures++ }
}

fun main() {
    // ---- SonosDlna.formatRelTime ----
    check("relTime zero", SonosDlna.formatRelTime(0) == "0:00:00")
    check("relTime 65s", SonosDlna.formatRelTime(65_000) == "0:01:05")
    check("relTime 1h2m3s", SonosDlna.formatRelTime(3_723_000) == "1:02:03")
    check("relTime negative clamps", SonosDlna.formatRelTime(-5_000) == "0:00:00")
    check("relTime sub-second truncates", SonosDlna.formatRelTime(61_999) == "0:01:01")

    // ---- SonosDlna.parseRelTime ----
    check("parse 0:04:32", SonosDlna.parseRelTime("0:04:32") == 272_000L)
    check("parse 1:02:03", SonosDlna.parseRelTime("1:02:03") == 3_723_000L)
    check("parse fractional", SonosDlna.parseRelTime("0:00:07.500") == 7_000L)
    check("parse blank", SonosDlna.parseRelTime("") == null)
    check("parse null", SonosDlna.parseRelTime(null) == null)
    check("parse garbage", SonosDlna.parseRelTime("abc") == null)
    check("parse two parts", SonosDlna.parseRelTime("04:32") == null)
    check("round trip", SonosDlna.parseRelTime(SonosDlna.formatRelTime(272_000)) == 272_000L)

    // ---- GetTransportInfo parsing ----
    val transportInfo = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
              <CurrentTransportState>PLAYING</CurrentTransportState>
              <CurrentTransportStatus>OK</CurrentTransportStatus>
              <CurrentSpeed>1</CurrentSpeed>
            </u:GetTransportInfoResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()
    check("state PLAYING", SonosDlna.parseTransportState(transportInfo) == "PLAYING")
    check(
        "state PAUSED_PLAYBACK",
        SonosDlna.parseTransportState(
            transportInfo.replace("PLAYING", "PAUSED_PLAYBACK")
        ) == "PAUSED_PLAYBACK"
    )
    check("state missing", SonosDlna.parseTransportState("<Envelope/>") == null)

    // ---- GetPositionInfo parsing ----
    val positionInfo = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body>
            <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
              <Track>1</Track>
              <TrackDuration>0:04:32</TrackDuration>
              <TrackMetaData></TrackMetaData>
              <TrackURI>http://example/stream</TrackURI>
              <RelTime>0:01:05</RelTime>
              <AbsTime>0:01:05</AbsTime>
              <RelCount>65</RelCount>
              <AbsCount>65</AbsCount>
            </u:GetPositionInfoResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()
    val pos = SonosDlna.parsePositionInfo(positionInfo)
    check("position parsed", pos != null)
    check("position rel", pos?.first == 65_000L)
    check("position dur", pos?.second == 272_000L)
    check("position missing", SonosDlna.parsePositionInfo("<Envelope/>") == null)

    // ---- Seek envelope carries the formatted target ----
    val seekEnv = SonosDlna.buildSoapEnvelope(
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
            "<Target>${SonosDlna.formatRelTime(272_000)}</Target>"
    )
    check("seek envelope target", seekEnv.contains("<Target>0:04:32</Target>"))
    check("seek envelope action", seekEnv.contains("<u:Seek "))

    // ---- castEndAction decision table ----
    // repeatMode: 0 off, 1 all, 2 one (androidx.media3.common.Player)
    check("end: mid-queue -> NEXT", castEndAction(0, 3, 0) == CastEndAction.NEXT)
    check("end: last -> FINISH", castEndAction(2, 3, 0) == CastEndAction.FINISH)
    check("end: last + repeatAll -> WRAP", castEndAction(2, 3, 1) == CastEndAction.WRAP)
    check("end: mid + repeatAll -> NEXT", castEndAction(1, 3, 1) == CastEndAction.NEXT)
    check("end: repeatOne -> REPLAY", castEndAction(2, 3, 2) == CastEndAction.REPLAY)
    check("end: repeatOne first -> REPLAY", castEndAction(0, 3, 2) == CastEndAction.REPLAY)
    check("end: single track -> FINISH", castEndAction(0, 1, 0) == CastEndAction.FINISH)
    check("end: single + repeatAll -> WRAP", castEndAction(0, 1, 1) == CastEndAction.WRAP)
    check("end: empty queue -> FINISH", castEndAction(0, 0, 0) == CastEndAction.FINISH)
    check("end: empty + repeatOne -> FINISH", castEndAction(0, 0, 2) == CastEndAction.FINISH)

    println(if (failures == 0) "ALL CAST TESTS PASSED" else "$failures CAST TEST(S) FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
