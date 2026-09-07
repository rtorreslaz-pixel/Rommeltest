"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { Role } from "@/generated/prisma/enums";

export default function NavLinks({ role }: { role: Role }) {
  const pathname = usePathname();

  const baseLinks =
    role === "SUPERVISOR"
      ? [
          { href: "/dashboard-bi", label: "Dashboard" },
          { href: "/dashboard-bi/engranaje", label: "Engranaje granja-clientes" },
          { href: "/dashboard/pesaje", label: "Monitor de pesaje" },
          { href: "/jornadas", label: "Jornadas" },
          { href: "/inspecciones", label: "Inspecciones" },
          { href: "/admin", label: "Catálogos" },
        ]
      : role === "VERIFICADOR"
        ? [
            { href: "/jornadas", label: "Mis jornadas" },
            { href: "/dashboard/pesaje", label: "Monitor de pesaje" },
          ]
        : role === "COMERCIAL"
          ? [
              { href: "/dashboard/pesaje", label: "Monitor de pesaje" },
            ]
          : [
              { href: "/dashboard-bi", label: "Dashboard" },
              { href: "/dashboard-bi/engranaje", label: "Engranaje granja-clientes" },
            ];

  // Resumen por galpón: promedio, CV y uniformidad del pesaje de preventa por complex hasta
  // galpón. Lo consultan todos los roles que ven pesaje.
  const conResumen = [
    ...baseLinks,
    // Plan diario de muestreo que arma el verificador en la app, con su cumplimiento.
    { href: "/planeamiento", label: "Planeamiento" },
    { href: "/resumen-galpon", label: "Resumen por galpón" },
  ];

  // Pesaje de saca: muestreo de jabas antes de la saca, comparado contra preventa.
  const conSaca = [...conResumen, { href: "/saca", label: "Pesaje de saca" }];

  // Reporte de apilamiento y ventilación de jabas: lo consultan calidad y jefatura (los
  // verificadores registran desde el enlace público, pero también pueden revisar lo enviado).
  const links =
    role === "COMERCIAL" ? conSaca : [...conSaca, { href: "/apilamiento-reporte", label: "Apilamiento de jabas" }];

  return (
    <div className="flex gap-1 overflow-x-auto pb-2 text-sm">
      {links.map((link) => {
        const isPrefixMatch = link.href !== "/dashboard" && pathname.startsWith(link.href);
        // Si otro link de la lista es un prefijo más específico que también matchea (p.ej.
        // "/dashboard-bi/engranaje" frente a "/dashboard-bi"), solo el más específico se
        // marca activo -- si no, ambos se resaltarían a la vez en esa subpágina.
        const hayHermanoMasEspecifico = links.some(
          (otro) => otro.href !== link.href && otro.href.startsWith(link.href) && pathname.startsWith(otro.href)
        );
        const active = pathname === link.href || (isPrefixMatch && !hayHermanoMasEspecifico);
        return (
          <Link
            key={link.href}
            href={link.href}
            className={`whitespace-nowrap rounded-md px-3 py-1.5 font-medium ${
              active
                ? "bg-white/20 text-white"
                : "text-white/75 hover:bg-white/10"
            }`}
          >
            {link.label}
          </Link>
        );
      })}
    </div>
  );
}
