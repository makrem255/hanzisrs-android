package com.example.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.CharacterStrokeEntity
import com.example.data.srs.StrokeNameParser

/**
 * The v2 to v3 step: give strokes a row of their own, and record compositional structure.
 *
 * v2 kept the stroke breakdown on `vocabulary.strokeJson`, a comma-separated list of names. That
 * was a layering inversion rather than a storage problem. A stroke belongs to a *character*: 长 is
 * written identically whether it is read `cháng` or `zhǎng`, so a per-vocabulary blob duplicates
 * the same strokes for every reading and lets the copies disagree - and a writing-practice
 * system cannot score "stroke 4" at all, because there is no row for stroke 4. The list is the
 * same shape, so nothing is lost by moving it; it just has a proper parent.
 *
 * `characters.structure` is added here rather than in its own migration because it is the same
 * decision: it is a property of the written form, and the two columns are the two halves of what
 * the app knows about how a character is built.
 *
 * Two rules govern the code below, as in [MIGRATION_1_2].
 *
 * **Nothing is discarded, and nothing is invented.** The stroke names migrate across verbatim. A
 * character whose `strokeJson` is empty stays with no rows, and its `strokeCount` stays at 0 -
 * both are the documented "unknown", and neither is replaced with a guess.
 *
 * **The DDL is not written by hand.** Room validates a migrated database against the entity
 * declarations and refuses to open it on any difference, so the statements below are the
 * `createSql` values generated into `app/schemas/com.example.data.db.AppDatabase/3.json`.
 * `DatabaseMigrationTest` fails loudly if the two drift apart.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. The new column. SQLite will not add a NOT NULL column to a populated table without a
        //    DEFAULT, and Room validates the migrated schema against the entity declarations -
        //    so the default the migration applies has to be the default the entity declares. It
        //    is: `''` is this column's documented unknown, and `CharacterEntity.structure`
        //    carries `defaultValue = "''"` so a fresh install and an upgraded one agree.
        db.execSQL(ADD_CHARACTER_STRUCTURE)

        // 2. The strokes table. It only references `characters.id`, which exists before and after
        //    the column is added, so the two steps are independent and either order works.
        db.execSQL(CREATE_CHARACTER_STROKES)

        // 3. Data, after both halves of its destination exist. Nothing is copied into the new
        //    column: the absence of a value *is* the unknown, and every existing row has it.
        backfillStrokesFromVocabulary(db)
    }
}

/**
 * Moves each character's stroke breakdown out of `vocabulary` and into `character_strokes`.
 *
 * One character can be reached by several vocabulary rows, and v2 let each of them carry its own
 * blob. They are not merged, because merging would mean deciding which copy is right - and
 * picking the longest, or concatenating them, would invent a sequence nobody authored. The
 * lowest `vocabulary.id` for the character wins, which is deterministic and is the row the
 * migration itself created first.
 *
 * Every distinct `strokeJson` is parsed once and the resulting rows are re-used for every
 * character that shares it, which is the common case: a handful of stroke profiles between
 * thousands of characters.
 */
private fun backfillStrokesFromVocabulary(db: SupportSQLiteDatabase) {
    // `GROUP BY characterId` with `MIN(id)` picks the winning row per character in one pass. The
    // statement is grouped rather than looping in Kotlin so the whole read is a single snapshot;
    // a migration runs inside Room's transaction, but taking the read in one statement keeps the
    // parse from being interleaved with anything else.
    db.query(
        "SELECT `characterId`, `strokeJson` FROM `vocabulary` " +
            "WHERE `strokeJson` IS NOT NULL AND TRIM(`strokeJson`) != '' " +
            "AND `id` IN (" +
            "  SELECT MIN(`id`) FROM `vocabulary` " +
            "  WHERE `strokeJson` IS NOT NULL AND TRIM(`strokeJson`) != '' " +
            "  GROUP BY `characterId`" +
            ")"
    ).use { cursor ->
        // The cache is keyed on the raw blob, because the point is to parse each distinct
        // breakdown once rather than once per character that happens to share it.
        val parsedByBlob = mutableMapOf<String, List<CharacterStrokeEntity>>()
        val characterIdIndex = cursor.getColumnIndexOrThrow("characterId")
        val blobIndex = cursor.getColumnIndexOrThrow("strokeJson")

        while (cursor.moveToNext()) {
            val characterId = cursor.getLong(characterIdIndex)
            val blob = cursor.getString(blobIndex) ?: continue
            val strokes = parsedByBlob.getOrPut(blob) { StrokeNameParser.parse(blob) }
            if (strokes.isEmpty()) continue

            for (stroke in strokes) {
                // The entity carries a default 0 for the surrogate key it does not have; the
                // primary key is (characterId, position) and Room inserts the rest.
                db.execSQL(
                    INSERT_STROKE,
                    arrayOf<Any>(characterId, stroke.position, stroke.nameCn, stroke.namePinyin)
                )
            }
        }
    }
}

/** Copied from the generated `createSql`. The composite key is what makes the order unique. */
private const val CREATE_CHARACTER_STROKES =
    "CREATE TABLE IF NOT EXISTS `character_strokes` " +
        "(`characterId` INTEGER NOT NULL, " +
        "`position` INTEGER NOT NULL, " +
        "`nameCn` TEXT NOT NULL, " +
        "`namePinyin` TEXT NOT NULL, " +
        "PRIMARY KEY(`characterId`, `position`), " +
        "FOREIGN KEY(`characterId`) REFERENCES `characters`(`id`) " +
        "ON UPDATE NO ACTION ON DELETE CASCADE )"

/**
 * The rebuilt `characters`, copied from the generated `createSql` plus the added column.
 *
 * The default matches `CharacterEntity.structure`'s `defaultValue`. Room compares the default
 * `PRAGMA table_info` reports against the one the entity declares, so a migration that invents a
 * default the entity does not have is a validation failure, not a silent difference.
 */
private const val ADD_CHARACTER_STRUCTURE =
    "ALTER TABLE `characters` ADD COLUMN `structure` TEXT NOT NULL DEFAULT ''"

private const val INSERT_STROKE =
    "INSERT OR REPLACE INTO `character_strokes` " +
        "(`characterId`, `position`, `nameCn`, `namePinyin`) VALUES (?, ?, ?, ?)"
