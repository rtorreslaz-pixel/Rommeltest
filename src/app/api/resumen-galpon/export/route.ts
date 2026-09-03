import { NextRequest } from "next/server";
import { resolveExportUser, tablaResponse } from "@/lib/export-csv";
import { construirResumenGalpones, filasResumenGalpones, leerFiltrosResumen } from "@/lib/resumen-galpon";

// Descarga de la hoja de resumen por galpón. Mismos filtros que la pantalla; Excel con
// ?formato=xlsx. Las filas se arman en lib/resumen-galpon para compartirlas con la hoja extra
// del Excel de la toma de muestras.

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
  return tablaResponse(filasResumenGalpones(galpones, filtros.tolerancia), "resumen-galpon", searchParams, "Resumen por galpon");
}
