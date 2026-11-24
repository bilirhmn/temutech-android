package id.ac.ceh.data

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers

internal const val DICODING_SOURCE = "dicoding"
internal const val DICODING_ONLINE = "Online"
internal val DICODING_ZONE = ZoneId.of("Asia/Jakarta")

internal interface DicodingApi {
    @Headers("Accept: application/json")
    @GET("events?active=-1&limit=20")
    suspend fun events(): DicodingResponse
}

internal fun dicodingApi(
    baseUrl: String = "https://event-api.dicoding.dev/",
    onHttpStatus: ((Int) -> Unit)? = null,
): DicodingApi =
    Retrofit.Builder().baseUrl(baseUrl)
        .client(OkHttpClient.Builder().followRedirects(false).callTimeout(20, TimeUnit.SECONDS).apply {
            if (onHttpStatus != null) addInterceptor { chain ->
                chain.proceed(chain.request()).also { onHttpStatus(it.code) }
            }
        }.build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(DicodingApi::class.java)

internal data class DicodingResponse(val error: Boolean?, val listEvents: List<DicodingEvent?>?)

internal data class DicodingEvent(
    val id: Long?, val name: String?, val summary: String?, val description: String?,
    val imageLogo: String?, val mediaCover: String?, val category: String?, val ownerName: String?,
    val cityName: String?, val quota: Int?, val registrants: Int?, val beginTime: String?, val endTime: String?,
    val link: String?, val timezone: String?,
) {
    fun toEvent(): Event? {
        val externalId = id?.takeIf { it > 0L } ?: return null
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val start = dicodingTime(beginTime) ?: return null
        return Event(
            title = title,
            description = plainText(summary) ?: plainText(description),
            categoryId = when (category?.trim()?.lowercase(Locale.ROOT)) {
                "workshop", "lokakarya" -> 3L
                "seminar", "webinar", "tech talk" -> 6L
                "kelas", "course", "class", "learning" -> 7L
                "meetup", "community", "komunitas" -> 8L
                else -> null
            },
            startTime = start,
            endTime = dicodingTime(endTime)?.takeIf { it >= start },
            location = cityName?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotEmpty() },
            imageUrl = sequenceOf(mediaCover, imageLogo).mapNotNull { it?.trim()?.toHttpUrlOrNull() }
                .firstOrNull { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString(),
            source = DICODING_SOURCE,
            externalId = "$DICODING_SOURCE:$externalId",
        )
    }
}

private fun dicodingTime(value: String?): Long? {
    val text = value?.trim() ?: return null
    val iso = if (text.getOrNull(10) == ' ') text.replaceRange(10, 11, "T") else text
    return try {
        val instant = try {
            // OffsetDateTime handles explicit offsets on API 26 as well as newer devices.
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeException) {
            LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(DICODING_ZONE).toInstant()
        }
        instant.toEpochMilli()
    } catch (_: DateTimeException) { null }
    catch (_: ArithmeticException) { null }
}

private fun plainText(value: String?): String? = value?.replace(Regex("<[^>]*>"), " ")
    ?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
