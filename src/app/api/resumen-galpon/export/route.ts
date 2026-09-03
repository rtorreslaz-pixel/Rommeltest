import { NextRequest } from "next/server";
import { resolveExportUser, tablaResponse } from "@/lib/export-csv";
import { CATEGORIA_LABEL, construirResumenGalpones, leerFiltrosResumen } from "@/lib/resumen-galpon";

// Descarga de la hoja de resumen por galpón. Una fila por muestreo (corral · sexo · día) y,
// antes de ellas, una fila "GALPÓN" con el total del complex hasta galpón. Mismos filtros que la
// pantalla; Excel con ?formato=xlsx.

function r3(v: number | null): string | number {
  return v == null ? "" : Number((v / 1000).toFixed(3));
}
function r1(v: number | null): string | number {
  return v == null ? "" : Number(v.toFixed(1));
}
function r0(v: number | null): string | number {
  return v == null ? "" : Math.round(v);
}

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const user = await resolveExportUser(request);
  const filtros = leerFiltrosResumen({
    desde: searchParams.get("desde") ?? undefined,
    hasta: searchParams.get("hasta") ?? undefined,
    plantel: searchParams.get("plantel") ?? undefined,
    tolerancia: searchParams.get("tolerancia") ?? undefined,
  });
  const galpones = await construirResumenGalpones(user, filtros);

  const headers = [
    "TIPO",
    "COMPLEX GALPÓN",
    "PLANTEL",
    "NOMBRE PLANTEL",
    "CAMPAÑA",
    "GALPÓN",
    "CORRAL",
    "SEXO",
    "DÍA",
    "EDAD (días)",
    "LÍNEA",
    "COMPLEX",
    "AVES",
    "AVES INDIVIDUALES",
    "AVES EN GRUPO",
    "PROMEDIO (g)",
    "PROMEDIO (kg)",
    "DESV. EST. (g)",
    "CV (%)",
    `UNIFORMIDAD ±${filtros.tolerancia}% (%)`,
    "MÍNIMO (g)",
    "MÁXIMO (g)",
    "VERIFICADOR",
  ];

  const rows: (string | number)[][] = [headers];
  for (const g of galpones) {
    rows.push([
      "GALPÓN",
      g.complexGalpon,
      g.plantelCodigo,
      g.plantelNombre,
      g.campania,
      g.galpon,
      "",
      g.categorias.map((c) => CATEGORIA_LABEL[c]).join(" + "),
      g.primerDia === g.ultimoDia ? g.primerDia : `${g.primerDia} a ${g.ultimoDia}`,
      g.edades.join(", "),
      "",
      "",
      g.totalAves,
      g.avesIndividuales,
      g.avesAgrupadas,
      r0(g.promedioTotal),
      r3(g.promedioTotal),
      r0(g.stats.desviacion),
      r1(g.stats.cv),
      r1(g.stats.uniformidad),
      r0(g.stats.minimo),
      r0(g.stats.maximo),
      "",
    ]);
    for (const c of g.porCategoria) {
      rows.push([
        "GALPÓN · " + CATEGORIA_LABEL[c.categoria].toUpperCase(),
        g.complexGalpon, g.plantelCodigo, g.plantelNombre, g.campania, g.galpon, "",
        CATEGORIA_LABEL[c.categoria], "", "", "", "",
        c.totalAves, c.stats.n, c.totalAves - c.stats.n,
        r0(c.promedioTotal), r3(c.promedioTotal), r0(c.stats.desviacion), r1(c.stats.cv), r1(c.stats.uniformidad),
        r0(c.stats.minimo), r0(c.stats.maximo), "",
      ]);
    }
    for (const m of g.muestreos) {
      rows.push([
        "MUESTREO",
        g.complexGalpon,
        g.plantelCodigo,
        g.plantelNombre,
        g.campania,
        g.galpon,
        m.corral,
        CATEGORIA_LABEL[m.categoria],
        m.dia,
        m.edad ?? "",
        m.linea ?? "",
        m.complex ?? "",
        m.totalAves,
        m.avesIndividuales,
        m.avesAgrupadas,
        r0(m.promedioTotal),
        r3(m.promedioTotal),
        r0(m.stats.desviacion),
        r1(m.stats.cv),
        r1(m.stats.uniformidad),
        r0(m.stats.minimo),
        r0(m.stats.maximo),
        m.verificadores.join(", "),
      ]);
    }
  }

  return tablaResponse(rows, "resumen-galpon", searchParams, "Resumen por galpon");
}
