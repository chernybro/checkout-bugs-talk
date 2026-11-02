package dev.checkout.talk

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Bug 2, slides 15-18. The parent.
 *
 * Deliberately two columns and no more: the story is about the edge between the tables,
 * not about what an order holds.
 */
@Entity
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)

/**
 * The child. The cascade here is correct — and that is the point of the story: a correct
 * `ON DELETE CASCADE` does nothing while the `foreign_keys` pragma is off, and Room turns
 * that pragma off for the duration of every migration.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("orderId")],
)
data class OrderProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
)

@Dao
interface OrderDao {

    /** Returns the id SQLite assigned — nobody picks order ids by hand in production. */
    @Insert
    suspend fun insert(order: OrderEntity): Long

    @Insert
    suspend fun insert(product: OrderProductEntity): Long

    /** The production path: deleting through Room, where the pragma is on. */
    @Delete
    suspend fun delete(order: OrderEntity)

    @Query("SELECT COUNT(*) FROM OrderEntity")
    suspend fun orderCount(): Int

    @Query("SELECT COUNT(*) FROM OrderProductEntity")
    suspend fun productCount(): Int
}
