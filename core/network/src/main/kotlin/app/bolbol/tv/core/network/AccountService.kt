package app.bolbol.tv.core.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * Retrofit view of the account routes of `mapi/v1` (docs/auth.md section 0). Every call returns the raw
 * [Response] so [RetrofitAccountRemote] can read status and body itself: a 400/401 here is an answer, not an error.
 * Account answers are per user, so they never go through the shared HTTP cache (`Cache-Control: no-store` on the
 * request makes OkHttp skip both reading and storing).
 */
internal interface AccountService {

    @Headers("Accept: application/json", "Cache-Control: no-store")
    @FormUrlEncoded
    @POST(AccountWire.LOGIN_PATH)
    suspend fun login(
        @Field(AccountWire.FIELD_USERNAME) username: String,
        @Field(AccountWire.FIELD_PASSWORD) password: String,
    ): Response<ResponseBody>

    @Headers("Accept: application/json", "Cache-Control: no-store")
    @GET(AccountWire.PROFILE_PATH)
    suspend fun profile(): Response<ResponseBody>

    /** [page] null omits the parameter (the first request asks for the plain list like the official app). */
    @Headers("Accept: application/json", "Cache-Control: no-store")
    @GET(AccountWire.FAVORITES_PATH)
    suspend fun favorites(@Query(AccountWire.QUERY_PAGE) page: Int?): Response<ResponseBody>

    /** The server toggles membership. */
    @Headers("Accept: application/json", "Cache-Control: no-store")
    @FormUrlEncoded
    @POST(AccountWire.FAVORITES_PATH)
    suspend fun toggleFavorite(@Field(AccountWire.FIELD_POST_ID) postId: Long): Response<ResponseBody>

    @Headers("Accept: application/json", "Cache-Control: no-store")
    @GET(AccountWire.PROGRESS_PATH)
    suspend fun progressList(): Response<ResponseBody>

    @Headers("Accept: application/json", "Cache-Control: no-store")
    @Multipart
    @POST(AccountWire.PROGRESS_PATH)
    suspend fun saveProgress(@Part parts: List<MultipartBody.Part>): Response<ResponseBody>
}
