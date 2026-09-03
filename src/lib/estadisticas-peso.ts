// Estadística de un muestreo de pesos (aves individuales), con las definiciones que usa la
// industria avícola:
//
// - Promedio: media aritmética de los pesos.
// - Desviación estándar MUESTRAL (n-1): el muestreo es una muestra del galpón, no el galpón.
// - CV%: desviación / promedio × 100. Cuanto más bajo, más parejo el lote.
// - Uniformidad%: porcentaje de aves cuyo peso cae dentro de ±tolerancia del promedio. El
//   estándar para pollo de engorde es ±10 %; algunas guías usan ±15 %, por eso es parámetro.
//
// Funciones puras, sin acceso a datos: la pantalla y la descarga las comparten.

export type ResumenPesos = {
  n: number;
  promedio: number | null;
  desviacion: number | null;
  cv: number | null;
  uniformidad: number | null;
  minimo: number | null;
  maximo: number | null;
};

export const TOLERANCIA_UNIFORMIDAD_DEFAULT = 10;

export function media(valores: number[]): number | null {
  if (valores.length === 0) return null;
  return valores.reduce((a, b) => a + b, 0) / valores.length;
}

/** Desviación estándar muestral (divisor n-1). Necesita al menos 2 valores. */
export function desviacionMuestral(valores: number[]): number | null {
  const n = valores.length;
  if (n < 2) return null;
  const m = media(valores)!;
  const sumaCuadrados = valores.reduce((a, v) => a + (v - m) * (v - m), 0);
  return Math.sqrt(sumaCuadrados / (n - 1));
}

/** Coeficiente de variación en porcentaje. */
export function coeficienteVariacion(valores: number[]): number | null {
  const m = media(valores);
  const d = desviacionMuestral(valores);
  if (m == null || d == null || m === 0) return null;
  return (d / m) * 100;
}

/**
 * Uniformidad: % de aves dentro de ±[toleranciaPct] del promedio. Con una sola ave no tiene
 * sentido (siempre daría 100 %), así que se exige n >= 2 igual que la desviación.
 */
export function uniformidad(valores: number[], toleranciaPct = TOLERANCIA_UNIFORMIDAD_DEFAULT): number | null {
  if (valores.length < 2) return null;
  const m = media(valores)!;
  if (m === 0) return null;
  const inferior = m * (1 - toleranciaPct / 100);
  const superior = m * (1 + toleranciaPct / 100);
  const dentro = valores.filter((v) => v >= inferior && v <= superior).length;
  return (dentro / valores.length) * 100;
}

export function resumirPesos(valores: number[], toleranciaPct = TOLERANCIA_UNIFORMIDAD_DEFAULT): ResumenPesos {
  return {
    n: valores.length,
    promedio: media(valores),
    desviacion: desviacionMuestral(valores),
    cv: coeficienteVariacion(valores),
    uniformidad: uniformidad(valores, toleranciaPct),
    minimo: valores.length ? Math.min(...valores) : null,
    maximo: valores.length ? Math.max(...valores) : null,
  };
}
