import kotlinx.coroutines.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

data class EndpointConf(
    val name: String,
    val url: String,
    val timeoutMS: Long = 2000,
    val attempts: Int = 3,
    val intervalSec: Int = 5,
    val expectedStatus: Int = 200
)

sealed interface Response{
    val endpointName: String
    val timestamp: Long

    data class Success(
        override val endpointName: String,
        override val timestamp: Long,
        val statusCode: Int,
        val responseTimeMs: Long,
        val response: String
    ) : Response
    data class Failure(
        override val endpointName: String,
        override val timestamp: Long,
        val error: Throwable,
    ) : Response
    data class Degraded(
        override val endpointName: String,
        override val timestamp: Long,
        val latencyMs: Long,
        val reason: String,
    ) : Response
}


@DslMarker
annotation class  PulseDsl

@PulseDsl
class EndpointBuilder(val name: String) {
    var url: String = ""
    var timeoutMs: Long = 2000
    var attempts = 3
    var intervalSec: Int = 5
    var expectedStatus: Int = 200
    fun build(): EndpointConf {
        require(url.isNotBlank()) {"поле url пустое для конфига для '$name'"}
        return EndpointConf(
            name = name,
            url = url,
            timeoutMS = timeoutMs,
            attempts = attempts,
            intervalSec = intervalSec,
            expectedStatus = expectedStatus
        )
    }
}


class Monitor(val endpoints: List<EndpointConf>, val client: HttpClient) {

    private val endpointsByName = endpoints.associateBy{it.name}
    private val pingResults = mutableListOf<Response>()

    suspend fun pingAll(): List<Response> {

        val results: List<Response> = coroutineScope {
            endpoints.map { endpoint ->
                async(Dispatchers.IO) {
                    pingEndpoint(endpoint, client)
                }
            }.awaitAll()
        }
        pingResults.addAll(results)
        return results
    }
    suspend fun ping(name: String): Response? {
        val endpoint = endpointsByName[name] ?: run {Log.error { "эндпоинт с именем $name не найден в списке"}; return null}
        return pingEndpoint(endpoint, client).also {pingResults.add(it)}
    }

    private suspend fun pingEndpoint(endpointConf: EndpointConf, client: HttpClient): Response {
        Log.info {"Пингуем эндпоинт ${endpointConf.name}"}
        return try {
            val url = endpointConf.url
            val request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMillis(endpointConf.timeoutMS)).build()
            var timed: TimedValue<HttpResponse<String>>? = null

            for (attempt in 1..endpointConf.attempts) {
                try {
                    timed = measureTimedValue {
                        client.send(request, HttpResponse.BodyHandlers.ofString())
                    }
                    break
                } catch (e: HttpTimeoutException) {
                    Log.warn{"Исключение $e, таймаут"}
                    if (attempt < endpointConf.attempts) {
                        delay(endpointConf.intervalSec.seconds)
                    }
                }
            }
            when {
                timed == null || timed.value.statusCode() != endpointConf.expectedStatus || timed.duration.inWholeMilliseconds > endpointConf.timeoutMS * 0.8 -> Response.Degraded(
                    endpointName =endpointConf.name,
                    timestamp=Instant.now().toEpochMilli(),
                    latencyMs = if (timed==null) endpointConf.attempts * endpointConf.timeoutMS else timed.duration.inWholeMilliseconds,
                    reason= if (timed==null) "не удалось дойти до сайта"
                    else if (timed.duration.inWholeMilliseconds > endpointConf.timeoutMS * 0.8) "долгое ожидание ${timed.duration.inWholeMilliseconds}мс"
                    else "статус ${timed.value.statusCode()} не совпадает с ожидаемым"
                )
                else -> Response.Success(
                    endpointConf.name,
                    Instant.now().toEpochMilli(),
                    timed.value.statusCode(),
                    timed.duration.inWholeMilliseconds,
                    timed.value.body().take(100)

                )
            }

        } catch (e: Exception) {
            Response.Failure(
                endpointConf.name,
                Instant.now().toEpochMilli(),
                e
            )
        }
    }

    fun getFailures(): List<Response.Failure> = pingResults.filterIsInstance<Response.Failure>()
    fun getDegraded(): List<Response.Degraded> = pingResults.filterIsInstance<Response.Degraded>()
    fun getSuccessful(): List<Response.Success> = pingResults.filterIsInstance<Response.Success>()

    fun getReport(): String {
        val failed = getFailures()
        val degraded = getDegraded()
        val successful = getSuccessful()
        val avgLatencySuccessful = successful.map{it.responseTimeMs}.average().takeIf {!it.isNaN()}?.roundToInt() ?: 0
        val avgLatencyDegraded = degraded.map {it.latencyMs}.average().takeIf{!it.isNaN()}?.roundToInt() ?: 0
        val fastest = successful.minByOrNull { it.responseTimeMs }
        val slowest = successful.maxByOrNull { it.responseTimeMs }

        return """
            отчет о анализе эндпоинтов (${Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))})
            Успешные: ${successful.joinToString { it.endpointName }} | кол-во: ${successful.size}
            Проваленые: ${failed.joinToString { it.endpointName }} | кол-во ${failed.size}
            Несостоятельные: ${degraded.joinToString { it.endpointName }} | кол-во ${degraded.size}
            Процент успешных: ${round((successful.size.toDouble() / (failed.size + successful.size + degraded.size)) * 100)}%
            Среднее время ответа у успешных: $avgLatencySuccessful мс
            Среднее время ответа несостоятельных: $avgLatencyDegraded мс
            Самый медленный успешный эндпоинт: ${slowest?.let {"${slowest.endpointName} (${slowest.responseTimeMs})мс"} ?: "Нет данных"}
            Самый быстрый успешный эндпоинт:  ${fastest?.let{"${fastest.endpointName} (${fastest.responseTimeMs})мс"} ?: "Нет данных"}
            
            """.trimIndent()

    }
}

