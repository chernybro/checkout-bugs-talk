package dev.checkout.talk

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Could a migration just turn foreign keys on itself?"
 *
 * No. SQLite documents `PRAGMA foreign_keys` as a no-op inside a transaction, and Room
 * runs every migration inside `BEGIN EXCLUSIVE TRANSACTION`. So the statement is accepted,
 * silently does nothing, and the cascade still does not fire.
 *
 * This is the slide that turns "Room's fault" into "SQLite's rule".
 */
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class PragmaInsideTransactionTest : SQLiteDatabaseTest by SQLiteDatabaseTest(
    create = {
        writeableDatabase(version = 1) {
            onCreate {
                execSQL("CREATE TABLE parent (id INTEGER PRIMARY KEY NOT NULL)")
                execSQL(
                    "CREATE TABLE child (" +
                        "id INTEGER PRIMARY KEY NOT NULL, " +
                        "parentId INTEGER NOT NULL, " +
                        "FOREIGN KEY(parentId) REFERENCES parent(id) ON DELETE CASCADE" +
                        ")"
                )
            }
        }
    }
) {

    /** The pragma is accepted and ignored. No error, no effect. */
    @Test
    fun `PRAGMA foreign_keys is a no-op inside a transaction`() {
        db.execSQL("PRAGMA foreign_keys = OFF")

        db.inExclusiveTransaction {
            execSQL("PRAGMA foreign_keys = ON")
            // accepted, and ignored
            assertEquals(
                0,
                query("PRAGMA foreign_keys").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getInt(0)
                }
            )
        }
    }

    /** Which is why writing it in a migration changes nothing about the cascade. */
    @Test
    fun `turning the pragma on inside a migration does not make the cascade fire`() {
        db.seedParentWithChild()
        db.execSQL("PRAGMA foreign_keys = OFF")

        db.inExclusiveTransaction {
            execSQL("PRAGMA foreign_keys = ON")
            execSQL("DELETE FROM parent")
        }

        assertEquals(0, db.countOf("parent"))
        assertEquals(1, db.countOf("child")) // the orphan is still here
    }

    /** Outside a transaction the very same statement works. */
    @Test
    fun `the same pragma works outside a transaction`() {
        db.seedParentWithChild()
        db.execSQL("PRAGMA foreign_keys = OFF")

        db.execSQL("PRAGMA foreign_keys = ON")
        assertEquals(1, db.foreignKeysFlag())
        db.execSQL("DELETE FROM parent")

        assertEquals(0, db.countOf("parent"))
        assertEquals(0, db.countOf("child")) // cascade fired
    }

    private fun SupportSQLiteDatabase.seedParentWithChild() {
        execSQL("INSERT INTO parent (id) VALUES (1)")
        execSQL("INSERT INTO child (id, parentId) VALUES (1, 1)")
    }

    private fun SupportSQLiteDatabase.foreignKeysFlag(): Int =
        query("PRAGMA foreign_keys").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.countOf(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** Exactly how Room brackets a migration. */
    private fun SupportSQLiteDatabase.inExclusiveTransaction(block: SupportSQLiteDatabase.() -> Unit) {
        execSQL("BEGIN EXCLUSIVE TRANSACTION")
        try {
            block()
            execSQL("END TRANSACTION")
        } catch (e: Throwable) {
            execSQL("ROLLBACK TRANSACTION")
            throw e
        }
    }
}
