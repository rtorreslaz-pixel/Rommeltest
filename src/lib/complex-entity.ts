import type { CategoriaAve } from "@/generated/prisma/enums";

// Misma idea que buildComplexEntity en el wizard de evaluación (Plantel-Campaña-Galpón-
// Sexo-Corral), pero con CategoriaAve (MACHO/HEMBRA/MEDIANO) en vez de SexoAve (MACHO/
// HEMBRA) -- MEDIANO no tiene equivalente en calidad, por lo que esos registros de
// preventa no van a cruzar contra ninguna Inspeccion.complex, lo cual es esperado.
const CATEGORIA_ABREV: Record<CategoriaAve, string> = {
  MACHO: "M",
  HEMBRA: "H",
  MEDIANO: "MD",
};

export function buildComplexEntity(parts: {
  plantelCodigo: string | null;
  campania: string | null;
  galpon: string | null;
  categoria: CategoriaAve | null;
  corral: string | null;
}): string | null {
  const { plantelCodigo, campania, galpon, categoria, corral } = parts;
  const categoriaAbrev = categoria ? CATEGORIA_ABREV[categoria] : "";
  const piezas = [plantelCodigo ?? "", campania ?? "", normGalpon(galpon), categoriaAbrev, corral ?? ""];
  if (piezas.every((p) => !p)) return null;
  return piezas.join("-");
}

/**
 * Complex a NIVEL LOTE: Plantel-Campaña-Galpón-Categoría (el complex de preventa SIN el
 * corral). Es la clave con la que se cruza el muestreo de saca (que se toma del galpón)
 * contra los registros de preventa (que bajan hasta corral): basta recortarles el corral.
 */
export function buildComplexLote(parts: {
  plantelCodigo: string | null;
  campania: string | null;
  galpon: string | null;
  categoria: CategoriaAve | null;
}): string | null {
  const { plantelCodigo, campania, galpon, categoria } = parts;
  const categoriaAbrev = categoria ? CATEGORIA_ABREV[categoria] : "";
  const piezas = [plantelCodigo ?? "", campania ?? "", normGalpon(galpon), categoriaAbrev];
  if (piezas.every((p) => !p)) return null;
  return piezas.join("-");
}

/**
 * Complex a NIVEL GALPÓN: Plantel-Campaña-Galpón, sin categoría ni corral. Es la clave del
 * resumen por galpón: agrupa los muestreos de los 4 corrales (y de ambos sexos, si los hay)
 * bajo el mismo techo.
 */
export function buildComplexGalpon(parts: {
  plantelCodigo: string | null;
  campania: string | null;
  galpon: string | null;
}): string {
  return [parts.plantelCodigo ?? "", parts.campania ?? "", normGalpon(parts.galpon)].join("-");
}

/** Recorta el corral de un complex de preventa para obtener su complex de lote. */
export function complexLoteFromComplex(complex: string | null): string | null {
  if (!complex) return null;
  const piezas = complex.split("-");
  if (piezas.length < 5) return complex;
  return piezas.slice(0, 4).join("-");
}

// Normaliza el galpón para el cruce: quita ceros a la izquierda si es numérico
// ("09" -> "9"), de modo que preventa (granja) y calidad (cliente) generen el MISMO
// complex aunque los orígenes formateen distinto el número de galpón.
export function normGalpon(g: string | null): string {
  if (!g) return "";
  const s = String(g).trim().toUpperCase();
  return /^\d+$/.test(s) ? String(parseInt(s, 10)) : s;
}
