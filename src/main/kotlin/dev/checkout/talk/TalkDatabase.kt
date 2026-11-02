package dev.checkout.talk

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity
data class OrderRow(
    @PrimaryKey val id: Long,
)

@Dao
interface OrderRowDao {

    @Insert
    suspend fun insert(row: OrderRow)

    @Query("SELECT COUNT(*) FROM OrderRow")
    suspend fun count(): Int
}

@Database(
    entities = [OrderRow::class, OrderEntity::class, OrderProductEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TalkDatabase : RoomDatabase() {
    abstract fun orderDao(): OrderRowDao

    /** The two tables bug 2 is told on. */
    abstract fun orders(): OrderDao
}
