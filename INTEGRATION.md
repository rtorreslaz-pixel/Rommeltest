# Contrato de integración — App de Calidad (web) ↔ Bluetooth Scale App (Android)

Fuente de verdad compartida entre las dos conversaciones de desarrollo. Ambas apps
viven en este mismo repositorio y comparten la **misma base de datos**.

> **Si cambias algo de este contrato, actualiza este documento en el mismo commit.**

## 1. Reparto de responsabilidades

| Área | Dueño |
|------|-------|
| UI web, dashboards, BI, reportes, formatos, preventa/engranaje | **Web** |
| Esquema Prisma y **todas** las migraciones | **Web** (dueño único de la BD) |
| Lado **servidor** de `/api/mobile/*` | **Web** |
| Export / conexión BI | **Web** |
| App Android (`android-scale-prototype/`), balanza Bluetooth, UI móvil | **Android** |
| Lado **cliente** de `/api/mobile/*` | **Android** |

**Superficie compartida que hay que coordinar:** el contrato `/api/mobile/*` y el esquema de BD.

## 2. Ramas

- Todo el código (web + Android) vive en la rama **`main`** de este repositorio.
- **La base de datos y sus migraciones las lidera Web** (ver reglas abajo). Android consume
  el contrato `/api/mobile/*` sin tocar el esquema Prisma.

## 3. Reglas de cambio

1. **Todo cambio de esquema/migración pasa por Web.** Si Android necesita un campo nuevo,
   lo solicita y Web lo agrega + migra. Nunca crear migraciones desde la rama Android
   (evita migraciones en conflicto sobre la misma BD).
2. **El contrato `/api/mobile/*` no se rompe sin aviso.** Los cambios deben ser
   retrocompatibles o coordinarse explícitamente entre ambas conversaciones.
3. **Los campos que alimentan el BI/export no se renombran.**

## 4. Autenticación

Las rutas móviles (salvo `auth`) exigen el header:

```
Authorization: Bearer <apiToken>
```

- El `apiToken` se obtiene en `POST /api/mobile/auth` y se valida con `requireMobileUser()`
  (`src/lib/auth.ts`): busca el `User` por `apiToken`, que debe estar `activo`.
- El token es `randomBytes(32).base64url`, único por usuario (`User.apiToken @unique`).
- Se emite de forma perezosa: si el usuario aún no tiene token, el primer `auth` lo crea.
- El admin puede **rotar/revocar** el token desde `admin/usuarios` (al revocar, el celular
  queda 401 y debe re-autenticarse).
- No hay restricción de rol en las rutas móviles: basta un usuario activo con token válido.

## 5. Endpoints

### `POST /api/mobile/auth`
Login para obtener el token. **No** requiere Bearer.

Request:
```json
{ "email": "verificador1@avicola.com", "password": "..." }
```
Response `200`:
```json
{ "token": "<apiToken>", "user": { "id": "...", "nombre": "...", "email": "...", "role": "SUPERVISOR" } }
```
`role`: `SUPERVISOR` | `VERIFICADOR` | `JEFE` | `COMERCIAL` — usar para mostrar/ocultar la pantalla de Indicadores (solo `SUPERVISOR` y `ADMIN` la ven).

Errores: `400` faltan campos · `401` credenciales inválidas o usuario inactivo.

### `GET /api/mobile/catalogos`
Catálogo de planteles + tabla de pesos estándar. Requiere Bearer. Descargar una vez al iniciar la sesión del día.

Response `200`:
```json
{
  "planteles": [ { "id": "...", "codigo": "P006", "nombre": "...", "cliente": "AKIM" } ],
  "pesosEstandar": [
    { "linea": "Ross", "sexo": "MACHO",  "edadDias": 35, "pesoGramos": 2441 },
    { "linea": "Ross", "sexo": "HEMBRA", "edadDias": 35, "pesoGramos": 2038 }
  ]
}
```
`cliente` y `nombre` en planteles pueden ser `null`. `pesosEstandar` contiene la tabla STD completa (días 0–49 por defecto con datos Ross 308); vacío si aún no se ha cargado la tabla del cliente.

### `GET /api/mobile/numero-ave-max`
Devuelve el mayor `numeroAve` ya sincronizado para un corral concreto. El celular lo usa
como piso para su contador local (que se pierde si se reinstala la app). Requiere Bearer.

Query (todos obligatorios): `plantelId`, `campania`, `galpon`, `corral`, `categoria`.

