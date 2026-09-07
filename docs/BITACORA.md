# Bitácora de contexto y decisiones

> Documento de **handoff**: captura el estado, las decisiones y los "trucos" del
> proyecto para retomar en cualquier sesión (o persona) sin depender del historial
> de chat. Actualizar al tomar decisiones o cambiar despliegues.
> Última actualización: 15/07/2026.

## 1. Qué es el proyecto
**Sistema de Calidad y Pesaje** (marca genérica; antes "San Fernando"): control de
calidad del pollo en el cliente + pesaje preventa en granja. Omnicanal:
- **Web** (Next.js 16 / React 19 / TS) — supervisión, calidad en cliente, indicadores, admin.
- **App Android** (Kotlin, `android-scale-prototype/`) — pesaje en granja con báscula
  Bluetooth, offline con cola de sincronización.
- Ambas comparten base de datos y el contrato `/api/mobile/*` (ver `INTEGRATION.md`).

## 2. Repositorio y ramas
- Repo: `github.com/rtorreslaz-pixel/SFcalidad` (público).
- **Una sola rama: `main`** (todo consolidado; las ramas `claude/*` se borraron).
- Trabajar siempre sobre `main`. Cambios grandes → rama + ambiente staging, luego merge.

## 3. Despliegues (Railway)
| | Producción | Demo |
|---|---|---|
| Proyecto | `san-fernando-produccion` | `san-fernando-demo` |
| Servicio | `web` | `web` |
| URL | `sfcaliprod.up.railway.app` | `sfcalidemo.up.railway.app` |
| Volumen | **Sí** (`/data`) | No |
| `DATABASE_URL` | `file:/data/dev.db` | `file:/app/dev.db` |
| `DISABLE_WAL` | `true` | — |
| `SEED_DEMO` / `DEMO_MODE` | no | `true` / `true` |
| Datos | Reales, persistentes | Prueba, se re-siembran cada deploy |
| Ingreso | `supervisor@avicola.com` / `11223344` | `supervisor@avicola.com` / `demo1234` |

- Ambos **auto-despliegan desde `main`** al hacer push.
- Prototipo interactivo público (sin login): `…/prototipo`.
- ⚠️ El **Trial de Railway** estuvo casi agotado ($2.41). Si se apagan los servicios, revisar el plan.

## 4. "Gotchas" del despliegue (esto vale oro)
- **Puerto 8080**: el dominio de Railway debe apuntar al puerto **8080** (el `PORT` que inyecta). Si el dominio apunta a otro (ej. 3000), da **502** aunque la app esté "Ready".
- **No dejar `Dockerfile` en la raíz**: Railway lo usaría en vez de su builder y **saltaría `db:setup`** (migraciones + seed) → la base no se crea → `SQLITE_CANTOPEN`. El Dockerfile vive en `migration-spike/`.
- **`DATABASE_URL` absoluta en producción** (relativa `file:./dev.db` falla con `CANTOPEN` en el build de producción de Next).
- **`DISABLE_WAL=true`** cuando SQLite está en un **volumen** de Railway (WAL necesita archivos de memoria compartida que el volumen no soporta → `SQLITE_READONLY`).
- El **demo es efímero** (sin volumen): se re-siembra en cada deploy — ideal para mostrar, no para datos reales.
- **APK por CI**: workflow "Android APK" (`.github/workflows/android-apk.yml`) corre tests y publica `app-debug.apk` como artefacto en cada push que toque `android-scale-prototype/`. Debug→demo, release→prod.

## 5. Trabajo local (entorno de desarrollo)
- Si `node_modules` se corrompe: `npm install`; luego **`npx prisma generate`** (cliente en `src/generated/prisma`) y, si falla el binario nativo, `cd node_modules/better-sqlite3 && npx node-gyp rebuild --release`.
- Para **capturas de la web**: `next build` → `next start` con `DATABASE_URL=file:./algo.db DEMO_MODE=true`, sembrar con `SEED_DEMO=true npx tsx prisma/seed.ts`, crear un token de sesión y capturar con Playwright contra `localhost` (chromium en `/opt/pw-browsers`).
- `npm start` = `db:setup && next start`; `db:setup` = `migrate deploy && enable-wal.cjs && (seed || no-fatal)`.

