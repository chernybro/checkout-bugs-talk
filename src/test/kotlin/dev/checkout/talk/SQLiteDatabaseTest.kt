package dev.checkout.talk

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before

interface SQLiteDatabaseTest {
    val db: SupportSQLiteDatabase
    @Before fun create()
    @After fun close() = db.close()
}

fun SQLiteDatabaseTest(create: Context.() -> SupportSQLiteDatabase): SQLiteDatabaseTest =
    object : SQLiteDatabaseTest {
        private var database: SupportSQLiteDatabase? = null
        override val db: SupportSQLiteDatabase get() = checkNotNull(database)
        override fun create() {
            database = InstrumentationRegistry.getInstrumentation().context.create()
        }
    }

class SchemaBuilder {
    internal var createSchema: SupportSQLiteDatabase.() -> Unit = {}
    fun onCreate(block: SupportSQLiteDatabase.() -> Unit) { createSchema = block }
}

fun Context.writeableDatabase(version: Int, build: SchemaBuilder.() -> Unit): SupportSQLiteDatabase {
    val schema = SchemaBuilder().apply(build)
    val configuration = SupportSQLiteOpenHelper.Configuration.builder(this)
        .callback(object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) { schema.createSchema(db) }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("The test fixture does not perform version upgrades")
            }
        }).build()
    return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
}
