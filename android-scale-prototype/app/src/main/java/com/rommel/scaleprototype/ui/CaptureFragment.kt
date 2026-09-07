package com.rommel.scaleprototype.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rommel.scaleprototype.R
import com.rommel.scaleprototype.ScaleConnectionManager
import com.rommel.scaleprototype.ScaleEvent
import com.rommel.scaleprototype.ScaleProtocols
import com.rommel.scaleprototype.auth.AuthRepository
import com.rommel.scaleprototype.data.AppDatabase
import com.rommel.scaleprototype.data.RegistroPeso
import com.rommel.scaleprototype.databinding.FragmentCaptureBinding
import com.rommel.scaleprototype.net.ApiClient
import com.rommel.scaleprototype.net.ApiException
import com.rommel.scaleprototype.net.LiveWeightRequest
import com.rommel.scaleprototype.sync.SyncScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class CaptureFragment : Fragment() {

    private var binding: FragmentCaptureBinding? = null
    private val scaleListener: (ScaleEvent) -> Unit = { event -> handleScaleEvent(event) }
    private var latestWeightGramos: Double? = null
    private var lastLiveWeightSentAtMillis = 0L
    // Reloj monótono del último peso que sí se pudo interpretar (0 = ninguno todavía).
    private var ultimoPesoOkMillis = 0L
    // Aves registradas en esta sesión de captura (para el resumen de "Finalizar muestreo").
    private var avesRegistradasSesion = 0

    // Piso traído del servidor para el corral actual: si la app se reinstaló o se borró su
    // storage, Room local arranca en 0 pero el servidor ya tiene aves sincronizadas de antes
    // para esta misma combinación -- sin esto se duplicarían números de ave ya usados.
    private var serverMaxNumeroAve: Int = 0

    private lateinit var plantelId: String
    private lateinit var plantelCodigo: String
    private lateinit var campania: String
    private lateinit var galpon: String
    private lateinit var corral: String
    private lateinit var categoria: String
    private var edad: Int = 0
    private lateinit var linea: String
    private lateinit var lote: String
    private var nAvesPorPesada: Int = 1
    private var soloCalidad: Boolean = false
    /** Fila del plan del día de la que salió este muestreo (null si se configuró a mano). */
    private var planItemId: String? = null

    private val requestBluetoothConnect = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) connectToSavedScale() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        plantelId = args.getString(CaptureSetupFragment.ARG_PLANTEL_ID)!!
        plantelCodigo = args.getString(CaptureSetupFragment.ARG_PLANTEL_CODIGO)!!
        campania = args.getString(CaptureSetupFragment.ARG_CAMPANIA)!!
        galpon = args.getString(CaptureSetupFragment.ARG_GALPON)!!
        corral = args.getString(CaptureSetupFragment.ARG_CORRAL)!!
        categoria = args.getString(CaptureSetupFragment.ARG_CATEGORIA)!!
        edad = args.getInt(CaptureSetupFragment.ARG_EDAD, 0)
        linea = args.getString(CaptureSetupFragment.ARG_LINEA) ?: ""
        lote = args.getString(CaptureSetupFragment.ARG_LOTE) ?: "J"
        nAvesPorPesada = args.getInt(CaptureSetupFragment.ARG_N_AVES_PESADA, 1)
        soloCalidad = args.getBoolean(CaptureSetupFragment.ARG_SOLO_CALIDAD, false)
        planItemId = args.getString(CaptureSetupFragment.ARG_PLAN_ITEM_ID)

        binding?.textSelectionHeader?.text =
            getString(R.string.capture_header_format, plantelCodigo, campania, galpon, categoria, corral)
        binding?.textCaptureSubHeader?.text =
            getString(R.string.capture_sub_header_format, edad, linea, lote, nAvesPorPesada)

        binding?.textChangeSelection?.setOnClickListener {
            findNavController().navigate(R.id.action_capture_to_captureSetup)
        }
        binding?.textOpenDiagnostic?.setOnClickListener {
            findNavController().navigate(R.id.action_capture_to_diagnostic)
        }
        binding?.textMuestreos?.setOnClickListener {
            findNavController().navigate(R.id.action_capture_to_muestreos)
        }
        binding?.buttonRegisterAve?.setOnClickListener { onRegisterAveClicked() }
        binding?.buttonFinalizar?.setOnClickListener { onFinalizarClicked() }

        binding?.switchEvaluarCalidad?.setOnCheckedChangeListener { _, checked ->
            binding?.layoutCalidad?.visibility = if (checked) View.VISIBLE else View.GONE
        }

        applyCaptureMode()

        observePendingCount()
        fetchServerNumeroAveBaseline()
        // En "solo calidad" no hay báscula: no se conecta ni se escucha peso.
        if (!soloCalidad) {
            ScaleConnectionManager.addListener(scaleListener)
            ensurePermissionThenConnect()
            vigilarLlegadaDeDatos()
        }
    }

    /** Ajusta la pantalla según el modo: en solo calidad se oculta el peso/báscula. */
    private fun applyCaptureMode() {
        val b = binding ?: return
        if (soloCalidad) {
            b.textCaptureWeight.visibility = View.GONE
            b.textCaptureStatus.visibility = View.GONE
            b.switchEvaluarCalidad.visibility = View.GONE
            b.textOpenDiagnostic.visibility = View.GONE
            b.layoutCalidad.visibility = View.VISIBLE
            b.buttonRegisterAve.text = getString(R.string.action_register_calidad)
            b.buttonRegisterAve.isEnabled = true
        }
    }

    // Best-effort: si falla (sin red al entrar), simplemente se usa solo el máximo local,
    // que es el comportamiento que ya existía antes de este fix.
    private fun fetchServerNumeroAveBaseline() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                ApiClient.getInstance(requireContext())
                    .getNumeroAveMax(plantelId, campania, galpon, corral, categoria)
            }.onSuccess { response ->
                serverMaxNumeroAve = response.maxNumeroAve ?: 0
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // El SyncWorker borra la sesión local si el servidor responde 401 (token revocado o
        // rotado desde /admin/usuarios). Se revisa aquí -- no solo al entrar -- porque esa
        // revocación puede ocurrir en cualquier momento mientras esta pantalla está abierta.
        if (!AuthRepository(requireContext()).isLoggedIn()) {
            Toast.makeText(requireContext(), getString(R.string.session_revoked_message), Toast.LENGTH_LONG).show()
            findNavController().navigate(R.id.action_capture_to_login)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // La conexión NO se cierra al salir: el gestor la mantiene viva para el siguiente lote.
        ScaleConnectionManager.removeListener(scaleListener)
        binding = null
    }

    private fun observePendingCount() {
        val dao = AppDatabase.getInstance(requireContext()).registroPesoDao()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.countUnsyncedFlow().collect { count ->
                    // Confirmación operativa: 0 pendientes = todo lo pesado ya está en el
                    // servidor; el verificador puede irse/apagar tranquilo. Verde cuando está
                    // al día, ámbar cuando falta subir algo (igual que el prototipo).
                    val badge = binding?.textPendingBadge ?: return@collect
                    if (count == 0) {
                        badge.text = getString(R.string.sync_all_done)
                        badge.setBackgroundResource(R.drawable.bg_badge_verde)
                        badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.sf_green))
                    } else {
                        badge.text = getString(R.string.pending_count_format, count)
                        badge.setBackgroundResource(R.drawable.bg_badge_ambar)
                        badge.setTextColor(ContextCompat.getColor(requireContext(), R.color.sf_amber))
                    }
                }
            }
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionThenConnect() {
        if (!hasBluetoothConnectPermission()) {
            requestBluetoothConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        connectToSavedScale()
    }

    @SuppressLint("MissingPermission") // hasBluetoothConnectPermission() ya fue verificado arriba
    private fun connectToSavedScale() {
        // Si el gestor ya tiene una báscula conectada (p. ej. recién vinculada en Ajustes de
        // báscula), no se reconecta: el peso ya empieza a llegar por el listener.
        if (ScaleConnectionManager.isConnected()) {
            setStatus(getString(R.string.connected))
            return
        }

        val prefs = requireContext().getSharedPreferences(SCALE_PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_LAST_DEVICE_ADDRESS, null)
        if (address == null) {
            setStatus(getString(R.string.status_no_saved_scale))
            return
        }

        val btManager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus(getString(R.string.status_bluetooth_disabled))
            return
        }

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            setStatus(getString(R.string.status_no_saved_scale))
            return
        }

        val protocolIndex = prefs.getInt(KEY_LAST_PROTOCOL_INDEX, -1)
        setStatus(getString(R.string.status_connecting))
        ScaleConnectionManager.connect(device, protocolIndex)
    }

    private fun handleScaleEvent(event: ScaleEvent) {
        when (event) {
            is ScaleEvent.Status -> setStatus(event.message)
            is ScaleEvent.Connected -> setStatus(getString(R.string.connected))
            is ScaleEvent.Disconnected -> {
                setStatus(getString(R.string.disconnected))
                setWeight(null)
            }
            is ScaleEvent.Error -> setStatus(getString(R.string.error_format, event.message))
            is ScaleEvent.RawLine -> updateWeightFromLine(event.text)
        }
    }

    private fun updateWeightFromLine(line: String) {
        val prefs = requireContext().getSharedPreferences(SCALE_PREFS_NAME, Context.MODE_PRIVATE)
        val protocolIndex = ScaleConnectionManager.protocolIndex.takeIf { it >= 0 }
            ?: prefs.getInt(KEY_LAST_PROTOCOL_INDEX, -1)
        val protocol = ScaleProtocols.all.getOrNull(protocolIndex) ?: ScaleProtocols.default
        val parsed = protocol.parse(line) ?: return
        ultimoPesoOkMillis = SystemClock.elapsedRealtime()
        setWeight(parsed.value)
        maybeSendLiveWeight(parsed.value)
    }

    /**
     * Vigila que además de "Conectado" esté entrando peso de verdad. Antes, si las tramas no se
     * podían interpretar, se descartaban en silencio y la pantalla se quedaba en "-- kg" —
     * indistinguible de una báscula apagada. Ahora se dice cuál de las dos cosas pasa.
     */
    private fun vigilarLlegadaDeDatos() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(INTERVALO_VIGILANCIA_MS)
                    sincronizarProtocoloGuardado()
                    revisarLlegadaDeDatos()
                }
            }
        }
    }

    private fun revisarLlegadaDeDatos() {
        if (soloCalidad || !ScaleConnectionManager.isConnected()) return
        val ahora = SystemClock.elapsedRealtime()
        val hayPesoReciente = ultimoPesoOkMillis != 0L && ahora - ultimoPesoOkMillis < TOLERANCIA_PESO_MS
        if (hayPesoReciente) return
        if (ScaleConnectionManager.millisSinDatos() > TOLERANCIA_DATOS_MS) {
            setStatus(getString(R.string.status_sin_datos))
        } else {
            // Llegan tramas pero el protocolo no las reconoce: es accionable, hay que decirlo.
            val protocolo = ScaleConnectionManager.protocoloActual()?.displayName
                ?: getString(R.string.protocolo_desconocido)
            setStatus(getString(R.string.status_datos_no_interpretados, protocolo))
        }
    }

    /** Deja guardado el protocolo en uso (p. ej. si el gestor lo corrigió solo). */
    private fun sincronizarProtocoloGuardado() {
        val indice = ScaleConnectionManager.protocolIndex
        if (indice < 0) return
        val prefs = requireContext().getSharedPreferences(SCALE_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_PROTOCOL_INDEX, -1) == indice) return
        prefs.edit().putInt(KEY_LAST_PROTOCOL_INDEX, indice).apply()
    }

    // Una lectura en vivo vieja no sirve de nada, así que (a diferencia de los registros, que
    // van a Room y se sincronizan solos) no se encola: si falla, se descarta. Lo que sí se hace
    // es DECIRLO en pantalla -- antes fallaba en silencio y era imposible saber, desde la granja,
    // si el peso estaba llegando al monitor de la web o no.
    private fun maybeSendLiveWeight(valueKg: Double) {
        val now = System.currentTimeMillis()
        if (now - lastLiveWeightSentAtMillis < LIVE_WEIGHT_THROTTLE_MS) return
        lastLiveWeightSentAtMillis = now
        viewLifecycleOwner.lifecycleScope.launch {
            val resultado = runCatching {
                ApiClient.getInstance(requireContext()).postLiveWeight(
                    LiveWeightRequest(
                        pesoGramos = valueKg * 1000.0,
                        plantelCodigo = plantelCodigo,
                        campania = campania,
                        galpon = galpon,
                        corral = corral,
                        categoria = categoria,
                    )
                )
            }
            mostrarEstadoEnvio(resultado.exceptionOrNull())
        }
    }

    /** Pinta si el peso llegó a la web, para no depender de adivinar. */
    private fun mostrarEstadoEnvio(error: Throwable?) {
        val vista = binding?.textEnvioWeb ?: return
        vista.visibility = View.VISIBLE
        when {
            error == null -> {
                vista.text = getString(R.string.envio_web_ok)
                vista.setTextColor(ContextCompat.getColor(requireContext(), R.color.sf_green))
            }
            // 401: el token dejó de valer (revocado o rotado desde el admin).
            error is ApiException && error.code == 401 -> {
                vista.text = getString(R.string.envio_web_sesion)
                vista.setTextColor(ContextCompat.getColor(requireContext(), R.color.sf_red))
            }
            else -> {
                vista.text = getString(R.string.envio_web_error)
                vista.setTextColor(ContextCompat.getColor(requireContext(), R.color.sf_amber))
            }
        }
    }

    private fun setWeight(valueKg: Double?) {
        // Si se pesan N aves juntas, la báscula devuelve el total. Se divide para obtener
        // el promedio por ave, que es lo que se almacena y compara contra el estándar.
        latestWeightGramos = valueKg?.let { (it * 1000.0) / nAvesPorPesada }
        binding?.textCaptureWeight?.text = if (valueKg != null) {
            getString(R.string.weight_format, valueKg, "kg")
        } else {
            getString(R.string.weight_placeholder)
        }
        binding?.buttonRegisterAve?.isEnabled = latestWeightGramos != null
    }

    private fun setStatus(text: String) {
        binding?.textCaptureStatus?.text = text
    }

    private fun onRegisterAveClicked() {
        // En pesaje se requiere peso; en solo calidad no hay peso (se guarda 0 y tipo CALIDAD).
        val pesoGramos: Double
        val tipoMuestreo: String
        val evaluarCalidad: Boolean
        if (soloCalidad) {
            pesoGramos = 0.0
            tipoMuestreo = "CALIDAD"
            evaluarCalidad = true
        } else {
            pesoGramos = latestWeightGramos ?: return
            tipoMuestreo = "PREVENTA"
            evaluarCalidad = binding?.switchEvaluarCalidad?.isChecked == true
        }
        val dao = AppDatabase.getInstance(requireContext()).registroPesoDao()
        val gradoPododermatitis = if (evaluarCalidad) gradoFromRadioGroup(
            binding?.radioGroupPododermatitis?.checkedRadioButtonId, R.id.radioPodoLeve, R.id.radioPodoGrave
        ) else null
        val gradoRasguno = if (evaluarCalidad) gradoFromRadioGroup(
            binding?.radioGroupRasguno?.checkedRadioButtonId, R.id.radioRasgLeve, R.id.radioRasgGrave
        ) else null
        val pigmentacion = if (evaluarCalidad) pigmentacionSeleccionada() else null

        val authRepository = AuthRepository(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val nowMillis = System.currentTimeMillis()
            val localMaxNumeroAve = dao.getMaxNumeroAve(plantelId, campania, galpon, corral, categoria) ?: 0
            val numeroAve = maxOf(localMaxNumeroAve, serverMaxNumeroAve) + 1
            dao.insert(
                RegistroPeso(
                    id = UUID.randomUUID().toString(),
                    verificadorId = authRepository.getVerificadorId(),
                    verificadorNombre = authRepository.getVerificadorNombre(),
                    plantelId = plantelId,
                    plantelCodigo = plantelCodigo,
                    campania = campania,
                    galpon = galpon,
                    corral = corral,
                    categoria = categoria,
                    numeroAve = numeroAve,
                    pesoGramos = pesoGramos,
                    tipoMuestreo = tipoMuestreo,
                    fechaHoraEpochMillis = nowMillis,
                    edad = edad,
                    linea = linea,
                    lote = lote,
                    nAvesPorPesada = nAvesPorPesada,
                    tieneHematoma = null,
                    tieneDefectoSeleccion = null,
                    gradoPododermatitis = gradoPododermatitis,
                    gradoRasguno = gradoRasguno,
                    pigmentacion = pigmentacion,
                    createdAtEpochMillis = nowMillis,
                )
            )
            SyncScheduler.scheduleSyncNow(requireContext())
            avesRegistradasSesion++
            binding?.textLastRegistered?.text = if (soloCalidad) {
                getString(R.string.last_registered_calidad_format, numeroAve)
            } else {
                getString(R.string.last_registered_format, numeroAve, pesoGramos)
            }
        }
    }

    /** Pigmentación 0–7 (botones): devuelve null si no se seleccionó ninguno. */
    private fun pigmentacionSeleccionada(): Int? = when (binding?.togglePigmentacion?.checkedButtonId) {
        R.id.pig0 -> 0
        R.id.pig1 -> 1
        R.id.pig2 -> 2
        R.id.pig3 -> 3
        R.id.pig4 -> 4
        R.id.pig5 -> 5
        R.id.pig6 -> 6
        R.id.pig7 -> 7
        else -> null
    }

    private fun onFinalizarClicked() {
        if (avesRegistradasSesion == 0) {
            Toast.makeText(requireContext(), getString(R.string.finalizar_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val dao = AppDatabase.getInstance(requireContext()).registroPesoDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val pendientes = dao.countUnsynced()
            val mensaje = if (pendientes > 0) {
                getString(R.string.finalizar_message_pending, avesRegistradasSesion, pendientes)
            } else {
                getString(R.string.finalizar_message, avesRegistradasSesion)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.finalizar_title)
                .setMessage(mensaje)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.finalizar_confirm) { _, _ ->
                    // Los pendientes siguen subiéndose solos (WorkManager); la báscula queda
                    // conectada para el siguiente muestreo.
                    ConfiguracionMuestreoStore.marcarCorralCompletado(requireContext())
                    val idPlan = planItemId
                    if (idPlan != null) {
                        // Salió del plan: la fila queda hecha aquí mismo (el servidor la cruzará
                        // igual al sincronizar) y se vuelve al plan para tocar el siguiente corral.
                        viewLifecycleOwner.lifecycleScope.launch {
                            AppDatabase.getInstance(requireContext()).planDao().marcarHecho(idPlan)
                            findNavController().navigate(R.id.action_capture_to_planDia)
                        }
                    } else {
                        // Vuelve a configurar, con los datos del lote ya puestos y el corral
                        // avanzado al siguiente del galpón.
                        findNavController().navigate(R.id.action_capture_to_captureSetup)
                    }
                }
                .show()
        }
    }

    // 0 sin lesión, 1 leve, 2 grave -- mismo mapeo que EvaluacionLesion.{sinLesion,leve,grave}.
    private fun gradoFromRadioGroup(checkedId: Int?, leveId: Int, graveId: Int): Int = when (checkedId) {
        leveId -> 1
        graveId -> 2
        else -> 0
    }

    companion object {
        const val SCALE_PREFS_NAME = "scale_connection_prefs"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        const val KEY_LAST_PROTOCOL_INDEX = "last_protocol_index"
        private const val LIVE_WEIGHT_THROTTLE_MS = 1500L
        private const val INTERVALO_VIGILANCIA_MS = 2000L
        /** Sin peso interpretado por más de esto, se explica por qué. */
        private const val TOLERANCIA_PESO_MS = 4000L
        /** Sin ninguna trama por más de esto, el problema es la báscula, no el protocolo. */
        private const val TOLERANCIA_DATOS_MS = 6000L
    }
}
