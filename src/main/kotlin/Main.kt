import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.time.measureTimedValue

@JvmInline
value class TimeoutMs(val value: Long)

data class EndpointConf(
    val name: String,
    val url: String,
    val timeoutMS: TimeoutMs = TimeoutMs(2000),
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

fun getEndpoint(endpointConf: EndpointConf, client: HttpClient): Response {
    return try {
        val url = endpointConf.url
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()

        val timed = measureTimedValue {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }

        Response.Success(
            endpointConf.name,
            Instant.now().toEpochMilli(),
            timed.value.statusCode(),
            timed.duration.inWholeMilliseconds,
            timed.value.toString()

        )
    } catch (e: Exception) {
        Response.Failure(
            endpointConf.name,
            Instant.now().toEpochMilli(),
            e
        )
    }
}

fun main(args: Array<String>) {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    val endpoints = listOf(
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
        EndpointConf("weird-status", "https://httpbin.org/status/418", expectedStatus = 418, attempts = 3)

    )
    val result: List<Response> = endpoints.map { endpoint ->
        println("Ping endpoint: ${endpoint.url}")

        val resp: Response = getEndpoint(endpoint, client)
        when (resp) {
            is Response.Success -> {println("Successful pinged endpoint: ${endpoint.url}! Status: ${resp.statusCode}, Latency: ${resp.responseTimeMs}")}
            is Response.Failure -> {println("Failed pinging endpoint: ${endpoint.url}! Error: ${resp.error}")}
            is Response.Degraded -> {println("Degraded endpoint: ${endpoint.url}. reason: ${resp.reason}")}
        }
        resp
    }

    print(result)
}