import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/db";
import { requireMobileUser } from "@/lib/auth";
import { AgrupamientoMuestreo, EstadoPlanMuestreo, TipoMuestreo } from "@/generated/prisma/enums";
import { complexDePlan, cumplirPlanPendiente, diaLima, isValidPlanItem } from "@/lib/plan-muestreo";

// Plan diario de muestreo del verificador.
//
// POST { items: [...], borrar?: [ids] }: sube (o actualiza) filas del plan, idempotente por id.
//   Una fila ya HECHA no se pisa: lo que se cumplió, se cumplió. `borrar` elimina filas
//   pendientes propias (las hechas no se borran). Tras guardar, se cruza contra los registros
//   que ya hubieran llegado ese día, por si el muestreo subió antes que el plan.
// GET ?fecha=yyyy-MM-dd (hoy por defecto): el plan del verificador para ese día con su estado,
//   para que la app refresque qué quedó cumplido según el servidor.

export async function POST(request: NextRequest) {
  const user = await requireMobileUser(request);
  if (!user) return NextResponse.json({ error: "No autorizado" }, { status: 401 });

  const body = await request.json().catch(() => null);
  const items: unknown[] = Array.isArray(body?.items) ? body.items : [];
  const borrar: unknown[] = Array.isArray(body?.borrar) ? body.borrar : [];
  if (items.length === 0 && borrar.length === 0) {
    return NextResponse.json({ error: "items o borrar debe traer al menos un elemento" }, { status: 400 });
  }
  if (!items.every(isValidPlanItem)) {
    return NextResponse.json({ error: "Una o más filas del plan tienen campos inválidos" }, { status: 400 });
  }
  if (!borrar.every((id) => typeof id === "string")) {
    return NextResponse.json({ error: "borrar debe ser un arreglo de ids" }, { status: 400 });
  }

  const plantelIds = [...new Set(items.map((i) => i.plantelId))];
  const planteles = await prisma.plantel.findMany({ where: { id: { in: plantelIds } }, select: { id: true, codigo: true } });
  if (planteles.length !== plantelIds.length) {
    return NextResponse.json({ error: "Uno o más plantelId no existen" }, { status: 400 });
  }
  const codigoPorPlantel = new Map(planteles.map((p) => [p.id, p.codigo]));

  // Dueño y estado de las filas que ya existen: nadie modifica el plan de otro verificador, y
  // las filas ya cumplidas no se reescriben (lo que se cumplió, se cumplió).
  const existentes = await prisma.planMuestreoItem.findMany({
    where: { id: { in: items.map((i) => i.id) } },
    select: { id: true, verificadorId: true, estado: true },
  });
  if (existentes.some((e) => e.verificadorId !== user.id)) {
    return NextResponse.json({ error: "Una o más filas pertenecen a otro verificador" }, { status: 403 });
  }
  const hechas = new Set(existentes.filter((e) => e.estado === EstadoPlanMuestreo.HECHO).map((e) => e.id));

  await prisma.$transaction([
    ...items
      .filter((i) => !hechas.has(i.id))
      .map((i) => {
        const datos = {
          fecha: i.fecha,
          plantelId: i.plantelId,
          campania: i.campania,
          galpon: i.galpon.trim(),
          corral: i.corral.trim().toUpperCase(),
          categoria: i.categoria,
          edad: i.edad ?? null,
          tipoMuestreo: i.tipoMuestreo ?? TipoMuestreo.PREVENTA,
          linea: i.linea ?? null,
          lote: i.lote ?? null,
          agrupamiento: i.agrupamiento ?? AgrupamientoMuestreo.INDIVIDUAL,
          // Solo tiene sentido en grupal; en individual se guarda null aunque venga un número.
          avesPorPesada: (i.agrupamiento ?? AgrupamientoMuestreo.INDIVIDUAL) === AgrupamientoMuestreo.GRUPAL ? (i.avesPorPesada ?? null) : null,
          circuito: i.circuito ?? null,
          orden: i.orden ?? 0,
          complex: complexDePlan(codigoPorPlantel.get(i.plantelId) ?? "", i),
        };
        return prisma.planMuestreoItem.upsert({
          where: { id: i.id },
          update: datos,
          create: { id: i.id, verificadorId: user.id, ...datos },
        });
      }),
    ...(borrar.length > 0
      ? [
          prisma.planMuestreoItem.deleteMany({
            where: { id: { in: borrar as string[] }, verificadorId: user.id, estado: EstadoPlanMuestreo.PENDIENTE },
          }),
        ]
      : []),
  ]);

  const cumplidos = await cumplirPlanPendiente(user.id, items.map((i) => i.fecha));

  const estados = await prisma.planMuestreoItem.findMany({
    where: { id: { in: items.map((i) => i.id) } },
    select: { id: true, estado: true },
  });
  return NextResponse.json({ ingested: items.length, cumplidos, items: estados });
}

export async function GET(request: NextRequest) {
  const user = await requireMobileUser(request);
  if (!user) return NextResponse.json({ error: "No autorizado" }, { status: 401 });

  const { searchParams } = new URL(request.url);
  const fecha = searchParams.get("fecha") ?? diaLima(new Date());
  if (!/^\d{4}-\d{2}-\d{2}$/.test(fecha)) {
    return NextResponse.json({ error: "fecha debe ser yyyy-MM-dd" }, { status: 400 });
  }

  const items = await prisma.planMuestreoItem.findMany({
    where: { verificadorId: user.id, fecha },
    orderBy: [{ orden: "asc" }, { createdAt: "asc" }],
    include: { plantel: { select: { codigo: true } } },
  });
  return NextResponse.json({
    fecha,
    items: items.map((i) => ({
      id: i.id,
      fecha: i.fecha,
      plantelId: i.plantelId,
      plantelCodigo: i.plantel.codigo,
      campania: i.campania,
      galpon: i.galpon,
      corral: i.corral,
      categoria: i.categoria,
      edad: i.edad,
      tipoMuestreo: i.tipoMuestreo,
      linea: i.linea,
      lote: i.lote,
      agrupamiento: i.agrupamiento,
      avesPorPesada: i.avesPorPesada,
      circuito: i.circuito,
      orden: i.orden,
      estado: i.estado,
      cumplidoEn: i.cumplidoEn,
    })),
  });
}
