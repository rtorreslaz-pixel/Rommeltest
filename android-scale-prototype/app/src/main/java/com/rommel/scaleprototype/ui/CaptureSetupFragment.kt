package com.rommel.scaleprototype.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rommel.scaleprototype.data.AppDatabase
import com.rommel.scaleprototype.R
import com.rommel.scaleprototype.auth.AuthRepository
import com.rommel.scaleprototype.databinding.FragmentCaptureSetupBinding
import com.rommel.scaleprototype.net.ApiClient
import com.rommel.scaleprototype.net.ApiException
import com.rommel.scaleprototype.net.PlantelDto
import kotlinx.coroutines.launch

class CaptureSetupFragment : Fragment() {

    private var binding: FragmentCaptureSetupBinding? = null
    private var planteles: List<PlantelDto> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentCaptureSetupBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.spinnerNAvesPesada?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            (1..10).toList(),
        )
        binding?.spinnerLinea?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            LINEAS_GENETICAS,
        )
        binding?.spinnerCorral?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            ConfiguracionMuestreoStore.CORRALES + ConfiguracionMuestreoStore.CORRAL_OTRO,
        )
        binding?.spinnerCorral?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val esOtro = position == ConfiguracionMuestreoStore.CORRALES.size
                binding?.editCorralOtro?.visibility = if (esOtro) View.VISIBLE else View.GONE
                ocultarError()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // El aviso de error deja de tener sentido en cuanto se corrige el campo.
        binding?.editCampania?.doAfterTextChanged { ocultarError() }
        binding?.editGalpon?.doAfterTextChanged { ocultarError() }
        binding?.editEdad?.doAfterTextChanged { ocultarError() }

        // Estándar al cambiar el tipo a mano: pesaje de a una ave, calidad de a tres.
        binding?.radioGroupModo?.setOnCheckedChangeListener { _, checkedId ->
            val tipo = if (checkedId == R.id.radioModoCalidad) EstandaresMuestreo.TIPO_CALIDAD else EstandaresMuestreo.TIPO_PREVENTA
            binding?.spinnerNAvesPesada?.setSelection((EstandaresMuestreo.avesPorPesadaPara(tipo) - 1).coerceIn(0, 9))
        }

        loadPlanteles()
        warnIfStalePendingRecords()
        binding?.buttonStartCapture?.setOnClickListener { onStartCaptureClicked() }
    }

    /**
     * Deja la pantalla como quedó en el corral anterior. Solo el corral avanza al siguiente
     * (A→B→C→D), que es lo único que cambia dentro de un galpón. Se llama después de cargar los
     * planteles, porque hace falta el catálogo para poder seleccionar el que estaba en uso.
     */
    private fun restaurarUltimaConfiguracion() {
        val b = binding ?: return
        // Si se llegó desde el plan del día, manda la fila del plan: no hay nada que recordar.
        val planItemId = arguments?.getString(ARG_PLAN_ITEM_ID)
        if (planItemId != null) {
            llenarDesdePlan(planItemId)
            return
        }
        val verificadorId = AuthRepository(requireContext()).getVerificadorId()
        val cfg = ConfiguracionMuestreoStore.leer(requireContext(), verificadorId) ?: return

        val iPlantel = planteles.indexOfFirst { it.id == cfg.plantelId }
        if (iPlantel >= 0) b.spinnerPlantel.setSelection(iPlantel)
        b.editCampania.setText(cfg.campania)
        b.editGalpon.setText(cfg.galpon)

        // Si el corral anterior se dio por terminado, se propone el siguiente del galpón; si se
        // entró por "Cambiar" a mitad del muestreo, se deja el mismo.
        val completado = ConfiguracionMuestreoStore.consumirCorralCompletado(requireContext())
        val propuesto = if (completado) {
            ConfiguracionMuestreoStore.siguienteCorral(cfg.corral) ?: cfg.corral
        } else {
            cfg.corral
        }
        val iCorral = ConfiguracionMuestreoStore.CORRALES.indexOf(propuesto)
        if (iCorral >= 0) {
            b.spinnerCorral.setSelection(iCorral)
        } else if (propuesto.isNotEmpty()) {
            b.spinnerCorral.setSelection(ConfiguracionMuestreoStore.CORRALES.size) // "Otro…"
            b.editCorralOtro.setText(propuesto)
        }

        b.radioGroupCategoria.check(
            when (cfg.categoria) {
                "HEMBRA" -> R.id.radioHembra
                "MEDIANO" -> R.id.radioMediano
                else -> R.id.radioMacho
            }
        )
        // La edad envejece: si lo guardado es de otro día, se deja vacía para que la confirmen.
        if (cfg.dia == ConfiguracionMuestreoStore.hoy() && cfg.edad > 0) {
            b.editEdad.setText(cfg.edad.toString())
        }
        val iLinea = LINEAS_GENETICAS.indexOf(cfg.linea)
        if (iLinea >= 0) b.spinnerLinea.setSelection(iLinea)
        b.radioGroupLote.check(if (cfg.lote == "A") R.id.radioLoteA else R.id.radioLoteJ)
        b.radioGroupModo.check(if (cfg.soloCalidad) R.id.radioModoCalidad else R.id.radioModoPesaje)
        if (cfg.nAvesPorPesada in 1..10) b.spinnerNAvesPesada.setSelection(cfg.nAvesPorPesada - 1)

        mostrarCorralesYaMuestreados(cfg)
    }

    /**
     * La fila del plan trae todo el lote: solo queda confirmar (o ajustar las aves por pesada,
     * que el plan define como individual/grupal pero no cuántas).
     */
    private fun llenarDesdePlan(planItemId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val item = AppDatabase.getInstance(requireContext()).planDao().getItem(planItemId) ?: return@launch
            val b = binding ?: return@launch
            val iPlantel = planteles.indexOfFirst { it.id == item.plantelId }
            if (iPlantel >= 0) b.spinnerPlantel.setSelection(iPlantel)
            b.editCampania.setText(item.campania)
            b.editGalpon.setText(item.galpon)
            val iCorral = ConfiguracionMuestreoStore.CORRALES.indexOf(item.corral.uppercase())
            if (iCorral >= 0) {
                b.spinnerCorral.setSelection(iCorral)
            } else {
                b.spinnerCorral.setSelection(ConfiguracionMuestreoStore.CORRALES.size)
                b.editCorralOtro.setText(item.corral)
            }
            b.radioGroupCategoria.check(
                when (item.categoria) {
                    "HEMBRA" -> R.id.radioHembra
                    "MEDIANO" -> R.id.radioMediano
                    else -> R.id.radioMacho
                }
            )
            item.edad?.let { b.editEdad.setText(it.toString()) }
            val iLinea = LINEAS_GENETICAS.indexOf(item.linea)
            if (iLinea >= 0) b.spinnerLinea.setSelection(iLinea)
            b.radioGroupLote.check(if (item.lote == "A") R.id.radioLoteA else R.id.radioLoteJ)
            b.radioGroupModo.check(if (item.tipoMuestreo == "CALIDAD") R.id.radioModoCalidad else R.id.radioModoPesaje)
            // Estándar: preventa de a una ave, calidad de a tres. Si la fila del plan pide grupal
            // en un pesaje de preventa (fuera de estándar), se propone la última cantidad usada.
            val nAves = when {
                item.agrupamiento != "GRUPAL" -> EstandaresMuestreo.AVES_POR_PESADA_PREVENTA
                // El plan dice cuántas aves van juntas; si no lo trae, el estándar.
                item.avesPorPesada != null -> item.avesPorPesada
                item.tipoMuestreo == EstandaresMuestreo.TIPO_CALIDAD -> EstandaresMuestreo.AVES_POR_PESADA_CALIDAD
                else -> {
                    val cfg = ConfiguracionMuestreoStore.leer(requireContext(), AuthRepository(requireContext()).getVerificadorId())
                    cfg?.nAvesPorPesada?.takeIf { it > 1 } ?: EstandaresMuestreo.AVES_POR_PESADA_CALIDAD
                }
            }
            b.spinnerNAvesPesada.setSelection((nAves - 1).coerceIn(0, 9))
        }
    }

    /** "Hoy ya muestreaste: A, B" — para no repetir un corral ni saltarse otro. */
    private fun mostrarCorralesYaMuestreados(cfg: ConfiguracionMuestreo) {
        val plantelCodigo = planteles.firstOrNull { it.id == cfg.plantelId }?.codigo ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(requireContext()).registroPesoDao()
            val inicioDelDia = System.currentTimeMillis() - MILLIS_PER_DAY
            val hechos = dao.muestreosDelDia(inicioDelDia)
                .filter { it.plantelCodigo == plantelCodigo && it.galpon == cfg.galpon && it.campania == cfg.campania }
                .map { it.corral }
                .distinct()
            val vista = binding?.textCorralHechos ?: return@launch
            if (hechos.isEmpty()) {
                vista.visibility = View.GONE
            } else {
                vista.text = getString(R.string.corrales_ya_muestreados, hechos.sorted().joinToString(", "))
                vista.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Disciplina operativa: si hay registros sin subir desde hace más de 12 horas, se
     * alerta al entrar — la única copia está en este teléfono hasta que sincronice.
     */
    private fun warnIfStalePendingRecords() {
        viewLifecycleOwner.lifecycleScope.launch {
            val dao = AppDatabase.getInstance(requireContext()).registroPesoDao()
            val count = dao.countUnsynced()
            if (count == 0) return@launch
            val oldest = dao.oldestUnsyncedEpochMillis() ?: return@launch
            val horas = (System.currentTimeMillis() - oldest) / MILLIS_PER_HOUR
            if (horas < STALE_PENDING_THRESHOLD_HOURS) return@launch
            val antiguedad = if (horas >= 24) {
                resources.getQuantityString(R.plurals.stale_pending_days, (horas / 24).toInt(), horas / 24)
            } else {
                getString(R.string.stale_pending_hours, horas)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.stale_pending_title)
                .setMessage(getString(R.string.stale_pending_message, count, antiguedad))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun loadPlanteles() {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = ApiClient.getInstance(requireContext()).getCatalogos()
                planteles = response.planteles
                binding?.spinnerPlantel?.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    planteles.map { if (it.cliente != null) "${it.codigo} — ${it.cliente}" else it.codigo },
                )
                restaurarUltimaConfiguracion()
            } catch (e: ApiException) {
                if (e.code == 401) {
                    forceReLogin()
                } else if (!loadPlantelesFromCache()) {
                    showError(getString(R.string.error_load_planteles, e.message))
                }
            } catch (e: Exception) {
                // Sin señal (típico en granja): se usa el catálogo guardado de la última
                // sesión con internet, para poder configurar la jornada 100% offline.
                if (!loadPlantelesFromCache()) {
                    showError(getString(R.string.error_load_planteles, e.message ?: e.javaClass.simpleName))
                }
            } finally {
                setLoading(false)
            }
        }
    }

    /** Puebla el spinner con el catálogo guardado. Devuelve false si nunca se descargó uno. */
    private fun loadPlantelesFromCache(): Boolean {
        val cached = ApiClient.getInstance(requireContext()).getCatalogosOffline() ?: return false
        if (cached.planteles.isEmpty()) return false
        planteles = cached.planteles
        binding?.spinnerPlantel?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            planteles.map { if (it.cliente != null) "${it.codigo} — ${it.cliente}" else it.codigo },
        )
        restaurarUltimaConfiguracion()
        Toast.makeText(requireContext(), getString(R.string.offline_catalog_notice), Toast.LENGTH_LONG).show()
        return true
    }

    private fun onStartCaptureClicked() {
        val b = binding ?: return
        val plantel = planteles.getOrNull(b.spinnerPlantel.selectedItemPosition)
        val campania = b.editCampania.text.toString().trim()
        val galpon = b.editGalpon.text.toString().trim()
        val corral = corralElegido()
        val edadStr = b.editEdad.text.toString().trim()
        val linea = LINEAS_GENETICAS.getOrNull(b.spinnerLinea.selectedItemPosition) ?: LINEAS_GENETICAS.first()

        // Se nombra lo que falta: antes decía "completa todos los campos" y había que adivinar
        // cuál era, con el agravante de que el aviso se quedaba en pantalla al corregirlo.
        val faltantes = buildList {
            if (plantel == null) add(getString(R.string.label_plantel))
            if (campania.isEmpty()) add(getString(R.string.label_campania))
            if (galpon.isEmpty()) add(getString(R.string.label_galpon))
            if (corral.isEmpty()) add(getString(R.string.label_corral))
            if (edadStr.isEmpty()) add(getString(R.string.label_edad))
        }
        if (faltantes.isNotEmpty() || plantel == null) {
            showError(getString(R.string.error_setup_falta, faltantes.joinToString(", ")))
            return
        }

        val edad = edadStr.toIntOrNull()
        if (edad == null || edad <= 0) {
            showError(getString(R.string.error_setup_edad))
            return
        }
        ocultarError()

        val categoria = when (b.radioGroupCategoria.checkedRadioButtonId) {
            R.id.radioHembra -> "HEMBRA"
            R.id.radioMediano -> "MEDIANO"
            else -> "MACHO"
        }

        val lote = when (b.radioGroupLote.checkedRadioButtonId) {
            R.id.radioLoteA -> "A"
            else -> "J"
        }

        val nAvesPorPesada = b.spinnerNAvesPesada.selectedItemPosition + 1
        val soloCalidad = b.radioGroupModo.checkedRadioButtonId == R.id.radioModoCalidad

        // Se guarda para el siguiente corral: al volver, la pantalla aparece llena y solo hay
        // que confirmar (o cambiar) el corral que propone.
        ConfiguracionMuestreoStore.guardar(
            requireContext(),
            ConfiguracionMuestreo(
                verificadorId = AuthRepository(requireContext()).getVerificadorId() ?: "",
                plantelId = plantel.id,
                campania = campania,
                galpon = galpon,
                corral = corral,
                categoria = categoria,
                edad = edad,
                linea = linea,
                lote = lote,
                nAvesPorPesada = nAvesPorPesada,
                soloCalidad = soloCalidad,
                dia = ConfiguracionMuestreoStore.hoy(),
            ),
        )

        findNavController().navigate(
            R.id.action_captureSetup_to_capture,
            bundleOf(
                ARG_PLANTEL_ID to plantel.id,
                ARG_PLANTEL_CODIGO to plantel.codigo,
                ARG_CAMPANIA to campania,
                ARG_GALPON to galpon,
                ARG_CORRAL to corral,
                ARG_CATEGORIA to categoria,
                ARG_EDAD to edad,
                ARG_LINEA to linea,
                ARG_LOTE to lote,
                ARG_N_AVES_PESADA to nAvesPorPesada,
                ARG_SOLO_CALIDAD to soloCalidad,
                // Si el muestreo salió del plan, Captura marca la fila como hecha al finalizar.
                ARG_PLAN_ITEM_ID to arguments?.getString(ARG_PLAN_ITEM_ID),
            ),
        )
    }

    private fun setLoading(loading: Boolean) {
        binding?.progressSetup?.visibility = if (loading) View.VISIBLE else View.GONE
        binding?.buttonStartCapture?.isEnabled = !loading
    }

    /** Corral elegido: uno de los cuatro del galpón, o el que se escriba en "Otro…". */
    private fun corralElegido(): String {
        val b = binding ?: return ""
        val i = b.spinnerCorral.selectedItemPosition
        return if (i in ConfiguracionMuestreoStore.CORRALES.indices) {
            ConfiguracionMuestreoStore.CORRALES[i]
        } else {
            b.editCorralOtro.text.toString().trim().uppercase()
        }
    }

    private fun showError(message: String) {
        binding?.textSetupError?.text = message
        binding?.textSetupError?.visibility = View.VISIBLE
    }

    private fun ocultarError() {
        binding?.textSetupError?.visibility = View.GONE
    }

    // El catálogo respondió 401: el admin revocó o rotó el token desde /admin/usuarios.
    // No tiene sentido mostrar el error crudo -- se manda directo al login, igual que
    // CaptureFragment.onResume() hace cuando detecta la sesión muerta en pantalla.
    private fun forceReLogin() {
        AuthRepository(requireContext()).logout()
        ConfiguracionMuestreoStore.limpiar(requireContext())
        Toast.makeText(requireContext(), getString(R.string.session_revoked_message), Toast.LENGTH_LONG).show()
        findNavController().navigate(R.id.action_captureSetup_to_login)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        const val ARG_PLANTEL_ID = "plantelId"
        const val ARG_PLANTEL_CODIGO = "plantelCodigo"
        const val ARG_CAMPANIA = "campania"
        const val ARG_GALPON = "galpon"
        const val ARG_CORRAL = "corral"
        const val ARG_CATEGORIA = "categoria"
        const val ARG_EDAD = "edad"
        const val ARG_LINEA = "linea"
        const val ARG_LOTE = "lote"
        const val ARG_N_AVES_PESADA = "nAvesPorPesada"
        const val ARG_SOLO_CALIDAD = "soloCalidad"
        const val ARG_PLAN_ITEM_ID = "planItemId"

        /** Líneas genéticas disponibles (desplegable). Se guarda el texto tal cual en el registro. */
        val LINEAS_GENETICAS = listOf("ROSS", "COBB")

        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val STALE_PENDING_THRESHOLD_HOURS = 12L
    }
}
