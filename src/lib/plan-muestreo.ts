import { prisma } from "@/lib/db";
import {
  AgrupamientoMuestreo,
  CategoriaAve,
  CircuitoMuestreo,
  EstadoPlanMuestreo,
  TipoMuestreo,
} from "@/generated/prisma/enums";
import { buildComplexEntity, normGalpon } from "@/lib/complex-entity";

// Plan diario de muestreo. El verificador lo arma en la app antes de salir (una fila por corral
// que va a muestrear) y se sincroniza a la web, que lo muestra y mide el cumplimiento.
//
// El cumplimiento es AUTOMÁTICO: nadie marca nada. Cuando llega un muestreo (registros de peso)
// del mismo verificador, el mismo día, mismo plantel, galpón, corral, sexo y tipo de muestreo,
// la fila del plan pasa a HECHO. El cruce se hace en las dos direcciones, porque en granja el
// orden de llegada no está garantizado: puede subir primero el muestreo (sin señal al armar el
// plan) y después el plan.

export type PlanItemInput = {
  id: string;
  fecha: string; // yyyy-MM-dd, día de la granja
  plantelId: string;
  campania: string;
  galpon: string;
  corral: string;
  categoria: CategoriaAve;
  edad?: number | null;
  tipoMuestreo?: TipoMuestreo | null;
  linea?: string | null;
  lote?: string | null;
  agrupamiento?: AgrupamientoMuestreo | null;
  circuito?: CircuitoMuestreo | null;
  orden?: number | null;
};

const FECHA_REGEX = /^\d{4}-\d{2}-\d{2}$/;

function esTexto(v: unknown): v is string {
  return typeof v === "string";
}
function esTextoOpcional(v: unknown): v is string | null | undefined {
  return v === undefined || v === null || typeof v === "string";
}
function esEnumOpcional<T extends string>(v: unknown, valores: readonly T[]): v is T | null | undefined {
  return v === undefined || v === null || (typeof v === "string" && (valores as readonly string[]).includes(v));
}

export function isValidPlanItem(r: unknown): r is PlanItemInput {
  if (typeof r !== "object" || r === null) return false;
  const v = r as Record<string, unknown>;
  return (
    esTexto(v.id) &&
    esTexto(v.fecha) &&
    FECHA_REGEX.test(v.fecha) &&
    esTexto(v.plantelId) &&
    esTexto(v.campania) &&
    esTexto(v.galpon) &&
    v.galpon.trim() !== "" &&
    esTexto(v.corral) &&
    v.corral.trim() !== "" &&
    esTexto(v.categoria) &&
    Object.values(CategoriaAve).includes(v.categoria as CategoriaAve) &&
    (v.edad === undefined || v.edad === null || (typeof v.edad === "number" && Number.isInteger(v.edad) && v.edad >= 0)) &&
    esEnumOpcional(v.tipoMuestreo, Object.values(TipoMuestreo)) &&
    esTextoOpcional(v.linea) &&
    esTextoOpcional(v.lote) &&
    esEnumOpcional(v.agrupamiento, Object.values(AgrupamientoMuestreo)) &&
    esEnumOpcional(v.circuito, Object.values(CircuitoMuestreo)) &&
    (v.orden === undefined || v.orden === null || (typeof v.orden === "number" && Number.isInteger(v.orden)))
  );
}

/** Día de la granja (Perú, sin horario de verano) de un instante. */
export function diaLima(d: Date): string {
  return d.toLocaleDateString("en-CA", { timeZone: "America/Lima" });
}

/** Rango UTC [inicio, fin) de un día de la granja: Lima es UTC-5 todo el año. */
export function rangoUtcDelDia(fecha: string): { desde: Date; hasta: Date } {
  const desde = new Date(`${fecha}T05:00:00.000Z`);
  const hasta = new Date(desde.getTime() + 24 * 60 * 60 * 1000);
  return { desde, hasta };
}

type ClaveMuestreo = {
  fecha: string;
  plantelId: string;
  galpon: string;
  corral: string;
  categoria: CategoriaAve;
  tipoMuestreo: TipoMuestreo;
};

