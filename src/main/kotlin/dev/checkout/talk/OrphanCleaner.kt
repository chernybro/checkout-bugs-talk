package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Demo repair: ask SQLite which rows have missing parents, then delete those rows.
 * This version omits production concerns such as reporting and transaction handling.
 */
fun SupportSQLiteDatabase.clearRowsWithoutParent() {
    readForeignKeyViolations().forEach { (table, rowId) ->
        execSQL("DELETE FROM $table WHERE rowid = $rowId")
    }
}

/**
 * Every row `foreign_key_check` reports as violating, as table name to rowid.
 *
 * Read fully before deleting anything: deleting while the pragma cursor is still open
 * is asking for trouble.
 */
private fun SupportSQLiteDatabase.readForeignKeyViolations(): List<Pair<String, Long>> =
    query("PRAGMA foreign_key_check").use { cursor ->
        val table = cursor.getColumnIndexOrThrow("table")
        val rowId = cursor.getColumnIndexOrThrow("rowid")
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(table) to cursor.getLong(rowId))
            }
        }
    }
