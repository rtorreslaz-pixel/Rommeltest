import { prisma } from "@/lib/db";
import type { SessionUser } from "@/lib/auth";
import type { Prisma } from "@/generated/prisma/client";
import type { CategoriaAve } from "@/generated/prisma/enums";
import { buildComplexGalpon } from "@/lib/complex-entity";
import { resumirPesos, TOLERANCIA_UNIFORMIDAD_DEFAULT, type ResumenPesos } from "@/lib/estadisticas-peso";

// Resumen por galpón del pesaje de preventa: agrupa los registros bajo el complex hasta galpón
// (Plantel-Campaña-Galpón) y, dentro, un muestreo por corral/categoría/día, con promedio,
// desviación, CV y uniformidad. Lo comparten la pantalla y la descarga en Excel: lo que se ve
// es lo que se descarga.
//
// Sobre las lecturas de varias aves juntas (nAvesPorPesada > 1): el peso guardado ya es el
// promedio por ave de esa pesada, así que sirve para el promedio del lote (ponderado por las
// aves que representa) pero NO para la desviación ni la uniformidad -- promediar antes de
// medir la dispersión la achica artificialmente. Esas lecturas se cuentan aparte y se avisan.

export type FiltrosResumenGalpon = {
  desde: string;
  hasta: string;
  plantelId: string;
  tolerancia: number;
};

export function leerFiltrosResumen(params: {
  desde?: string;
  hasta?: string;
  plantel?: string;
  tolerancia?: string;
}): FiltrosResumenGalpon {
  const t = Number(params.tolerancia);
  return {
    desde: params.desde ?? "",
    hasta: params.hasta ?? "",
    plantelId: params.plantel ?? "",
    tolerancia: Number.isFinite(t) && t > 0 && t <= 50 ? t : TOLERANCIA_UNIFORMIDAD_DEFAULT,
  };
}

export function queryDeFiltrosResumen(f: FiltrosResumenGalpon): string {
  const qs = new URLSearchParams();
  if (f.desde) qs.set("desde", f.desde);
  if (f.hasta) qs.set("hasta", f.hasta);
  if (f.plantelId) qs.set("plantel", f.plantelId);
  if (f.tolerancia !== TOLERANCIA_UNIFORMIDAD_DEFAULT) qs.set("tolerancia", String(f.tolerancia));
  return qs.toString();
}

export function describirFiltrosResumen(f: FiltrosResumenGalpon, nombrePlantel?: string): string {
  const partes: string[] = [];
  if (f.desde) partes.push(`desde ${f.desde}`);
  if (f.hasta) partes.push(`hasta ${f.hasta}`);
  if (f.plantelId) partes.push(`plantel: ${nombrePlantel ?? f.plantelId}`);
  partes.push(`uniformidad ±${f.tolerancia} %`);
  return partes.join(" · ");
}

export type MuestreoResumen = {
  clave: string;
  complex: string | null;
  dia: string;
  corral: string;
  categoria: CategoriaAve;
  edad: number | null;
  linea: string | null;
  verificadores: string[];
  /** Aves pesadas de una en una: la base de la desviación, el CV y la uniformidad. */
  avesIndividuales: number;
  /** Aves que entraron en lecturas de varias juntas (solo cuentan para el promedio). */
  avesAgrupadas: number;
  totalAves: number;
  /** Estadística sobre los pesos individuales. */
  stats: ResumenPesos;
  /** Promedio de TODAS las aves, ponderando cada lectura por las aves que representa. */
  promedioTotal: number | null;
};

export type GalponResumen = {
  complexGalpon: string;
  plantelCodigo: string;
  plantelNombre: string;
  campania: string;
  galpon: string;
  categorias: CategoriaAve[];
  edades: number[];
  primerDia: string;
  ultimoDia: string;
  muestreos: MuestreoResumen[];
  avesIndividuales: number;
  avesAgrupadas: number;
  totalAves: number;
  stats: ResumenPesos;
  promedioTotal: number | null;
  /** Solo cuando el galpón mezcla categorías: el resumen de cada una por separado. */
  porCategoria: { categoria: CategoriaAve; totalAves: number; stats: ResumenPesos; promedioTotal: number | null }[];
};

/** Día local de la captura (la granja está en Perú), en formato ordenable. */
function diaLima(d: Date): string {
  return d.toLocaleDateString("en-CA", { timeZone: "America/Lima" });
}

function promedioPonderado(lecturas: { peso: number; aves: number }[]): number | null {
  const aves = lecturas.reduce((a, l) => a + l.aves, 0);
  if (aves === 0) return null;
  return lecturas.reduce((a, l) => a + l.peso * l.aves, 0) / aves;
}

