package app.fjj.stun.repo

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles")
    fun getAll(): LiveData<List<Profile>>

    @Query("SELECT * FROM profiles")
    fun getAllStatic(): List<Profile>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getById(id: String): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(profile: Profile)

    @Update
    fun update(profile: Profile)

    @Delete
    fun delete(profile: Profile)

    @Query("DELETE FROM profiles")
    fun deleteAll()

    @Query("UPDATE profiles SET totalTx = :tx, totalRx = :rx WHERE id = :id")
    fun updateTrafficStats(id: String, tx: Long, rx: Long)
}
