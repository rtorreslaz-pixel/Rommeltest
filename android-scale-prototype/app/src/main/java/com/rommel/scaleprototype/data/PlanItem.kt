package com.rommel.scaleprototype.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Una fila del plan del día: un corral que el verificador se propone muestrear, con los
 * parámetros del lote ya definidos. Lo arma él mismo en la app antes de salir (offline) y se
 * sincroniza a la web, que mide el cumplimiento.
 *
 * El estado pasa a HECHO solo: en el teléfono al finalizar el muestreo que salió de esta fila, y
 * en el servidor cuando llega un muestreo del mismo día, plantel, galpón, corral, sexo y tipo.
 * El id es un UUID generado aquí para que un reintento de red no duplique la fila.
 */
@Entity(tableName = "plan_item")
data class PlanItem(
    @PrimaryKey val id: String,
    /** Día del plan (yyyy-MM-dd, hora de la granja). */
    val fecha: String,
    val plantelId: String,
    val plantelCodigo: String,
    val campania: String,
    val galpon: String,
    val corral: String,
    /** MACHO / HEMBRA / MEDIANO. */
    val categoria: String,
    val edad: Int?,
    /** PREVENTA / CALIDAD. */
    val tipoMuestreo: String,
    val linea: String?,
    val lote: String?,
    /** INDIVIDUAL / GRUPAL. */
    val agrupamiento: String,
    /** Aves que van juntas en cada pesada cuando es GRUPAL; null = individual. */
    val avesPorPesada: Int? = null,
    /** CV (vivo) / CB (beneficiado), o null. */
    val circuito: String?,
    val orden: Int,
    /** PENDIENTE / HECHO. */
    val estado: String = ESTADO_PENDIENTE,
    val verificadorId: String?,
    /** Marcada para borrar: se elimina del servidor en la próxima sincronización y luego local. */
    val borrado: Boolean = false,
    val synced: Boolean = false,
    val createdAtEpochMillis: Long,
) {
    companion object {
        const val ESTADO_PENDIENTE = "PENDIENTE"
        const val ESTADO_HECHO = "HECHO"
    }
}
