package com.rommel.scaleprototype.net

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponseUser(val id: String, val nombre: String, val email: String)

@Serializable
data class LoginResponse(val token: String, val user: LoginResponseUser)

@Serializable
// nombre es nullable porque Plantel.nombre lo es en el schema -- hay planteles
// que solo tienen codigo asignado todavia.
data class PlantelDto(val id: String, val codigo: String, val nombre: String? = null, val cliente: String? = null)

@Serializable
data class CatalogosResponse(val planteles: List<PlantelDto>)

// categoria viaja como String (no como el enum de Kotlin) para no acoplar nombres
// de enum entre Android y el servidor.
@Serializable
data class RegistroDto(
    val id: String,
    val plantelId: String,
    val campania: String? = null,
    val galpon: String,
    val corral: String,
    val categoria: String,
    val numeroAve: Int,
    // null en muestreos de solo calidad (tipoMuestreo = CALIDAD).
    val pesoGramos: Double? = null,
    val tipoMuestreo: String = "PREVENTA",
    val fechaHora: String,
    // Metadatos del lote -- null en registros anteriores a este campo.
    val edad: Int? = null,
    val linea: String? = null,
    val lote: String? = null,
    val nAvesPorPesada: Int? = null,
    // Calidad evaluada opcionalmente sobre esta misma ave -- null si no se evaluó.
    val tieneHematoma: Boolean? = null,
    val tieneDefectoSeleccion: Boolean? = null,
    val gradoPododermatitis: Int? = null,
    val gradoRasguno: Int? = null,
    val pigmentacion: Int? = null,
)

@Serializable
data class RegistrosBatchRequest(val registros: List<RegistroDto>)

@Serializable
data class RegistrosBatchResponse(val ingested: Int, val ids: List<String>)

@Serializable
data class ApiErrorResponse(val error: String)

@Serializable
data class LiveWeightRequest(
    val pesoGramos: Double,
    val plantelCodigo: String? = null,
    val campania: String? = null,
    val galpon: String? = null,
    val corral: String? = null,
    val categoria: String? = null,
)

@Serializable
data class NumeroAveMaxResponse(val maxNumeroAve: Int? = null)

// --- Muestreo de saca (jabas) ---

@Serializable
data class SacaPesadaDto(
    val id: String,
    val numJabas: Int,
    val pesoBrutoGramos: Double,
    val pesoNetoGramos: Double,
    val avesTotal: Int,
    val promedioGramos: Double,
    val fechaHora: String,
)

@Serializable
data class SacaMuestreoDto(
    val id: String,
    val plantelId: String,
    val campania: String? = null,
    val galpon: String,
    val corral: String? = null,
    val categoria: String,
    val fecha: String,
    val edad: Int? = null,
    val avesPorJaba: Int,
    val taraGramosPorJaba: Double,
    val tipoJaba: String? = null,
    val pesadas: List<SacaPesadaDto>,
)

@Serializable
data class SacaBatchRequest(val muestreos: List<SacaMuestreoDto>)

@Serializable
data class SacaBatchResponse(val ingested: Int, val ids: List<String>)

// --- Plan diario de muestreo ---

@Serializable
data class PlanItemDto(
    val id: String,
    val fecha: String,
    val plantelId: String,
    val campania: String,
    val galpon: String,
    val corral: String,
    val categoria: String,
    val edad: Int? = null,
    val tipoMuestreo: String? = null,
    val linea: String? = null,
    val lote: String? = null,
    val agrupamiento: String? = null,
    val circuito: String? = null,
    val orden: Int? = null,
    // Solo vienen del servidor (GET): la app no los manda.
    val plantelCodigo: String? = null,
    val estado: String? = null,
)

@Serializable
data class PlanBatchRequest(val items: List<PlanItemDto>, val borrar: List<String> = emptyList())

@Serializable
data class PlanEstadoDto(val id: String, val estado: String)

@Serializable
data class PlanBatchResponse(val ingested: Int, val cumplidos: Int = 0, val items: List<PlanEstadoDto> = emptyList())

@Serializable
data class PlanDiaResponse(val fecha: String, val items: List<PlanItemDto>)

