package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.SrsReviewEntity
import com.example.data.model.UserEntity
import com.example.data.model.WordEntity
import com.example.util.PasswordHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        UserEntity::class,
        WordEntity::class,
        SrsReviewEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun wordDao(): WordDao
    abstract fun srsReviewDao(): SrsReviewDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hanzi_srs_database"
                )
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        seedInitialData(database)
                    }
                }
            }
        }

        suspend fun seedInitialData(database: AppDatabase) {
            val userDao = database.userDao()
            val wordDao = database.wordDao()
            val srsDao = database.srsReviewDao()

            // Create default demo/guest user
            val demoUser = UserEntity(
                id = 1,
                identifier = "learner@hanzisrs.com",
                authType = "EMAIL",
                passwordHash = PasswordHasher.createHash("learnhanzi"),
                displayName = "Alex Learner",
                token = "",
                createdAt = System.currentTimeMillis()
            )
            userDao.insertUser(demoUser)

            val starterWords = listOf(
                InitialWord(
                    hanzi = "学",
                    pinyin = "xué",
                    meaning = "to study; to learn",
                    hsk = 1,
                    radical = "子 (child)",
                    exampleCn = "我每天在学校学习中文。",
                    examplePy = "Wǒ měitiān zài xuéxiào xuéxí zhōngwén.",
                    exampleEn = "I study Chinese at school every day.",
                    strokes = "点 (Diǎn), 点 (Diǎn), 撇 (Piě), 点 (Diǎn), 横钩 (Héng Gōu), 弯钩 (Wān Gōu), 横 (Héng)"
                ),
                InitialWord(
                    hanzi = "好",
                    pinyin = "hǎo",
                    meaning = "good; well; fine",
                    hsk = 1,
                    radical = "女 (woman)",
                    exampleCn = "今天天气很好，我们去公园吧。",
                    examplePy = "Jīntiān tiānqì hěn hǎo, wǒmen qù gōngyuán ba.",
                    exampleEn = "The weather is very good today, let's go to the park.",
                    strokes = "撇点 (Piě Diǎn), 撇 (Piě), 提 (Tí), 横撇 (Héng Piě), 弯钩 (Wān Gōu), 横 (Héng)"
                ),
                InitialWord(
                    hanzi = "你",
                    pinyin = "nǐ",
                    meaning = "you (singular)",
                    hsk = 1,
                    radical = "亻 (person)",
                    exampleCn = "你好！很高兴认识你。",
                    examplePy = "Nǐ hǎo! Hěn gāoxìng rènshí nǐ.",
                    exampleEn = "Hello! Very nice to meet you.",
                    strokes = "撇 (Piě), 竖 (Shù), 撇 (Piě), 横钩 (Héng Gōu), 竖钩 (Shù Gōu), 撇 (Piě), 点 (Diǎn)"
                ),
                InitialWord(
                    hanzi = "朋",
                    pinyin = "péng",
                    meaning = "friend; companion",
                    hsk = 1,
                    radical = "月 (moon)",
                    exampleCn = "他是我的好朋友。",
                    examplePy = "Tā shì wǒ de hǎo péngyǒu.",
                    exampleEn = "He is my good friend.",
                    strokes = "撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng), 撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng)"
                ),
                InitialWord(
                    hanzi = "茶",
                    pinyin = "chá",
                    meaning = "tea",
                    hsk = 1,
                    radical = "艹 (grass)",
                    exampleCn = "你想喝绿茶还是红茶？",
                    examplePy = "Nǐ xiǎng hē lǜchá háishì hóngchá?",
                    exampleEn = "Would you like to drink green tea or black tea?",
                    strokes = "横 (Héng), 竖 (Shù), 竖 (Shù), 撇 (Piě), 捺 (Nà), 横 (Héng), 撇 (Piě), 竖钩 (Shù Gōu), 撇 (Piě), 点 (Diǎn)"
                ),
                InitialWord(
                    hanzi = "明",
                    pinyin = "míng",
                    meaning = "bright; clear; tomorrow",
                    hsk = 1,
                    radical = "日 (sun)",
                    exampleCn = "明天我们要参加中文考试。",
                    examplePy = "Míngtiān wǒmen yào cānjiā zhōngwén kǎoshì.",
                    exampleEn = "Tomorrow we will take a Chinese exam.",
                    strokes = "竖 (Shù), 横折 (Héng Zhé), 横 (Héng), 横 (Héng), 撇 (Piě), 横折钩 (Héng Zhé Gōu), 横 (Héng), 横 (Héng)"
                )
            )

            val now = System.currentTimeMillis()
            starterWords.forEachIndexed { index, item ->
                val wordId = wordDao.insertWord(
                    WordEntity(
                        userId = 1,
                        hanzi = item.hanzi,
                        pinyin = item.pinyin,
                        meaning = item.meaning,
                        hskLevel = item.hsk,
                        radical = item.radical,
                        exampleCn = item.exampleCn,
                        examplePy = item.examplePy,
                        exampleEn = item.exampleEn,
                        strokeJson = item.strokes,
                        tags = "HSK${item.hsk},Essential"
                    )
                )

                // Stagger reviews so some are due immediately, some scheduled
                val isDue = index < 4
                val dueMillis = if (isDue) now - (index * 3600000L) else now + ((index - 3) * 86400000L)
                val state = if (isDue) "LEARNING" else "REVIEW"

                srsDao.insertOrUpdateReview(
                    SrsReviewEntity(
                        wordId = wordId,
                        userId = 1,
                        intervalDays = if (isDue) 1 else 3,
                        repetitions = if (isDue) 1 else 2,
                        easeFactor = 2.5,
                        dueDateMillis = dueMillis,
                        lastReviewMillis = now - 86400000L,
                        state = state,
                        lastRating = 3,
                        totalReviews = 1
                    )
                )
            }
        }
    }
}

private data class InitialWord(
    val hanzi: String,
    val pinyin: String,
    val meaning: String,
    val hsk: Int,
    val radical: String,
    val exampleCn: String,
    val examplePy: String,
    val exampleEn: String,
    val strokes: String
)
