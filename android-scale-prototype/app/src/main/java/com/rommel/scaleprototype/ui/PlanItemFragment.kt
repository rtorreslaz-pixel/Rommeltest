package com.rommel.scaleprototype.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.rommel.scaleprototype.R
import com.rommel.scaleprototype.auth.AuthRepository
import com.rommel.scaleprototype.data.AppDatabase
import com.rommel.scaleprototype.data.PlanItem
import com.rommel.scaleprototype.databinding.FragmentPlanItemBinding
import com.rommel.scaleprototype.net.ApiClient
import com.rommel.scaleprototype.net.PlantelDto
import com.rommel.scaleprototype.sync.SyncScheduler
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Agregar (o editar) UN corral del plan del día, con sus propios datos: sexo, edad, tipo,
 * línea, lote, agrupamiento y circuito son por corral, porque un galpón puede tener hembras en A
 * y machos en B, o un corral de otra línea.
 *
 * Para que no sea lento, "Agregar y siguiente" guarda la fila y deja el formulario listo para el
 * corral siguiente (A→B→C→D) con los mismos valores propuestos, que se cambian solo si toca. Al
 * abrir, se proponen los valores de la última fila agregada hoy.
 */
class PlanItemFragment : Fragment() {

    private var binding: FragmentPlanItemBinding? = null
    private var planteles: List<PlantelDto> = emptyList()

    /** Fila que se está editando (null = agregando). */
    private var editandoId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPlanItemBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        editandoId = arguments?.getString(ARG_PLAN_EDIT_ID)

