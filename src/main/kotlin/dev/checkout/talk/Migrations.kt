package dev.checkout.talk

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Slide 17. The shape that produced the phantom items: Room disables the foreign_keys
 * pragma for the duration of a migration, so ON DELETE CASCADE does not fire here and
 * OrderProductEntity keeps its rows.
 */
internal class BrokenOrderQueueMigration : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM OrderEntity") // children survive: FKs are off
    }
}

/** Slide 18. The same migration, using the helper that walks the children itself. */
internal class FixedOrderQueueMigration : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.findAndClearTableAndChildren("OrderEntity")
    }
}
