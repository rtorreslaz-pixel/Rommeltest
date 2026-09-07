-- CreateTable
CREATE TABLE "PlanMuestreoItem" (
    "id" TEXT NOT NULL PRIMARY KEY,
    "verificadorId" TEXT NOT NULL,
    "fecha" TEXT NOT NULL,
    "plantelId" TEXT NOT NULL,
    "campania" TEXT NOT NULL,
    "galpon" TEXT NOT NULL,
    "corral" TEXT NOT NULL,
    "categoria" TEXT NOT NULL,
    "edad" INTEGER,
    "tipoMuestreo" TEXT NOT NULL DEFAULT 'PREVENTA',
    "linea" TEXT,
    "lote" TEXT,
    "agrupamiento" TEXT NOT NULL DEFAULT 'INDIVIDUAL',
    "circuito" TEXT,
    "orden" INTEGER NOT NULL DEFAULT 0,
    "estado" TEXT NOT NULL DEFAULT 'PENDIENTE',
    "cumplidoEn" DATETIME,
    "complex" TEXT,
    "createdAt" DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" DATETIME NOT NULL,
    CONSTRAINT "PlanMuestreoItem_verificadorId_fkey" FOREIGN KEY ("verificadorId") REFERENCES "User" ("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "PlanMuestreoItem_plantelId_fkey" FOREIGN KEY ("plantelId") REFERENCES "Plantel" ("id") ON DELETE RESTRICT ON UPDATE CASCADE
);

-- CreateIndex
CREATE INDEX "PlanMuestreoItem_verificadorId_fecha_idx" ON "PlanMuestreoItem"("verificadorId", "fecha");

-- CreateIndex
CREATE INDEX "PlanMuestreoItem_plantelId_fecha_idx" ON "PlanMuestreoItem"("plantelId", "fecha");
