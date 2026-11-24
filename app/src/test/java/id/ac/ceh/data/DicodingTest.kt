package id.ac.ceh.data

import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class DicodingTest {
    @Test fun mapsFixtureAndSendsNoCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"error":false,"listEvents":[
                    {"id":19,"name":"  Belajar Android  ","summary":"<p>Untuk pemula</p>",
                     "category":"Seminar","cityName":"  Kota   Bandung  ",
                     "beginTime":"2026-12-01T19:00:00+07:00","endTime":"2026-12-01T21:00:00+07:00",
                     "mediaCover":"http://example.invalid/cover.png","imageLogo":"https://example.invalid/logo.png"},
                    {"id":20,"name":"Jadwal WIB tanpa offset","beginTime":"2026-12-01 19:00:00"},
                    {"id":21,"name":"Offset eksplisit bukan WIB","beginTime":"2026-12-01 19:00:00+08:00","timezone":"Asia/Jakarta"}
                ]}
            """.trimIndent()))
            val response = dicodingApi(server.url("/").toString()).events()
            assertEquals(false, response.error)
            val events = requireNotNull(response.listEvents)
            val mapped = events.mapNotNull { it?.toEvent() }
            assertEquals(3, mapped.size)
            val first = mapped.first()
            assertEquals("dicoding:19", first.externalId)
            assertEquals("dicoding", first.source)
            assertEquals("Belajar Android", first.title)
            assertEquals("Untuk pemula", first.description)
            assertEquals(6L, first.categoryId ?: 0L)
            assertEquals("Kota Bandung", first.location)
            assertEquals("https://example.invalid/logo.png", first.imageUrl)
            assertEquals(Instant.parse("2026-12-01T12:00:00Z").toEpochMilli(), first.startTime)
            assertEquals(Instant.parse("2026-12-01T14:00:00Z").toEpochMilli(), first.endTime)
            assertEquals(Instant.parse("2026-12-01T12:00:00Z").toEpochMilli(), mapped[1].startTime)
            assertEquals(Instant.parse("2026-12-01T11:00:00Z").toEpochMilli(), mapped[2].startTime)
            assertNull(first.latitude)
            assertNull(first.longitude)
            assertNull(first.organizerId)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("GET", request.method)
            assertEquals("/events?active=-1&limit=20", request.path)
            assertEquals("application/json", request.getHeader("Accept"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-API-Key"))
        }
    }

    @Test fun verificationHtmlIsNotAResultPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(405).setHeader("Content-Type", "text/html")
                .setBody("<html><title>Human Verification</title></html>"))
            val failure = expectFailure<HttpException> { dicodingApi(server.url("/").toString()).events() }
            assertEquals(405, failure.code())
            assertEquals("text/html", failure.response()?.headers()?.get("Content-Type"))
        }
    }

    @Test fun rateLimitIsAnHttpFailureWithoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                .setBody("""{"error":true}"""))
            val failure = expectFailure<HttpException> { dicodingApi(server.url("/").toString()).events() }
            assertEquals(429, failure.code())
            assertEquals("60", failure.response()?.headers()?.get("Retry-After"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun successfulHttpStatusDoesNotMakeHtmlOrBrokenJsonValid() = runBlocking {
        MockWebServer().use { server ->
            val api = dicodingApi(server.url("/").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .setBody("<html><title>Human Verification</title></html>"))
            expectFailure<MalformedJsonException> { api.events() }

            server.enqueue(jsonResponse("""{"error":false,"listEvents":[{"name":"\q"}]}"""))
            expectFailure<MalformedJsonException> { api.events() }

            server.enqueue(jsonResponse("""{"error":false,"listEvents":["""))
            expectFailure<EOFException> { api.events() }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun distinguishesMissingNullAndEmptyLists() = runBlocking {
        MockWebServer().use { server ->
            val api = dicodingApi(server.url("/").toString())
            server.enqueue(jsonResponse("""{"error":false}"""))
            server.enqueue(jsonResponse("""{"error":false,"listEvents":null}"""))
            server.enqueue(jsonResponse("""{"error":false,"listEvents":[]}"""))

            val missing = api.events()
            val explicitNull = api.events()
            val empty = api.events()
            assertEquals(false, missing.error)
            assertNull(missing.listEvents)
            assertEquals(false, explicitNull.error)
            assertNull(explicitNull.listEvents)
            assertEquals(false, empty.error)
            assertEquals(emptyList<DicodingEvent?>(), empty.listEvents)
        }
    }

    @Test fun successfulHttpStatusPreservesProviderErrorFlag() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""{"error":true,"listEvents":[]}"""))
            val response = dicodingApi(server.url("/").toString()).events()
            assertEquals(true, response.error)
            assertEquals(emptyList<DicodingEvent?>(), response.listEvents)
        }
    }

    @Test fun nonEmptyListOfInvalidRowsMapsToNoEvents() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse("""
                {"error":false,"listEvents":[
                    null,
                    {"name":"Tanpa ID","beginTime":"2026-12-01 19:00:00"},
                    {"id":22,"name":"  ","beginTime":"2026-12-01 19:00:00"},
                    {"id":23,"name":"Waktu tidak valid","beginTime":"bukan waktu"}
                ]}
            """.trimIndent()))
            val response = dicodingApi(server.url("/").toString()).events()
            assertEquals(false, response.error)
            val rows = requireNotNull(response.listEvents)
            assertEquals(4, rows.size)
            assertTrue(rows.mapNotNull { it?.toEvent() }.isEmpty())
        }
    }

    @Test fun missingResponseSurfacesSocketTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            // Exercise the same Retrofit endpoint without waiting for production timeouts.
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .client(OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(DicodingApi::class.java)

            withTimeout(2_000) { expectFailure<SocketTimeoutException> { api.events() } }
            assertEquals(1, server.requestCount)
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json").setBody(body)

    private inline fun <reified T : Throwable> expectFailure(request: () -> Unit): T {
        try {
            request()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
