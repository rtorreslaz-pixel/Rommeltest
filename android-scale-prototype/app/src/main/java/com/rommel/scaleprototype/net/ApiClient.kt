package com.rommel.scaleprototype.net

import android.content.Context
import com.rommel.scaleprototype.BuildConfig
import com.rommel.scaleprototype.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class ApiException(val code: Int, override val message: String) : IOException(message)

class ApiClient(private val baseUrl: String, context: Context) {

    private val authRepository = AuthRepository(context.applicationContext)
    private val catalogCache = CatalogCache(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(authRepository))
        .build()

    suspend fun login(email: String, password: String): LoginResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginRequest(email, password)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrl + "api/mobile/auth").post(body).build()
        execute(request) { json.decodeFromString(LoginResponse.serializer(), it) }
    }

    suspend fun getCatalogos(): CatalogosResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + "api/mobile/catalogos").get().build()
        execute(request) { raw ->
            val parsed = json.decodeFromString(CatalogosResponse.serializer(), raw)
            catalogCache.save(raw) // respaldo offline: solo se guarda si parseó bien
            parsed
        }
    }

    /**
     * Respaldo offline: la última respuesta buena de catálogos, o null si el teléfono
     * nunca logró descargarla. Ver [CatalogCache].
     */
    fun getCatalogosOffline(): CatalogosResponse? = catalogCache.load()?.let { raw ->
        runCatching { json.decodeFromString(CatalogosResponse.serializer(), raw) }.getOrNull()
    }

    suspend fun postRegistros(registros: List<RegistroDto>): RegistrosBatchResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(RegistrosBatchRequest(registros)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrl + "api/mobile/registros").post(body).build()
        execute(request) { json.decodeFromString(RegistrosBatchResponse.serializer(), it) }
    }

    suspend fun postSaca(muestreos: List<SacaMuestreoDto>): SacaBatchResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SacaBatchRequest(muestreos)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrl + "api/mobile/saca").post(body).build()
        execute(request) { json.decodeFromString(SacaBatchResponse.serializer(), it) }
    }

    suspend fun postPlan(items: List<PlanItemDto>, borrar: List<String> = emptyList()): PlanBatchResponse =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(PlanBatchRequest(items, borrar)).toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder().url(baseUrl + "api/mobile/plan").post(body).build()
            execute(request) { json.decodeFromString(PlanBatchResponse.serializer(), it) }
        }

    /** El plan de un día según el servidor, con el estado de cada fila. */
    suspend fun getPlan(fecha: String): PlanDiaResponse = withContext(Dispatchers.IO) {
        val url = (baseUrl + "api/mobile/plan").toHttpUrl().newBuilder()
            .addQueryParameter("fecha", fecha)
            .build()
        val request = Request.Builder().url(url).get().build()
        execute(request) { json.decodeFromString(PlanDiaResponse.serializer(), it) }
    }

    suspend fun postLiveWeight(liveWeight: LiveWeightRequest) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(liveWeight).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(baseUrl + "api/mobile/live-weight").post(body).build()
        execute(request) { }
    }

    suspend fun getNumeroAveMax(
        plantelId: String,
        campania: String,
        galpon: String,
        corral: String,
        categoria: String,
    ): NumeroAveMaxResponse = withContext(Dispatchers.IO) {
        val url = (baseUrl + "api/mobile/numero-ave-max").toHttpUrl().newBuilder()
            .addQueryParameter("plantelId", plantelId)
            .addQueryParameter("campania", campania)
            .addQueryParameter("galpon", galpon)
            .addQueryParameter("corral", corral)
            .addQueryParameter("categoria", categoria)
            .build()
        val request = Request.Builder().url(url).get().build()
        execute(request) { json.decodeFromString(NumeroAveMaxResponse.serializer(), it) }
    }

    private inline fun <T> execute(request: Request, parse: (String) -> T): T {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, responseBody)
            return parse(responseBody)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        @Volatile
        private var instance: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient(BuildConfig.API_BASE_URL, context).also { instance = it }
            }
        }
    }
}

private class AuthInterceptor(private val authRepository: AuthRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = authRepository.getToken()
        val request = if (token != null) {
            chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
