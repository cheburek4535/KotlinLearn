import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Log {
    private const val RESET = "\u001B[0m"
    private const val GREEN = "\u001B[32m"
    private const val YELLOW = "\u001B[33m"
    private const val RED = "\u001B[31m"
    private const val CYAN = "\u001B[36m"
    private val TIME: String
        get() = Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))

    enum class Level {DEBUG, INFO, WARN, ERROR}
    private var minLevel = Level.DEBUG
    fun setMinLevel(lvl: Level) {
        minLevel = lvl
    }
    fun debug(msg: () -> String) {
        if (minLevel == Level.DEBUG) println("[$TIME] [DEBUG] ${msg()}")
    }
    fun info(msg: () -> String) {
        if (minLevel <= Level.INFO) println("$CYAN[$TIME] [INFO]: ${msg()}$RESET")
    }
    fun success(msg: () -> String) {
        if (minLevel <= Level.INFO) println("$GREEN[$TIME] [OK]: ${msg()}$RESET")
    }
    fun warn(msg: () -> String) {
        if (minLevel <= Level.WARN) println("$YELLOW[$TIME] [WARN]: ${msg()}$RESET")
    }
    fun error(msg: () -> String) {
        if (minLevel <= Level.ERROR) println("$RED[$TIME] [ERROR]: ${msg()}$RESET")
    }
}