export async function construirResumenGalpones(
  user: SessionUser | null,
  f: FiltrosResumenGalpon
): Promise<GalponResumen[]> {
  const where: Prisma.RegistroPesoPreventaWhereInput = {
    tipoMuestreo: "PREVENTA",
    pesoGramos: { not: null },
  };
  if (user?.role === "VERIFICADOR") where.verificadorId = user.id;
  if (f.plantelId) where.plantelId = f.plantelId;
  if (f.desde || f.hasta) {
    where.fechaHora = {};
    if (f.desde) where.fechaHora.gte = new Date(f.desde);
    if (f.hasta) where.fechaHora.lte = new Date(f.hasta + "T23:59:59");
  }

  const registros = await prisma.registroPesoPreventa.findMany({
    where,
    orderBy: { fechaHora: "asc" },
    select: {
      campania: true,
      galpon: true,
      corral: true,
      categoria: true,
      pesoGramos: true,
      fechaHora: true,
      complex: true,
      edad: true,
      linea: true,
      nAvesPorPesada: true,
      plantel: { select: { codigo: true, nombre: true } },
      verificador: { select: { nombre: true } },
    },
  });

  type Lectura = { peso: number; aves: number; individual: boolean };
  type Acum = {
    lecturas: Lectura[];
    verificadores: Set<string>;
    edad: number | null;
    linea: string | null;
    complex: string | null;
    dia: string;
    corral: string;
    categoria: CategoriaAve;
    galponClave: string;
    plantelCodigo: string;
    plantelNombre: string;
    campania: string;
    galpon: string;
  };

  const muestreos = new Map<string, Acum>();
  for (const r of registros) {
    if (r.pesoGramos == null) continue;
    const dia = diaLima(r.fechaHora);
    const galponClave = buildComplexGalpon({
      plantelCodigo: r.plantel.codigo,
      campania: r.campania,
      galpon: r.galpon,
    });
    const clave = `${galponClave}|${r.categoria}|${r.corral}|${dia}`;
    const aves = r.nAvesPorPesada && r.nAvesPorPesada > 1 ? r.nAvesPorPesada : 1;
    const acum =
      muestreos.get(clave) ??
      {
        lecturas: [] as Lectura[],
        verificadores: new Set<string>(),
        edad: r.edad,
        linea: r.linea,
        complex: r.complex,
        dia,
        corral: r.corral,
        categoria: r.categoria,
        galponClave,
        plantelCodigo: r.plantel.codigo,
        plantelNombre: r.plantel.nombre ?? "",
        campania: r.campania ?? "",
        galpon: r.galpon,
      };
    acum.lecturas.push({ peso: r.pesoGramos, aves, individual: aves === 1 });
    acum.verificadores.add(r.verificador.nombre);
    if (acum.edad == null && r.edad != null) acum.edad = r.edad;
    muestreos.set(clave, acum);
  }

  const resumirMuestreo = (clave: string, a: Acum): MuestreoResumen => {
    const individuales = a.lecturas.filter((l) => l.individual).map((l) => l.peso);
    const agrupadas = a.lecturas.filter((l) => !l.individual).reduce((s, l) => s + l.aves, 0);
    return {
      clave,
      complex: a.complex,
      dia: a.dia,
      corral: a.corral,
      categoria: a.categoria,
      edad: a.edad,
      linea: a.linea,
      verificadores: [...a.verificadores].sort(),
      avesIndividuales: individuales.length,
      avesAgrupadas: agrupadas,
      totalAves: individuales.length + agrupadas,
      stats: resumirPesos(individuales, f.tolerancia),
      promedioTotal: promedioPonderado(a.lecturas),
    };
  };

  // Agrupar los muestreos por galpón.
  const galpones = new Map<string, { cabecera: Acum; muestreos: MuestreoResumen[]; lecturas: Lectura[] }>();
  for (const [clave, a] of muestreos) {
    const g = galpones.get(a.galponClave) ?? { cabecera: a, muestreos: [], lecturas: [] };
    g.muestreos.push(resumirMuestreo(clave, a));
    g.lecturas.push(...a.lecturas);
    galpones.set(a.galponClave, g);
  }

  const resultado: GalponResumen[] = [];
  for (const [complexGalpon, g] of galpones) {
    g.muestreos.sort((x, y) => x.dia.localeCompare(y.dia) || x.categoria.localeCompare(y.categoria) || x.corral.localeCompare(y.corral));
    const individuales = g.lecturas.filter((l) => l.individual).map((l) => l.peso);
    const agrupadas = g.lecturas.filter((l) => !l.individual).reduce((s, l) => s + l.aves, 0);
    const categorias = [...new Set(g.muestreos.map((m) => m.categoria))].sort() as CategoriaAve[];

    // Un galpón con machos y hembras mezcla dos poblaciones: el CV global sale inflado. Se
    // muestra igual (es lo que pidieron), pero acompañado del desglose por categoría.
    const porCategoria =
      categorias.length > 1
        ? categorias.map((categoria) => {
            const ms = g.muestreos.filter((m) => m.categoria === categoria);
            const lecturasCat = [...muestreos.entries()]
              .filter(([, a]) => a.galponClave === complexGalpon && a.categoria === categoria)
              .flatMap(([, a]) => a.lecturas);
            return {
              categoria,
              totalAves: ms.reduce((s, m) => s + m.totalAves, 0),
              stats: resumirPesos(lecturasCat.filter((l) => l.individual).map((l) => l.peso), f.tolerancia),
              promedioTotal: promedioPonderado(lecturasCat),
            };
          })
        : [];

    resultado.push({
      complexGalpon,
      plantelCodigo: g.cabecera.plantelCodigo,
      plantelNombre: g.cabecera.plantelNombre,
      campania: g.cabecera.campania,
      galpon: g.cabecera.galpon,
      categorias,
      edades: [...new Set(g.muestreos.map((m) => m.edad).filter((e): e is number => e != null))].sort((a, b) => a - b),
      primerDia: g.muestreos[0].dia,
      ultimoDia: g.muestreos[g.muestreos.length - 1].dia,
      muestreos: g.muestreos,
      avesIndividuales: individuales.length,
      avesAgrupadas: agrupadas,
      totalAves: individuales.length + agrupadas,
      stats: resumirPesos(individuales, f.tolerancia),
      promedioTotal: promedioPonderado(g.lecturas),
      porCategoria,
    });
  }

  // Los galpones más recientes primero.
  resultado.sort((a, b) => b.ultimoDia.localeCompare(a.ultimoDia) || a.complexGalpon.localeCompare(b.complexGalpon));
  return resultado;
}

export const CATEGORIA_LABEL: Record<CategoriaAve, string> = { MACHO: "Macho", HEMBRA: "Hembra", MEDIANO: "Mediano" };
