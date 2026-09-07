package com.rommel.scaleprototype.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.migration.Migration

@Database(
    entities = [RegistroPeso::class, SacaMuestreo::class, SacaPesada::class, PlanItem::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun registroPesoDao(): RegistroPesoDao

    abstract fun sacaDao(): SacaDao

    abstract fun planDao(): PlanDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // Filas existentes no tienen campania -- se quedan en '' y simplemente
        // re-empiezan su conteo de numeroAve cuando se les asigne una campania real.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN campania TEXT NOT NULL DEFAULT ''")
            }
        }

        // Columnas de calidad por ave, todas nullable -- filas existentes quedan sin
        // evaluar (NULL), igual que cualquier ave nueva donde no se activa "Evaluar calidad".
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN tieneHematoma INTEGER")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN tieneDefectoSeleccion INTEGER")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN gradoPododermatitis INTEGER")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN gradoRasguno INTEGER")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN pigmentacion INTEGER")
            }
        }

        // Campos de cabecera del lote: edad en días, línea genética, lote (J/A) y N° aves
        // por pesada. Todos opcionales para filas existentes (null / default 1).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN edad INTEGER")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN linea TEXT")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN lote TEXT")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN nAvesPorPesada INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Quién creó cada registro (para advertir si otro usuario va a subir pendientes
        // ajenos). Filas existentes quedan NULL: dueño desconocido, no se advierte.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN verificadorId TEXT")
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN verificadorNombre TEXT")
            }
        }

        // Tipo de muestreo: PREVENTA (pesaje) o CALIDAD (solo calidad, sin peso). Filas
        // existentes quedan como PREVENTA, que es lo que eran.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE registro_peso ADD COLUMN tipoMuestreo TEXT NOT NULL DEFAULT 'PREVENTA'")
            }
        }

        // Muestreo de saca (jabas) con su cola de sincronización propia. Tablas nuevas: no
        // toca nada de lo que ya había en el teléfono.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saca_muestreo (" +
                        "id TEXT NOT NULL PRIMARY KEY, plantelId TEXT NOT NULL, plantelCodigo TEXT NOT NULL, " +
                        "campania TEXT NOT NULL, galpon TEXT NOT NULL, categoria TEXT NOT NULL, edad INTEGER, " +
                        "avesPorJaba INTEGER NOT NULL, taraGramosPorJaba REAL NOT NULL, tipoJaba TEXT, " +
                        "fechaEpochMillis INTEGER NOT NULL, verificadorId TEXT, verificadorNombre TEXT, " +
                        "synced INTEGER NOT NULL DEFAULT 0, createdAtEpochMillis INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saca_pesada (" +
                        "id TEXT NOT NULL PRIMARY KEY, muestreoId TEXT NOT NULL, numJabas INTEGER NOT NULL, " +
                        "pesoBrutoGramos REAL NOT NULL, pesoNetoGramos REAL NOT NULL, avesTotal INTEGER NOT NULL, " +
                        "promedioGramos REAL NOT NULL, fechaHoraEpochMillis INTEGER NOT NULL)"
                )
            }
        }

        // El corral ("lado") entra al muestreo de saca para poder cruzarlo con preventa por el
        // complex completo. Las filas anteriores quedan con corral vacío.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saca_muestreo ADD COLUMN corral TEXT NOT NULL DEFAULT ''")
            }
        }

        // Plan diario de muestreo (una fila por corral a muestrear), con su cola de
        // sincronización. Tabla nueva: no toca la cola de pesos ni la de saca.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS plan_item (" +
                        "id TEXT NOT NULL PRIMARY KEY, fecha TEXT NOT NULL, plantelId TEXT NOT NULL, " +
                        "plantelCodigo TEXT NOT NULL, campania TEXT NOT NULL, galpon TEXT NOT NULL, " +
                        "corral TEXT NOT NULL, categoria TEXT NOT NULL, edad INTEGER, tipoMuestreo TEXT NOT NULL, " +
                        "linea TEXT, lote TEXT, agrupamiento TEXT NOT NULL, circuito TEXT, orden INTEGER NOT NULL, " +
                        "estado TEXT NOT NULL, verificadorId TEXT, borrado INTEGER NOT NULL DEFAULT 0, " +
                        "synced INTEGER NOT NULL DEFAULT 0, createdAtEpochMillis INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scale-prototype.db",
                    // Sin fallbackToDestructiveMigration(): un futuro cambio de esquema
                    // debe ir por una Migration real, no borrar la cola de un verificador.
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                )
                    .build().also { instance = it }
            }
        }
    }
}