function mismaClave(item: { plantelId: string; galpon: string; corral: string; categoria: CategoriaAve; tipoMuestreo: TipoMuestreo }, k: ClaveMuestreo): boolean {
  return (
    item.plantelId === k.plantelId &&
    normGalpon(item.galpon) === normGalpon(k.galpon) &&
    item.corral.trim().toUpperCase() === k.corral.trim().toUpperCase() &&
    item.categoria === k.categoria &&
    item.tipoMuestreo === k.tipoMuestreo
  );
}

/**
 * Registros recién recibidos → filas del plan que quedan cumplidas. Se llama después de
 * ingerir un lote en /api/mobile/registros.
 */
export async function cumplirPlanConRegistros(
  verificadorId: string,
  registros: { plantelId: string; galpon: string; corral: string; categoria: CategoriaAve; tipoMuestreo: TipoMuestreo; fechaHora: Date }[]
): Promise<number> {
  const claves = new Map<string, ClaveMuestreo>();
  for (const r of registros) {
    const k: ClaveMuestreo = {
      fecha: diaLima(r.fechaHora),
      plantelId: r.plantelId,
      galpon: r.galpon,
      corral: r.corral,
      categoria: r.categoria,
      tipoMuestreo: r.tipoMuestreo,
    };
    claves.set(`${k.fecha}|${k.plantelId}|${normGalpon(k.galpon)}|${k.corral.trim().toUpperCase()}|${k.categoria}|${k.tipoMuestreo}`, k);
  }
  if (claves.size === 0) return 0;

  const fechas = [...new Set([...claves.values()].map((k) => k.fecha))];
  const pendientes = await prisma.planMuestreoItem.findMany({
    where: { verificadorId, fecha: { in: fechas }, estado: EstadoPlanMuestreo.PENDIENTE },
  });
  const cumplidos = pendientes.filter((item) => [...claves.values()].some((k) => k.fecha === item.fecha && mismaClave(item, k)));
  if (cumplidos.length === 0) return 0;

  await prisma.planMuestreoItem.updateMany({
    where: { id: { in: cumplidos.map((c) => c.id) } },
    data: { estado: EstadoPlanMuestreo.HECHO, cumplidoEn: new Date() },
  });
  return cumplidos.length;
}

/**
 * Filas pendientes de un día → se cruzan contra los registros que YA llegaron ese día. Se llama
 * después de recibir el plan, por si el muestreo subió antes que el plan.
 */
export async function cumplirPlanPendiente(verificadorId: string, fechas: string[]): Promise<number> {
  let total = 0;
  for (const fecha of [...new Set(fechas)]) {
    const pendientes = await prisma.planMuestreoItem.findMany({
      where: { verificadorId, fecha, estado: EstadoPlanMuestreo.PENDIENTE },
    });
    if (pendientes.length === 0) continue;
    const { desde, hasta } = rangoUtcDelDia(fecha);
    const registros = await prisma.registroPesoPreventa.findMany({
      where: { verificadorId, fechaHora: { gte: desde, lt: hasta } },
      select: { plantelId: true, galpon: true, corral: true, categoria: true, tipoMuestreo: true, fechaHora: true },
    });
    if (registros.length === 0) continue;
    total += await cumplirPlanConRegistros(verificadorId, registros);
  }
  return total;
}

/** Complex de la fila del plan (misma clave que el registro de peso). */
export function complexDePlan(plantelCodigo: string, item: { campania: string; galpon: string; corral: string; categoria: CategoriaAve }): string | null {
  return buildComplexEntity({
    plantelCodigo,
    campania: item.campania,
    galpon: item.galpon,
    categoria: item.categoria,
    corral: item.corral,
  });
}

export const AGRUPAMIENTO_LABEL: Record<AgrupamientoMuestreo, string> = { INDIVIDUAL: "INDIVIDUAL", GRUPAL: "GRUPAL" };
export const CIRCUITO_LABEL: Record<CircuitoMuestreo, string> = { CV: "CV — vivo", CB: "CB — beneficiado" };
export const ESTADO_PLAN_LABEL: Record<EstadoPlanMuestreo, string> = { PENDIENTE: "Pendiente", HECHO: "Hecho" };