Response `200`:
```json
{ "maxNumeroAve": 142 }
```
`maxNumeroAve` es `null` si no hay registros aún. Errores: `400` parámetros inválidos.

### `POST /api/mobile/live-weight`
Publica el **peso en vivo** (última lectura de balanza) del verificador, para el dashboard
de preventa en tiempo real. Upsert **único por verificador** (`LiveWeightReading`, PK =
`verificadorId`): solo se conserva la última lectura. Requiere Bearer.

Request:
```json
{
  "pesoGramos": 2310.5,
  "categoria": "MACHO",
  "plantelCodigo": "P006",
  "campania": "C1",
  "galpon": "12",
  "corral": "A"
}
```
Solo `pesoGramos` (number finito) es obligatorio; el resto es opcional/nullable.
Response `200`: `{ "ok": true }`. Errores: `400` `pesoGramos` inválido.

### `POST /api/mobile/registros`
Sincroniza por lotes los registros de peso por ave (`RegistroPesoPreventa`). Requiere Bearer.

Request:
```json
{
  "registros": [
    {
      "id": "uuid-v4-generado-en-el-celular",
      "plantelId": "...",
      "campania": "C1",
      "galpon": "12",
      "corral": "A",
      "categoria": "MACHO",
      "numeroAve": 143,
      "pesoGramos": 2305.0,
      "fechaHora": "2026-06-28T13:40:00.000Z",
      "tieneHematoma": false,
      "tieneDefectoSeleccion": false,
      "gradoPododermatitis": 0,
      "gradoRasguno": 1,
      "pigmentacion": 4,
      "edad": 42,
      "linea": "Ross",
      "lote": "J",
      "nAvesPorPesada": 1
    }
  ]
}
```

Reglas de cada registro:
- `id`: **UUID generado por el celular ANTES de sincronizar** (idempotencia). El upsert usa
  `update: {}`, es decir **inserta una sola vez**: reenviar el mismo `id` NO modifica la fila
  existente. A futuro: si Android necesita corregir un registro ya sincronizado, requiere
  un endpoint nuevo — hoy no se puede editar vía reenvío.
- `plantelId`: debe existir (si alguno no existe → `400`, no se ingiere nada).
- `categoria`: `MACHO` | `HEMBRA` | `MEDIANO`.
- `numeroAve`, `pesoGramos`: number.
- `fechaHora`: ISO-8601 parseable (hora de captura en campo, no de sync).
- `tieneHematoma`, `tieneDefectoSeleccion`: boolean | null.
- `gradoPododermatitis`, `gradoRasguno`: `0` | `1` | `2` | null (0 sin lesión, 1 leve, 2 grave).
- `pigmentacion`: entero `0`–`7` | null.
- `campania`: string | null.
- `edad`: entero ≥ 0 | null — días de vida del lote al momento de la pesada.
- `linea`: string | null — línea genética (ej. `"Ross"`, `"Cobb"`).
- `lote`: string | null — clasificación del lote (`"J"` Joven / `"A"` Adulto).
- `nAvesPorPesada`: entero > 0 | null — N° aves pesadas juntas por lectura de báscula.

Response `200`:
```json
{ "ingested": 1, "ids": ["uuid-v4-generado-en-el-celular"] }
```
Errores: `400` arreglo vacío, registro con campos inválidos, o `plantelId` inexistente.
El `complex` (`Plantel-Campaña-Galpón-Categoría-Corral`) lo calcula el **servidor**; el
celular no lo envía.

### `POST /api/mobile/plan` y `GET /api/mobile/plan`

Plan diario de muestreo **de preventa / calidad** (la saca no lleva plan): lo arma el verificador en la
app antes de salir (una fila por corral) y se sincroniza. **El cumplimiento es automático**: una fila pasa a `HECHO` cuando llega un
muestreo (`POST /api/mobile/registros`) del mismo verificador, mismo día de la granja
(`America/Lima`), mismo plantel, galpón (normalizado: `01` = `1`), corral (sin importar
mayúsculas), sexo y tipo de muestreo. El cruce se hace en ambos órdenes de llegada.

**POST** — idempotente por `id` (UUID generado en la app). Una fila ya `HECHO` no se pisa.
`borrar` elimina filas **pendientes propias**; las hechas se conservan.

