import { NextRequest } from "next/server";
import { prisma } from "@/lib/db";
import { resolveExportUser, tablaResponse } from "@/lib/export-csv";
import type { Prisma } from "@/generated/prisma/client";
import { CATEGORIA_LABEL } from "@/lib/resumen-galpon";
import { AGRUPAMIENTO_LABEL, ESTADO_PLAN_LABEL, diaLima } from "@/lib/plan-muestreo";

// Descarga del plan de muestreo con su cumplimiento, en el mismo formato de columnas del Excel
// de planificación (PLANTEL, GALPON, CORRAL, SEXO, EDAD, TIPO DE MUESTREO, LINEA, LOTE,
// AGRUPAMIENTO, CIRCUITO) más fecha, campaña, verificador y estado. Mismos filtros que la pantalla.

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const user = await resolveExportUser(request);

  const hoy = diaLima(new Date());
  const f = searchParams.get("fecha") ?? "";
  const h = searchParams.get("hasta") ?? "";
  const desde = /^\d{4}-\d{2}-\d{2}$/.test(f) ? f : hoy;
  const hasta = /^\d{4}-\d{2}-\d{2}$/.test(h) ? h : desde;

  const where: Prisma.PlanMuestreoItemWhereInput = { fecha: { gte: desde, lte: hasta } };
  if (user?.role === "VERIFICADOR") where.verificadorId = user.id;
  else if (searchParams.get("verificador")) where.verificadorId = searchParams.get("verificador")!;

  const items = await prisma.planMuestreoItem.findMany({
    where,
    orderBy: [{ fecha: "asc" }, { verificadorId: "asc" }, { orden: "asc" }, { createdAt: "asc" }],
    include: { plantel: { select: { codigo: true } }, verificador: { select: { nombre: true } } },
  });

  const headers = [
    "FECHA", "VERIFICADOR", "PLANTEL", "CAMPAÑA", "GALPON", "CORRAL", "SEXO", "EDAD", "TIPO DE MUESTREO",
    "LINEA", "LOTE", "AGRUPAMIENTO", "AVES POR PESADA", "CIRCUITO", "ESTADO", "CUMPLIDO EN", "COMPLEX",
  ];
  const rows: (string | number)[][] = [
    headers,
    ...items.map((i) => [
      i.fecha,
      i.verificador.nombre,
      i.plantel.codigo,
      i.campania,
      i.galpon,
      i.corral,
      CATEGORIA_LABEL[i.categoria].toUpperCase(),
      i.edad ?? "",
      i.tipoMuestreo,
      i.linea ?? "",
      i.lote ?? "",
      AGRUPAMIENTO_LABEL[i.agrupamiento],
      i.agrupamiento === "GRUPAL" ? (i.avesPorPesada ?? "") : 1,
      i.circuito ?? "",
      ESTADO_PLAN_LABEL[i.estado].toUpperCase(),
      i.cumplidoEn ? i.cumplidoEn.toISOString().replace("T", " ").slice(0, 19) : "",
      i.complex ?? "",
    ]),
  ];
  return tablaResponse(rows, "planeamiento", searchParams, "Planeamiento");
}
