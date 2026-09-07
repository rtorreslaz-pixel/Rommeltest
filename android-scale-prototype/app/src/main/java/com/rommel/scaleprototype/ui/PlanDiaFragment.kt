package com.rommel.scaleprototype.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rommel.scaleprototype.R
import com.rommel.scaleprototype.data.AppDatabase
import com.rommel.scaleprototype.data.PlanItem
import com.rommel.scaleprototype.databinding.FragmentPlanDiaBinding
import com.rommel.scaleprototype.databinding.ItemPlanBinding
import com.rommel.scaleprototype.sync.SyncScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plan del día: la lista de corrales que el verificador se propuso muestrear hoy, con su avance.
 * Tocar una fila pendiente abre la configuración de captura ya llena con esos datos; al finalizar
 * ese muestreo la fila pasa a HECHO. Mantener pulsada una fila pendiente permite editarla o quitarla.
 */
class PlanDiaFragment : Fragment() {

    private var binding: FragmentPlanDiaBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPlanDiaBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.textPlanFecha?.text = getString(R.string.plan_fecha_format, fechaLegible())
        binding?.buttonAgregarPlan?.setOnClickListener {
            findNavController().navigate(R.id.action_planDia_to_planItem)
        }
        binding?.buttonSincronizarPlan?.setOnClickListener {
            SyncScheduler.scheduleSyncNow(requireContext())
        }
        observarPlan()
    }

    private fun observarPlan() {
        val dao = AppDatabase.getInstance(requireContext()).planDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.getDelDiaFlow(hoy()).collect { items -> pintar(items) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.countUnsyncedFlow().collect { pendientes ->
                    binding?.textPlanSync?.text = if (pendientes == 0) {
                        getString(R.string.plan_sync_ok)
                    } else {
                        getString(R.string.plan_sync_pendientes, pendientes)
                    }
                }
            }
        }
    }

    private fun pintar(items: List<PlanItem>) {
        val b = binding ?: return
        val hechos = items.count { it.estado == PlanItem.ESTADO_HECHO }
        b.textPlanAvance.text = if (items.isEmpty()) {
            getString(R.string.plan_vacio)
        } else {
            getString(R.string.plan_avance_format, hechos, items.size)
        }
        b.progressPlan.max = items.size.coerceAtLeast(1)
        b.progressPlan.progress = hechos
        b.progressPlan.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        b.containerPlan.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        items.forEach { item ->
            val fila = ItemPlanBinding.inflate(inflater, b.containerPlan, false)
            val hecho = item.estado == PlanItem.ESTADO_HECHO
            fila.textPlanCorral.text = item.corral
            fila.textPlanLote.text = getString(
                R.string.plan_item_lote_format, item.plantelCodigo, item.galpon, sexoLegible(item.categoria)
            )
            fila.textPlanDetalle.text = listOfNotNull(
                if (item.tipoMuestreo == "CALIDAD") getString(R.string.modo_solo_calidad) else getString(R.string.modo_pesaje),
                item.edad?.let { getString(R.string.plan_item_edad_format, it) },
                item.linea,
                item.lote?.let { getString(R.string.plan_item_lote_letra_format, it) },
                if (item.agrupamiento == "GRUPAL") getString(R.string.plan_grupal) else getString(R.string.plan_individual),
                item.circuito,
            ).joinToString(" · ")
            fila.textPlanEstado.text = getString(if (hecho) R.string.plan_estado_hecho else R.string.plan_estado_pendiente)
            fila.textPlanEstado.setBackgroundResource(if (hecho) R.drawable.bg_badge_verde else R.drawable.bg_badge_ambar)
            fila.textPlanEstado.setTextColor(
                ContextCompat.getColor(requireContext(), if (hecho) R.color.sf_green else R.color.sf_amber)
            )
            fila.root.alpha = if (hecho) 0.6f else 1f
            if (!hecho) {
                fila.root.setOnClickListener {
                    findNavController().navigate(
                        R.id.action_planDia_to_captureSetup,
                        bundleOf(CaptureSetupFragment.ARG_PLAN_ITEM_ID to item.id),
                    )
                }
                fila.root.setOnLongClickListener {
                    opciones(item)
                    true
                }
            }
            b.containerPlan.addView(fila.root)
        }
    }

    /** Mantener pulsado un corral pendiente: pesar, editar sus datos o quitarlo. */
    private fun opciones(item: PlanItem) {
        val acciones = arrayOf(
            getString(R.string.plan_opcion_pesar),
            getString(R.string.plan_opcion_editar),
            getString(R.string.plan_opcion_quitar),
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.plan_opciones_titulo, item.corral, item.plantelCodigo, item.galpon))
            .setItems(acciones) { _, cual ->
                when (cual) {
                    0 -> findNavController().navigate(
                        R.id.action_planDia_to_captureSetup,
                        bundleOf(CaptureSetupFragment.ARG_PLAN_ITEM_ID to item.id),
                    )
                    1 -> findNavController().navigate(
                        R.id.action_planDia_to_planItem,
                        bundleOf(PlanItemFragment.ARG_PLAN_EDIT_ID to item.id),
                    )
                    else -> confirmarQuitar(item)
                }
            }
            .show()
    }

    private fun confirmarQuitar(item: PlanItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.plan_quitar_titulo)
            .setMessage(getString(R.string.plan_quitar_mensaje, item.corral, item.plantelCodigo, item.galpon))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plan_quitar_confirmar) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AppDatabase.getInstance(requireContext()).planDao().marcarBorrado(item.id)
                    SyncScheduler.scheduleSyncNow(requireContext())
                }
            }
            .show()
    }

    private fun sexoLegible(categoria: String): String = when (categoria) {
        "HEMBRA" -> getString(R.string.categoria_hembra)
        "MEDIANO" -> getString(R.string.categoria_mediano)
        else -> getString(R.string.categoria_macho)
    }

    private fun fechaLegible(): String =
        SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "PE")).apply {
            timeZone = TimeZone.getTimeZone("America/Lima")
        }.format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        /** Día de la granja (Perú), igual que lo calcula el servidor. */
        fun hoy(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/Lima")
        }.format(Date())
    }
}
