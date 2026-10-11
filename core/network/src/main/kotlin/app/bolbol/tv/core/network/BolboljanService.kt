package app.bolbol.tv.core.network

import app.bolbol.tv.core.network.dto.DetailEnvelopeDto
import app.bolbol.tv.core.network.dto.PagedEnvelopeDto
import app.bolbol.tv.core.network.dto.PlayerLinksEnvelopeDto
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit view of the `mapi/v1` API (docs/api.md section 3). Paths are relative to
 * `<mirror>/wp-json/mapi/v1/`; the host is chosen per call by [MirrorInterceptor].
 * Non-2xx answers surface as `retrofit2.HttpException` and are translated by [RetrofitCatalogRemote].
 */
internal interface BolboljanService {

    /** Home feed, returned as raw text so the exact JSON can be cached. */
    @GET("post/all")
    suspend fun home(): ResponseBody

    /** [category] is one of movies, series, cartoons, documentaries, suggestions. */
    @GET("post/{category}")
    suspend fun list(
        @Path("category") category: String,
        @Query("page") page: Int,
        @Query("country") country: Int?,
        @Query("genres") genres: Int?,
        @Query("imdbtop250") imdbTop250: Int?,
    ): PagedEnvelopeDto

    @GET("post/search")
    suspend fun search(
        @Query("search") text: String,
        @Query("page") page: Int,
    ): PagedEnvelopeDto

    @GET("post/{id}")
    suspend fun detail(@Path("id") id: Long): DetailEnvelopeDto

    /**
     * Download and player links of a movie or a whole series. The official app takes streams from here because
     * [detail] can carry empty link arrays (docs/api.md section 11).
     */
    @GET("post/player-links")
    suspend fun playerLinks(@Query("id") id: Long): PlayerLinksEnvelopeDto
}
