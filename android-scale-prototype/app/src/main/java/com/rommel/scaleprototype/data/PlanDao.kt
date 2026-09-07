package com.rommel.scaleprototype.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlanItem>)

    @Query("SELECT * FROM plan_item WHERE id = :id")
    suspend fun getItem(id: String): PlanItem?

    /** El plan de un día, en el orden en que se armó, sin las filas marcadas para borrar. */
    @Query("SELECT * FROM plan_item WHERE fecha = :fecha AND borrado = 0 ORDER BY orden ASC, createdAtEpochMillis ASC")
    fun getDelDiaFlow(fecha: String): Flow<List<PlanItem>>

    @Query("SELECT * FROM plan_item WHERE fecha = :fecha AND borrado = 0 ORDER BY orden ASC, createdAtEpochMillis ASC")
    suspend fun getDelDia(fecha: String): List<PlanItem>

    @Query("SELECT COALESCE(MAX(orden), 0) FROM plan_item WHERE fecha = :fecha")
    suspend fun maxOrden(fecha: String): Int

    /** La última fila agregada hoy: sus valores se proponen para el siguiente corral. */
    @Query("SELECT * FROM plan_item WHERE fecha = :fecha AND borrado = 0 ORDER BY orden DESC, createdAtEpochMillis DESC LIMIT 1")
    suspend fun getUltimoDelDia(fecha: String): PlanItem?

    /** Cumplimiento local inmediato: al finalizar el muestreo que salió de esta fila. */
    @Query("UPDATE plan_item SET estado = 'HECHO' WHERE id = :id")
    suspend fun marcarHecho(id: String)

    /** Estado según el servidor (que cruza contra todos los muestreos, no solo los de este teléfono). */
    @Query("UPDATE plan_item SET estado = :estado WHERE id = :id AND estado != 'HECHO'")
    suspend fun actualizarEstado(id: String, estado: String)

    /** Borrar = marcar y avisar al servidor en la siguiente sincronización. */
    @Query("UPDATE plan_item SET borrado = 1, synced = 0 WHERE id = :id AND estado != 'HECHO'")
    suspend fun marcarBorrado(id: String)

    // Cola de sincronización: filas nuevas o editadas, y filas marcadas para borrar.
    @Query("SELECT * FROM plan_item WHERE synced = 0 AND borrado = 0 ORDER BY createdAtEpochMillis ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 50): List<PlanItem>

    @Query("SELECT id FROM plan_item WHERE synced = 0 AND borrado = 1")
    suspend fun getBorradosPendientes(): List<String>

    @Query("UPDATE plan_item SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("DELETE FROM plan_item WHERE id IN (:ids)")
    suspend fun eliminar(ids: List<String>)

    @Query("SELECT COUNT(*) FROM plan_item WHERE synced = 0")
    fun countUnsyncedFlow(): Flow<Int>
}
