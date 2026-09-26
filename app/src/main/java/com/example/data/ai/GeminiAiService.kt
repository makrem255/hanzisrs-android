package com.example.data.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeneratedWordData(
    val hanzi: String,
    val pinyin: String,
    val meaning: String,
    val hskLevel: Int,
    val radical: String,
    val exampleCn: String,
    val examplePy: String,
    val exampleEn: String,
    val strokeBreakdown: String,
    val strokeCount: Int,
    val origin: WordDataOrigin = WordDataOrigin.LOCAL_FALLBACK
)

enum class WordDataOrigin { GEMINI, LOCAL_FALLBACK }

/**
 * Why a word could not be produced by Gemini. Callers can tell these apart so the
 * learner sees an actionable message instead of silently receiving fallback data.
 */
sealed class AiFailure(message: String) : Exception(message) {
    /** No usable API key was compiled into the build. */
    object MissingApiKey : AiFailure(
        "AI word generation is unavailable: no Gemini API key is configured in this build."
    )

    /** The request never reached Google (offline, DNS, timeout, TLS). */
    class Network(cause: Throwable) : AiFailure(
        "Couldn't reach the AI service. Check your connection and try again."
    ) { init { initCause(cause) } }

    /** Google answered, but rejected the request (bad key, quota, unsupported model). */
    class Api(val code: Int, val detail: String) : AiFailure(
        "The AI service rejected the request (HTTP $code). $detail"
    )

    /** The response arrived but did not contain the JSON we asked for. */
    class MalformedResponse(cause: Throwable?) : AiFailure(
        "The AI service returned an unexpected response. Please try again."
    ) { init { if (cause != null) initCause(cause) } }
}

class GeminiAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * SECURITY NOTE: the API key is compiled into the APK via BuildConfig and is
     * therefore extractable by anyone who downloads the app. This is a known,
     * accepted trade-off for this build (see project README). App Check is not
     * enforced on this endpoint because the call goes directly to
     * generativelanguage.googleapis.com rather than through the Firebase AI proxy.
     * A production release should move this behind a backend proxy or the
     * Firebase AI SDK with App Check.
     */
    suspend fun generateChineseWordData(query: String): Result<GeneratedWordData> =
        withContext(Dispatchers.IO) {
            val apiKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("")

            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                Log.w("GeminiAiService", "No Gemini key configured in this build.")
                return@withContext Result.failure(AiFailure.MissingApiKey)
            }

            try {
                val request = Request.Builder()
                    .url("$BASE_URL/models/$MODEL_ID:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(buildRequestBody(query))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w("GeminiAiService", "Gemini API returned HTTP ${response.code}.")
                        return@withContext Result.failure(
                            AiFailure.Api(response.code, summarise(body))
                        )
                    }
                    Result.success(parseWordData(body, query))
                }
            } catch (e: AiFailure) {
                Result.failure(e)
            } catch (e: java.io.IOException) {
                // Connection reset, timeout, DNS failure, no network.
                Log.w("GeminiAiService", "Network failure calling Gemini: ${e.message}")
                Result.failure(AiFailure.Network(e))
            } catch (e: org.json.JSONException) {
                Log.e("GeminiAiService", "Malformed Gemini response: ${e.message}", e)
                Result.failure(AiFailure.MalformedResponse(e))
            } catch (e: Exception) {
                Log.e("GeminiAiService", "Unexpected Gemini failure: ${e.message}", e)
                Result.failure(AiFailure.MalformedResponse(e))
            }
        }

    private fun buildRequestBody(query: String): RequestBody {
        val systemPrompt = """
            You are an expert Chinese linguist and educator. Analyze the provided Chinese character (Hanzi) or Pinyin input.
            Return ONLY a valid, single JSON object with the following fields:
            - hanzi: Chinese character(s) in Simplified Chinese.
            - pinyin: Pinyin with correct tone marks (e.g. "xuéxí", "hǎo").
            - meaning: Concise English translation and grammatical function.
            - hskLevel: Integer from 1 to 6 (default 1).
            - radical: Radical with meaning (e.g. "子 (child)").
            - exampleCn: Natural, contextual example sentence in Simplified Chinese.
            - examplePy: Pinyin for the example sentence.
            - exampleEn: English translation for the example sentence.
            - strokeCount: Integer number of strokes for the primary character.
            - strokeBreakdown: Comma-separated list of stroke names with tone/direction (e.g. "点 (Diǎn), 横折 (Héng Zhé), 竖 (Shù)").
            Do NOT wrap the JSON in Markdown code fences if possible, or provide standard raw JSON.
        """.trimIndent()

        val jsonPayload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "$systemPrompt\n\nUser Input: $query"))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("responseMimeType", "application/json")
            })
        }
        return jsonPayload.toString().toRequestBody("application/json".toMediaType())
    }

    private fun parseWordData(responseBody: String, query: String): GeneratedWordData {
        val jsonResponse = JSONObject(responseBody)
        val text = jsonResponse.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()

        if (text.isBlank()) throw org.json.JSONException("empty candidates payload")

        val cleanedJson = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val wordJson = JSONObject(cleanedJson)
        return GeneratedWordData(
            hanzi = wordJson.optString("hanzi", query),
            pinyin = wordJson.optString("pinyin", "pīnyīn"),
            meaning = wordJson.optString("meaning", "Meaning"),
            hskLevel = wordJson.optInt("hskLevel", 1).coerceIn(1, 6),
            radical = wordJson.optString("radical", "部首"),
            exampleCn = wordJson.optString("exampleCn", "这是一个例句。"),
            examplePy = wordJson.optString("examplePy", "Zhè shì yí gè lìjù."),
            exampleEn = wordJson.optString("exampleEn", "This is an example sentence."),
            strokeBreakdown = wordJson.optString("strokeBreakdown", "横 (Héng), 竖 (Shù), 撇 (Piě), 捺 (Nà)"),
            strokeCount = wordJson.optInt("strokeCount", 4),
            origin = WordDataOrigin.GEMINI
        )
    }

    /** Pulls a short, safe message out of a Google error body for display. */
    private fun summarise(errorBody: String): String {
        val message = runCatching {
            JSONObject(errorBody).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() }?.take(200) ?: "No further detail available."
    }

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MODEL_ID = "gemini-3.5-flash"
    }

    /**
     * Offline sample data for a small built-in dictionary, plus a generic placeholder
     * for anything unknown.
     *
     * This is deliberately NOT used as an automatic fallback: previously any network
     * or API error was silently replaced with this data, so the learner was shown
     * placeholder content while the UI implied the AI had answered. Callers must now
     * invoke this explicitly (the result is tagged [WordDataOrigin.LOCAL_FALLBACK] so
     * the UI can label it) after the learner opts into offline sample data.
     */
    fun offlineSampleFor(query: String): GeneratedWordData {
        val trimmed = query.trim()
        val predefined = getPredefinedDictionary()

        // Exact Hanzi or Pinyin match
        predefined[trimmed]?.let { return it }

        // Character level lookup
        if (trimmed.length == 1) {
            return createHeuristicForChar(trimmed[0])
        }

        // Generic sample for any user input
        return GeneratedWordData(
            hanzi = if (isAllChinese(trimmed)) trimmed else "字",
            pinyin = if (!isAllChinese(trimmed)) trimmed else "zì",
            meaning = "character; word; written symbol",
            hskLevel = 1,
            radical = "宀 (roof)",
            exampleCn = "这个中文词语很有意思。",
            examplePy = "Zhège zhōngwén cíyǔ hěn yǒu yìsi.",
            exampleEn = "This Chinese vocabulary word is very interesting.",
            strokeBreakdown = "点 (Diǎn), 点 (Diǎn), 横钩 (Héng Gōu), 弯钩 (Wān Gōu), 横 (Héng)",
            strokeCount = 6
        )
    }

    private fun isAllChinese(str: String): Boolean {
        return str.isNotEmpty() && str.all { it.code in 0x4E00..0x9FFF }
    }

    private fun createHeuristicForChar(char: Char): GeneratedWordData {
        return GeneratedWordData(
            hanzi = char.toString(),
            pinyin = "zì",
            meaning = "Chinese character '$char'",
            hskLevel = 2,
            radical = "部首",
            exampleCn = "我们一起学习‘$char’这个汉字。",
            examplePy = "Wǒmen yìqǐ xuéxí '$char' zhège hànzì.",
            exampleEn = "Let's study the character '$char' together.",
            strokeBreakdown = "撇 (Piě), 横 (Héng), 竖 (Shù), 折 (Zhé), 点 (Diǎn)",
            strokeCount = 5
        )
    }

    private fun getPredefinedDictionary(): Map<String, GeneratedWordData> {
        return mapOf(
            "爱" to GeneratedWordData("爱", "ài", "love; affection; to like", 1, "爫 (claw)", "我非常爱我的家人。", "Wǒ fēicháng ài wǒ de jiārén.", "I love my family very much.", "撇 (Piě), 点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横撇 (Héng Piě), 横 (Héng), 撇 (Piě), 捺 (Nà)", 10),
            "ai" to GeneratedWordData("爱", "ài", "love; affection; to like", 1, "爫 (claw)", "我非常爱我的家人。", "Wǒ fēicháng ài wǒ de jiārén.", "I love my family very much.", "撇 (Piě), 点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横撇 (Héng Piě), 横 (Héng), 撇 (Piě), 捺 (Nà)", 10),
            "人" to GeneratedWordData("人", "rén", "person; people; human", 1, "人 (person)", "这里有很多好人。", "Zhèlǐ yǒu hěn duō hǎorén.", "There are many good people here.", "撇 (Piě), 捺 (Nà)", 2),
            "ren" to GeneratedWordData("人", "rén", "person; people; human", 1, "人 (person)", "这里有很多好人。", "Zhèlǐ yǒu hěn duō hǎorén.", "There are many good people here.", "撇 (Piě), 捺 (Nà)", 2),
            "中" to GeneratedWordData("中", "zhōng", "middle; center; China", 1, "丨 (line)", "他在教室中间坐着。", "Tā zài jiàoshì zhōngjiān zuòzhe.", "He is sitting in the middle of the classroom.", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù)", 4),
            "zhong" to GeneratedWordData("中", "zhōng", "middle; center; China", 1, "丨 (line)", "他在教室中间坐着。", "Tā zài jiàoshì zhōngjiān zuòzhe.", "He is sitting in the middle of the classroom.", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù)", 4),
            "大" to GeneratedWordData("大", "dà", "big; great; large", 1, "大 (big)", "这个苹果很大很甜。", "Zhège píngguǒ hěn dà hěn tián.", "This apple is very big and sweet.", "横 (Héng), 撇 (Piě), 捺 (Nà)", 3),
            "da" to GeneratedWordData("大", "dà", "big; great; large", 1, "大 (big)", "这个苹果很大很甜。", "Zhège píngguǒ hěn dà hěn tián.", "This apple is very big and sweet.", "横 (Héng), 撇 (Piě), 捺 (Nà)", 3),
            "水" to GeneratedWordData("水", "shuǐ", "water; liquid; river", 1, "水 (water)", "多喝水对身体好。", "Duō hē shuǐ duì shēntǐ hǎo.", "Drinking plenty of water is good for your health.", "竖钩 (Shù Gōu), 横撇 (Héng Piě), 撇 (Piě), 捺 (Nà)", 4),
            "shui" to GeneratedWordData("水", "shuǐ", "water; liquid; river", 1, "水 (water)", "多喝水对身体好。", "Duō hē shuǐ duì shēntǐ hǎo.", "Drinking plenty of water is good for your health.", "竖钩 (Shù Gōu), 横撇 (Héng Piě), 撇 (Piě), 捺 (Nà)", 4),
            "日" to GeneratedWordData("日", "rì", "sun; day; daytime", 1, "日 (sun)", "今天是一个阳光明媚的日子。", "Jīntiān shì yígè yángguāng míngmèi de rìzi.", "Today is a bright and sunny day.", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 横 (Héng)", 4),
            "月" to GeneratedWordData("月", "yuè", "moon; month", 1, "月 (moon)", "今晚的月亮特别圆。", "Jīnwǎn de yuèliàng tèbié yuán.", "Tonight's moon is especially round.", "撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng)", 4),
            "山" to GeneratedWordData("山", "shān", "mountain; hill", 1, "山 (mountain)", "周末我们打算去爬山。", "Zhōumò wǒmen dǎsuàn qù páshān.", "We plan to climb a mountain this weekend.", "竖 (Shù), 竖折 (Shù Zhé), 竖 (Shù)", 3),
            "火" to GeneratedWordData("火", "huǒ", "fire; flame; urgent", 1, "火 (fire)", "冬天我们在壁炉里生火。", "Dōngtiān wǒmen zài bìlú lǐ shēng huǒ.", "In winter we make a fire in the fireplace.", "点 (Diǎn), 撇 (Piě), 撇 (Piě), 捺 (Nà)", 4),
            "书" to GeneratedWordData("书", "shū", "book; letter; to write", 1, "丨 (line)", "我喜欢在图书馆看书。", "Wǒ xǐhuān zài túshūguǎn kàn shū.", "I like reading books in the library.", "横折 (Héng Zhé), 横折钩 (Héng Zhé Gōu), 竖 (Shù), 点 (Diǎn)", 4),
            "猫" to GeneratedWordData("猫", "māo", "cat; feline", 1, "犭 (animal)", "我家有一只可爱的小猫。", "Wǒ jiā yǒu yì zhī kě'ài de xiǎomāo.", "My family has a cute little cat.", "撇 (Piě), 弯钩 (Wān Gōu), 撇 (Piě), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù), 横 (Héng), 竖 (Shù), 竖 (Shù), 横 (Héng)", 11),
            "狗" to GeneratedWordData("狗", "gǒu", "dog; canine", 1, "犭 (animal)", "这只小狗非常活泼友好。", "Zhè zhī xiǎogǒu fēicháng huópō yǒuhǎo.", "This puppy is very lively and friendly.", "撇 (Piě), 弯钩 (Wān Gōu), 撇 (Piě), 撇 (Piě), 横折钩 (Héng Zhé Gōu), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng)", 8),
            "吃" to GeneratedWordData("吃", "chī", "to eat; to consume", 1, "口 (mouth)", "你想吃中国菜吗？", "Nǐ xiǎng chī zhōngguó cài ma?", "Do you want to eat Chinese food?", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 撇 (Piě), 横 (Héng), 竖弯钩 (Shù Wān Gōu)", 6),
            "喝" to GeneratedWordData("喝", "hē", "to drink", 1, "口 (mouth)", "天气热的时候多喝水。", "Tiānqì rè de shíhòu duō hē shuǐ.", "Drink more water when the weather is hot.", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 撇 (Piě), 竖折 (Shù Zhé), 竖 (Shù)", 12)
        )
    }
}
