import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
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
    fun pingAll(print: Boolean = true): List<Response> {
        return endpoints.map { endpoint ->
            val resp: Response = pingEndpoint(endpoint, client)
            if (print) {
                when (resp) {
                    is Response.Success -> println("Эндпоинт успешно пингнут")
                    is Response.Failure -> println("Пинг эндпоинта провален с ошибкой ${resp.error}")
                    is Response.Degraded -> println("Пинг эндпоинта произошел плохо")
                }
            }
            resp
        }
    }
    fun ping(name: String): Response? {
        val endpoint = endpoints.find{it.name == name} ?: return null
        return pingEndpoint(endpoint, client)
    }

    private fun pingEndpoint(endpointConf: EndpointConf, client: HttpClient): Response {
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
                } catch (e: TimeoutException) {
                    println("Исключение $e, таймаут")
                    if (attempt < endpointConf.attempts) {
                        Thread.sleep(endpointConf.intervalSec.seconds.inWholeMilliseconds)
                    }
                }
            }


            when {
                timed == null || timed.value.statusCode() != endpointConf.expectedStatus -> Response.Degraded(
                    endpointName =endpointConf.name,
                    timestamp=Instant.now().toEpochMilli(),
                    latencyMs = if (timed==null) endpointConf.attempts * endpointConf.timeoutMS else timed.duration.inWholeMilliseconds,
                    reason= if (timed==null) "не удалось дойти до сайта" else "статус ${timed.value.statusCode()} не совпадает с ожидаемым"
                )
                else -> Response.Success(
                    endpointConf.name,
                    Instant.now().toEpochMilli(),
                    timed.value.statusCode(),
                    timed.duration.inWholeMilliseconds,
                    timed.value.toString()

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
}

@PulseDsl
class MonitorBuilder {
    private val endpoints = mutableListOf<EndpointConf>()
    private var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    fun endpoint(name: String, block: EndpointBuilder.() -> Unit) {
        endpoints.add(EndpointBuilder(name).apply(block).build())
    }
    fun setClient(client: HttpClient) {
        this.client = client
    }
    fun build(): Monitor = Monitor(endpoints.toList(), client)
}

fun monitor(block: MonitorBuilder.() -> Unit): Monitor =
    MonitorBuilder().apply(block).build()


fun main(args: Array<String>) {
    val monitor = monitor {
        endpoint("github") {url="https://github.com"}
        endpoint("google") {url="https://google.com"}
        setClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build())
    }
    /*val endpoints = listOf(
        EndpointConf("healthcheck", "https://httpbin.org/status/200"),
        EndpointConf("json-api", "https://jsonplaceholder.typicode.com/todos/1"),
        EndpointConf("postman-echo", "https://postman-echo.com/get"),
        EndpointConf("github-api", "https://api.github.com", TimeoutMs(3000), attempts = 2),
        EndpointConf("httpbin-get", "https://httpbin.org/get", TimeoutMs(1500), attempts = 4),

        EndpointConf("bad-domain", "https://this-domain-definitely-does-not-exist-12345.com"),
        EndpointConf("wrong-port", "http://localhost:9999", TimeoutMs(1000), attempts = 1),
        EndpointConf("dns-fail", "https://nonexistent.invalid"),
        EndpointConf("unresolvable-host", "http://no-such-host.local", TimeoutMs(1000), attempts = 2),
        EndpointConf("dead-url", "https://httpbin.org/status/404", expectedStatus = 200),

        EndpointConf("slow-but-ok", "https://httpbin.org/delay/5", TimeoutMs(8000), attempts = 2, intervalSec = 10),
        EndpointConf("very-slow", "https://httpbin.org/delay/10", TimeoutMs(15000), attempts = 1, intervalSec = 15),
        EndpointConf("flaky-500", "https://httpbin.org/status/500", attempts = 5, intervalSec = 2),
        EndpointConf("redirect-loop", "https://httpbin.org/redirect/5", TimeoutMs(5000), attempts = 3),
        EndpointConf("weird-status", "https://httpbin.org/status/418", expectedStatus = 418, attempts = 3)*/

    //)

    println(monitor.ping("github"))
    print(monitor.pingAll())
}