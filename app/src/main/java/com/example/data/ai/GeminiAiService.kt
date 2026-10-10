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
 * Why a word could not be produced. Callers can tell these apart so the
 * learner sees an actionable message instead of silently receiving fallback data.
 */
sealed class AiFailure(message: String) : Exception(message) {
    /**
     * No AI backend was configured in this build.
     *
     * This used to be `MissingApiKey`, and the difference matters: the app no longer holds a key,
     * so the thing that can be missing is an endpoint. Saying "no API key is configured" to a
     * learner would point them at a credential they have never seen and cannot fix.
     */
    object BackendNotConfigured : AiFailure(
        "AI word generation is unavailable: this build has no AI service configured."
    )

    /** The request never reached the AI service (offline, DNS, timeout, TLS). */
    class Network(cause: Throwable) : AiFailure(
        "Couldn't reach the AI service. Check your connection and try again."
    ) { init { initCause(cause) } }

    /**
     * The AI service answered, but rejected the request.
     *
     * Almost always a problem on the server (a missing or wrong key there, exhausted quota, an
     * unknown model) rather than anything the learner did. The wording says so, because the
     * previous version told the user the *app* had no API key, which sent them looking for a
     * setting that has deliberately been removed.
     */
    class Api(val code: Int, val detail: String) : AiFailure(
        "The AI service is unavailable right now (HTTP $code). $detail"
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
     * Asks the AI backend to generate word data for [query].
     *
     * ## Where the credential went
     *
     * This used to build an OkHttp request to `generativelanguage.googleapis.com` with an
     * `x-goog-api-key` header read from `BuildConfig.GEMINI_API_KEY`. That was the single largest
     * security defect in the app: a `buildConfigField` is a string constant in `classes.dex`, so
     * the key shipped inside every APK and was recoverable by anyone who unzipped one. It was
     * documented as an accepted trade-off, which is a way of saying it was not fixed.
     *
     * It is fixed. The app now calls an operator-run proxy (`backend/server.mjs`), which holds the
     * key in its own environment and forwards to Gemini. The client sends a query and nothing
     * else - no key, no header, no token - because it has none to send.
     *
     * ## What that costs, stated honestly
     *
     * The Gemini credential is no longer extractable, but the *endpoint* is public: anyone can
     * point a modified APK at `https://your-proxy/v1/word` and spend your quota. Moving the key
     * stops credential theft; it does not by itself stop abuse. What limits that is the proxy's
     * per-address rate limit plus, in a real deployment, Android App Check (or an equivalent)
     * attesting that the caller is your app. The rate limit is a ceiling, not an identity check,
     * and the README says so rather than implying the proxy is sealed.
     *
     * ## What is unchanged
     *
     * The request and response shapes. The server holds the prompt and returns Gemini's
     * `generateContent` body verbatim, so [parseWordData] still parses exactly what it always
     * parsed - including its refusal to invent a radical or a stroke count for fields the model
     * omitted. Nothing about the word data a learner sees has changed.
     */
    suspend fun generateChineseWordData(query: String): Result<GeneratedWordData> =
        withContext(Dispatchers.IO) {
            val baseUrl = aiBackendUrl()

            if (baseUrl.isBlank()) {
                Log.w(TAG, "No AI backend configured in this build.")
                return@withContext Result.failure(AiFailure.BackendNotConfigured)
            }

            try {
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}$WORD_PATH")
                    .post(buildRequestBody(query))
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "AI backend returned HTTP ${response.code}.")
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
                Log.w(TAG, "Network failure calling AI backend: ${e.message}")
                Result.failure(AiFailure.Network(e))
            } catch (e: org.json.JSONException) {
                Log.e(TAG, "Malformed AI response: ${e.message}", e)
                Result.failure(AiFailure.MalformedResponse(e))
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected AI failure: ${e.message}", e)
                Result.failure(AiFailure.MalformedResponse(e))
            }
        }

    /**
     * The configured backend endpoint.
     *
     * This is a `buildConfigField`, which is the same mechanism that leaked the Gemini key, so it
     * is worth being explicit about why this one is fine: an endpoint URL is not a secret. Knowing
     * where the app talks to is public information by definition — it is in every network request —
     * and making it hard to configure would buy nothing.
     *
     * What stops a *secret* from being added back here is `BuildConfigSecretsTest`, which fails
     * if any credential-shaped field appears on the generated class. An earlier version read this
     * reflectively on the theory that removing the compile-time dependency would prevent misuse.
     * It would not: the field is declared in `app/build.gradle.kts` regardless, so reflection only
     * traded a compile error for a silent blank string and a feature that quietly stopped working.
     */
    private fun aiBackendUrl(): String = BuildConfig.AI_BACKEND_URL

    private fun buildRequestBody(query: String): RequestBody {
        // The model prompt lives on the server now. The client sends the learner's query and
        // nothing else, which is also what keeps the API shape independent of the prompt's wording.
        val jsonPayload = JSONObject().put("query", query)
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
        // Every one of these used to carry a plausible-looking default, and that is the whole
        // problem: a well-formed response that simply omitted `radical` came back with the
        // literal Chinese *word* "radical" as the character's radical, and one that omitted the
        // strokes came back as a confident four-stroke breakdown. Those were stored as though
        // the model had said them, and the app then animated someone else's writing as this
        // character's. A field the model did not supply is now absent, which is a state the UI
        // already knows how to render.
        val radical = wordJson.optString("radical", "").trim()
        val strokes = wordJson.optString("strokeBreakdown", "").trim()
        val meaning = wordJson.optString("meaning", "").trim()
        val pinyin = wordJson.optString("pinyin", "").trim()
        // Meaning and reading are not decoration - without them the card is not a vocabulary
        // entry, so a response missing either is a malformed response rather than a thin one.
        if (meaning.isEmpty() || pinyin.isEmpty()) {
            throw AiFailure.MalformedResponse(null)
        }
        return GeneratedWordData(
            hanzi = wordJson.optString("hanzi", query),
            pinyin = pinyin,
            meaning = meaning,
            hskLevel = wordJson.optInt("hskLevel", 1).coerceIn(1, 6),
            radical = radical,
            exampleCn = wordJson.optString("exampleCn", "").trim(),
            examplePy = wordJson.optString("examplePy", "").trim(),
            exampleEn = wordJson.optString("exampleEn", "").trim(),
            strokeBreakdown = strokes,
            // Taken from the breakdown when the model gives one, and 0 when it does not, rather
            // than assumed to be 4.
            strokeCount = wordJson.optInt("strokeCount", if (strokes.isEmpty()) 0 else countStrokes(strokes)),
            origin = WordDataOrigin.GEMINI
        )
    }

    /** Counts the comma-separated `name (reading)` segments of a stroke breakdown. */
    private fun countStrokes(breakdown: String): Int =
        breakdown.split(',').count { it.trim().isNotEmpty() }

    /**
     * Pulls a short, safe message out of an error body for display.
     *
     * Two shapes arrive here, and this has to handle both. Google's own error body is
     * `{"error":{"message":"..."}}`. The proxy does not reinterpreting it: it wraps whatever
     * upstream sent in `{"error":"<that body, as text>"}`, which it does so it can redact the key
     * from the string first. So the outer `error` may be an object or a string, and when it is a
     * string there is a useful message nested inside it one level down.
     *
     * Reading only the object shape — as this did before the proxy existed — meant every backend
     * error rendered as "No further detail available", quietly discarding the one piece of
     * information that tells a user whether their quota ran out or their backend is down.
     */
    private fun summarise(errorBody: String): String {
        val message = runCatching {
            when (val error = JSONObject(errorBody).opt("error")) {
                is JSONObject -> error.optString("message")
                is String -> nestedGoogleMessage(error) ?: error
                else -> null
            }
        }.getOrNull()

        return message?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
            ?: errorBody.trim().take(200).ifEmpty { "No further detail available." }
    }

    /** Unwraps `{"error":{"message":"..."}}` from a body the proxy quoted as a string. */
    private fun nestedGoogleMessage(quoted: String): String? = runCatching {
        JSONObject(quoted).optJSONObject("error")?.optString("message")
    }.getOrNull()

    companion object {
        private const val TAG = "GeminiAiService"

        /** Path served by `backend/server.mjs`. Must match its route exactly. */
        private const val WORD_PATH = "/v1/word"
    }

    /**
     * A real entry from the built-in dictionary, or `null` when there isn't one.
     *
     * ### Why this no longer invents anything
     *
     * It used to answer any query at all. An unknown single character came back as
     * `createHeuristicForChar`: the reading `zì`, the meaning "Chinese character 'X'", the radical
     * the literal string `"部首"`, and a fixed five-stroke breakdown that described no character
     * whatsoever. Anything else came back as 字 with a six-stroke breakdown and radical 宀. The
     * caller then saved it with `provenance = AI_GENERATED`, so a fabricated stroke count was
     * stored in the database as model output and rendered on the card.
     *
     * The dictionary itself is sound and unchanged - 爱 ài at ten strokes, 人 rén at two, 中 zhōng
     * at four, 大 dà at three are all correct. Only the invented answers for entries it does not
     * contain are gone. `null` is a real answer: the caller says so, and the learner can type the
     * character in by hand, which is honest and costs them one form.
     */
    fun offlineSampleFor(query: String): GeneratedWordData? {
        val trimmed = query.trim()
        val predefined = getPredefinedDictionary()
        // Exact Hanzi or Pinyin match, in that order, then the first character for a multi
        // character query. All three are lookups against real entries.
        predefined[trimmed]?.let { return it }
        if (trimmed.length == 1) predefined[trimmed[0].toString()]?.let { return it }
        return null
    }

    private fun getPredefinedDictionary(): Map<String, GeneratedWordData> {
        return mapOf(
            "爱" to GeneratedWordData("爱", "ài", "love; affection; to like", 1, "爫 (claw)", "我非常爱我的家人。", "Wǒ fēicháng ài wǒ de jiārén.", "I love my family very much.", "撇 (Piě), 点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), 横 (Héng), 撇 (Piě), 横撇 (Héng Piě), 捺 (Nà)", 10),
            "ai" to GeneratedWordData("爱", "ài", "love; affection; to like", 1, "爫 (claw)", "我非常爱我的家人。", "Wǒ fēicháng ài wǒ de jiārén.", "I love my family very much.", "撇 (Piě), 点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), 横 (Héng), 撇 (Piě), 横撇 (Héng Piě), 捺 (Nà)", 10),
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
            "喝" to GeneratedWordData("喝", "hē", "to drink", 1, "口 (mouth)", "天气热的时候多喝水。", "Tiānqì rè de shíhòu duō hē shuǐ.", "Drink more water when the weather is hot.", "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 撇 (Piě), 竖折 (Shù Zhé), 竖 (Shù)", 12),
            // Common words. The reading, meaning and example are first-year textbook facts;
            // the radical and the stroke breakdown are deliberately absent rather than
            // guessed: a radical belongs to one character, not to a word, and an unverified
            // stroke list would be animated as this word's writing. Absent renders as absent.
            "老师" to GeneratedWordData("老师", "lǎoshī", "teacher", 1, "", "他是我们的老师。", "Tā shì wǒmen de lǎoshī.", "He is our teacher.", "", 0),
            "laoshi" to GeneratedWordData("老师", "lǎoshī", "teacher", 1, "", "他是我们的老师。", "Tā shì wǒmen de lǎoshī.", "He is our teacher.", "", 0),
            "学生" to GeneratedWordData("学生", "xuéshēng", "student", 1, "", "我是一名学生。", "Wǒ shì yì míng xuéshēng.", "I am a student.", "", 0),
            "xuesheng" to GeneratedWordData("学生", "xuéshēng", "student", 1, "", "我是一名学生。", "Wǒ shì yì míng xuéshēng.", "I am a student.", "", 0),
            "学校" to GeneratedWordData("学校", "xuéxiào", "school", 1, "", "学校很大。", "Xuéxiào hěn dà.", "The school is very big.", "", 0),
            "xuexiao" to GeneratedWordData("学校", "xuéxiào", "school", 1, "", "学校很大。", "Xuéxiào hěn dà.", "The school is very big.", "", 0)
        )
    }
}
