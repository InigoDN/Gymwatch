package com.example.diamantizador

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator
    private lateinit var powerManager: PowerManager
    private var toneGenerator: ToneGenerator? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var isWorkoutActive = false
    private var isResting = false
    private var isTransitionRest = false
    private var isTimerPaused = false
    private var targetEndEpochMs = 0L
    private var totalRestSeconds = 120
    private var pausedRemainingSec = 0
    private var hasVibratedForCurrentRest = false

    private var currentWeightKg = 20.0
    private var weightStepKg = 2.5
    private var currentReps = 10
    private var currentRir = 1
    private var lastSyncedSetKey = ""

    private var isEditingWeight = true
    private var keypadBuffer = ""

    private lateinit var ringView: CircularProgressRingView

    private lateinit var layoutRoutinePickerScroll: ScrollView
    private lateinit var layoutRoutinesListContainer: LinearLayout
    private lateinit var tvRoutineSyncStatus: TextView

    private lateinit var layoutActiveSetDashboard: LinearLayout
    private lateinit var layoutRestTimerDashboard: LinearLayout
    private lateinit var layoutPrecisionKeypad: LinearLayout
    private lateinit var layoutExercisePickerScroll: ScrollView
    private lateinit var layoutExerciseListContainer: LinearLayout

    private lateinit var tvSetBadge: TextView
    private lateinit var btnExerciseTitle: Button
    private lateinit var tvAdviceCompact: TextView
    private lateinit var btnWeightCenter: Button
    private lateinit var btnRepsCenter: Button
    private val rirButtons = mutableListOf<Button>()

    private lateinit var tvKeypadTitle: TextView
    private lateinit var tvKeypadDisplay: TextView
    private lateinit var layoutQuickDeltasRow: LinearLayout

    private lateinit var tvRestBadge: TextView
    private lateinit var tvRestCountdown: TextView
    private lateinit var tvRestFeedback: TextView
    private lateinit var btnPauseResumeRest: Button

    private val localCountdownRunnable = object : Runnable {
        override fun run() {
            if (isWorkoutActive && isResting) {
                updateLocalRestCountdownUi()
                uiHandler.postDelayed(this, 250L)
            }
        }
    }

    private val autoConnectRetryRunnable = object : Runnable {
        override fun run() {
            if (!isWorkoutActive && layoutRoutinesListContainer.childCount == 0) {
                requestSyncFromPhone()
                uiHandler.postDelayed(this, 1800L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        buildCircularDashboardUi()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onResume() {
        super.onResume()
        requestSyncFromPhone()
        uiHandler.removeCallbacks(autoConnectRetryRunnable)
        uiHandler.postDelayed(autoConnectRetryRunnable, 1200L)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(autoConnectRetryRunnable)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        Wearable.getMessageClient(this).removeListener(this)
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/gym/state") {
            val jsonStr = String(event.data, Charsets.UTF_8)
            uiHandler.post {
                applyPhoneStateJson(jsonStr)
            }
        }
    }

    private fun sendCommandToPhone(action: String, extras: Map<String, Any> = emptyMap()) {
        val obj = JSONObject().apply {
            put("action", action)
            for ((k, v) in extras) {
                put(k, v)
            }
        }
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            for (node in nodes) {
                Wearable.getMessageClient(this).sendMessage(node.id, "/gym/command", bytes)
            }
        }
    }

    private fun requestSyncFromPhone() {
        sendCommandToPhone("REQUEST_SYNC")
    }

    private fun applyPhoneStateJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val active = root.optBoolean("active", false)

            if (!active) {
                isWorkoutActive = false
                isResting = false
                ringView.visibility = View.GONE
                val routinesArr = root.optJSONArray("routines")
                if (routinesArr != null && routinesArr.length() > 0) {
                    uiHandler.removeCallbacks(autoConnectRetryRunnable)
                    populateRoutinesSelectionMenu(routinesArr)
                }
                showScreen(layoutRoutinePickerScroll)
                return
            }

            uiHandler.removeCallbacks(autoConnectRetryRunnable)
            isWorkoutActive = true

            val exIdx = root.optInt("exerciseIndex", 0)
            val totalEx = root.optInt("totalExercises", 1)
            val exName = root.optString("exerciseName", "Ejercicio")
            val setNum = root.optInt("setNumber", 1)
            val targetSets = root.optInt("targetSets", 3)
            val minReps = root.optInt("minReps", 8)
            val maxReps = root.optInt("maxReps", 10)
            val suggestedW = root.optDouble("suggestedWeight", 20.0)
            val suggestedR = root.optInt("suggestedReps", 10)
            val advice = root.optString("adviceTitle", "")
            val prevTxt = root.optString("previousText", "")

            val setKey = "${exIdx}_${setNum}"
            if (setKey != lastSyncedSetKey) {
                lastSyncedSetKey = setKey
                currentWeightKg = suggestedW
                weightStepKg = if (suggestedW < 16.0) 1.25 else 2.5
                currentReps = suggestedR
                currentRir = 1
                refreshDashboardValuesUi()
            }

            tvSetBadge.text = "EJ ${exIdx + 1}/$totalEx  •  SERIE $setNum/$targetSets ($minReps-$maxReps)"
            btnExerciseTitle.text = "$exName ▾"

            val cleanAdvice = advice
                .replace("🔥 ¡Comprobante RIR superado! ", "🔥 ")
                .replace("⚠️ ", "")
                .replace("🎯 ", "")
            tvAdviceCompact.text = if (cleanAdvice.isNotBlank()) "$cleanAdvice · $prevTxt" else prevTxt

            val exArray = root.optJSONArray("exercisesList") ?: JSONArray()
            populateExercisePickerList(exArray, exIdx)

            val nowResting = root.optBoolean("isResting", false)
            isTransitionRest = root.optBoolean("isTransition", false)
            isTimerPaused = root.optBoolean("isPaused", false)
            totalRestSeconds = max(1, root.optInt("totalRestSeconds", 120))
            pausedRemainingSec = root.optInt("remainingSeconds", 0)
            val newTargetEpoch = root.optLong("targetEndEpochMs", 0L)

            if (nowResting) {
                if (!isResting || newTargetEpoch != targetEndEpochMs) {
                    hasVibratedForCurrentRest = false
                }
                isResting = true
                targetEndEpochMs = newTargetEpoch

                val nextExName = root.optString("nextExerciseName", "")
                tvRestBadge.text = if (isTransitionRest) {
                    "SIGUIENTE: ${nextExName.uppercase()}"
                } else {
                    "DESCANSO • SERIE $setNum COMPLETADA"
                }
                tvRestBadge.setTextColor(
                    Color.parseColor(if (isTransitionRest) "#00B0FF" else "#00E676")
                )

                val feedback = root.optString("rirFeedback", "")
                tvRestFeedback.text = feedback.ifBlank { "Recupérate para la siguiente serie" }
                btnPauseResumeRest.text = if (isTimerPaused) "▶" else "⏸"

                ringView.visibility = View.VISIBLE
                showScreen(layoutRestTimerDashboard)
                uiHandler.removeCallbacks(localCountdownRunnable)
                uiHandler.post(localCountdownRunnable)
            } else {
                isResting = false
                uiHandler.removeCallbacks(localCountdownRunnable)
                ringView.visibility = View.GONE
                if (layoutPrecisionKeypad.visibility != View.VISIBLE &&
                    layoutExercisePickerScroll.visibility != View.VISIBLE
                ) {
                    showScreen(layoutActiveSetDashboard)
                }
            }
        } catch (_: Exception) {}
    }

    private fun updateLocalRestCountdownUi() {
        val remSec = if (isTimerPaused) {
            pausedRemainingSec
        } else {
            val diffMs = targetEndEpochMs - System.currentTimeMillis()
            ((diffMs + 999) / 1000).toInt().coerceAtLeast(0)
        }

        val mins = remSec / 60
        val secs = remSec % 60
        tvRestCountdown.text = String.format(Locale.US, "%02d:%02d", mins, secs)
        tvRestCountdown.setTextColor(
            if (isTimerPaused) Color.parseColor("#FFD600") else Color.WHITE
        )

        val fraction = (remSec.toFloat() / totalRestSeconds.toFloat()).coerceIn(0f, 1f)
        val ringColor = when {
            isTimerPaused -> "#FFD600"
            isTransitionRest -> "#00B0FF"
            remSec <= 10 -> "#FF5252"
            else -> "#00E676"
        }
        ringView.setRingState(fraction, ringColor)

        if (remSec <= 0 && !isTimerPaused && !hasVibratedForCurrentRest) {
            hasVibratedForCurrentRest = true
            triggerRestFinishedAlarm()
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerRestFinishedAlarm() {
        try {
            val wl = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GymWatch::RestFinishedWakeLock"
            )
            wl.acquire(6000L)
        } catch (_: Exception) {}

        if (vibrator.hasVibrator()) {
            val timings = longArrayOf(0, 350, 150, 350, 150, 500)
            if (vibrator.hasAmplitudeControl()) {
                val amps = intArrayOf(0, 255, 0, 255, 0, 255)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
            } else {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        }

        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        } catch (_: Exception) {}
    }

    private fun buildCircularDashboardUi() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        ringView = CircularProgressRingView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        rootFrame.addView(ringView)

        layoutRoutinePickerScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }

        val routineRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(24), dpToPx(22), dpToPx(24), dpToPx(28))
        }

        val tvAppHeader = TextView(this).apply {
            text = "DIAMANTIZADOR"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        routineRoot.addView(tvAppHeader)

        tvRoutineSyncStatus = TextView(this).apply {
            text = "Conectando con tu móvil..."
            setTextColor(Color.parseColor("#9E9EA7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(2), 0, dpToPx(6))
        }
        routineRoot.addView(tvRoutineSyncStatus)

        layoutRoutinesListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        routineRoot.addView(layoutRoutinesListContainer)

        val btnManualSync = createStyledButton("🔄 Reconectar", "#161920", "#2A2F3D", 34) {
            vibrateTick()
            tvRoutineSyncStatus.text = "Despertando app en el móvil..."
            requestSyncFromPhone()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(130),
                dpToPx(34)
            ).apply {
                topMargin = dpToPx(8)
            }
        }
        routineRoot.addView(btnManualSync)

        layoutRoutinePickerScroll.addView(routineRoot)
        rootFrame.addView(layoutRoutinePickerScroll)

        layoutActiveSetDashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvSetBadge = TextView(this).apply {
            text = "EJ 1/6  •  SERIE 1/3"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layoutActiveSetDashboard.addView(tvSetBadge)

        btnExerciseTitle = Button(this).apply {
            text = "Press de banca ▾"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(176),
                dpToPx(26)
            ).apply {
                topMargin = dpToPx(2)
            }
            setOnClickListener {
                vibrateTick()
                showScreen(layoutExercisePickerScroll)
            }
        }
        styleButton(btnExerciseTitle, "#161920", "#2A2F3D", 13)
        layoutActiveSetDashboard.addView(btnExerciseTitle)

        tvAdviceCompact = TextView(this).apply {
            text = "Ant: 70kg × 9"
            setTextColor(Color.parseColor("#9E9EA7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(3))
        }
        layoutActiveSetDashboard.addView(tvAdviceCompact)

        btnWeightCenter = Button(this)
        layoutActiveSetDashboard.addView(
            buildCircularStepperBar(
                centerBtn = btnWeightCenter,
                accentHex = "#00E676",
                onMinus = {
                    currentWeightKg = ((currentWeightKg - weightStepKg) * 100.0).roundToInt() / 100.0
                    if (currentWeightKg < 0.0) currentWeightKg = 0.0
                    vibrateTick()
                    refreshDashboardValuesUi()
                },
                onCenterTap = {
                    vibrateTick()
                    openPrecisionKeypad(forWeight = true)
                },
                onPlus = {
                    currentWeightKg = ((currentWeightKg + weightStepKg) * 100.0).roundToInt() / 100.0
                    vibrateTick()
                    refreshDashboardValuesUi()
                }
            )
        )

        btnRepsCenter = Button(this)
        layoutActiveSetDashboard.addView(
            buildCircularStepperBar(
                centerBtn = btnRepsCenter,
                accentHex = "#00B0FF",
                onMinus = {
                    currentReps = (currentReps - 1).coerceAtLeast(1)
                    vibrateTick()
                    refreshDashboardValuesUi()
                },
                onCenterTap = {
                    vibrateTick()
                    openPrecisionKeypad(forWeight = false)
                },
                onPlus = {
                    currentReps += 1
                    vibrateTick()
                    refreshDashboardValuesUi()
                }
            )
        )

        val rirRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(174),
                dpToPx(26)
            ).apply {
                topMargin = dpToPx(4)
            }
        }
        rirButtons.clear()
        val rirLabels = listOf("RIR 0", "RIR 1", "RIR 2", "3+")
        for (rIdx in 0..3) {
            val b = Button(this).apply {
                text = rirLabels[rIdx]
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dpToPx(2)
                    marginEnd = dpToPx(2)
                }
                setOnClickListener {
                    currentRir = rIdx
                    vibrateTick()
                    refreshDashboardValuesUi()
                }
            }
            rirButtons.add(b)
            rirRow.addView(b)
        }
        layoutActiveSetDashboard.addView(rirRow)

        val btnCompleteSet = Button(this).apply {
            text = "✓ COMPLETAR"
            setTextColor(Color.parseColor("#003816"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(138),
                dpToPx(34)
            ).apply {
                topMargin = dpToPx(5)
            }
            setOnClickListener {
                vibrateConfirmation()
                sendCommandToPhone(
                    "COMPLETE_SET",
                    mapOf(
                        "weightKg" to currentWeightKg,
                        "reps" to currentReps,
                        "rir" to currentRir
                    )
                )
            }
        }
        styleButton(btnCompleteSet, "#00E676", "#00E676", 17)
        layoutActiveSetDashboard.addView(btnCompleteSet)

        rootFrame.addView(layoutActiveSetDashboard)

        layoutPrecisionKeypad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(dpToPx(18), dpToPx(12), dpToPx(18), dpToPx(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvKeypadTitle = TextView(this).apply {
            text = "AJUSTE EXACTO DE PESO"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layoutPrecisionKeypad.addView(tvKeypadTitle)

        tvKeypadDisplay = TextView(this).apply {
            text = "70 kg"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(1), 0, dpToPx(3))
        }
        layoutPrecisionKeypad.addView(tvKeypadDisplay)

        layoutQuickDeltasRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(184),
                dpToPx(26)
            ).apply {
                bottomMargin = dpToPx(3)
            }
        }
        layoutPrecisionKeypad.addView(layoutQuickDeltasRow)

        val keysRows = listOf(
            listOf("1", "2", "3", "4"),
            listOf("5", "6", "7", "8"),
            listOf("9", "0", ".", "⌫")
        )
        for (rowKeys in keysRows) {
            val kRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(176), dpToPx(27)).apply {
                    topMargin = dpToPx(2)
                }
            }
            for (k in rowKeys) {
                val kb = Button(this).apply {
                    text = k
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = dpToPx(2)
                        marginEnd = dpToPx(2)
                    }
                    setOnClickListener {
                        vibrateTick()
                        onKeypadKeyPressed(k)
                    }
                }
                styleButton(kb, "#161920", "#2A2F3D", 8)
                kRow.addView(kb)
            }
            layoutPrecisionKeypad.addView(kRow)
        }

        val btnKeypadDone = Button(this).apply {
            text = "✓ LISTO"
            setTextColor(Color.parseColor("#003816"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpToPx(124), dpToPx(30)).apply {
                topMargin = dpToPx(4)
            }
            setOnClickListener {
                vibrateConfirmation()
                commitKeypadValueAndClose()
            }
        }
        styleButton(btnKeypadDone, "#00E676", "#00E676", 15)
        layoutPrecisionKeypad.addView(btnKeypadDone)

        rootFrame.addView(layoutPrecisionKeypad)

        layoutRestTimerDashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvRestBadge = TextView(this).apply {
            text = "DESCANSO"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        layoutRestTimerDashboard.addView(tvRestBadge)

        tvRestCountdown = TextView(this).apply {
            text = "02:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        layoutRestTimerDashboard.addView(tvRestCountdown)

        tvRestFeedback = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9E9EA7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(6), 0, dpToPx(6), dpToPx(6))
        }
        layoutRestTimerDashboard.addView(tvRestFeedback)

        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(168), dpToPx(32))
        }

        val btnMinus30 = createCompactActionBtn("-30s") {
            vibrateTick()
            sendCommandToPhone("ADD_REST_TIME", mapOf("seconds" to -30))
        }
        btnPauseResumeRest = createCompactActionBtn("⏸") {
            vibrateTick()
            sendCommandToPhone("TOGGLE_PAUSE")
        }
        val btnPlus30 = createCompactActionBtn("+30s") {
            vibrateTick()
            sendCommandToPhone("ADD_REST_TIME", mapOf("seconds" to 30))
        }

        timerRow.addView(btnMinus30)
        timerRow.addView(btnPauseResumeRest)
        timerRow.addView(btnPlus30)
        layoutRestTimerDashboard.addView(timerRow)

        val btnSkipRest = Button(this).apply {
            text = "▶ SIGUIENTE SERIE"
            setTextColor(Color.parseColor("#003816"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpToPx(138), dpToPx(32)).apply {
                topMargin = dpToPx(6)
            }
            setOnClickListener {
                vibrateConfirmation()
                sendCommandToPhone("SKIP_REST")
            }
        }
        styleButton(btnSkipRest, "#00E676", "#00E676", 16)
        layoutRestTimerDashboard.addView(btnSkipRest)

        rootFrame.addView(layoutRestTimerDashboard)

        layoutExercisePickerScroll = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
        }

        val pickerInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(24), dpToPx(22), dpToPx(24), dpToPx(28))
        }

        val tvPickerHeader = TextView(this).apply {
            text = "ELEGIR EJERCICIO"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(6))
        }
        pickerInner.addView(tvPickerHeader)

        layoutExerciseListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        pickerInner.addView(layoutExerciseListContainer)

        val btnBackFromPicker = createStyledButton("⬅ Volver", "#1F232D", "#2A2F3D", 36) {
            showScreen(layoutActiveSetDashboard)
        }
        pickerInner.addView(btnBackFromPicker)

        layoutExercisePickerScroll.addView(pickerInner)
        rootFrame.addView(layoutExercisePickerScroll)

        setContentView(rootFrame)
        refreshDashboardValuesUi()
    }

    private fun buildCircularStepperBar(
        centerBtn: Button,
        accentHex: String,
        onMinus: () -> Unit,
        onCenterTap: () -> Unit,
        onPlus: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(192),
                dpToPx(34)
            ).apply {
                topMargin = dpToPx(3)
            }
        }

        val btnMinus = Button(this).apply {
            text = "−"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onMinus() }
        }
        styleButton(btnMinus, "#161920", "#2A2F3D", 12)

        centerBtn.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            setOnClickListener { onCenterTap() }
        }
        styleButton(centerBtn, "#12151B", accentHex, 12)

        val btnPlus = Button(this).apply {
            text = "+"
            setTextColor(Color.parseColor(accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { onPlus() }
        }
        styleButton(btnPlus, "#161920", accentHex, 12)

        row.addView(btnMinus)
        row.addView(centerBtn)
        row.addView(btnPlus)
        return row
    }

    private fun openPrecisionKeypad(forWeight: Boolean) {
        isEditingWeight = forWeight
        keypadBuffer = ""

        layoutQuickDeltasRow.removeAllViews()
        if (forWeight) {
            tvKeypadTitle.text = "TOCA ± O ESCRIBE PESO EXACTO"
            tvKeypadDisplay.text = "${formatWeightClean(currentWeightKg)} kg"

            val deltas = listOf(-2.5, -1.0, -0.5, +0.5, +1.0, +2.5)
            for (d in deltas) {
                val sign = if (d > 0) "+" else ""
                val lbl = "$sign${formatWeightClean(d)}"
                val b = Button(this).apply {
                    text = lbl
                    setTextColor(if (d > 0) Color.parseColor("#00E676") else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = dpToPx(1)
                        marginEnd = dpToPx(1)
                    }
                    setOnClickListener {
                        vibrateTick()
                        keypadBuffer = ""
                        currentWeightKg = ((currentWeightKg + d).coerceAtLeast(0.0) * 100.0).roundToInt() / 100.0
                        weightStepKg = abs(d)
                        tvKeypadDisplay.text = "${formatWeightClean(currentWeightKg)} kg"
                        refreshDashboardValuesUi()
                    }
                }
                styleButton(b, "#1F232D", "#2A2F3D", 7)
                layoutQuickDeltasRow.addView(b)
            }
        } else {
            tvKeypadTitle.text = "TOCA ± O ESCRIBE REPS EXACTAS"
            tvKeypadDisplay.text = "$currentReps reps"

            val deltas = listOf(-5, -2, -1, +1, +2, +5)
            for (d in deltas) {
                val sign = if (d > 0) "+" else ""
                val b = Button(this).apply {
                    text = "$sign$d"
                    setTextColor(if (d > 0) Color.parseColor("#00B0FF") else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = dpToPx(1)
                        marginEnd = dpToPx(1)
                    }
                    setOnClickListener {
                        vibrateTick()
                        keypadBuffer = ""
                        currentReps = (currentReps + d).coerceAtLeast(1)
                        tvKeypadDisplay.text = "$currentReps reps"
                        refreshDashboardValuesUi()
                    }
                }
                styleButton(b, "#1F232D", "#2A2F3D", 7)
                layoutQuickDeltasRow.addView(b)
            }
        }

        showScreen(layoutPrecisionKeypad)
    }

    private fun onKeypadKeyPressed(key: String) {
        when (key) {
            "⌫" -> {
                if (keypadBuffer.isNotEmpty()) {
                    keypadBuffer = keypadBuffer.dropLast(1)
                }
            }
            "." -> {
                if (isEditingWeight && !keypadBuffer.contains(".")) {
                    keypadBuffer = if (keypadBuffer.isEmpty()) "0." else "$keypadBuffer."
                }
            }
            else -> {
                if (keypadBuffer.length < 6) {
                    keypadBuffer += key
                }
            }
        }

        val unit = if (isEditingWeight) "kg" else "reps"
        if (keypadBuffer.isEmpty()) {
            val fallback = if (isEditingWeight) formatWeightClean(currentWeightKg) else "$currentReps"
            tvKeypadDisplay.text = "$fallback $unit"
        } else {
            tvKeypadDisplay.text = "$keypadBuffer $unit"
        }
    }

    private fun commitKeypadValueAndClose() {
        if (keypadBuffer.isNotBlank()) {
            if (isEditingWeight) {
                val parsed = keypadBuffer.toDoubleOrNull()
                if (parsed != null && parsed >= 0.0 && parsed <= 999.0) {
                    currentWeightKg = parsed
                }
            } else {
                val parsed = keypadBuffer.toDoubleOrNull()?.toInt()
                if (parsed != null && parsed in 1..200) {
                    currentReps = parsed
                }
            }
        }
        refreshDashboardValuesUi()
        showScreen(layoutActiveSetDashboard)
    }

    private fun populateRoutinesSelectionMenu(routinesArr: JSONArray) {
        tvRoutineSyncStatus.text = "Elige entrenamiento para empezar:"
        layoutRoutinesListContainer.removeAllViews()

        for (i in 0 until routinesArr.length()) {
            val obj = routinesArr.getJSONObject(i)
            val id = obj.optInt("id", i + 1)
            val name = obj.optString("name", "Sesión")
            val isToday = obj.optBoolean("isToday", false)

            val label = if (isToday) "🔥 HOY: $name" else "▶ $name"
            val btn = createStyledButton(
                label,
                if (isToday) "#003816" else "#161920",
                if (isToday) "#00E676" else "#2A2F3D",
                38
            ) {
                vibrateConfirmation()
                tvRoutineSyncStatus.text = "Abriendo $name..."
                sendCommandToPhone("START_ROUTINE_FROM_WATCH", mapOf("routineDayId" to id))
            }
            layoutRoutinesListContainer.addView(btn)
        }
    }

    private fun populateExercisePickerList(exArray: JSONArray, currentIdx: Int) {
        layoutExerciseListContainer.removeAllViews()
        for (i in 0 until exArray.length()) {
            val obj = exArray.getJSONObject(i)
            val idx = obj.optInt("index", i)
            val name = obj.optString("name", "Ejercicio")
            val done = obj.optInt("done", 0)
            val target = obj.optInt("target", 3)
            val isCompleted = done >= target
            val isCurrent = idx == currentIdx

            val prefix = if (isCompleted) "✓ " else "${idx + 1}. "
            val btn = createStyledButton(
                "$prefix$name ($done/$target)",
                if (isCurrent) "#003816" else "#161920",
                if (isCurrent) "#00E676" else "#2A2F3D",
                38
            ) {
                vibrateTick()
                sendCommandToPhone("SELECT_EXERCISE", mapOf("exerciseIndex" to idx))
                showScreen(layoutActiveSetDashboard)
            }
            layoutExerciseListContainer.addView(btn)
        }
    }

    private fun refreshDashboardValuesUi() {
        btnWeightCenter.text = "${formatWeightClean(currentWeightKg)} kg ✎"
        btnRepsCenter.text = "$currentReps reps ✎"

        for ((idx, btn) in rirButtons.withIndex()) {
            val isSel = idx == currentRir
            val activeColor = when (idx) {
                0 -> "#FFD600"
                1, 2 -> "#00E676"
                else -> "#FF9100"
            }
            if (isSel) {
                btn.setTextColor(Color.BLACK)
                styleButton(btn, activeColor, activeColor, 8)
            } else {
                btn.setTextColor(Color.parseColor("#9E9EA7"))
                styleButton(btn, "#161920", "#2A2F3D", 8)
            }
        }
    }

    private fun formatWeightClean(w: Double): String {
        return if (w % 1.0 == 0.0) {
            w.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", w).trimEnd('0').trimEnd('.')
        }
    }

    private fun createCompactActionBtn(label: String, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(3)
                marginEnd = dpToPx(3)
            }
            setOnClickListener { onClick() }
        }
        styleButton(btn, "#1F232D", "#2A2F3D", 12)
        return btn
    }

    private fun showScreen(target: View) {
        layoutRoutinePickerScroll.visibility = if (target === layoutRoutinePickerScroll) View.VISIBLE else View.GONE
        layoutActiveSetDashboard.visibility = if (target === layoutActiveSetDashboard) View.VISIBLE else View.GONE
        layoutPrecisionKeypad.visibility = if (target === layoutPrecisionKeypad) View.VISIBLE else View.GONE
        layoutRestTimerDashboard.visibility = if (target === layoutRestTimerDashboard) View.VISIBLE else View.GONE
        layoutExercisePickerScroll.visibility = if (target === layoutExercisePickerScroll) View.VISIBLE else View.GONE
    }

    private fun createStyledButton(
        title: String,
        fillHex: String,
        strokeHex: String,
        heightDp: Int,
        onClick: () -> Unit
    ): Button {
        val btn = Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(heightDp)
            ).apply {
                topMargin = dpToPx(5)
            }
            setOnClickListener { onClick() }
        }
        styleButton(btn, fillHex, strokeHex, 18)
        return btn
    }

    private fun styleButton(btn: Button, fillHex: String, strokeHex: String, cornerDp: Int) {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(cornerDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dpToPx(1), Color.parseColor(strokeHex))
        }
        val ripple = ColorStateList.valueOf(Color.parseColor("#38FFFFFF"))
        btn.background = RippleDrawable(ripple, shape, null)
        btn.stateListAnimator = null
    }

    private fun vibrateTick() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrateConfirmation() {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}