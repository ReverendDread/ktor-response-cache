package dev.hlwgroup

import com.google.common.cache.CacheBuilder
import dev.hlwgroup.ResponseCache.AcceptedStatuses
import dev.hlwgroup.ResponseCache.CachingTime
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.logging.Level.FINER
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * @project ktor-response-cache
 * @author andy@hlwgroup.dev - 8/16/2023 7:39 PM
 */
class ResponseCachingConfig {
    /** The duration to cache responses */
    var defaultDuration: Duration = 30.seconds

    /** Max cache size */
    var maximumSize: Long = 1000
}

data class CachedResponse(
    val expiresAt: Instant,
    val status: HttpStatusCode,
    val body: Any,
) {
    fun isExpired(): Boolean {
        return expiresAt.isBefore(Instant.now())
    }
}

object ResponseCache {
    val CacheAttribute = AttributeKey<Boolean>("cached-response")
    val NeedsCachingAttribute = AttributeKey<Boolean>("needs-caching")
    val CachingTime = AttributeKey<Duration>("caching-time")
    val AcceptedStatuses = AttributeKey<List<HttpStatusCode>>("accepted-statuses")
}

class ResponseCaching(internal var config: ResponseCachingConfig) {

    companion object : BaseApplicationPlugin<Application, ResponseCachingConfig, ResponseCaching> {

        private val logger = Logger.getLogger("ResponseCaching")

        lateinit var cache: com.google.common.cache.Cache<CacheKey, CachedResponse>

        override val key: AttributeKey<ResponseCaching> = AttributeKey("ResponseCaching")

        @OptIn(DelicateCoroutinesApi::class)
        override fun install(pipeline: Application, configure: ResponseCachingConfig.() -> Unit): ResponseCaching {
            val config = ResponseCachingConfig().apply(configure)

            // setup the cache
            cache = CacheBuilder.newBuilder()
                .maximumSize(config.maximumSize)
                .build()

            pipeline.intercept(ApplicationCallPipeline.Plugins) {
                val key = call.toCacheKey()

                val value = (cache.getIfPresent(key) ?: return@intercept)

                logger.log(FINER, "Checking if cache is expired for ${call.request.path()}")
                if (value.isExpired()) {
                    logger.log(FINER, "Cache expired for ${call.request.path()}")
                    cache.invalidate(key)
                    return@intercept
                }

                logger.log(FINER, "Cache hit ${call.request.path()}")
                call.attributes.put(ResponseCache.CacheAttribute, true)
                call.respond(value.body)
            }

            pipeline.sendPipeline.intercept(ApplicationSendPipeline.After) { body ->
                val isCachedResponse = call.attributes.contains(ResponseCache.CacheAttribute)
                if (isCachedResponse) return@intercept

                // if the response is not cacheable, don't cache it
                val needsCaching = call.attributes.contains(ResponseCache.NeedsCachingAttribute)
                if (!needsCaching) return@intercept

                val status = call.response.status() ?: return@intercept
                val statuses = call.attributes[AcceptedStatuses]

                // if the status code is not cacheable, don't cache it
                if (!statuses.contains(status)) return@intercept

                val cacheDuration = call.attributes[CachingTime]
                logger.log(FINER,"Caching response for ${call.request.path()} for ${cacheDuration.inWholeSeconds} seconds")

                val expires = Instant.now().plusSeconds(cacheDuration.inWholeSeconds)
                val key = call.toCacheKey()

                cache.put(key, CachedResponse(expires, status, body))
            }

            GlobalScope.launch {
                while (true) {
                    cache.asMap().forEach { (key, value) ->
                        if (value.isExpired()) {
                            logger.log(FINER, "Cache expired for ${key.route}")
                            cache.invalidate(key)
                        }
                    }
                    delay(30.seconds)
                }
            }

            return ResponseCaching(config)
        }
    }

}

data class CacheKey(
    val route: String,
    val parameters: Map<String, List<String>>,
    val method: HttpMethod,
    // maybe consider headers here in future?
)

fun ApplicationCall.toCacheKey() = CacheKey(
    request.path(),
    request.queryParameters.toMap(),
    request.httpMethod,
)

suspend fun PipelineContext<Unit, ApplicationCall>.cache(vararg statuses: HttpStatusCode = arrayOf(HttpStatusCode.OK), duration: Duration? = null, block: suspend () -> Unit) {
    require(call.application.pluginOrNull(ResponseCaching) != null) { "ResponseCaching plugin is not installed" }

    call.attributes.put(ResponseCache.NeedsCachingAttribute, true)
    val cacheDuration = duration ?: call.application.plugin(ResponseCaching).config.defaultDuration
    call.attributes.put(CachingTime, cacheDuration)
    call.attributes.put(AcceptedStatuses, statuses.toList())
    block()
}