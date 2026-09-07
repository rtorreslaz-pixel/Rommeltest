package com.rommel.scaleprototype.ui

/**
 * Estándares de muestreo de la empresa. Son los valores que la app propone sola al elegir el
 * tipo de muestreo; el verificador puede cambiarlos en pantalla si un caso lo amerita.
 *
 * - Pesaje de preventa: aves de UNA en una (agrupamiento individual).
 * - Calidad: de a TRES aves por registro (agrupamiento grupal).
 */
object EstandaresMuestreo {
    const val TIPO_PREVENTA = "PREVENTA"
    const val TIPO_CALIDAD = "CALIDAD"

    const val AVES_POR_PESADA_PREVENTA = 1
    const val AVES_POR_PESADA_CALIDAD = 3

    /** INDIVIDUAL o GRUPAL según el tipo. */
    fun agrupamientoPara(tipo: String): String =
        if (tipo == TIPO_CALIDAD) "GRUPAL" else "INDIVIDUAL"

    /** Aves por pesada/registro según el tipo. */
    fun avesPorPesadaPara(tipo: String): Int =
        if (tipo == TIPO_CALIDAD) AVES_POR_PESADA_CALIDAD else AVES_POR_PESADA_PREVENTA
}
