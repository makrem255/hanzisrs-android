package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import com.example.data.db.migrations.MIGRATION_1_2
import com.example.data.db.seed.ReferenceData
import com.example.data.db.seed.StarterContent
import com.example.data.model.AchievementEntity
import com.example.data.model.CharacterEntity
import com.example.data.model.DailyStatEntity
import com.example.data.model.ExampleSentenceEntity
import com.example.data.model.LearningItemEntity
import com.example.data.model.LearningLevelEntity
import com.example.data.model.LearningSessionEntity
import com.example.data.model.PinyinSyllableEntity
import com.example.data.model.ReviewLogEntity
import com.example.data.model.SessionCardEntity
import com.example.data.model.SrsStateEntity
import com.example.data.model.StreakEntity
import com.example.data.model.UserAchievementEntity
import com.example.data.model.UserEntity
import com.example.data.model.UserPreferenceEntity
import com.example.data.model.UserProfileEntity
import com.example.data.model.UserVocabularyEntity
import com.example.data.model.VocabularyEntity
import com.example.util.PasswordHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The application's local store.
 *
 * The schema is split into two tiers that are never mixed:
 *
 *  - **Content** ([LearningLevelEntity], [CharacterEntity], [PinyinSyllableEntity],
 *    [VocabularyEntity], [ExampleSentenceEntity], [LearningItemEntity], [AchievementEntity]) is
 *    shared by every learner on the device and holds no user id at all.
 *  - **Learner state** ([UserProfileEntity], [UserPreferenceEntity], [UserVocabularyEntity],
 *    [SrsStateEntity], [ReviewLogEntity], [LearningSessionEntity], [SessionCardEntity],
 *    [DailyStatEntity], [StreakEntity], [UserAchievementEntity]) is reachable only by joining
 *    through [UserEntity], and every table in it carries a `userId`.
 *
 * That split is what lets two learners study 好 while storing it once, and it is enforced by
 * the schema rather than by convention: content tables have no `userId` column to leak through.
 *
 * Foreign keys are enabled explicitly. SQLite leaves them off per connection by default, and
 * Room only turns them on for the connections it opens itself, so a cascade that silently does
 * nothing is otherwise possible.
 */
@Database(
    entities = [
        // Content tier.
        LearningLevelEntity::class,
        CharacterEntity::class,
        PinyinSyllableEntity::class,
        VocabularyEntity::class,
        ExampleSentenceEntity::class,
        LearningItemEntity::class,
        AchievementEntity::class,
        // Learner tier.
        UserEntity::class,
        UserProfileEntity::class,
        UserPreferenceEntity::class,
        UserVocabularyEntity::class,
        SrsStateEntity::class,
        ReviewLogEntity::class,
        LearningSessionEntity::class,
        SessionCardEntity::class,
        DailyStatEntity::class,
        StreakEntity::class,
        UserAchievementEntity::class
    ],
    // Spelled out rather than as `AppDatabase.VERSION`: an annotation argument must be a
    // compile-time constant, and a constant read off the class it annotates is a forward
    // reference. `SchemaRelationshipTest` asserts the two never drift apart.
    version = 2,
    exportSchema = true,
    autoMigrations = []
)
abstract class AppDatabase : RoomDatabase() {

    // Content.
    abstract fun learningLevelDao(): LearningLevelDao
    abstract fun characterDao(): CharacterDao
    abstract fun pinyinDao(): PinyinDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun exampleSentenceDao(): ExampleSentenceDao
    abstract fun learningItemDao(): LearningItemDao
    abstract fun achievementDao(): AchievementDao

    // Learners.
    abstract fun userDao(): UserDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun userVocabularyDao(): UserVocabularyDao
    abstract fun srsStateDao(): SrsStateDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun learningSessionDao(): LearningSessionDao
    abstract fun sessionCardDao(): SessionCardDao
    abstract fun dailyStatDao(): DailyStatDao
    abstract fun streakDao(): StreakDao
    abstract fun userAchievementDao(): UserAchievementDao

    // Reporting. Reads that answer "how am I doing?" and own no scheduling state.
    abstract fun dashboardDao(): DashboardDao

    companion object {
        const val VERSION = 2
        const val DATABASE_NAME = "hanzi_srs_database"

        /**
         * The v1 to v2 step. The `words` and `srs_reviews` tables are decomposed into the
         * content and learner tiers; no learner row is discarded.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    // There is no destructive fallback on purpose. A missing migration must
                    // crash loudly on first launch so it is caught in testing, rather than
                    // silently deleting a learner's word collection in the field.
                    .addCallback(SeedCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /** Closes the singleton. Tests use this to isolate one database per case. */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }

        /**
         * Fills in the rows a schema cannot imply.
         *
         * This replaces the old hard-coded seed, which inserted a per-user copy of six words
         * with invented intervals, ease factors and due dates. Nothing is invented now: the
         * reference catalogue (levels, badges) and the starter words are real content rows, and
         * every card starts genuinely `NEW`.
         */
        private class SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        seedReferenceData(database)
                        StarterContent.install(database)
                    }
                }
            }
        }

        /**
         * Inserts the level and badge catalogues, then guarantees the starter pack exists.
         *
         * Safe to call on every launch. The starter content is not attached to a learner: it is
         * shared content that any learner can enrol from, which is what lets a second learner
         * on the same device start from the same words without a second copy.
         */
        suspend fun seedReferenceData(database: AppDatabase) {
            database.withTransaction {
                database.learningLevelDao().upsertAll(ReferenceData.learningLevels)
                database.achievementDao().insertAllIfAbsent(ReferenceData.achievements)
            }
        }

        /**
         * The password for the first-run demo profile, hashed once at creation time.
         *
         * Kept as a real PBKDF2 hash rather than a magic string compared at login: an earlier
         * build had a hard-coded `demo_hash_123` bypass in the login path, which was an
         * authentication backdoor on every account, not just the demo one.
         */
        const val DEMO_IDENTIFIER = "learner@hanzisrs.com"
        const val DEMO_DISPLAY_NAME = "Alex Learner"

        fun demoPasswordHash(): String = PasswordHasher.createHash("learnhanzi")
    }
}