## 6. Decisiones clave (y el porqué)
- **SQLite ahora → SQL Server corporativo** (validado en `migration-spike/`). Se mantiene SQLite hasta SQL Server para **no migrar dos veces** (Postgres se descartó por eso).
- **Dos ambientes** (demo/prod) en vez de uno; el demo es el que se comparte.
- **Monorepo** (web + Android juntos); contrato en `INTEGRATION.md`.
- **App móvil offline-first** con **sincronización idempotente** (UUID por registro).
- **Marca genérica** "Sistema de Calidad y Pesaje" en app + documentos (sin "San Fernando").
- **Base de datos = archivo SQLite en volumen** en prod; lenta por ser disco de red → otra razón para SQL Server.
- **Descargas filtradas por fecha**: toda exportación de la web acepta `desde`/`hasta` y usa el
  mismo filtro que la pantalla ("lo que se ve es lo que se descarga"). Los filtros viven en
  `src/lib/*-filtros.ts` para que pantalla y CSV no se desincronicen.
- **Resumen por galpón** (`/resumen-galpon`): pesaje de preventa agrupado por el complex hasta galpón
  (`Plantel-Campaña-Galpón`, `buildComplexGalpon`) y, dentro, un muestreo por corral·sexo·día. Promedio,
  desviación **muestral** (n−1), CV y uniformidad (±10 % por defecto, ±15 % opcional) en
  `src/lib/estadisticas-peso.ts` (puro, verificado contra `statistics` de Python). Las pesadas de varias
  aves juntas (`nAvesPorPesada > 1`) entran al promedio ponderado pero **no** a desviación/CV/uniformidad
  (promediar antes achica la dispersión); se muestran aparte como "en grupo". Un galpón con dos sexos
  muestra además el desglose por sexo. Excel/CSV en `/api/resumen-galpon/export`. **El Excel de la toma
  de muestras lleva este resumen como segunda pestaña** (mismos filtros), y el generador `crearXlsx`
  acepta varias hojas con nombre (`HojaXlsx[]`); el CSV sigue siendo una sola tabla.
- **Descarga en Excel**: los botones de la web piden `?formato=xlsx` y reciben un `.xlsx` real
  (`src/lib/xlsx.ts`, un ZIP con los XML de OOXML armado con el mismo `crearZip` de la evidencia;
  sin dependencias nuevas). El **CSV sigue siendo el predeterminado** del endpoint para no romper
  integraciones que ya lo consumen con `EXPORT_API_TOKEN` (Power BI).

## 7. Documentación en el repo
- `docs/DFU-Calidad-Pesaje.docx` — Documento Funcional (con capturas).
- `docs/PRD.md` + `docs/PRD.docx` — requisitos (RF/RNF, MoSCoW, criterios de aceptación).
- `docs/arquitectura-tecnica.png` — diagrama de arquitectura.
- `docs/guia-prueba-app-pesaje.md` — guía para probar la app.
- `docs/BD-Pesaje-diccionario-y-datos-de-prueba.xlsx` — diccionario de datos.
- `INTEGRATION.md` — contrato de la API móvil.
- `migration-spike/` — port a SQL Server (esquema + docker-compose + hallazgos).
- `public/prototipo.html` (`/prototipo`) — prototipo interactivo de la app móvil.

