import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/db";
import { requireMobileUser } from "@/lib/auth";
import { CategoriaAve, TipoMuestreo } from "@/generated/prisma/enums";
import { buildComplexEntity } from "@/lib/complex-entity";
import { cumplirPlanConRegistros } from "@/lib/plan-muestreo";

type RegistroInput = {
  id: string;
  plantelId: string;
  campania?: string | null;
  galpon: string;
  corral: string;
  categoria: string;
  numeroAve: number;
  // null/ausente solo en muestreos de solo calidad (tipoMuestreo = CALIDAD).
  pesoGramos?: number | null;
  tipoMuestreo?: string | null;
  fechaHora: string;
  edad?: number | null;
  linea?: string | null;
  lote?: string | null;
  nAvesPorPesada?: number | null;
  tieneHematoma?: boolean | null;
  tieneDefectoSeleccion?: boolean | null;
  gradoPododermatitis?: number | null;
  gradoRasguno?: number | null;
  pigmentacion?: number | null;
};

function isValidGrado(v: unknown): v is number | null | undefined {
  return v === undefined || v === null || (typeof v === "number" && [0, 1, 2].includes(v));
}

function isValidTipoMuestreo(v: unknown): v is string | null | undefined {
  return v === undefined || v === null || Object.values(TipoMuestreo).includes(v as TipoMuestreo);
}

function isValidRegistro(r: unknown): r is RegistroInput {
  if (typeof r !== "object" || r === null) return false;
  const v = r as Record<string, unknown>;
  // El peso es obligatorio salvo en muestreos de solo calidad (CALIDAD).
  const esCalidad = v.tipoMuestreo === "CALIDAD";
  const pesoValido = esCalidad
    ? v.pesoGramos === undefined || v.pesoGramos === null || typeof v.pesoGramos === "number"
    : typeof v.pesoGramos === "number";
  return (
    typeof v.id === "string" &&
    typeof v.plantelId === "string" &&
    (v.campania === undefined || v.campania === null || typeof v.campania === "string") &&
    typeof v.galpon === "string" &&
    typeof v.corral === "string" &&
    typeof v.categoria === "string" &&
    Object.values(CategoriaAve).includes(v.categoria as CategoriaAve) &&
    isValidTipoMuestreo(v.tipoMuestreo) &&
    typeof v.numeroAve === "number" &&
    pesoValido &&
    typeof v.fechaHora === "string" &&
    !Number.isNaN(Date.parse(v.fechaHora)) &&
    (v.tieneHematoma === undefined || v.tieneHematoma === null || typeof v.tieneHematoma === "boolean") &&
    (v.tieneDefectoSeleccion === undefined ||
      v.tieneDefectoSeleccion === null ||
      typeof v.tieneDefectoSeleccion === "boolean") &&
    isValidGrado(v.gradoPododermatitis) &&
    isValidGrado(v.gradoRasguno) &&
    (v.pigmentacion === undefined ||
      v.pigmentacion === null ||
      (typeof v.pigmentacion === "number" && v.pigmentacion >= 0 && v.pigmentacion <= 7)) &&
    (v.edad === undefined || v.edad === null || (typeof v.edad === "number" && Number.isInteger(v.edad) && v.edad >= 0)) &&
    (v.linea === undefined || v.linea === null || typeof v.linea === "string") &&
    (v.lote === undefined || v.lote === null || typeof v.lote === "string") &&
    (v.nAvesPorPesada === undefined || v.nAvesPorPesada === null || (typeof v.nAvesPorPesada === "number" && Number.isInteger(v.nAvesPorPesada) && v.nAvesPorPesada > 0))
  );
}

export async function POST(request: NextRequest) {
  const user = await requireMobileUser(request);
  if (!user) return NextResponse.json({ error: "No autorizado" }, { status: 401 });

  const body = await request.json().catch(() => null);
  const registros = body?.registros;
  if (!Array.isArray(registros) || registros.length === 0) {
    return NextResponse.json({ error: "registros debe ser un arreglo no vacío" }, { status: 400 });
  }
  if (!registros.every(isValidRegistro)) {
    return NextResponse.json({ error: "Uno o más registros tienen campos inválidos" }, { status: 400 });
  }

  const plantelIds = [...new Set(registros.map((r) => r.plantelId))];
  const planteles = await prisma.plantel.findMany({
    where: { id: { in: plantelIds } },
    select: { id: true, codigo: true },
  });
  if (planteles.length !== plantelIds.length) {
    return NextResponse.json({ error: "Uno o más plantelId no existen" }, { status: 400 });
  }
  const codigoByPlantelId = new Map(planteles.map((p) => [p.id, p.codigo]));

  const ids = await prisma.$transaction(
    registros.map((r) =>
      prisma.registroPesoPreventa.upsert({
        where: { id: r.id },
        update: {},
        create: {
          id: r.id,
          plantelId: r.plantelId,
          campania: r.campania ?? null,
          galpon: r.galpon,
          corral: r.corral,
          categoria: r.categoria as CategoriaAve,
          numeroAve: r.numeroAve,
          pesoGramos: r.pesoGramos ?? null,
          tipoMuestreo: (r.tipoMuestreo as TipoMuestreo | null | undefined) ?? TipoMuestreo.PREVENTA,
          fechaHora: new Date(r.fechaHora),
          edad: r.edad ?? null,
          linea: r.linea ?? null,
          lote: r.lote ?? null,
          nAvesPorPesada: r.nAvesPorPesada ?? null,
          tieneHematoma: r.tieneHematoma ?? null,
          tieneDefectoSeleccion: r.tieneDefectoSeleccion ?? null,
          gradoPododermatitis: r.gradoPododermatitis ?? null,
          gradoRasguno: r.gradoRasguno ?? null,
          pigmentacion: r.pigmentacion ?? null,
          verificadorId: user.id,
          complex: buildComplexEntity({
            plantelCodigo: codigoByPlantelId.get(r.plantelId) ?? null,
            campania: r.campania ?? null,
            galpon: r.galpon,
            categoria: r.categoria as CategoriaAve,
            corral: r.corral,
          }),
        },
        select: { id: true },
      })
    )
  );

  // Cumplimiento del plan del día: las filas que coinciden con lo recién muestreado pasan a
  // HECHO. Si esto fallara no se pierde el lote ya guardado; se registra y se responde igual.
  try {
    await cumplirPlanConRegistros(
      user.id,
      registros.map((r) => ({
        plantelId: r.plantelId,
        galpon: r.galpon,
        corral: r.corral,
        categoria: r.categoria as CategoriaAve,
        tipoMuestreo: (r.tipoMuestreo as TipoMuestreo | null | undefined) ?? TipoMuestreo.PREVENTA,
        fechaHora: new Date(r.fechaHora),
      }))
    );
  } catch (e) {
    console.error("No se pudo cruzar el plan de muestreo:", e);
  }

  return NextResponse.json({ ingested: ids.length, ids: ids.map((r) => r.id) });
}
