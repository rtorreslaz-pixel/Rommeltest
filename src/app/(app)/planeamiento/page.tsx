import { redirect } from "next/navigation";
import Link from "next/link";
import { prisma } from "@/lib/db";
import { getCurrentUser } from "@/lib/auth";
import type { Prisma } from "@/generated/prisma/client";
import { CATEGORIA_LABEL } from "@/lib/resumen-galpon";
import { AGRUPAMIENTO_LABEL, CIRCUITO_LABEL, ESTADO_PLAN_LABEL, diaLima } from "@/lib/plan-muestreo";

// Planeamiento del muestreo: el plan diario que cada verificador arma en la app antes de salir,
// con su cumplimiento. Una fila = un corral a muestrear; pasa a HECHO sola cuando llega el
// muestreo que coincide. Mismo formato de columnas que el Excel de planificación del usuario.

function fecha(dia: string): string {
  const [a, m, d] = dia.split("-");
  return `${d}/${m}/${a}`;
}

export default async function PlaneamientoPage({
  searchParams,
}: {
  searchParams: Promise<{ fecha?: string; hasta?: string; verificador?: string }>;
}) {
  const user = await getCurrentUser();
  if (!user) redirect("/login");

  const params = await searchParams;
  const hoy = diaLima(new Date());
  const desde = /^\d{4}-\d{2}-\d{2}$/.test(params.fecha ?? "") ? params.fecha! : hoy;
  const hasta = /^\d{4}-\d{2}-\d{2}$/.test(params.hasta ?? "") ? params.hasta! : desde;
  const verificadorFiltro = user.role === "VERIFICADOR" ? user.id : (params.verificador ?? "");

  const where: Prisma.PlanMuestreoItemWhereInput = { fecha: { gte: desde, lte: hasta } };
  if (verificadorFiltro) where.verificadorId = verificadorFiltro;

  const [items, verificadores] = await Promise.all([
    prisma.planMuestreoItem.findMany({
      where,
      orderBy: [{ fecha: "asc" }, { verificadorId: "asc" }, { orden: "asc" }, { createdAt: "asc" }],
      include: { plantel: { select: { codigo: true } }, verificador: { select: { nombre: true } } },
    }),
    user.role === "VERIFICADOR"
      ? Promise.resolve([])
      : prisma.user.findMany({ where: { role: "VERIFICADOR", activo: true }, orderBy: { nombre: "asc" }, select: { id: true, nombre: true } }),
  ]);

  const hechas = items.filter((i) => i.estado === "HECHO").length;
  const pendientes = items.length - hechas;
  const cumplimiento = items.length > 0 ? (hechas / items.length) * 100 : null;

  const qs = new URLSearchParams();
  qs.set("fecha", desde);
  if (hasta !== desde) qs.set("hasta", hasta);
  if (verificadorFiltro && user.role !== "VERIFICADOR") qs.set("verificador", verificadorFiltro);
  const query = qs.toString();

  // Cumplimiento por verificador, para ver de un vistazo quién va cómo.
  const porVerificador = new Map<string, { nombre: string; total: number; hechas: number }>();
  for (const i of items) {
    const v = porVerificador.get(i.verificadorId) ?? { nombre: i.verificador.nombre, total: 0, hechas: 0 };
    v.total += 1;
    if (i.estado === "HECHO") v.hechas += 1;
    porVerificador.set(i.verificadorId, v);
  }

  return (
    <div>
      <div className="mb-1 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-bold text-slate-900">Planeamiento del muestreo de preventa / calidad</h1>
        <div className="flex items-center gap-2">
          <a href={`/api/planeamiento/export?${query}&formato=xlsx`} download className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50">
            Descargar Excel
          </a>
          <a href={`/api/planeamiento/export?${query}`} download className="text-xs font-semibold text-slate-400 hover:text-slate-600">
            CSV
          </a>
        </div>
      </div>
      <p className="mb-4 text-sm text-slate-500">
        Plan diario de <span className="font-semibold">pesaje de preventa y calidad</span> (la saca no lleva plan). Lo arma
        cada verificador en la app antes de salir a campo. Cada fila es un corral a muestrear y pasa a{" "}
        <span className="font-semibold">Hecho</span> sola cuando llega el muestreo que coincide (mismo día, plantel, galpón,
        corral, sexo y tipo). Estándar: preventa de a una ave (individual); calidad de a tres aves (grupal).
      </p>

      <form method="get" className="mb-6 flex flex-wrap items-end gap-3 rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200">
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Día
          <input type="date" name="fecha" defaultValue={desde} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800" />
        </label>
        <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
          Hasta (opcional)
          <input type="date" name="hasta" defaultValue={hasta !== desde ? hasta : ""} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800" />
        </label>
        {user.role !== "VERIFICADOR" && (
          <label className="flex flex-col gap-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
            Verificador
            <select name="verificador" defaultValue={verificadorFiltro} className="rounded-lg border border-slate-300 px-3 py-2 text-sm font-normal normal-case tracking-normal text-slate-800">
              <option value="">Todos</option>
              {verificadores.map((v) => (
                <option key={v.id} value={v.id}>{v.nombre}</option>
              ))}
            </select>
          </label>
        )}
        <button type="submit" className="rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white hover:bg-brand-hover">Ver</button>
        <Link href="/planeamiento" className="px-2 py-2 text-sm font-semibold text-slate-500 hover:text-slate-700">Hoy</Link>
      </form>

      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tarjeta titulo="Corrales planificados" valor={String(items.length)} />
        <Tarjeta titulo="Hechos" valor={String(hechas)} clase="text-green-700" />
        <Tarjeta titulo="Pendientes" valor={String(pendientes)} clase={pendientes > 0 ? "text-amber-700" : "text-slate-900"} />
        <Tarjeta titulo="Cumplimiento" valor={cumplimiento == null ? "—" : cumplimiento.toFixed(0) + " %"} clase={cumplimiento == null ? "" : cumplimiento >= 100 ? "text-green-700" : cumplimiento >= 60 ? "text-amber-700" : "text-red-700"} />
      </div>

      {porVerificador.size > 1 && (
        <div className="mb-6 flex flex-wrap gap-2 text-xs">
          {[...porVerificador.values()].map((v) => (
            <span key={v.nombre} className="rounded-full bg-white px-3 py-1.5 font-semibold text-slate-700 ring-1 ring-slate-200">
              {v.nombre}: {v.hechas}/{v.total}
            </span>
          ))}
        </div>
      )}

      {items.length === 0 ? (
        <div className="rounded-xl bg-white p-8 text-center text-sm text-slate-500 shadow-sm ring-1 ring-slate-200">
          No hay plan para {desde === hoy ? "hoy" : fecha(desde)}
          {hasta !== desde ? ` – ${fecha(hasta)}` : ""}. Los verificadores lo arman desde la app, en «Plan del día».
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl bg-white shadow-sm ring-1 ring-slate-200">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
              <tr>
                {hasta !== desde && <th className="px-3 py-2.5 font-medium">Fecha</th>}
                {!verificadorFiltro && <th className="px-3 py-2.5 font-medium">Verificador</th>}
                <th className="px-3 py-2.5 font-medium">Plantel</th>
                <th className="px-3 py-2.5 font-medium">Campaña</th>
                <th className="px-3 py-2.5 font-medium">Galpón</th>
                <th className="px-3 py-2.5 font-medium">Corral</th>
                <th className="px-3 py-2.5 font-medium">Sexo</th>
                <th className="px-3 py-2.5 font-medium">Edad</th>
                <th className="px-3 py-2.5 font-medium">Tipo de muestreo</th>
                <th className="px-3 py-2.5 font-medium">Línea</th>
                <th className="px-3 py-2.5 font-medium">Lote</th>
                <th className="px-3 py-2.5 font-medium">Agrupamiento</th>
                <th className="px-3 py-2.5 font-medium">Circuito</th>
                <th className="px-3 py-2.5 font-medium">Estado</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((i) => (
                <tr key={i.id} className={i.estado === "HECHO" ? "bg-green-50/40" : "hover:bg-slate-50"}>
                  {hasta !== desde && <td className="whitespace-nowrap px-3 py-2">{fecha(i.fecha)}</td>}
                  {!verificadorFiltro && <td className="whitespace-nowrap px-3 py-2 text-slate-500">{i.verificador.nombre}</td>}
                  <td className="px-3 py-2 font-semibold">{i.plantel.codigo}</td>
                  <td className="px-3 py-2">{i.campania}</td>
                  <td className="px-3 py-2">{i.galpon}</td>
                  <td className="px-3 py-2 font-semibold">{i.corral}</td>
                  <td className="px-3 py-2">{CATEGORIA_LABEL[i.categoria]}</td>
                  <td className="px-3 py-2">{i.edad != null ? `${i.edad} d` : "—"}</td>
                  <td className="px-3 py-2">{i.tipoMuestreo}</td>
                  <td className="px-3 py-2">{i.linea ?? "—"}</td>
                  <td className="px-3 py-2">{i.lote ?? "—"}</td>
                  <td className="px-3 py-2">{AGRUPAMIENTO_LABEL[i.agrupamiento]}</td>
                  <td className="px-3 py-2">{i.circuito ? CIRCUITO_LABEL[i.circuito] : "—"}</td>
                  <td className="px-3 py-2">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${i.estado === "HECHO" ? "bg-green-100 text-green-800" : "bg-amber-100 text-amber-800"}`}>
                      {ESTADO_PLAN_LABEL[i.estado]}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Tarjeta({ titulo, valor, clase }: { titulo: string; valor: string; clase?: string }) {
  return (
    <div className="rounded-xl bg-white p-4 shadow-sm ring-1 ring-slate-200">
      <div className="text-xs font-semibold uppercase tracking-wide text-slate-500">{titulo}</div>
      <div className={`mt-1 text-2xl font-bold ${clase || "text-slate-900"}`}>{valor}</div>
    </div>
  );
}