        b.spinnerPlanLinea.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CaptureSetupFragment.LINEAS_GENETICAS,
        )
        b.spinnerPlanCorral.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            ConfiguracionMuestreoStore.CORRALES + ConfiguracionMuestreoStore.CORRAL_OTRO,
        )
        b.spinnerPlanCorral.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val esOtro = position == ConfiguracionMuestreoStore.CORRALES.size
                binding?.editPlanCorralOtro?.visibility = if (esOtro) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // Estándar: preventa de a una ave; calidad de a tres. Al cambiar el tipo se propone el
        // agrupamiento que corresponde (sigue editable por si un caso lo amerita).
        b.radioGroupPlanTipo.setOnCheckedChangeListener { _, checkedId ->
            val tipo = if (checkedId == R.id.radioPlanCalidad) EstandaresMuestreo.TIPO_CALIDAD else EstandaresMuestreo.TIPO_PREVENTA
            binding?.radioGroupPlanAgrupamiento?.check(
                if (EstandaresMuestreo.agrupamientoPara(tipo) == "GRUPAL") R.id.radioPlanGrupal else R.id.radioPlanIndividual
            )
        }

        if (editandoId != null) {
            b.textPlanItemTitulo.text = getString(R.string.title_plan_item_editar)
            b.buttonGuardarSeguir.visibility = View.GONE
            b.buttonGuardarPlan.text = getString(R.string.plan_guardar_editar)
        }
        b.buttonGuardarSeguir.setOnClickListener { guardar(seguir = true) }
        b.buttonGuardarPlan.setOnClickListener { guardar(seguir = false) }

        cargarPlanteles()
    }

    private fun cargarPlanteles() {
        viewLifecycleOwner.lifecycleScope.launch {
            val api = ApiClient.getInstance(requireContext())
            val respuesta = runCatching { api.getCatalogos() }.getOrNull() ?: api.getCatalogosOffline()
            if (respuesta == null) {
                mostrarError(getString(R.string.error_load_planteles, "sin catálogo guardado"))
                return@launch
            }
            planteles = respuesta.planteles
            binding?.spinnerPlanPlantel?.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                planteles.map { if (it.cliente != null) "${it.codigo} — ${it.cliente}" else it.codigo },
            )
            precargar()
        }
    }

    /**
     * Valores propuestos: la fila que se edita; si no, la última agregada hoy con el corral
     * siguiente; si no hay ninguna, el último lote pesado.
     */
    private suspend fun precargar() {
        val b = binding ?: return
        val dao = AppDatabase.getInstance(requireContext()).planDao()
        val editando = editandoId?.let { dao.getItem(it) }
        if (editando != null) {
            pintar(editando, corral = editando.corral)
            return
        }
        val ultimo = dao.getUltimoDelDia(PlanDiaFragment.hoy())
        if (ultimo != null) {
            pintar(ultimo, corral = ConfiguracionMuestreoStore.siguienteCorral(ultimo.corral) ?: "")
            return
        }
        val cfg = ConfiguracionMuestreoStore.leer(requireContext(), AuthRepository(requireContext()).getVerificadorId()) ?: return
        val iPlantel = planteles.indexOfFirst { it.id == cfg.plantelId }
        if (iPlantel >= 0) b.spinnerPlanPlantel.setSelection(iPlantel)
        b.editPlanCampania.setText(cfg.campania)
        val iLinea = CaptureSetupFragment.LINEAS_GENETICAS.indexOf(cfg.linea)
        if (iLinea >= 0) b.spinnerPlanLinea.setSelection(iLinea)
        b.radioGroupPlanLote.check(if (cfg.lote == "A") R.id.radioPlanLoteA else R.id.radioPlanLoteJ)
    }

    private fun pintar(item: PlanItem, corral: String) {
        val b = binding ?: return
        val iPlantel = planteles.indexOfFirst { it.id == item.plantelId }
        if (iPlantel >= 0) b.spinnerPlanPlantel.setSelection(iPlantel)
        b.editPlanCampania.setText(item.campania)
        b.editPlanGalpon.setText(item.galpon)
        seleccionarCorral(corral)
        b.radioGroupPlanSexo.check(
            when (item.categoria) {
                "HEMBRA" -> R.id.radioPlanHembra
                "MEDIANO" -> R.id.radioPlanMediano
                else -> R.id.radioPlanMacho
            }
        )
        b.editPlanEdad.setText(item.edad?.toString() ?: "")
        b.radioGroupPlanTipo.check(if (item.tipoMuestreo == EstandaresMuestreo.TIPO_CALIDAD) R.id.radioPlanCalidad else R.id.radioPlanPesaje)
        val iLinea = CaptureSetupFragment.LINEAS_GENETICAS.indexOf(item.linea)
        if (iLinea >= 0) b.spinnerPlanLinea.setSelection(iLinea)
        b.radioGroupPlanLote.check(if (item.lote == "A") R.id.radioPlanLoteA else R.id.radioPlanLoteJ)
        // El agrupamiento se fija después del tipo: el listener del tipo pone el estándar y aquí
        // se respeta lo que tenía la fila.
        b.radioGroupPlanAgrupamiento.check(if (item.agrupamiento == "GRUPAL") R.id.radioPlanGrupal else R.id.radioPlanIndividual)
        b.radioGroupPlanCircuito.check(
            when (item.circuito) {
                "CV" -> R.id.radioPlanCircuitoCV
                "CB" -> R.id.radioPlanCircuitoCB
                else -> R.id.radioPlanCircuitoNinguno
            }
        )
    }

    private fun seleccionarCorral(corral: String) {
        val b = binding ?: return
        val i = ConfiguracionMuestreoStore.CORRALES.indexOf(corral.uppercase())
        if (i >= 0) {
            b.spinnerPlanCorral.setSelection(i)
            b.editPlanCorralOtro.setText("")
        } else {
            b.spinnerPlanCorral.setSelection(ConfiguracionMuestreoStore.CORRALES.size)
            b.editPlanCorralOtro.setText(corral)
        }
    }

    private fun corralElegido(): String {
        val b = binding ?: return ""
        val i = b.spinnerPlanCorral.selectedItemPosition
        return if (i in ConfiguracionMuestreoStore.CORRALES.indices) {
            ConfiguracionMuestreoStore.CORRALES[i]
        } else {
            b.editPlanCorralOtro.text.toString().trim().uppercase()
        }
    }

    private fun guardar(seguir: Boolean) {
        val b = binding ?: return
        val plantel = planteles.getOrNull(b.spinnerPlanPlantel.selectedItemPosition)
        val campania = b.editPlanCampania.text.toString().trim()
        val galpon = b.editPlanGalpon.text.toString().trim()
        val corral = corralElegido()
        val edad = b.editPlanEdad.text.toString().trim().toIntOrNull()

        val faltantes = buildList {
            if (plantel == null) add(getString(R.string.label_plantel))
            if (campania.isEmpty()) add(getString(R.string.label_campania))
            if (galpon.isEmpty()) add(getString(R.string.label_galpon))
            if (corral.isEmpty()) add(getString(R.string.label_corral))
        }
        if (faltantes.isNotEmpty() || plantel == null) {
            mostrarError(getString(R.string.error_setup_falta, faltantes.joinToString(", ")))
            return
        }
        b.textPlanItemError.visibility = View.GONE

        val categoria = when (b.radioGroupPlanSexo.checkedRadioButtonId) {
            R.id.radioPlanHembra -> "HEMBRA"
            R.id.radioPlanMediano -> "MEDIANO"
            else -> "MACHO"
        }
        val tipo = if (b.radioGroupPlanTipo.checkedRadioButtonId == R.id.radioPlanCalidad) EstandaresMuestreo.TIPO_CALIDAD else EstandaresMuestreo.TIPO_PREVENTA
        val linea = CaptureSetupFragment.LINEAS_GENETICAS.getOrNull(b.spinnerPlanLinea.selectedItemPosition)
        val lote = if (b.radioGroupPlanLote.checkedRadioButtonId == R.id.radioPlanLoteA) "A" else "J"
        val agrupamiento = if (b.radioGroupPlanAgrupamiento.checkedRadioButtonId == R.id.radioPlanGrupal) "GRUPAL" else "INDIVIDUAL"
        val circuito = when (b.radioGroupPlanCircuito.checkedRadioButtonId) {
            R.id.radioPlanCircuitoCV -> "CV"
            R.id.radioPlanCircuitoCB -> "CB"
            else -> null
        }

        val dao = AppDatabase.getInstance(requireContext()).planDao()
        val verificadorId = AuthRepository(requireContext()).getVerificadorId()
        val hoy = PlanDiaFragment.hoy()
        viewLifecycleOwner.lifecycleScope.launch {
            val existente = editandoId?.let { dao.getItem(it) }
            val fila = PlanItem(
                id = existente?.id ?: UUID.randomUUID().toString(),
                fecha = existente?.fecha ?: hoy,
                plantelId = plantel.id,
                plantelCodigo = plantel.codigo,
                campania = campania,
                galpon = galpon,
                corral = corral,
                categoria = categoria,
                edad = edad,
                tipoMuestreo = tipo,
                linea = linea,
                lote = lote,
                agrupamiento = agrupamiento,
                circuito = circuito,
                orden = existente?.orden ?: (dao.maxOrden(hoy) + 1),
                estado = existente?.estado ?: PlanItem.ESTADO_PENDIENTE,
                verificadorId = existente?.verificadorId ?: verificadorId,
                // Editada o nueva: vuelve a la cola para que el servidor la reciba.
                synced = false,
                createdAtEpochMillis = existente?.createdAtEpochMillis ?: System.currentTimeMillis(),
            )
            dao.insertAll(listOf(fila))
            SyncScheduler.scheduleSyncNow(requireContext())

            if (seguir && editandoId == null) {
                // Mismo lote, corral siguiente: se cambia solo lo que difiera.
                val siguiente = ConfiguracionMuestreoStore.siguienteCorral(corral) ?: ""
                seleccionarCorral(siguiente)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.plan_corral_agregado, corral, siguiente.ifEmpty { "—" }),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                findNavController().popBackStack()
            }
        }
    }

    private fun mostrarError(mensaje: String) {
        binding?.textPlanItemError?.text = mensaje
        binding?.textPlanItemError?.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        const val ARG_PLAN_EDIT_ID = "planEditId"
    }
}