## 8. App móvil — estado
- Nombre visible: "SF Pesaje Granja". Versión iterada en `app/build.gradle.kts`.
- Protocolos de báscula: Ohaus Ranger, Genérico, T-Scale BW, **BIT PS 4.0 IoT**.
- **BIT PS 4.0 IoT — trama confirmada** con captura hex de la báscula física
  (`scale-2413-0214`): SPP envía `<estado><peso>` + CR/LF, con estado `U`=inestable /
  `S`=estable y peso **entero en gramos** (`S1460` = 1.460 kg). El parser ya la decodifica
  (con la heurística de texto como respaldo). **Pendiente menor**: confirmar contra el
  display físico que 1460 = 1.460 kg (unidad/decimal); si difiere, ajustar `DIVISOR_A_KG`.
- Otra unidad (`scale-2313-4001`) dio `read failed / socket timeout` al conectar: es a nivel
  Bluetooth (báscula ocupada/dormida/fuera de alcance o tomada por otra app), no de la app —
  otra báscula conectó bien. Solución: apagar/encender la báscula, desemparejar de la app de
  Bröring, re-emparejar y reintentar.
- Offline: cola local (Room `scale-prototype.db`), sync por lotes, alertas de pendientes y de atribución (otro usuario).
- **Plan del día — solo preventa / calidad** (app + web; la saca no lleva plan): el verificador arma en la app, antes de salir, la lista de corrales
  que va a muestrear, **corral por corral y cada uno con sus propios datos** (plantel, campaña, galpón,
  corral, sexo, edad, tipo, línea, lote, agrupamiento INDIVIDUAL/GRUPAL, circuito CV vivo / CB beneficiado);
  "Agregar y siguiente" arrastra los valores al corral siguiente como propuesta, y cualquier fila
  pendiente se puede editar desde la lista. **Estándar** (`EstandaresMuestreo`):
  preventa de a 1 ave (individual), calidad de a 3 aves (grupal); la app lo propone al elegir el tipo y
  sigue editable. Tocar una fila abre la
  configuración de captura ya llena. **Cumplimiento automático**: la fila pasa a HECHO cuando llega
  el muestreo que coincide (mismo día, plantel, galpón, corral, sexo y tipo), en el teléfono al
  finalizar y en el servidor al sincronizar, en cualquier orden de llegada. Web: `/planeamiento`
  con filtros por día y verificador, tarjetas de cumplimiento y Excel con el formato de
  planificación del usuario. Contrato en `INTEGRATION.md` (`/api/mobile/plan`). Room v9.
- **Continuidad entre corrales**: un galpón tiene **4 corrales (A–D)** y entre uno y otro solo cambia
  el corral. La pantalla de configuración recuerda el último muestreo (`ConfiguracionMuestreoStore`,
  SharedPreferences) y vuelve a aparecer llena, con el corral avanzado al siguiente **solo si se
  pulsó "Finalizar muestreo"** (por "Cambiar" se respeta el mismo). La edad no se arrastra de un día
  para otro. La configuración es por verificador: si entra otro en el mismo teléfono, no la ve.
- **El protocolo lo manda `ScaleConnectionManager`, no cada pantalla.** El fallo clásico era: se
  conectaba, luego se cambiaba el desplegable de protocolo, Ajustes mostraba el peso bien (leía el
  desplegable en vivo) y Pesaje quedaba en `-- kg` (usaba el congelado al conectar). Ahora el
  desplegable lo fija con `setProtocol()` y todas las pantallas leen de ahí. Además: si llegan
  tramas que el protocolo no entiende se adopta solo el que sí (`DeteccionProtocolo`, nunca el
  genérico), si el socket queda mudo se reconecta solo, y si aun así no entra peso la pantalla
  dice si es "no llegan datos" o "llegan y no se entienden" en vez de quedarse en `-- kg`.

## 9. Pendientes / roadmap
- **Fase 2 (productización)**: migrar a SQL Server, SSO/Active Directory, despliegue Docker corporativo, respaldos/SLA, Power BI corporativo.
- **Fase 3**: integración SAP; soporte de más marcas/BLE de báscula; validar parser BIT PS con báscula física.
- Opcionales sueltos: quitar la franja "demo" de las capturas del DFU; renombrar el repo a algo neutro; correo a TI (borrador en el historial / se puede regenerar).