```json
{
  "items": [{
    "id": "uuid", "fecha": "2026-09-07", "plantelId": "...", "campania": "2026",
    "galpon": "1", "corral": "A", "categoria": "HEMBRA", "edad": 35,
    "tipoMuestreo": "PREVENTA", "linea": "ROSS", "lote": "J",
    "agrupamiento": "GRUPAL", "avesPorPesada": 3, "circuito": "CV", "orden": 1
  }],
  "borrar": ["uuid-de-fila-pendiente"]
}
```

Respuesta: `{ "ingested": 1, "cumplidos": 0, "items": [{ "id": "uuid", "estado": "PENDIENTE" }] }`.
`403` si alguna fila pertenece a otro verificador. Enums: `agrupamiento` INDIVIDUAL|GRUPAL,
`circuito` CV (vivo)|CB (beneficiado), `tipoMuestreo` PREVENTA|CALIDAD, `estado` PENDIENTE|HECHO.
`avesPorPesada` (1-20) solo aplica en GRUPAL: cuántas aves van juntas en cada pesada; en
INDIVIDUAL el servidor lo guarda null.

**GET** `?fecha=yyyy-MM-dd` (hoy por defecto): las filas del verificador ese día con
`plantelCodigo`, `estado` y `cumplidoEn`, en el orden del plan. La app la usa para refrescar el
estado que calculó el servidor (que cruza contra todos los muestreos, no solo los del teléfono).

## 6. Modelos de BD compartidos

Definidos en `prisma/schema.prisma`. **No editar sin coordinar con Web (dueño de la BD).**

### `RegistroPesoPreventa`
Registro por ave pesada en granja. Campos clave: `id` (UUID de cliente, **sin** `@default`),
`plantelId`, `campania?`, `galpon`, `corral`, `categoria`, `numeroAve`, `pesoGramos`,
`fechaHora` (captura), `complex?`, `tipoMuestreo` (`PREVENTA` por defecto), metadatos del
lote opcionales (`edad?`, `linea?`, `lote?`, `nAvesPorPesada?`), criterios de calidad
opcionales por ave (`tieneHematoma?`, `tieneDefectoSeleccion?`, `gradoPododermatitis?`,
`gradoRasguno?`, `pigmentacion?`), `verificadorId`, `syncedAt`, `createdAt`.
Índices: `[plantelId, galpon, corral, categoria]`, `[verificadorId, fechaHora]`.

### `PlanMuestreoItem`

Fila del plan diario (ver endpoint `plan`): `verificadorId`, `fecha` (texto `yyyy-MM-dd`),
`plantelId`, `campania`, `galpon`, `corral`, `categoria`, `edad`, `tipoMuestreo`, `linea`, `lote`,
`agrupamiento`, `avesPorPesada`, `circuito`, `orden`, `estado`, `cumplidoEn`, `complex`. En la app es la tabla
Room `plan_item` (v9), con `synced` y `borrado` para su cola de sincronización.

### `PesoEstandar`
Tabla de pesos estándar por raza. Campos: `linea` (Ross/Cobb/etc.), `sexo` (MACHO|HEMBRA),
`edadDias` (0–49), `pesoGramos`. Unique `[linea, sexo, edadDias]`.
Sembrada con datos Ross 308 (Aviagen 2022) hasta que el cliente provea su tabla STD.
Accesible en `GET /api/mobile/catalogos → pesosEstandar[]`.

### `LiveWeightReading`
Última lectura de balanza por verificador. PK = `verificadorId` (una fila por usuario):
`pesoGramos`, `plantelCodigo?`, `campania?`, `galpon?`, `corral?`, `categoria?`, `complex?`,
`updatedAt`.

### `User.apiToken`
`String? @unique` — token Bearer del usuario para la API móvil.

### Enums
- `CategoriaAve`: `MACHO` | `HEMBRA` | `MEDIANO` (la app de calidad usa `SexoAve` = `MACHO`|`HEMBRA`).
- `TipoMuestreo`: `PREVENTA` | `CALIDAD` (`CALIDAD` reservado, no usado aún).

## 7. Cruce calidad granja ↔ calidad cliente (`complex`)

`buildComplexEntity()` (`src/lib/complex-entity.ts`) arma `Plantel-Campaña-Galpón-Categoría-Corral`,
mismo formato que `Inspeccion.complex`, para comparar el peso/calidad medido en granja contra
las evaluaciones de calidad del cliente del mismo lote (dashboard **engranaje**).

⚠️ `MEDIANO` se abrevia `MD` y **no** tiene equivalente en `SexoAve`, así que esos registros de
preventa no cruzan contra ninguna `Inspeccion.complex` — es esperado.