@PulseDsl
class MonitorBuilder {
    private val endpoints = mutableListOf<EndpointConf>()
    var client = HttpClient.newBuilder().build()

    fun endpoint(name: String, block: EndpointBuilder.() -> Unit) {
        endpoints.add(EndpointBuilder(name).apply(block).build())
    }

    fun build(): Monitor = Monitor(endpoints.toList(), client)
}

fun monitor(block: MonitorBuilder.() -> Unit): Monitor =
    MonitorBuilder().apply(block).build()


fun main() = runBlocking {
    Log.debug {"Приложение запцщено"}
    val monitor = monitor {
        endpoint("github") {url="https://github.com"}
        endpoint("google") {url="https://google.com"}
        endpoint("healthcheck") { url = "https://httpbin.org/status/200" }
        endpoint("json-api") { url = "https://jsonplaceholder.typicode.com/todos/1" }
        endpoint("postman-echo") { url = "https://postman-echo.com/get" }
        endpoint("github-api") { url = "https://api.github.com"; timeoutMs = 3000; attempts = 2 }
        endpoint("httpbin-get") { url = "https://httpbin.org/get"; timeoutMs = 1500; attempts = 4 }
        endpoint("bad-domain") { url = "https://this-domain-definitely-does-not-exist-12345.com" }
        endpoint("wrong-port") { url = "http://localhost:9999"; timeoutMs = 1000; attempts = 1 }
        endpoint("dns-fail") { url = "https://nonexistent.invalid" }
        endpoint("unresolvable-host") { url = "http://no-such-host.local"; timeoutMs = 1000; attempts = 2 }
        endpoint("dead-url") { url = "https://httpbin.org/status/404"; expectedStatus = 200 }
        endpoint("slow-but-ok") { url = "https://httpbin.org/delay/5"; timeoutMs = 8000; attempts = 2; intervalSec = 10 }
        endpoint("very-slow") { url = "https://httpbin.org/delay/10"; timeoutMs = 15000; attempts = 1; intervalSec = 15 }
        endpoint("flaky-500") { url = "https://httpbin.org/status/500"; attempts = 5; intervalSec = 2 }
        endpoint("redirect-loop") { url = "https://httpbin.org/redirect/5"; timeoutMs = 5000; attempts = 3 }
        endpoint("weird-status") { url = "https://httpbin.org/status/418"; expectedStatus = 418; attempts = 3 }
        endpoint("youtube") {url = "https://youtube.com"}

        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    }

    monitor.pingAll()
    println("\n\n${monitor.getReport()}")
}