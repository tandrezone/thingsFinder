package app.thingsfinder.lookup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class LookupSuggestion(val name: String, val source: String)

/** Seam so the ViewModels / repository can be tested without the network. */
fun interface BarcodeNameLookup {
    suspend fun lookup(barcode: String): LookupSuggestion?
}

/**
 * Port of external_barcode_lookup() in includes/helpers.php: Open Food Facts
 * first, then UPCitemdb's keyless trial endpoint. Best-effort — returns null
 * on any failure (network, timeout, non-2xx, bad JSON) and never throws.
 * Only the barcode number leaves the phone.
 */
class OpenBarcodeLookup(
    client: OkHttpClient = defaultClient(),
    private val openFoodFactsBase: HttpUrl = "https://world.openfoodfacts.org/".toHttpUrl(),
    private val upcItemDbBase: HttpUrl = "https://api.upcitemdb.com/".toHttpUrl(),
) : BarcodeNameLookup {

    private val http = client
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookup(barcode: String): LookupSuggestion? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext null
        openFoodFacts(code) ?: upcItemDb(code)
    }

    private fun openFoodFacts(code: String): LookupSuggestion? {
        val url = openFoodFactsBase.newBuilder()
            .addPathSegments("api/v2/product")
            .addPathSegment("$code.json")
            .addQueryParameter("fields", "product_name,brands")
            .build()
        val data = getJson(url) as? JsonObject ?: return null
        if ((data["status"] as? JsonPrimitive)?.intOrNull != 1) return null
        val product = data["product"] as? JsonObject ?: return null
        var name = product.string("product_name")?.trim().orEmpty()
        if (name.isEmpty()) return null
        val brand = product.string("brands").orEmpty().split(',').first().trim()
        if (brand.isNotEmpty() && !name.contains(brand, ignoreCase = true)) name = "$brand $name"
        return LookupSuggestion(name, "Open Food Facts")
    }

    private fun upcItemDb(code: String): LookupSuggestion? {
        val url = upcItemDbBase.newBuilder()
            .addPathSegments("prod/trial/lookup")
            .addQueryParameter("upc", code)
            .build()
        val data = getJson(url) as? JsonObject ?: return null
        if (data.string("code") != "OK") return null
        val first = (data["items"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val title = first.string("title")?.trim().orEmpty()
        return if (title.isEmpty()) null else LookupSuggestion(title, "UPCitemdb")
    }

    private fun getJson(url: HttpUrl): JsonElement? = try {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "thingsFinder-Android/1.0 (personal inventory app)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            json.parseToJsonElement(body)
        }
    } catch (e: Exception) {
        // IOException, timeouts, malformed JSON: all mean "couldn't find out".
        null
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
