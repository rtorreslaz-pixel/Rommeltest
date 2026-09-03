import { redirect } from "next/navigation";
import Link from "next/link";
import { prisma } from "@/lib/db";
import { getCurrentUser } from "@/lib/auth";
import {
  CATEGORIA_LABEL,
  construirResumenGalpones,
  describirFiltrosResumen,
  leerFiltrosResumen,
  queryDeFiltrosResumen,
  type GalponResumen,
  type MuestreoResumen,
} from "@/lib/resumen-galpon";
import type { ResumenPesos } from "@/lib/estadisticas-peso";

// Hoja de resumen por galpón del pesaje de preventa: un bloque por complex hasta galpón
// (Plantel-Campaña-Galpón) con sus totales, y dentro cada muestreo (corral · categoría · día)
// con promedio, desviación, CV y uniformidad. Las definiciones están en lib/estadisticas-peso.

function kg(g: number | null): string {
  return g == null ? "—" : (g / 1000).toFixed(3);
}
function pct(v: number | null): string {
  return v == null ? "—" : v.toFixed(1) + " %";
}
function fecha(dia: string): string {
  const [a, m, d] = dia.split("-");
  return `${d}/${m}/${a}`;
}

/** Color de apoyo: CV bajo y uniformidad alta son lo deseable en engorde. */
function claseCv(cv: number | null): string {
  if (cv == null) return "text-slate-400";
  return cv <= 8 ? "text-green-700" : cv <= 12 ? "text-amber-700" : "text-red-700";
}
function claseUniformidad(u: number | null): string {
  if (u == null) return "text-slate-400";
  return u >= 80 ? "text-green-700" : u >= 70 ? "text-amber-700" : "text-red-700";
}

