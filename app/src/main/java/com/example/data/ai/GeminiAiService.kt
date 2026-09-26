package com.example.data.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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

class GeminiAiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateChineseWordData(query: String): Result<GeneratedWordData> = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d("GeminiAiService", "No Gemini key configured; using the local word-data fallback.")
            return@withContext Result.success(generateLocalFallback(query))
        }

        try {
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

            val userPrompt = "Generate complete Chinese SRS learning data for the input: $query"

            val jsonPayload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "$systemPrompt\n\nUser Input: $userPrompt"))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("responseMimeType", "application/json")
                })
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(requestBody)
                .build()

            val responseBody = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GeminiAiService", "Gemini API returned ${response.code}; using the local fallback.")
                    return@withContext Result.success(generateLocalFallback(query))
                }
                response.body?.string().orEmpty()
            }
            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            val content = candidates?.optJSONObject(0)?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text") ?: ""

            val cleanedJson = text.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val wordJson = JSONObject(cleanedJson)
            val generated = GeneratedWordData(
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

            Result.success(generated)
        } catch (e: Exception) {
            Log.e("GeminiAiService", "Failed to parse Gemini response: ${e.message}", e)
            Result.success(generateLocalFallback(query))
        }
    }

    private fun generateLocalFallback(query: String): GeneratedWordData {
        val trimmed = query.trim()
        val predefined = getPredefinedDictionary()

        // Exact Hanzi or Pinyin match
        predefined[trimmed]?.let { return it }

        // Character level lookup
        if (trimmed.length == 1) {
            val char = trimmed[0]
            val fallback = createHeuristicForChar(char)
            return fallback
        }

        // Generic fallback for any user input
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
