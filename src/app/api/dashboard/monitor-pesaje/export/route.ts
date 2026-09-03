import { NextRequest } from "next/server";
import { prisma } from "@/lib/db";
import { resolveExportUser, tablaResponse } from "@/lib/export-csv";
import type { Prisma } from "@/generated/prisma/client";
import { construirResumenGalpones, filasResumenGalpones, leerFiltrosResumen } from "@/lib/resumen-galpon";

// Descarga de la "base de datos de la toma de muestras": todos los registros de peso
// de preventa que la app Android sincroniza (RegistroPesoPreventa). Un VERIFICADOR
// solo descarga los suyos; los roles de supervisión descargan todo. Misma autenticación
// y formato (CSV UTF-8 con BOM, separador ";") que las exportaciones de inspecciones.

// Etiquetas legibles para el sexo/categoría del ave.
const CATEGORIA_LABEL: Record<string, string> = {
  MACHO: "Macho",
  HEMBRA: "Hembra",
  MEDIANO: "Mediano",
};

// 0 sin lesión, 1 leve, 2 grave (pododermatitis y rasguño).
const GRADO_LESION_LABEL: Record<number, string> = {
  0: "Sin lesión",
  1: "Leve",
  2: "Grave",
};

function boolLabel(v: boolean | null | undefined): string {
  if (v === null || v === undefined) return "";
  return v ? "Sí" : "No";
}

function gradoLabel(v: number | null | undefined): string {
  if (v === null || v === undefined) return "";
  return GRADO_LESION_LABEL[v] ?? String(v);
}

// Fecha/hora local legible (Perú) para las columnas de fecha del CSV.
function fechaHoraLegible(d: Date | null | undefined): string {
  if (!d) return "";
  return d.toISOString().replace("T", " ").slice(0, 19);
}

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const user = await resolveExportUser(request);

  // Un VERIFICADOR solo puede descargar sus propios registros; supervisión ve todo.
  // Se aceptan filtros opcionales (mismos que el monitor) si vienen en la query.
  const where: Prisma.RegistroPesoPreventaWhereInput = {};
  if (user?.role === "VERIFICADOR") {
    where.verificadorId = user.id;
  } else if (searchParams.get("verificadorId")) {
    where.verificadorId = searchParams.get("verificadorId")!;
  }
  if (searchParams.get("plantelId")) where.plantelId = searchParams.get("plantelId")!;
  const desde = searchParams.get("desde");
  const hasta = searchParams.get("hasta");
  if (desde || hasta) {
    where.fechaHora = {};
    if (desde) where.fechaHora.gte = new Date(desde);
    if (hasta) where.fechaHora.lte = new Date(hasta + "T23:59:59");
  }

  const registros = await prisma.registroPesoPreventa.findMany({
    where,
    orderBy: { createdAt: "desc" },
    include: {
      plantel: { select: { codigo: true, nombre: true } },
      verificador: { select: { nombre: true } },
    },
  });

  const headers = [
    "ID (UUID)",
    "FECHA/HORA CAPTURA",
    "PLANTEL",
    "NOMBRE PLANTEL",
    "CAMPAÑA",
    "GALPÓN",
    "CORRAL",
    "CATEGORÍA",
    "COMPLEX",
    "TIPO MUESTREO",
    "N° AVE",
    "PESO (g)",
    "PESO (kg)",
    "N° AVES POR PESADA",
    "EDAD (días)",
    "LÍNEA",
    "LOTE",
    "TIENE HEMATOMA",
    "TIENE DEFECTO SELECCIÓN",
    "GRADO PODODERMATITIS",
    "GRADO RASGUÑO",
    "PIGMENTACIÓN (0-7)",
    "VERIFICADOR",
    "SINCRONIZADO",
    "CREADO",
  ];

  const dataRows: (string | number)[][] = registros.map((r) => [
    r.id,
    fechaHoraLegible(r.fechaHora),
    r.plantel?.codigo ?? "",
    r.plantel?.nombre ?? "",
    r.campania ?? "",
    r.galpon,
    r.corral,
    CATEGORIA_LABEL[r.categoria] ?? r.categoria,
    r.complex ?? "",
    r.tipoMuestreo,
    r.numeroAve,
    r.pesoGramos ?? "",
    r.pesoGramos != null ? (r.pesoGramos / 1000).toFixed(3) : "",
    r.nAvesPorPesada ?? "",
    r.edad ?? "",
    r.linea ?? "",
    r.lote ?? "",
    boolLabel(r.tieneHematoma),
    boolLabel(r.tieneDefectoSeleccion),
    gradoLabel(r.gradoPododermatitis),
    gradoLabel(r.gradoRasguno),
    r.pigmentacion ?? "",
    r.verificador?.nombre ?? "",
    fechaHoraLegible(r.syncedAt),
    fechaHoraLegible(r.createdAt),
  ]);

  const rows: (string | number)[][] = [headers, ...dataRows];

  // En Excel va una segunda pestaña con el resumen por galpón (promedio, CV, uniformidad) de
  // los MISMOS registros: mismos filtros de fecha y plantel, misma restricción por verificador.
  const filtrosResumen = leerFiltrosResumen({
    desde: desde ?? undefined,
    hasta: hasta ?? undefined,
    plantel: searchParams.get("plantelId") ?? undefined,
    tolerancia: searchParams.get("tolerancia") ?? undefined,
  });
  const galpones = await construirResumenGalpones(user, filtrosResumen);
  const hojaResumen = { nombre: "Resumen por galpon", filas: filasResumenGalpones(galpones, filtrosResumen.tolerancia) };

  return tablaResponse(rows, "toma-muestras", searchParams, "Toma de muestras", [hojaResumen]);
}
