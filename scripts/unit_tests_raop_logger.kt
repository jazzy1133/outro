import com.opus.music.cast.raop.RaopLogger

// JVM smoke checks for RaopLogger (pure JVM, no Android APIs).

fun main() {
    var failures = 0
    fun check(name: String, cond: Boolean) {
        println((if (cond) "PASS" else "FAIL") + "  $name")
        if (!cond) failures++
    }

    RaopLogger.clear()
    check("L1 empty snapshot after clear", RaopLogger.snapshot().isEmpty())

    var notified = 0
    val listener: () -> Unit = { notified++ }
    RaopLogger.addListener(listener)
    RaopLogger.log("hello")
    check("L2 snapshot contains logged line", RaopLogger.snapshot().contains("hello"))
    check("L3 listener notified once", notified == 1)

    RaopLogger.logBlock("HDR", "a\nb")
    val lines = RaopLogger.snapshot().lines()
    check("L4 block logged as header + indented lines",
        lines.size == 4 && lines[1].endsWith("HDR") &&
            lines[2].endsWith("    a") && lines[3].endsWith("    b"))

    RaopLogger.removeListener(listener)
    RaopLogger.log("bye")
    check("L5 no notification after remove", notified == 4)

    repeat(500) { RaopLogger.log("x$it") }
    check("L6 ring buffer capped at 400", RaopLogger.snapshot().lines().size == 400)

    RaopLogger.clear()
    check("L7 clear empties snapshot", RaopLogger.snapshot().isEmpty())

    if (failures == 0) println("ALL RAOP LOGGER CHECKS PASSED")
    else println("$failures LOGGER CHECK(S) FAILED")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
}
