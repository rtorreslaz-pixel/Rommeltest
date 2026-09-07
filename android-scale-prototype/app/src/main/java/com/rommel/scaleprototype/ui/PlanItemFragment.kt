package com.rommel.scaleprototype.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Agregar corrales al plan del día. Se define el lote una vez (plantel, campaña, galpón, sexo,
 * edad, tipo, línea, lote, agrupamiento, circuito) y se marcan los corrales: sale una fila del
 * plan por cada corral marcado. Todo queda en el teléfono y se sube solo.
 */
class PlanItemFragment : Fragment() {

    private var binding: FragmentPlanItemBinding? = null
    private var planteles: List<PlantelDto> = emptyList()

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
        binding?.spinnerPlanLinea?.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            CaptureSetupFragment.LINEAS_GENETICAS,
        )
        cargarPlanteles()
        precargarUltimoLote()
        // Estándar: preventa de a una ave; calidad de a tres. Al cambiar el tipo se propone el
        // agrupamiento que corresponde (sigue editable por si un caso lo amerita).
        binding?.radioGroupPlanTipo?.setOnCheckedChangeListener { _, checkedId ->
            val tipo = if (checkedId == R.id.radioPlanCalidad) EstandaresMuestreo.TIPO_CALIDAD else EstandaresMuestreo.TIPO_PREVENTA
            binding?.radioGroupPlanAgrupamiento?.check(
                if (EstandaresMuestreo.agrupamientoPara(tipo) == "GRUPAL") R.id.radioPlanGrupal else R.id.radioPlanIndividual
            )
        }
        binding?.buttonGuardarPlan?.setOnClickListener { guardar() }
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
            // Si hay un lote reciente, se propone el mismo plantel para no buscarlo de nuevo.
            val cfg = ConfiguracionMuestreoStore.leer(requireContext(), AuthRepository(requireContext()).getVerificadorId())
            val i = planteles.indexOfFirst { it.id == cfg?.plantelId }
            if (i >= 0) binding?.spinnerPlanPlantel?.setSelection(i)
        }
    }

    /** Campaña, línea y lote suelen repetirse de un día a otro: se proponen los últimos usados. */
    private fun precargarUltimoLote() {
        val b = binding ?: return
        val cfg = ConfiguracionMuestreoStore.leer(requireContext(), AuthRepository(requireContext()).getVerificadorId()) ?: return
        b.editPlanCampania.setText(cfg.campania)
        val iLinea = CaptureSetupFragment.LINEAS_GENETICAS.indexOf(cfg.linea)
        if (iLinea >= 0) b.spinnerPlanLinea.setSelection(iLinea)
        b.radioGroupPlanLote.check(if (cfg.lote == "A") R.id.radioPlanLoteA else R.id.radioPlanLoteJ)
    }

    private fun guardar() {
        val b = binding ?: return
        val plantel = planteles.getOrNull(b.spinnerPlanPlantel.selectedItemPosition)
        val campania = b.editPlanCampania.text.toString().trim()
        val galpon = b.editPlanGalpon.text.toString().trim()
        val edad = b.editPlanEdad.text.toString().trim().toIntOrNull()
        val corrales = buildList {
            if (b.checkPlanCorralA.isChecked) add("A")
            if (b.checkPlanCorralB.isChecked) add("B")
            if (b.checkPlanCorralC.isChecked) add("C")
            if (b.checkPlanCorralD.isChecked) add("D")
            val otro = b.editPlanCorralOtro.text.toString().trim().uppercase()
            if (otro.isNotEmpty()) add(otro)
        }

        val faltantes = buildList {
            if (plantel == null) add(getString(R.string.label_plantel))
            if (campania.isEmpty()) add(getString(R.string.label_campania))
            if (galpon.isEmpty()) add(getString(R.string.label_galpon))
            if (corrales.isEmpty()) add(getString(R.string.label_corral))
        }
        if (faltantes.isNotEmpty() || plantel == null) {
            mostrarError(getString(R.string.error_setup_falta, faltantes.joinToString(", ")))
            return
        }

        val categoria = when (b.radioGroupPlanSexo.checkedRadioButtonId) {
            R.id.radioPlanHembra -> "HEMBRA"
            R.id.radioPlanMediano -> "MEDIANO"
            else -> "MACHO"
        }
        val tipo = if (b.radioGroupPlanTipo.checkedRadioButtonId == R.id.radioPlanCalidad) "CALIDAD" else "PREVENTA"
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
            var orden = dao.maxOrden(hoy)
            val ahora = System.currentTimeMillis()
            val filas = corrales.map { corral ->
                PlanItem(
                    id = UUID.randomUUID().toString(),
                    fecha = hoy,
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
                    orden = ++orden,
                    verificadorId = verificadorId,
                    createdAtEpochMillis = ahora,
                )
            }
            dao.insertAll(filas)
            SyncScheduler.scheduleSyncNow(requireContext())
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(R.plurals.plan_agregados, filas.size, filas.size),
                Toast.LENGTH_SHORT,
            ).show()
            findNavController().popBackStack()
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
}
