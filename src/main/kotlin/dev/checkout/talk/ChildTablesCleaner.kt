package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase

/** Deletes descendants first. Intended for the small, acyclic schemas in this demo. */
fun SupportSQLiteDatabase.findAndClearTableAndChildren(parentTable: String) {
    val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    val children = tables.filter { table ->
        query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            var referencesParent = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("table")) == parentTable) {
                    referencesParent = true
                }
            }
            referencesParent
        }
    }
    children.forEach { findAndClearTableAndChildren(it) }
    execSQL("DELETE FROM `$parentTable`")
}