export default async function ResumenGalponPage({
  searchParams,
}: {
  searchParams: Promise<{ desde?: string; hasta?: string; plantel?: string; tolerancia?: string }>;
}) {
  const user = await getCurrentUser();
  if (!user) redirect("/login");

  const filtros = leerFiltrosResumen(await searchParams);
  const qs = queryDeFiltrosResumen(filtros);

  const [galpones, planteles] = await Promise.all([
    construirResumenGalpones(user, filtros),
    prisma.plantel.findMany({ orderBy: { codigo: "asc" }, select: { id: true, codigo: true } }),
  ]);
  const nombrePlantel = planteles.find((p) => p.id === filtros.plantelId)?.codigo;

  const totalAves = galpones.reduce((a, g) => a + g.totalAves, 0);
  const totalMuestreos = galpones.reduce((a, g) => a + g.muestreos.length, 0);

  return (
    <div>
      <div className="mb-1 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-bold text-slate-900">Resumen por galpón</h1>
        <div className="flex items-center gap-2">
          <a
            href={`/api/resumen-galpon/export?${qs ? qs + "&" : ""}formato=xlsx`}
            download
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
          >
            Descargar Excel
          </a>
          <a href={`/api/resumen-galpon/export?${qs}`} download className="text-xs font-semibold text-slate-400 hover:text-slate-600">
            CSV
          </a>
        </div>
      </div>
      <p className="mb-4 text-sm text-slate-500">
        Pesaje de preventa agrupado por <span className="font-mono">Plantel-Campaña-Galpón</span>. Cada fila es un
        muestreo (corral · sexo · día). Promedio, desviación, CV y uniformidad se calculan sobre las aves pesadas de
        una en una; las pesadas en grupo solo entran al promedio.
      </p>

      <form method="get" className="mb-2 flex flex-wrap items-end gap-3 rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200">
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Desde
          <input type="date" name="desde" defaultValue={filtros.desde} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800" />
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Hasta
          <input type="date" name="hasta" defaultValue={filtros.hasta} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800" />
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Plantel
          <select name="plantel" defaultValue={filtros.plantelId} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800">
            <option value="">Todos</option>
            {planteles.map((p) => (
              <option key={p.id} value={p.id}>{p.codigo}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Uniformidad ±
          <select name="tolerancia" defaultValue={String(filtros.tolerancia)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800">
            <option value="10">10 %</option>
            <option value="15">15 %</option>
          </select>
        </label>
        <button type="submit" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-hover">
          Filtrar
        </button>
        {qs !== "" && (
          <Link href="/resumen-galpon" className="px-2 py-2 text-sm font-semibold text-slate-500 hover:text-slate-700">
            Limpiar
          </Link>
        )}
      </form>
      <p className="mb-6 text-xs text-slate-400">La descarga incluye {describirFiltrosResumen(filtros, nombrePlantel)}.</p>

      <div className="mb-6 grid grid-cols-3 gap-3">
        <Tarjeta titulo="Galpones" valor={String(galpones.length)} />
        <Tarjeta titulo="Muestreos" valor={String(totalMuestreos)} />
        <Tarjeta titulo="Aves pesadas" valor={totalAves.toLocaleString("es-PE")} />
      </div>

      {galpones.length === 0 ? (
        <div className="rounded-xl bg-white p-8 text-center text-sm text-slate-500 shadow-sm ring-1 ring-slate-200">
          No hay pesajes de preventa con estos filtros.
        </div>
      ) : (
        <div className="space-y-6">
          {galpones.map((g) => (
            <BloqueGalpon key={g.complexGalpon} g={g} tolerancia={filtros.tolerancia} />
          ))}
        </div>
      )}
    </div>
  );
}

function Tarjeta({ titulo, valor }: { titulo: string; valor: string }) {
  return (
    <div className="rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">{titulo}</div>
      <div className="mt-1 text-2xl font-bold text-slate-900">{valor}</div>
    </div>
  );
}

function Indicador({ titulo, valor, clase, nota }: { titulo: string; valor: string; clase?: string; nota?: string }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-wide text-slate-500">{titulo}</div>
      <div className={`text-lg font-bold ${clase ?? "text-slate-900"}`}>{valor}</div>
      {nota && <div className="text-[11px] text-slate-400">{nota}</div>}
    </div>
  );
}

function BloqueGalpon({ g, tolerancia }: { g: GalponResumen; tolerancia: number }) {
  return (
    <section className="overflow-hidden rounded-xl bg-white shadow-sm ring-1 ring-slate-200">
      <div className="border-b border-slate-100 bg-slate-50 px-4 py-3">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <div>
            <div className="font-mono text-base font-bold text-slate-900">{g.complexGalpon}</div>
            <div className="text-xs text-slate-500">
              {[
                g.plantelNombre,
                `campaña ${g.campania || "—"}`,
                `galpón ${g.galpon}`,
                g.categorias.map((c) => CATEGORIA_LABEL[c]).join(" + "),
                g.edades.length > 0 ? `edad ${g.edades.join(", ")} d` : "",
                g.primerDia === g.ultimoDia ? fecha(g.primerDia) : `${fecha(g.primerDia)} – ${fecha(g.ultimoDia)}`,
              ]
                .filter(Boolean)
                .join(" · ")}
            </div>
          </div>
          <div className="text-xs text-slate-500">
            {g.muestreos.length} muestreo(s) · {g.totalAves} aves
            {g.avesAgrupadas > 0 && (
              <span className="ml-1 text-amber-700" title="Pesadas en grupo: entran al promedio pero no al CV ni a la uniformidad">
                ({g.avesAgrupadas} en grupo)
              </span>
            )}
          </div>
        </div>
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-6">
          <Indicador titulo="Promedio" valor={`${kg(g.promedioTotal)} kg`} />
          <Indicador titulo="Desv. est." valor={kg(g.stats.desviacion)} nota={g.stats.desviacion != null ? "kg" : undefined} />
          <Indicador titulo="CV" valor={pct(g.stats.cv)} clase={claseCv(g.stats.cv)} />
          <Indicador titulo={`Uniformidad ±${tolerancia}%`} valor={pct(g.stats.uniformidad)} clase={claseUniformidad(g.stats.uniformidad)} />
          <Indicador titulo="Mínimo" valor={`${kg(g.stats.minimo)} kg`} />
          <Indicador titulo="Máximo" valor={`${kg(g.stats.maximo)} kg`} />
        </div>
        {g.porCategoria.length > 0 && (
          <div className="mt-3 flex flex-wrap gap-4 text-xs text-slate-600">
            {g.porCategoria.map((c) => (
              <div key={c.categoria} className="rounded-lg bg-white px-3 py-1.5 ring-1 ring-slate-200">
                <span className="font-semibold">{CATEGORIA_LABEL[c.categoria]}</span>: {c.totalAves} aves · prom.{" "}
                {kg(c.promedioTotal)} kg · CV <span className={claseCv(c.stats.cv)}>{pct(c.stats.cv)}</span> · unif.{" "}
                <span className={claseUniformidad(c.stats.uniformidad)}>{pct(c.stats.uniformidad)}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-white text-left text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2 font-medium">Día</th>
              <th className="px-3 py-2 font-medium">Corral</th>
              <th className="px-3 py-2 font-medium">Sexo</th>
              <th className="px-3 py-2 font-medium">Edad</th>
              <th className="px-3 py-2 text-right font-medium">Aves</th>
              <th className="px-3 py-2 text-right font-medium">Promedio</th>
              <th className="px-3 py-2 text-right font-medium">Desv.</th>
              <th className="px-3 py-2 text-right font-medium">CV</th>
              <th className="px-3 py-2 text-right font-medium">Unif.</th>
              <th className="px-3 py-2 text-right font-medium">Mín.</th>
              <th className="px-3 py-2 text-right font-medium">Máx.</th>
              <th className="px-3 py-2 font-medium">Verificador</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {g.muestreos.map((m) => (
              <FilaMuestreo key={m.clave} m={m} />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function FilaMuestreo({ m }: { m: MuestreoResumen }) {
  const s: ResumenPesos = m.stats;
  return (
    <tr className="hover:bg-slate-50">
      <td className="whitespace-nowrap px-3 py-2">{fecha(m.dia)}</td>
      <td className="px-3 py-2 font-semibold">{m.corral}</td>
      <td className="px-3 py-2">{CATEGORIA_LABEL[m.categoria]}</td>
      <td className="px-3 py-2">{m.edad != null ? `${m.edad} d` : "—"}</td>
      <td className="px-3 py-2 text-right tabular-nums">
        {m.totalAves}
        {m.avesAgrupadas > 0 && <span className="ml-1 text-xs text-amber-700">({m.avesAgrupadas} grupo)</span>}
      </td>
      <td className="px-3 py-2 text-right font-semibold tabular-nums">{kg(m.promedioTotal)}</td>
      <td className="px-3 py-2 text-right tabular-nums text-slate-600">{kg(s.desviacion)}</td>
      <td className={`px-3 py-2 text-right font-semibold tabular-nums ${claseCv(s.cv)}`}>{pct(s.cv)}</td>
      <td className={`px-3 py-2 text-right font-semibold tabular-nums ${claseUniformidad(s.uniformidad)}`}>{pct(s.uniformidad)}</td>
      <td className="px-3 py-2 text-right tabular-nums text-slate-600">{kg(s.minimo)}</td>
      <td className="px-3 py-2 text-right tabular-nums text-slate-600">{kg(s.maximo)}</td>
      <td className="whitespace-nowrap px-3 py-2 text-slate-500">{m.verificadores.join(", ")}</td>
    </tr>
  );
}
