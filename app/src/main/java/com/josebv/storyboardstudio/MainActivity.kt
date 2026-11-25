package com.josebv.storyboardstudio

// BV'Storyboarder 0.9.39 - pencil rendering, opacity consistency and timeline toolbar alignment.

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import android.util.Base64
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    companion object { private const val REQUEST_IMAGE = 8042; private const val REQUEST_SRT = 8043 }
    private lateinit var drawing: DrawingView
    private lateinit var timelineView: TimelineView
    private lateinit var status: TextView
    private lateinit var durationLabel: TextView
    private lateinit var playPauseButton: Button
    private lateinit var shotLabel: TextView
    private lateinit var timelineZoomSeek: SeekBar
    private var headerStatusMessage: String = ""
    private var paletteDialog: AlertDialog? = null
    private var mediaPlayer: MediaPlayer? = null
    private var sequencePlaying = false
    private var sequenceStartUptime = 0L
    private var sequenceStartPosition = 0
    private val appStorageDirectory by lazy {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Storyboarder")
    }
    private val sessionFile by lazy { File(appStorageDirectory, "Autosave/session.sbd") }
    private val sessionsDirectory by lazy { File(appStorageDirectory, "sessions") }
    private val exportsDirectory by lazy { File(appStorageDirectory, "Exports/PNG") }
    private val pdfDirectory by lazy { File(appStorageDirectory, "Exports/PDF") }
    private var restoringSession = false
    private var audioUri: Uri? = null
    private var audioDurationMs = 0
    private var audioActionButton: View? = null
    private var cutModeButton: Button? = null
    private var waveform = FloatArray(0)
    private val recentColors = mutableListOf<Int>()
    private var recentColorContainer: LinearLayout? = null
    private var inlineColorPicker: HueSatValPickerView? = null
    private var selectedColorIndicator: View? = null
    private var selectionActionsView: View? = null
    private var brushSizeSlider: VerticalSliderView? = null

    private val frameDurations = mutableListOf(3000)
    private var currentFrame = 0
    private var projectName = "Mi storyboard"
    private lateinit var projectTitleLabel: TextView
    private var updatingFromPlayback = false
    private val undoHistory = ArrayDeque<String>()
    private val redoHistory = ArrayDeque<String>()
    private var restoringHistory = false
    private var lastHistoryState: String? = null
    private val maxHistorySteps = 10

    private val handler = Handler(Looper.getMainLooper())
    private val playbackUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            val position = when {
                player?.isPlaying == true -> player.currentPosition
                sequencePlaying -> sequenceStartPosition + (SystemClock.uptimeMillis() - sequenceStartUptime).toInt()
                else -> return
            }
            val bounded = position.coerceAtMost(totalDuration())
            timelineView.setPlayhead(bounded)
            val frame = if (bounded >= totalDuration()) frameDurations.lastIndex else frameAtTime(bounded)
            if (frame != currentFrame && frame in frameDurations.indices) {
                currentFrame = frame
                drawing.setFrame(frame)
                updateFrameStatus()
                timelineView.setSelectedFrame(frame)
            }
            if (sequencePlaying && bounded >= totalDuration()) {
                stopPlayback()
                selectFrame(frameDurations.lastIndex, false)
                timelineView.setPlayhead(totalDuration())
                return
            }
            handler.postDelayed(this, 35)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildInterface()
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 910)
        } else {
            initializeSessionStorage()
        }
    }

    private fun initializeSessionStorage() {
        prepareStorageFolders()
        migratePreviousStorage()
        loadSession()
        resetHistory()
        handler.removeCallbacks(autosaveRunnable)
        handler.postDelayed(autosaveRunnable, 15_000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 910) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                initializeSessionStorage()
            } else {
                setStatus("Se necesita permiso de almacenamiento para usar Documentos/Storyboarder")
                Toast.makeText(this, "Autoriza el almacenamiento para guardar sesiones en Documentos/Storyboarder", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildInterface() {
        drawing = DrawingView(this).apply {
            onChanged = {
                scheduleAutosave()
            }
            onHistoryAction = {
                if (!restoringHistory) recordHistory()
            }
            onColorUsed = { usedColor -> rememberRecentColor(usedColor) }
        }
        if (recentColors.isEmpty()) {
            recentColors.addAll(listOf(
                Color.rgb(48, 160, 235), Color.rgb(73, 190, 40), Color.rgb(112, 95, 220),
                Color.rgb(238, 83, 139), Color.rgb(245, 190, 35), Color.rgb(32, 205, 170), Color.rgb(18, 49, 75)
            ))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(45, 45, 45))
            setPadding(0, 0, 0, 0)
        }

        // Top menu: fixed visual proportions based on the 2000 x 1200 reference.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(65, 65, 65))
            setPadding(0, 0, dp(10), 0)
        }
        // Acciones de Archivo visibles permanentemente en el encabezado.
        fun headerAction(label: String, widthDp: Int, action: () -> Unit) {
            val button = addHeaderButton(top, label, dp(widthDp), action)
            button.textSize = 9f
            button.setPadding(dp(1), 0, dp(1), 0)
            button.maxLines = 1
        }
        headerAction("Nueva", 52, ::confirmNewSession)
        headerAction("Abrir sesión", 70, ::openSessionFile)
        headerAction("Abrir", 48, ::showLocalSessionsDialog)
        headerAction("Guardar como", 76, ::saveSessionAsFile)
        headerAction("Guardar copia", 80, ::saveLocalSnapshot)
        val saveHeaderButton = addHeaderButton(top, "Guardar", dp(60)) { saveProject() }
        saveHeaderButton.textSize = 10f
        projectTitleLabel = TextView(this).apply {
            text = projectName
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(10), 0, dp(8), 0)
            maxWidth = dp(220)
            setOnClickListener { editProjectName() }
            contentDescription = "Nombre de la sesión. Toca para cambiarlo."
        }
        top.addView(projectTitleLabel, LinearLayout.LayoutParams(-2, -1))
        status = TextView(this).apply {
            setTextColor(Color.rgb(195, 205, 215))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(0, 0, dp(8), 0)
        }
        top.addView(status, LinearLayout.LayoutParams(0, -1, 1f))
        val brand = TextView(this).apply {
            text = "BV'Storyboarder  v${packageManager.getPackageInfo(packageName, 0).versionName}"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setPadding(0, 0, dp(14), 0)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -1, 0.72f))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(40)))
        // Work area: left tool panel and a large central canvas.
        val work = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(48, 48, 48))
        }

        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(67, 67, 67))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val sideContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }

        val picker = HueSatValPickerView(
            this, drawing.currentHue(), drawing.currentSaturation() / 100f, drawing.currentValue() / 100f
        ) { h, sat, v ->
            drawing.setColorFromHsv(h, sat, v)
            updateSelectedColorIndicator()
            scheduleAutosave()
        }
        inlineColorPicker = picker
        selectedColorIndicator = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(drawing.currentColor())
                setStroke(dp(1), Color.WHITE)
                cornerRadius = dp(4).toFloat()
            }
            contentDescription = "Color seleccionado"
        }
        sideContent.addView(selectedColorIndicator, LinearLayout.LayoutParams(-1, dp(24)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(dp(12), dp(2), dp(12), dp(3))
        })
        sideContent.addView(picker, LinearLayout.LayoutParams(-1, 0, 2.8f).apply {
            setMargins(dp(0.25f), dp(0.25f), dp(0.25f), dp(4))
        })

        val swatchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Match the horizontal inset of the hue bar inside HueSatValPickerView.
            setPadding(dp(12), dp(0), dp(12), dp(6))
        }
        recentColorContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        swatchWrap.addView(recentColorContainer, LinearLayout.LayoutParams(-1, dp(24)))
        val grayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val grays = intArrayOf(Color.BLACK, 0xFF303030.toInt(), 0xFF555555.toInt(), 0xFF808080.toInt(),
            0xFFAAAAAA.toInt(), 0xFFD5D5D5.toInt(), Color.WHITE)
        grays.forEach { color ->
            val chip = View(this).apply {
                setBackgroundColor(color)
                setOnClickListener {
                    drawing.setColor(color)
                    picker.setColor(color)
                    updateSelectedColorIndicator()
                    scheduleAutosave()
                }
            }
            grayRow.addView(chip, LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                setMargins(dp(0.25f), dp(0.25f), dp(0.25f), dp(0.25f))
            })
        }
        swatchWrap.addView(grayRow, LinearLayout.LayoutParams(-1, dp(24)))
        sideContent.addView(swatchWrap, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(0, 0, 0, dp(8))
        })
        refreshRecentColorSwatches()

        // Tool buttons: two explicit weighted rows. This avoids GridLayout width collapse
        // observed on some tablet densities with zero-width weighted children.
        val toolsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }

        val toolButtons = mutableListOf<Button>()
        fun tool(iconName: String, description: String, action: () -> Unit): Button {
            val button = Button(this).apply {
                text = ""
                contentDescription = description
                textSize = 20f
                isAllCaps = false
                setTextColor(Color.LTGRAY)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                elevation = 0f
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                stateListAnimator = null
                setOnClickListener {
                    if (description != "Selección tipo lazo") {
                        drawing.cancelSelection()
                        selectionActionsView?.visibility = View.GONE
                    }
                    action()
                }
                setButtonIcon(this, iconName, 40)
            }
            toolButtons.add(button)
            return button
        }

        val toolRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        val toolRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }

        fun addToolCell(row: LinearLayout, button: Button) {
            // Exact zero margins: adjacent button bounds share an edge.
            row.addView(button, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(0, 0, dp(1), 0)
            })
        }

        addToolCell(toolRow1, tool("ic_pencil", "Lápiz") {
            drawing.setEraser(false)
            brushSizeSlider?.setValue(drawing.activeBrushSize().toInt())
        })
        addToolCell(toolRow1, tool("ic_eraser", "Goma") {
            drawing.setEraser(true)
            brushSizeSlider?.setValue(drawing.activeBrushSize().toInt())
        })
        addToolCell(toolRow1, tool("ic_text", "Texto") { addTextNote() })

        addToolCell(toolRow2, tool("ic_pressure", "Presión del lápiz") {
            drawing.configureBrush(
                drawing.activeBrushSize(),
                drawing.brushOpacity() * 255 / 100,
                !drawing.pressureEnabled()
            )
            setStatus(if (drawing.pressureEnabled()) "Presión activada" else "Presión desactivada")
            scheduleAutosave()
        })
        addToolCell(toolRow2, tool("ic_clear", "Limpiar lienzo") { drawing.clear(); scheduleAutosave() })
        val lassoButton = tool("ic_pressure", "Selección tipo lazo") {
            drawing.startLassoSelection()
            selectionActionsView?.visibility = View.VISIBLE
        }
        addToolCell(toolRow2, lassoButton)
        toolButtons.forEach { it.compoundDrawables.getOrNull(1)?.alpha = 128 }

        // Tool buttons are flush: row height matches button height exactly, with no hidden vertical gap.
        toolsGrid.addView(toolRow1, LinearLayout.LayoutParams(-1, dp(40)))
        toolsGrid.addView(toolRow2, LinearLayout.LayoutParams(-1, dp(40)))
        sideContent.addView(toolsGrid, LinearLayout.LayoutParams(-1, dp(80)))

        val importLabel = TextView(this).apply {
            text = "Importar"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        sideContent.addView(importLabel, LinearLayout.LayoutParams(-1, dp(25)))
        val imports = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        fun importButton(iconName: String, description: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = ""
                contentDescription = description
                textSize = 10f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTextColor(Color.LTGRAY)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                elevation = 0f
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
                setButtonIcon(this, iconName, 40)
                tag = false
                compoundDrawables.getOrNull(1)?.alpha = 128
            }
            imports.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(0, 0, dp(1), 0)
            })
        }
        importButton("ic_import_image", "Importar imágenes") { chooseImage() }
        // Temporary icon: reuse the PDF export artwork until the final SRT icon is supplied.
        importButton("ic_import_srt", "Importar archivo SRT") { chooseSrt() }
        sideContent.addView(imports, LinearLayout.LayoutParams(-1, dp(40)))

        val exportSpacer = View(this)
        sideContent.addView(exportSpacer, LinearLayout.LayoutParams(1, 0, 0.35f))
        val exportLabel = TextView(this).apply {
            text = "Exportar"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        sideContent.addView(exportLabel, LinearLayout.LayoutParams(-1, dp(25)))
        val exports = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        fun exportButton(iconName: String, description: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = ""
                contentDescription = description
                textSize = 10f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTextColor(Color.LTGRAY)
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                elevation = 0f
                stateListAnimator = null
                setPadding(0, 0, 0, 0)
                setOnClickListener { action() }
                setButtonIcon(this, iconName, 40)
                tag = false
                compoundDrawables.getOrNull(1)?.alpha = 128
            }
            exports.addView(b, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(0, 0, dp(1), 0)
            })
        }
        exportButton("ic_export_png", "Exportar plano PNG") { exportCurrentPng() }
        exportButton("ic_export_all", "Exportar todos los planos PNG") { exportAllPng() }
        exportButton("ic_export_pdf", "Exportar PDF") { exportPdf() }
        sideContent.addView(exports, LinearLayout.LayoutParams(-1, dp(40)))
        sidebar.addView(sideContent, LinearLayout.LayoutParams(0, -1, 1f))

        // Sliders are positioned beside the canvas, matching the annotated layout.
        val sizeSlider = VerticalSliderView(this, "", 1, 200,
            drawing.activeBrushSize().toInt()) { value ->
            drawing.setActiveBrushSize(value.toFloat())
            scheduleAutosave()
        }
        brushSizeSlider = sizeSlider
        val opacitySlider = VerticalSliderView(this, "", 0, 100,
            drawing.brushOpacity()) { value ->
            drawing.configureBrush(drawing.activeBrushSize(), value * 255 / 100, drawing.pressureEnabled())
            scheduleAutosave()
        }
        val divider = View(this).apply { setBackgroundColor(Color.rgb(105, 105, 105)) }
        sidebar.addView(divider, LinearLayout.LayoutParams(dp(1), -1))
        work.addView(sidebar, LinearLayout.LayoutParams(0, -1, 0.12f))

        val canvasArea = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(48, 48, 48))
            clipChildren = false
            clipToPadding = false
        }
        canvasArea.addView(drawing, FrameLayout.LayoutParams(-1, -1))

        val canvasButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addCanvasIconButton(canvasButtons, "ic_undo", "Deshacer") { undoGlobal() }
        addCanvasIconButton(canvasButtons, "ic_redo", "Rehacer") { redoGlobal() }
        canvasArea.addView(canvasButtons, FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(32)
            topMargin = dp(18)
        })
        val selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val acceptSelection = addCompactButton(selectionActions, "Aceptar", "Confirmar transformación") {
            drawing.confirmSelection()
            selectionActionsView?.visibility = View.GONE
            recordHistory()
            scheduleAutosave()
        }
        val cancelSelection = addCompactButton(selectionActions, "Cancelar", "Cancelar selección") {
            drawing.cancelSelection()
            selectionActionsView?.visibility = View.GONE
        }
        selectionActionsView = selectionActions
        canvasArea.addView(selectionActions, FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP or Gravity.RIGHT).apply {
            rightMargin = dp(12)
            topMargin = dp(12)
        })

        val sliderRail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        sliderRail.addView(sizeSlider, LinearLayout.LayoutParams(dp(32), 0, 1f))
        sliderRail.addView(opacitySlider, LinearLayout.LayoutParams(dp(32), 0, 1f))
        canvasArea.addView(
            sliderRail,
            FrameLayout.LayoutParams(dp(32), -1, Gravity.LEFT or Gravity.TOP).apply {
                leftMargin = -dp(6)
                topMargin = 0
                rightMargin = 0
                bottomMargin = 0
            }
        )
        val centerButton = Button(this).apply {
            text = ""
            contentDescription = "Centrar vista"
            textSize = 17f
            setPadding(0, 0, 0, 0)
            minWidth = dp(38)
            setTextColor(Color.LTGRAY)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(83, 83, 83)))
            setOnClickListener { drawing.resetViewport() }
            setButtonIcon(this, "ic_fit_canvas", 34, transparent = false)
        }
        canvasArea.addView(centerButton, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = dp(32)
            bottomMargin = dp(12)
        })
        work.addView(canvasArea, LinearLayout.LayoutParams(0, -1, 0.83f))
        root.addView(work, LinearLayout.LayoutParams(-1, 0, 2.4f))

        // Timeline toolbar: shot management left, playback centered, zoom/navigation right.
        val timelinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(55, 55, 55))
            setPadding(0, 0, 0, 0)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        val shotGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addIconCompactButton(shotGroup, "ic_add_frame", "Nuevo plano") { addFrame() }
        cutModeButton = addIconCompactButton(shotGroup, "ic_cut_frame", "Cortar plano") {
            timelineView.splitMode = !timelineView.splitMode
            cutModeButton?.let { button ->
                button.tag = timelineView.splitMode
                button.compoundDrawables.getOrNull(1)?.alpha = if (timelineView.splitMode) 255 else 128
            }
        }.apply {
            tag = false
            compoundDrawables.getOrNull(1)?.alpha = 128
        }
        addIconCompactButton(shotGroup, "ic_duplicate_frame", "Duplicar plano") { duplicateFrame() }
        addIconCompactButton(shotGroup, "ic_delete_frame", "Eliminar plano") { deleteFrame() }
        shotLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = "Plano seleccionado y duración"
        }
        shotGroup.addView(shotLabel, LinearLayout.LayoutParams(dp(112), -1))
        addIconCompactButton(shotGroup, "ic_duration_down", "Reducir duración") { changeDuration(-500) }
        addIconCompactButton(shotGroup, "ic_duration_up", "Aumentar duración") { changeDuration(500) }
        addIconCompactButton(shotGroup, "ic_move_before", "Mover plano antes") { moveSelectedFrame(-1) }
        addIconCompactButton(shotGroup, "ic_move_after", "Mover plano después") { moveSelectedFrame(1) }
        toolbar.addView(shotGroup, LinearLayout.LayoutParams(-2, dp(40)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val playback = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addIconCompactButton(playback, "ic_first_frame", "Ir al inicio") { seekToTimelinePosition(0) }
        addIconCompactButton(playback, "ic_previous_frame", "Plano anterior") {
            stopPlayback(); selectFrame((currentFrame - 1).coerceAtLeast(0), true)
        }
        playPauseButton = addIconCompactButton(playback, "ic_play", "Reproducir o pausar", sizeDp = 28) { toggleAudio() }
        addIconCompactButton(playback, "ic_next_frame", "Plano siguiente") {
            stopPlayback(); selectFrame((currentFrame + 1).coerceAtMost(frameDurations.lastIndex), true)
        }
        addIconCompactButton(playback, "ic_last_frame", "Ir al final") { seekToTimelinePosition(totalDuration()) }
        toolbar.addView(playback, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val zoomGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addIconCompactButton(zoomGroup, "ic_center_timeline", "Ir al plano seleccionado") { timelineView.centerOnPlayhead() }
        addIconCompactButton(zoomGroup, "ic_zoom_out", "Alejar timeline") {
            timelineZoomSeek.progress = timelineView.changeZoom(1f / 1.25f)
        }
        timelineZoomSeek = SeekBar(this).apply {
            max = 100
            progress = 35
            setOnSeekBarChangeListener(simpleSeekListener {
                timelineView.setZoomProgress(progress)
            })
        }
        zoomGroup.addView(timelineZoomSeek, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setMargins(dp(4), 0, dp(4), 0)
        })
        addIconCompactButton(zoomGroup, "ic_zoom_in", "Acercar timeline") {
            timelineZoomSeek.progress = timelineView.changeZoom(1.25f)
        }
        toolbar.addView(zoomGroup, LinearLayout.LayoutParams(dp(430), dp(40)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        // Keep the toolbar and all three control groups exactly the same height.
        // This prevents the zoom slider or shot label from increasing the row height
        // and pinning the icon buttons to its upper edge.
        listOf(shotGroup, playback, zoomGroup).forEach { group ->
            group.gravity = Gravity.CENTER
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Button) {
                    child.setPadding(0, 0, 0, 0)
                    child.gravity = Gravity.CENTER
                    child.translationY = 0f
                }
            }
        }
        timelinePanel.addView(toolbar, LinearLayout.LayoutParams(-1, dp(40)))

        val timelineScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        timelineView = TimelineView(this).apply {
            onSelectFrame = { index -> selectFrame(index, true) }
            onDurationChanged = { index, duration ->
                if (index in frameDurations.indices) {
                    frameDurations[index] = duration
                    updateFrameStatus()
                    updateTimeline()
                    scheduleAutosave()
                }
            }
            onBoundaryChanged = { index, firstDuration, secondDuration ->
                if (index in 0 until frameDurations.lastIndex) {
                    frameDurations[index] = firstDuration
                    frameDurations[index + 1] = secondDuration
                    updateFrameStatus()
                    updateTimeline()
                    scheduleAutosave()
                }
            }
            onSeek = { position ->
                if (audioDurationMs > 0) {
                    mediaPlayer?.let { it.seekTo(position.coerceIn(0, max(0, audioDurationMs))) }
                }
                val idx = frameAtTime(position)
                if (idx != currentFrame) {
                    currentFrame = idx
                    drawing.setFrame(currentFrame)
                    updateFrameStatus()
                    timelineView.setSelectedFrame(currentFrame)
                }
                timelineView.setPlayhead(position)
            }
            onMoveFrame = { from, to -> moveFrame(from, to) }
            onResizeFinished = {
                recordHistory()
                scheduleAutosave()
            }
            onSplitFrame = { index, firstDuration -> splitFrame(index, firstDuration) }
            onRequestAudio = { chooseAudio() }
            projectLabel = projectName
        }
        timelineScroll.addView(timelineView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(178)
        ))
        val timelineContent = FrameLayout(this)
        timelineContent.addView(timelineScroll, FrameLayout.LayoutParams(-1, -1))
        // Reemplazar audio: mismo tamaño y presentación que el gráfico "Cargar audio",
        // cambiando únicamente el recurso gráfico. Sin fondo ni drawable comprimido.
        val replaceAudioButton = ImageView(this).apply {
            contentDescription = if (audioUri == null) "Agregar audio" else "Reemplazar audio"
            setImageResource(R.drawable.ic_replace_audio)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { chooseAudio() }
        }
        audioActionButton = replaceAudioButton
        timelineContent.addView(replaceAudioButton, FrameLayout.LayoutParams(dp(104), dp(38),
            Gravity.BOTTOM or Gravity.LEFT).apply {
            leftMargin = dp(4)
            bottomMargin = dp(3)
        })
        timelinePanel.addView(timelineContent, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(timelinePanel, LinearLayout.LayoutParams(-1, 0, 1f))

        applySystemTypeface(root)
        setContentView(root)
        updateFrameStatus()
        updateTimeline()
    }

    private fun setButtonIcon(button: Button, drawableName: String, sizeDp: Int = 30, transparent: Boolean = true) {
        val resourceId = resources.getIdentifier(drawableName, "drawable", packageName)
        if (resourceId == 0) return
        val bitmap = BitmapFactory.decodeResource(resources, resourceId) ?: return
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        val size = dp(sizeDp)
        drawable.setBounds(0, 0, size, size)
        button.text = ""
        drawable.alpha = 128
        button.setCompoundDrawables(null, drawable, null, null)
        var iconHovered = false
        var pressReleaseRunnable: Runnable? = null
        fun refreshIconAlpha(alpha: Int) {
            button.compoundDrawables.getOrNull(1)?.alpha = alpha
        }
        button.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    iconHovered = true
                    refreshIconAlpha(128)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    iconHovered = false
                    refreshIconAlpha(128)
                }
            }
            false
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressReleaseRunnable?.let { handler.removeCallbacks(it) }
                    refreshIconAlpha(255)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val reset = Runnable {
                        refreshIconAlpha(128)
                    }
                    pressReleaseRunnable = reset
                    handler.postDelayed(reset, 150)
                }
            }
            false
        }
        button.compoundDrawablePadding = 0
        button.gravity = Gravity.CENTER
        button.setPadding(0, 0, 0, 0)
        button.stateListAnimator = null
        button.elevation = 0f
        if (transparent) {
            button.backgroundTintList = null
            button.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            button.foreground = null
            button.stateListAnimator = null
            button.elevation = 0f
        }
    }

    private fun addIconCompactButton(
        parent: LinearLayout,
        drawableName: String,
        description: String,
        sizeDp: Int = 28,
        action: () -> Unit
    ): Button {
        val button = addCompactButton(parent, "", description, action)
        setButtonIcon(button, drawableName, sizeDp, transparent = true)
        button.setPadding(0, dp(8), 0, 0)
        button.isClickable = true
        return button
    }

    private fun addHeaderButton(parent: LinearLayout, label: String, width: Int, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label; isAllCaps = false; textSize = 12f
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.ColorDrawable(Color.rgb(83, 83, 83))
            setPadding(0, 0, 0, 0)
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(width, -1))
        return button
    }

    private fun addCanvasButton(parent: LinearLayout, label: String, description: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label; contentDescription = description; textSize = 18f
            setPadding(0, 0, 0, 0); minWidth = dp(38)
            setTextColor(Color.LTGRAY)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(83, 83, 83)))
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
    }

    private fun addCanvasIconButton(parent: LinearLayout, iconName: String, description: String, action: () -> Unit) {
        val button = Button(this).apply {
            contentDescription = description
            minWidth = dp(38); minimumWidth = dp(38)
            setTextColor(Color.LTGRAY)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(83, 83, 83)))
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
            setButtonIcon(this, iconName, 34, transparent = false)
        }
        parent.addView(button, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
    }

    private fun addCompactButton(parent: LinearLayout, label: String, description: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label; contentDescription = description
            textSize = if (label.length > 3) 9f else 16f
            isAllCaps = false; minWidth = dp(30); minimumWidth = dp(30)
            setPadding(dp(2), dp(3), dp(2), 0)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(78, 78, 78)))
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-2, dp(40)).apply {
            gravity = Gravity.CENTER
            setMargins(dp(3), 0, dp(3), 0)
        })
        return button
    }

    private fun rememberRecentColor(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > 7) recentColors.removeAt(recentColors.lastIndex)
        refreshRecentColorSwatches()
    }

    private fun updateSelectedColorIndicator() {
        val indicator = selectedColorIndicator ?: return
        indicator.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(drawing.currentColor())
            setStroke(dp(1), Color.WHITE)
            cornerRadius = dp(4).toFloat()
        }
        indicator.invalidate()
    }

    private fun refreshRecentColorSwatches() {
        val row = recentColorContainer ?: return
        row.removeAllViews()
        while (recentColors.size < 7) recentColors.add(Color.GRAY)
        recentColors.take(7).forEach { color ->
            val chip = View(this).apply {
                setBackgroundColor(color)
                setOnClickListener {
                    drawing.setColor(color)
                    inlineColorPicker?.setColor(color)
                    updateSelectedColorIndicator()
                    scheduleAutosave()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, dp(24), 1f).apply {
                setMargins(dp(0), 1, dp(0), 1)
            })
        }
    }

    private fun applySystemTypeface(view: View) {
        if (view is TextView) {
            val style = if (view.typeface?.isBold == true) Typeface.BOLD else Typeface.NORMAL
            view.typeface = Typeface.create("sans-serif", style)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applySystemTypeface(view.getChildAt(i))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            contentDescription = label
            textSize = if (label.length <= 3) 15f else 11f
            isAllCaps = false
            minWidth = if (label.length <= 3) dp(34) else dp(54)
            minimumWidth = if (label.length <= 3) dp(34) else dp(54)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-2, dp(40)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
    }

    private fun addButtonReturning(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            contentDescription = label
            textSize = 17f
            isAllCaps = false
            minWidth = dp(38)
            minimumWidth = dp(38)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-2, dp(34)).apply {
            setMargins(dp(2), 0, dp(2), 0)
        })
        return button
    }

    private fun addButtonVertical(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            contentDescription = label
            textSize = if (label.length <= 3) 15f else 10f
            isAllCaps = false
            minWidth = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(3), 0, dp(3), 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(30)).apply {
            setMargins(0, dp(2), 0, dp(2))
        })
    }

    private fun addToolLabel(parent: LinearLayout, label: String) {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(160, 177, 197))
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(3, 4, 3, 4)
        })
    }

    private fun setStatus(message: String) {
        headerStatusMessage = message
        if (::status.isInitialized) updateFrameStatus()
    }

    private fun totalDuration(): Int = frameDurations.sum()

    private fun frameStartTime(index: Int): Int = frameDurations.take(index.coerceIn(0, frameDurations.size)).sum()

    private fun frameAtTime(positionMs: Int): Int {
        var elapsed = 0
        frameDurations.forEachIndexed { index, duration ->
            elapsed += duration
            if (positionMs < elapsed) return index
        }
        return frameDurations.lastIndex.coerceAtLeast(0)
    }

    private fun selectFrame(index: Int, movePlayhead: Boolean) {
        if (frameDurations.isEmpty()) return
        currentFrame = index.coerceIn(0, frameDurations.lastIndex)
        drawing.setFrame(currentFrame)
        if (movePlayhead) {
            stopPlayback()
            val start = frameStartTime(currentFrame)
            mediaPlayer?.seekTo(start.coerceIn(0, max(0, audioDurationMs)))
            timelineView.setPlayhead(start)
        }
        updateFrameStatus()
        updateTimeline()
        scheduleAutosave()
    }

    private fun updateFrameStatus() {
        if (!::status.isInitialized) return
        val duration = frameDurations.getOrElse(currentFrame) { 3000 }
        if (::projectTitleLabel.isInitialized) projectTitleLabel.text = projectName
        if (::timelineView.isInitialized) timelineView.projectLabel = projectName
        if (::status.isInitialized) {
            status.text = headerStatusMessage
        }
        if (::durationLabel.isInitialized) durationLabel.text = formatTime(duration)
        if (::shotLabel.isInitialized) shotLabel.text = "${projectName}_${currentFrame + 1} · ${formatTime(duration)}"
    }

    private fun historySnapshot(): String {
        val snapshot = buildSessionJson()
        snapshot.optJSONObject("drawing")?.let { drawingState ->
            listOf("brushSize", "eraserSize", "hue", "saturation", "value",
                "brushColor", "brushOpacity", "pressureSensitivity").forEach { drawingState.remove(it) }
        }
        return snapshot.toString()
    }

    private fun resetHistory() {
        undoHistory.clear()
        redoHistory.clear()
        lastHistoryState = historySnapshot()
    }

    private fun recordHistory() {
        if (restoringHistory || !::drawing.isInitialized) return
        val state = historySnapshot()
        val previous = lastHistoryState
        if (previous == null) {
            lastHistoryState = state
            return
        }
        if (state == previous) return
        undoHistory.addLast(previous)
        while (undoHistory.size > maxHistorySteps) undoHistory.removeFirst()
        redoHistory.clear()
        lastHistoryState = state
    }

    private fun restoreHistoryState(state: String) {
        restoringHistory = true
        try {
            val preservedColor = drawing.currentColor()
            val preservedSize = drawing.activeBrushSize()
            val preservedEraser = drawing.isEraser()
            val preservedOpacity = drawing.brushOpacity()
            val preservedPressure = drawing.pressureEnabled()
            applySessionJson(JSONObject(state))
            drawing.setColor(preservedColor)
            drawing.setEraser(preservedEraser)
            drawing.configureBrush(preservedSize, preservedOpacity * 255 / 100, preservedPressure)
            inlineColorPicker?.setColor(preservedColor)
            updateSelectedColorIndicator()
            updateFrameStatus()
            updateTimeline()
            timelineView.setSelectedFrame(currentFrame)
            drawing.setFrame(currentFrame)
            lastHistoryState = historySnapshot()
            scheduleAutosave()
        } finally {
            restoringHistory = false
        }
    }

    private fun undoGlobal() {
        if (undoHistory.isEmpty()) return
        val current = historySnapshot()
        redoHistory.addLast(current)
        while (redoHistory.size > maxHistorySteps) redoHistory.removeFirst()
        restoreHistoryState(undoHistory.removeLast())
    }

    private fun redoGlobal() {
        if (redoHistory.isEmpty()) return
        val current = historySnapshot()
        undoHistory.addLast(current)
        while (undoHistory.size > maxHistorySteps) undoHistory.removeFirst()
        restoreHistoryState(redoHistory.removeLast())
    }

    private fun updateTimeline() {
        if (!::timelineView.isInitialized) return
        timelineView.setData(
            frameDurations.toList(),
            currentFrame,
            waveform,
            audioDurationMs,
            mediaPlayer?.currentPosition ?: frameStartTime(currentFrame)
        )
    }

    private fun addFrame() {
        frameDurations.add(3000)
        currentFrame = frameDurations.lastIndex
        drawing.setFrame(currentFrame)
        updateFrameStatus()
        updateTimeline()
        recordHistory()
        scheduleAutosave()
    }

    private fun duplicateFrame() {
        val target = currentFrame + 1
        drawing.duplicateFrame(currentFrame, target)
        frameDurations.add(target, frameDurations[currentFrame])
        currentFrame = target
        drawing.setFrame(currentFrame)
        updateFrameStatus()
        updateTimeline()
        recordHistory()
        scheduleAutosave()
    }

    private fun splitFrame(index: Int, firstDuration: Int) {
        if (index !in frameDurations.indices) return
        val originalDuration = frameDurations[index]
        val first = firstDuration.coerceIn(100, originalDuration - 100)
        val second = originalDuration - first
        val target = index + 1
        drawing.duplicateFrame(index, target)
        frameDurations[index] = first
        frameDurations.add(target, second)
        currentFrame = target
        drawing.setFrame(currentFrame)
        updateFrameStatus()
        updateTimeline()
        recordHistory()
        scheduleAutosave()
    }

    private fun deleteFrame() {
        if (frameDurations.size <= 1) {
            Toast.makeText(this, "El proyecto debe conservar al menos un plano", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar plano")
            .setMessage("¿Eliminar el plano ${currentFrame + 1}? El plano anterior ocupará su duración.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                val removedDuration = frameDurations.removeAt(currentFrame)
                if (currentFrame > 0) {
                    frameDurations[currentFrame - 1] += removedDuration
                } else if (frameDurations.isNotEmpty()) {
                    // If the first shot is removed, the new first shot absorbs its duration.
                    frameDurations[0] += removedDuration
                }
                drawing.removeFrame(currentFrame)
                currentFrame = currentFrame.coerceAtMost(frameDurations.lastIndex)
                drawing.setFrame(currentFrame)
                updateFrameStatus()
                updateTimeline()
                recordHistory()
                scheduleAutosave()
            }.show()
    }

    private fun moveSelectedFrame(direction: Int) {
        val target = currentFrame + direction
        if (target !in frameDurations.indices) {
            Toast.makeText(this, "La toma ya está en ese extremo", Toast.LENGTH_SHORT).show()
            return
        }
        moveFrame(currentFrame, target)
    }

    private fun moveFrame(from: Int, to: Int) {
        if (from !in frameDurations.indices || to !in frameDurations.indices || from == to) return
        val duration = frameDurations.removeAt(from)
        frameDurations.add(to, duration)
        drawing.moveFrame(from, to)
        currentFrame = to
        drawing.setFrame(currentFrame)
        updateFrameStatus()
        updateTimeline()
        recordHistory()
        scheduleAutosave()
    }

    private fun changeDuration(delta: Int) {
        val minDuration = 100
        frameDurations[currentFrame] = (frameDurations[currentFrame] + delta).coerceAtLeast(minDuration)
        updateFrameStatus()
        updateTimeline()
        recordHistory()
        scheduleAutosave()
    }

    private fun editDuration() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format("%.2f", frameDurations[currentFrame] / 1000f).replace(',', '.'))
            hint = "Segundos"
        }
        AlertDialog.Builder(this)
            .setTitle("Duración del plano ${currentFrame + 1}")
            .setMessage("Introduce la duración en segundos")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                val seconds = input.text.toString().replace(',', '.').toFloatOrNull()
                if (seconds != null && seconds > 0f) {
                    frameDurations[currentFrame] = (seconds * 1000).toInt().coerceAtLeast(100)
                    updateFrameStatus()
                    updateTimeline()
                    recordHistory()
                    scheduleAutosave()
                }
            }.show()
    }

    private fun addTextNote() {
        val input = EditText(this).apply {
            hint = "Texto descriptivo del plano"
            setText(drawing.currentNote())
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Editar texto del plano").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                drawing.setFrameText(input.text.toString().trim(), drawing.currentColor())
                scheduleAutosave()
            }
            .setNeutralButton("Quitar texto", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    drawing.setFrameText("", drawing.currentColor())
                    scheduleAutosave()
                    dialog.dismiss()
                }
            }
    }

    private fun showBrushColorPalette() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val picker = HueSatValPickerView(this, drawing.currentHue(), drawing.currentSaturation()/100f, drawing.currentValue()/100f) { h, sat, v ->
            drawing.setColorFromHsv(h, sat, v); scheduleAutosave()
        }
        panel.addView(picker, LinearLayout.LayoutParams(dp(280), dp(280)))
        val preview = View(this).apply { setBackgroundColor(drawing.currentColor()) }
        panel.addView(preview, LinearLayout.LayoutParams(-1, dp(24)))
        val opacityLabel = TextView(this).apply { text = "Opacidad ${drawing.brushOpacity()}%"; setTextColor(Color.WHITE); textSize = 12f }
        panel.addView(opacityLabel)
        val opacity = SeekBar(this).apply { max = 100; progress = drawing.brushOpacity() }
        opacity.setOnSeekBarChangeListener(simpleSeekListener {
            drawing.configureBrush(drawing.activeBrushSize(), opacity.progress*255/100, drawing.pressureEnabled())
            opacityLabel.text = "Opacidad ${opacity.progress}%"; scheduleAutosave()
        })
        panel.addView(opacity)
        AlertDialog.Builder(this).setTitle("Fill Color").setView(panel).setPositiveButton("Listo", null).show()
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    private fun chooseSrt() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "application/octet-stream", "text/plain"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_SRT)
    }

    private data class SrtBlock(val startMs: Int, val endMs: Int, val text: String)

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun parseSrtBlocks(source: String): List<SrtBlock> {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val timestamp = Regex("(\\d{2,}):(\\d{2}):(\\d{2})[,\\.](\\d{1,3})\\s*-->\\s*(\\d{2,}):(\\d{2}):(\\d{2})[,\\.](\\d{1,3})")
        fun toMs(h: String, m: String, sec: String, fraction: String): Int {
            val millis = fraction.padEnd(3, '0').take(3).toInt()
            return (((h.toLong() * 3600 + m.toLong() * 60 + sec.toLong()) * 1000L) + millis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val lines = normalized.lines()
        val blocks = mutableListOf<SrtBlock>()
        var i = 0
        while (i < lines.size) {
            val match = timestamp.find(lines[i])
            if (match == null) { i++; continue }
            val start = toMs(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            val end = toMs(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])
            i++
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && timestamp.find(lines[i]) == null) {
                textLines.add(lines[i].trim())
                i++
            }
            val text = textLines.joinToString(" ").trim()
            if (text.isNotBlank() && end > start) blocks.add(SrtBlock(start, end, text))
        }
        return blocks
    }

    private fun importSrtAsNewSession(displayName: String, source: String) {
        val blocks = parseSrtBlocks(source)
        if (blocks.isEmpty()) throw IllegalArgumentException("No se encontraron bloques con tiempos y textos válidos")

        // Preserve the session currently open before replacing the workspace.
        saveSession(showMessage = false)
        saveLocalSnapshot()

        stopPlayback()
        mediaPlayer?.release()
        mediaPlayer = null
        audioUri = null
        audioDurationMs = 0
        waveform = FloatArray(0)

        projectName = displayName.substringBeforeLast('.', displayName).ifBlank { "Storyboard SRT" }
        frameDurations.clear()
        frameDurations.addAll(blocks.map { (it.endMs - it.startMs).coerceAtLeast(100) })
        currentFrame = 0
        drawing.clearProject()
        blocks.forEachIndexed { index, block ->
            drawing.setFrame(index)
            drawing.setFrameText(block.text, drawing.currentColor())
        }
        drawing.setFrame(0)
        drawing.resetViewport()
        if (::projectTitleLabel.isInitialized) projectTitleLabel.text = projectName
        updateFrameStatus()
        updateTimeline()
        saveSession(showMessage = false)
        saveLocalSnapshot()
        resetHistory()
        setStatus("SRT importado · ${blocks.size} planos creados")
        Toast.makeText(this, "Sesión creada: $projectName · ${blocks.size} planos", Toast.LENGTH_LONG).show()
    }

    private fun simpleSeekListener(onChanged: () -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChanged()
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 101)
    }

    @Deprecated("Legacy callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQUEST_SRT -> {
                try {
                    val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Importado.srt"
                    val srtText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalArgumentException("No se pudo leer el archivo SRT")
                    importSrtAsNewSession(displayName, srtText)
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo importar el SRT: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_IMAGE -> {
                try {
                    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    contentResolver.openInputStream(uri).use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                            ?: throw IllegalArgumentException("Formato de imagen no compatible")
                        drawing.addImportedImage(bitmap)
                        scheduleAutosave()
                        Toast.makeText(this, "Imagen incorporada al plano", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo importar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            101 -> {
                try {
                    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    attachAudio(uri, persistPermission = false)
                    scheduleAutosave()
                } catch (e: Exception) {
                    setStatus("No se pudo abrir el audio: ${e.message}")
                }
            }
            102 -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(buildSessionJson().toString(2).toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("No se pudo crear el archivo")
                    Toast.makeText(this, "Archivo de sesión guardado", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    setStatus("No se pudo exportar la sesión: ${e.message}")
                }
            }
            103 -> {
                try {
                    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("No se pudo leer el archivo")
                    saveSession(showMessage = false)
                    saveLocalSnapshot()
                    applySessionJson(JSONObject(json))
                    resetHistory()
                    Toast.makeText(this, "Sesión cargada", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    setStatus("No se pudo abrir la sesión: ${e.message}")
                }
            }
        }
    }
    private fun extractWaveform(uri: Uri): FloatArray {
        val bucketCount = 3200
        val peaks = FloatArray(bucketCount)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(this, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) return peaks
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return peaks
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val estimatedSamples = max(1L, durationUs * sampleRate.toLong() * channels / 1_000_000L)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleCounter = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex >= 0 -> {
                        val output = codec.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            while (buffer.remaining() >= 2) {
                                val value = abs(buffer.short.toInt()) / 32768f
                                val bucket = min(bucketCount - 1, ((sampleCounter * bucketCount) / estimatedSamples).toInt())
                                if (value > peaks[bucket]) peaks[bucket] = value
                                sampleCounter++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* decoded PCM format now available */ }
                }
            }
        } catch (_: Exception) {
            // Waveform is optional: audio playback remains available if a device codec rejects decoding.
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
        return peaks
    }

    private fun attachAudio(uri: Uri, persistPermission: Boolean) {
        try {
            if (persistPermission) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            audioUri = uri
            audioActionButton?.contentDescription = "Reemplazar audio"
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                setOnPreparedListener { player ->
                    audioDurationMs = player.duration
                    updateTimeline()
                    setStatus("Audio cargado · ${formatTime(audioDurationMs)}")
                    scheduleAutosave()
                }
                setOnCompletionListener {
                    stopPlayback()
                    seekToTimelinePosition(0)
                }
                setOnErrorListener { _, _, _ ->
                    setStatus("No se pudo reproducir el audio")
                    true
                }
                prepareAsync()
            }
            waveform = FloatArray(0)
            timelineView.setAudioLoading(true)
            updateTimeline()
            Thread {
                val extracted = extractWaveform(uri)
                runOnUiThread {
                    waveform = extracted
                    timelineView.setAudioLoading(false)
                    updateTimeline()
                }
            }.start()
        } catch (e: Exception) {
            setStatus("No se pudo recuperar el audio: ${e.message}")
        }
    }

    private fun toggleAudio() {
        val player = mediaPlayer
        if (player?.isPlaying == true || sequencePlaying) {
            stopPlayback()
            return
        }
        if (player != null && audioDurationMs > 0) {
            val start = frameStartTime(currentFrame).coerceAtMost(audioDurationMs)
            if (player.currentPosition < start || player.currentPosition >= player.duration) {
                player.seekTo(start)
            }
            player.start()
            setButtonIcon(playPauseButton, "ic_pause", 28)
            handler.removeCallbacks(playbackUpdater)
            handler.post(playbackUpdater)
        } else {
            sequencePlaying = true
            sequenceStartPosition = frameStartTime(currentFrame)
            sequenceStartUptime = SystemClock.uptimeMillis()
            setButtonIcon(playPauseButton, "ic_pause", 28)
            handler.removeCallbacks(playbackUpdater)
            handler.post(playbackUpdater)
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        sequencePlaying = false
        handler.removeCallbacks(playbackUpdater)
        if (::playPauseButton.isInitialized) setButtonIcon(playPauseButton, "ic_play", 28)
    }

    private fun seekToTimelinePosition(position: Int) {
        val bounded = position.coerceIn(0, totalDuration())
        stopPlayback()
        val frame = if (bounded >= totalDuration()) frameDurations.lastIndex else frameAtTime(bounded)
        selectFrame(frame, false)
        mediaPlayer?.seekTo(bounded.coerceAtMost(audioDurationMs))
        timelineView.setPlayhead(bounded)
        timelineView.centerOnPlayhead()
    }

    private fun scheduleAutosave() {
        if (restoringSession) return
        handler.removeCallbacks(autosaveRunnable)
        handler.postDelayed(autosaveRunnable, 500)
    }

    private val autosaveRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed && !restoringSession && ::drawing.isInitialized) {
                saveSession(showMessage = false)
            }
            if (!isFinishing && !isDestroyed) handler.postDelayed(this, 15_000)
        }
    }

    private fun editProjectName() {
        val input = EditText(this).apply {
            setText(projectName)
            hint = "Nombre del proyecto"
            selectAll()
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Nombre de la sesión")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar nombre") { _, _ ->
                val nextName = input.text.toString().trim()
                if (nextName.isNotBlank()) {
                    projectName = nextName
                    recordHistory()
                    updateFrameStatus()
                    updateTimeline()
                    scheduleAutosave()
                    Toast.makeText(this, "Nombre actualizado", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun safeProjectFileName(): String =
        projectName.trim().ifBlank { "Mi_storyboard" }
            .replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ_-]"), "_")
            .replace(Regex("_+"), "_")

    private fun saveProject() {
        saveSession(showMessage = true)
        updateFrameStatus()
        updateTimeline()
    }

    private fun buildSessionJson(): JSONObject {
        val root = JSONObject()
        root.put("version", 4)
        root.put("projectName", projectName)
        root.put("currentFrame", currentFrame)
        val durations = JSONArray()
        frameDurations.forEach { durations.put(it) }
        root.put("durations", durations)
        root.put("drawing", drawing.toJson())
        root.put("audioUri", audioUri?.toString() ?: JSONObject.NULL)
        root.put("audioDurationMs", audioDurationMs)
        return root
    }

    private fun saveSession(showMessage: Boolean) {
        if (!::drawing.isInitialized) return
        try {
            if (sessionFile.parentFile?.exists() != true && sessionFile.parentFile?.mkdirs() != true) {
                throw IllegalStateException("No se pudo crear la carpeta de guardado")
            }
            sessionFile.writeText(buildSessionJson().toString())
            if (showMessage) Toast.makeText(this, "Sesión guardada", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            setStatus("Error al guardar sesión: ${e.message}")
        }
    }

    private fun applySessionJson(root: JSONObject) {
        restoringSession = true
        try {
            stopPlayback()
            val savedAudioRaw = root.optString("audioUri", "")
            val savedAudio = if (savedAudioRaw == "null") "" else savedAudioRaw
            val currentAudio = audioUri?.toString().orEmpty()
            val sameAudioSource = savedAudio == currentAudio
            if (!sameAudioSource) {
                mediaPlayer?.release()
                mediaPlayer = null
                audioUri = null
                audioDurationMs = 0
                waveform = FloatArray(0)
            }

            projectName = root.optString("projectName", "Mi storyboard")
            if (::projectTitleLabel.isInitialized) projectTitleLabel.text = projectName
            frameDurations.clear()
            val savedDurations = root.optJSONArray("durations")
            if (savedDurations != null && savedDurations.length() > 0) {
                for (i in 0 until savedDurations.length()) {
                    frameDurations.add(savedDurations.optInt(i, 3000).coerceAtLeast(100))
                }
            } else {
                frameDurations.add(3000)
            }

            drawing.loadJson(root.optJSONObject("drawing") ?: JSONObject())
            drawing.resetViewport()
            currentFrame = root.optInt("currentFrame", 0).coerceIn(0, frameDurations.lastIndex)
            drawing.setFrame(currentFrame)

            if (!sameAudioSource && savedAudio.isNotBlank() && savedAudio != "null") {
                attachAudio(Uri.parse(savedAudio), persistPermission = false)
            }
            updateFrameStatus()
            updateTimeline()
            setStatus("Sesión abierta · ${frameDurations.size} planos")
        } finally {
            restoringSession = false
        }
        scheduleAutosave()
    }

    private fun showArchivoPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Guardar copia en la aplicación").setOnMenuItemClickListener { saveLocalSnapshot(); true }
        popup.menu.add("Abrir sesión guardada").setOnMenuItemClickListener { showLocalSessionsDialog(); true }
        popup.menu.add("Guardar como archivo…").setOnMenuItemClickListener { saveSessionAsFile(); true }
        popup.menu.add("Abrir archivo de sesión…").setOnMenuItemClickListener { openSessionFile(); true }
        popup.menu.add("Crear sesión nueva").setOnMenuItemClickListener { confirmNewSession(); true }
        popup.show()
    }

    private fun showSessionDialog() {
        val options = arrayOf(
            "Guardar copia en la aplicación",
            "Abrir sesión guardada en la aplicación",
            "Guardar como archivo…",
            "Abrir archivo de sesión…",
            "Crear sesión nueva"
        )
        AlertDialog.Builder(this)
            .setTitle("Administrar sesiones")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveLocalSnapshot()
                    1 -> showLocalSessionsDialog()
                    2 -> saveSessionAsFile()
                    3 -> openSessionFile()
                    4 -> confirmNewSession()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveLocalSnapshot() {
        try {
            if (!sessionsDirectory.exists() && !sessionsDirectory.mkdirs()) {
                throw IllegalStateException("No se pudo crear la carpeta de sesiones")
            }
            val base = projectName.trim().ifBlank { "Storyboard" }
                .replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ_-]"), "_")
            val stamp = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.US).format(java.util.Date())
            val file = File(sessionsDirectory, "${base}_$stamp.sbd")
            file.writeText(buildSessionJson().toString(2))
            setStatus("Sesión guardada: ${sessionsDirectory.absolutePath}")
            Toast.makeText(this, "Sesión guardada en Documentos/Storyboarder/Sessions/${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("No se pudo guardar la copia: ${e.message}")
        }
    }

    private fun showLocalSessionsDialog() {
        val files = sessionsDirectory.listFiles { file ->
            file.isFile && (file.name.endsWith(".sbd") || file.name.endsWith(".storyboard.json"))
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Sesiones guardadas")
                .setMessage("Todavía no hay sesiones guardadas en Documentos/Storyboarder/Sessions.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }
        val labels = files.map { file ->
            file.nameWithoutExtension
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Abrir sesión")
            .setItems(labels) { _, which ->
                try {
                    saveSession(showMessage = false)
                    saveLocalSnapshot()
                    applySessionJson(JSONObject(files[which].readText()))
                    resetHistory()
                    Toast.makeText(this, "Sesión abierta", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    setStatus("No se pudo abrir la sesión: ${e.message}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveSessionAsFile() {
        val nameInput = EditText(this).apply {
            setText(projectName)
            hint = "Nombre de la sesión"
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Guardar sesión")
            .setMessage("La sesión se guardará en Documentos/Storyboarder/sessions.")
            .setView(nameInput)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) projectName = name
                if (::projectTitleLabel.isInitialized) projectTitleLabel.text = projectName
                updateFrameStatus()
                updateTimeline()
                saveLocalSnapshot()
                scheduleAutosave()
            }
            .show()
    }

    private fun openSessionFile() {
        showLocalSessionsDialog()
    }

    private fun confirmNewSession() {
        AlertDialog.Builder(this)
            .setTitle("Crear sesión nueva")
            .setMessage("Se guardará la sesión actual automáticamente antes de crear un proyecto vacío.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear") { _, _ ->
                saveSession(showMessage = false)
                saveLocalSnapshot()
                stopPlayback()
                mediaPlayer?.release()
                mediaPlayer = null
                audioUri = null
                audioDurationMs = 0
                waveform = FloatArray(0)
                frameDurations.clear()
                frameDurations.add(3000)
                currentFrame = 0
                projectName = "Mi storyboard"
                if (::projectTitleLabel.isInitialized) projectTitleLabel.text = projectName
                drawing.clearProject()
                drawing.resetViewport()
                drawing.setFrame(0)
                updateFrameStatus()
                updateTimeline()
                scheduleAutosave()
                setStatus("Nueva sesión")
            }
            .show()
    }
    private fun prepareStorageFolders() {
        listOf(
            File(appStorageDirectory, "Autosave"),
            sessionsDirectory,
            exportsDirectory,
            pdfDirectory
        ).forEach { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                android.util.Log.w("StoryboardStudio", "Could not create folder: ${directory.absolutePath}")
            }
        }
    }

    private fun migratePreviousStorage() {
        try {
            val oldRoot = File(filesDir, "storyboardstudio")
            val oldAutosave = File(oldRoot, "session.json")
            if (!sessionFile.exists() && oldAutosave.exists()) {
                sessionFile.parentFile?.mkdirs()
                oldAutosave.copyTo(sessionFile, overwrite = false)
            }
            val oldSessions = File(oldRoot, "sessions")
            val previousDocumentsSessions = File(appStorageDirectory, "Sessions")
            listOf(oldSessions, previousDocumentsSessions).filter { it.isDirectory }.forEach { sourceDirectory ->
                sessionsDirectory.mkdirs()
                sourceDirectory.listFiles()?.filter { it.isFile }?.forEach { old ->
                    val target = File(sessionsDirectory, old.name)
                    if (!target.exists()) old.copyTo(target, overwrite = false)
                }
            }
        } catch (e: Exception) {
            // Keep the app usable even if the legacy folder cannot be migrated.
            android.util.Log.w("StoryboardStudio", "Storage migration skipped", e)
        }
    }

    private fun loadSession() {
        if (!sessionFile.exists()) return
        try {
            applySessionJson(JSONObject(sessionFile.readText()))
            setStatus("Sesión recuperada · ${frameDurations.size} planos")
        } catch (e: Exception) {
            setStatus("No se pudo recuperar la sesión: ${e.message}")
        }
    }
    private fun writeToDocuments(
        displayName: String,
        mimeType: String,
        subfolder: String,
        writer: (OutputStream) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/Storyboarder/Exports/" + subfolder
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("Android no permitió crear el archivo en Documentos")
            try {
                contentResolver.openOutputStream(uri, "w")?.use(writer)
                    ?: throw IllegalStateException("No se pudo abrir el archivo para escritura")
                val ready = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, ready, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 6–9 fallback: app's Documents export folder (legacy shared-storage access is restricted).
            val base = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir,
                "Storyboarder/Exports/$subfolder")
            if (!base.exists() && !base.mkdirs()) {
                throw IllegalStateException("No se pudo crear ${base.absolutePath}")
            }
            FileOutputStream(File(base, displayName)).use(writer)
        }
    }

    private fun exportPdf() {
        var document: PdfDocument? = null
        try {
            val pdf = PdfDocument()
            document = pdf
            frameDurations.indices.forEach { index ->
                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(1920, 1080, index + 1).create()
                )
                drawing.drawFrameToCanvas(index, page.canvas)
                pdf.finishPage(page)
            }
            writeToDocuments(
                "${safeProjectFileName()}.pdf",
                "application/pdf",
                "PDF"
            ) { output -> pdf.writeTo(output) }
            setStatus("PDF guardado en Documentos/Storyboarder/Exports/PDF")
            Toast.makeText(this, "PDF exportado a Documentos", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Error al exportar PDF: ${e.message}")
            Toast.makeText(this, "Falló la exportación PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private fun exportCurrentPng() {
        try {
            val name = "${safeProjectFileName()}_plano_${(currentFrame + 1).toString().padStart(3, '0')}.png"
            writeToDocuments(name, "image/png", "PNG") { output -> drawing.exportCurrent(output) }
            setStatus("PNG guardado en Documentos/Storyboarder/Exports/PNG")
            Toast.makeText(this, "Plano ${currentFrame + 1} exportado a Documentos", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Error al exportar PNG: ${e.message}")
            Toast.makeText(this, "Falló la exportación PNG: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportAllPng() {
        try {
            for (i in frameDurations.indices) {
                val name = "${safeProjectFileName()}_plano_${(i + 1).toString().padStart(3, '0')}.png"
                writeToDocuments(name, "image/png", "PNG") { output -> drawing.exportFrame(i, output) }
            }
            setStatus("${frameDurations.size} PNG guardados en Documentos/Storyboarder/Exports/PNG")
            Toast.makeText(this, "${frameDurations.size} planos exportados a Documentos", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Error al exportar PNG: ${e.message}")
            Toast.makeText(this, "Falló la exportación: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000f
        return String.format(java.util.Locale.US, "%.2f s", seconds)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::drawing.isInitialized && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            drawing.releaseCachedBitmaps()
            drawing.invalidate()
        }
    }

    override fun onPause() {
        saveSession(showMessage = false)
        super.onPause()
    }

    override fun onDestroy() {
        saveSession(showMessage = false)
        if (::drawing.isInitialized) drawing.releaseCachedBitmaps()
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}

class TimelineView(context: android.content.Context) : View(context) {
    companion object {
        private const val MIN_ADJACENT_SHOT_MS = 500 // 0.5 seconds, never 5 seconds
    }

    var onSelectFrame: ((Int) -> Unit)? = null
    var onDurationChanged: ((Int, Int) -> Unit)? = null
    var onBoundaryChanged: ((Int, Int, Int) -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onMoveFrame: ((Int, Int) -> Unit)? = null
    var onResizeFinished: (() -> Unit)? = null
    var onSplitFrame: ((Int, Int) -> Unit)? = null
    var splitMode: Boolean = false
    var onRequestAudio: (() -> Unit)? = null
    var projectLabel: String = "Mi storyboard"

    private var durations = listOf(3000)
    private var selected = 0
    private var waveform = FloatArray(0)
    private val recentColors = mutableListOf<Int>()
    private var recentColorContainer: LinearLayout? = null
    private var inlineColorPicker: HueSatValPickerView? = null
    private var audioDurationMs = 0
    private var audioLoading = false
    private var playheadMs = 0
    private var pixelsPerSecond = 85f
    private val density = resources.displayMetrics.density
    private val leftMargin = 0f
    private val rulerHeight = 22f * density
    private val cardsTop = 29f * density
    private val cardHeight = 68f * density
    private val audioTop = 103f * density
    private val audioHeight = 66f * density
    private val handleWidth = 13f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            gestureMode = GestureMode.NONE
            this@TimelineView.parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = pixelsPerSecond
            val newScale = (oldScale * detector.scaleFactor).coerceIn(22f, 420f)
            if (newScale != oldScale) {
                val scroll = this@TimelineView.parent as? HorizontalScrollView
                val focusX = detector.focusX
                val oldScrollX = scroll?.scrollX ?: 0
                val screenFocusX = focusX - oldScrollX
                val timeUnderFingers = (focusX - leftMargin) / oldScale
                pixelsPerSecond = newScale
                this@TimelineView.requestLayout()
                this@TimelineView.invalidate()
                if (scroll != null) {
                    this@TimelineView.post {
                        val newScrollX = (leftMargin + timeUnderFingers * pixelsPerSecond - screenFocusX)
                            .toInt().coerceAtLeast(0)
                        scroll.scrollTo(newScrollX, 0)
                    }
                }
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            this@TimelineView.parent?.requestDisallowInterceptTouchEvent(false)
        }
    })
    private var gestureMode = GestureMode.NONE
    private var activeIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var originalDuration = 0
    private var originalNextDuration = 0
    private var lastDuration = 0
    private var lastNextDuration = 0
    private var dragTarget = -1

    private enum class GestureMode { NONE, RESIZE, MOVE, SCRUB, AUDIO_PENDING, PAN_AUDIO }

    fun setData(
        newDurations: List<Int>,
        current: Int,
        newWaveform: FloatArray,
        audioDuration: Int,
        playhead: Int
    ) {
        durations = newDurations
        selected = current.coerceIn(0, max(0, durations.lastIndex))
        waveform = newWaveform
        audioDurationMs = audioDuration
        playheadMs = playhead
        requestLayout()
        invalidate()
    }

    fun setAudioLoading(loading: Boolean) {
        audioLoading = loading
        invalidate()
    }

    fun setPlayhead(position: Int) {
        playheadMs = position.coerceAtLeast(0)
        invalidate()
    }

    fun setSelectedFrame(index: Int) {
        selected = index.coerceIn(0, max(0, durations.lastIndex))
        invalidate()
    }

    fun changeZoom(factor: Float): Int {
        pixelsPerSecond = (pixelsPerSecond * factor).coerceIn(8f, 420f)
        requestLayout()
        invalidate()
        return (((pixelsPerSecond - 8f) / (420f - 8f)) * 100f).toInt().coerceIn(0, 100)
    }

    fun zoomToFitAll(): Int {
        val scroll = parent as? HorizontalScrollView
        val viewport = (scroll?.width ?: width).toFloat().coerceAtLeast(1f)
        val totalSeconds = max(durations.sum(), audioDurationMs).coerceAtLeast(1) / 1000f
        pixelsPerSecond = (viewport / totalSeconds).coerceIn(8f, 420f)
        val progress = (((pixelsPerSecond - 8f) / (420f - 8f)) * 100f).toInt().coerceIn(0, 100)
        requestLayout()
        invalidate()
        scroll?.post { scroll.scrollTo(0, 0) }
        return progress
    }

    fun setZoomProgress(progress: Int) {
        pixelsPerSecond = (8f + (progress.coerceIn(0, 100) / 100f) * (420f - 8f))
            .coerceIn(8f, 420f)
        requestLayout()
        invalidate()
    }

    fun setZoomLevel(level: Float) {
        pixelsPerSecond = (85f * level).coerceIn(8f, 420f)
        requestLayout()
        invalidate()
    }

    fun centerOnPlayhead() {
        val parent = parent as? HorizontalScrollView ?: return
        val x = leftMargin + playheadMs / 1000f * pixelsPerSecond
        parent.smoothScrollTo((x - parent.width / 2f).toInt().coerceAtLeast(0), 0)
    }

    private fun contentDurationMs(): Int = max(durations.sum(), audioDurationMs)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val contentWidth = contentDurationMs() / 1000f * pixelsPerSecond
        val desiredWidth = max(MeasureSpec.getSize(widthMeasureSpec).toFloat(), contentWidth).toInt()
        val desiredHeight = (178f * density).toInt()
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(25, 29, 35))
        drawRuler(canvas)
        drawCards(canvas)
        drawWaveform(canvas)
        drawPlayhead(canvas)
    }

    private fun drawRuler(canvas: Canvas) {
        paint.color = Color.rgb(43, 49, 58)
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, paint)
        paint.color = Color.rgb(160, 174, 190)
        paint.textSize = 11f * density
        paint.strokeWidth = 1f * density
        val totalSeconds = ceil(contentDurationMs() / 1000f).toInt()
        val step = when { pixelsPerSecond >= 110f -> 1; pixelsPerSecond >= 55f -> 5; else -> 10 }
        var second = 0
        while (second <= totalSeconds) {
            val x = leftMargin + second * pixelsPerSecond
            canvas.drawLine(x, rulerHeight - 7f * density, x, rulerHeight, paint)
            if (second % step == 0) {
                val label = String.format(java.util.Locale.US, "%02d:%02d", second / 60, second % 60)
                canvas.drawText(label, x + 3f * density, 17f * density, paint)
            }
            second += 1
        }
    }

    private fun drawCards(canvas: Canvas) {
        var elapsed = 0
        durations.forEachIndexed { index, duration ->
            val x = leftMargin + elapsed / 1000f * pixelsPerSecond
            val w = max(55f * density, duration / 1000f * pixelsPerSecond)
            val rect = RectF(x, cardsTop, x + w, cardsTop + cardHeight)
            paint.color = if (index == selected) Color.rgb(20, 137, 195) else Color.rgb(112, 112, 112)
            canvas.drawRect(rect, paint)
            paint.color = Color.WHITE
            paint.textSize = 12f * density
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            val label = "${projectLabel}_${index + 1}"
            canvas.drawText(label, x + 7f * density, cardsTop + 17f * density, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val durationText = String.format(java.util.Locale.US, "%.2fs", duration / 1000f)
            val tw = paint.measureText(durationText)
            canvas.drawText(durationText, x + w - tw - 8f * density, cardsTop + cardHeight - 9f * density, paint)
            paint.color = Color.WHITE
            canvas.drawRect(x + w - handleWidth, cardsTop, x + w, cardsTop + cardHeight, paint)
            paint.color = Color.rgb(55, 55, 55)
            canvas.drawRect(x + w - handleWidth / 2f - density, cardsTop + cardHeight * 0.28f,
                x + w - handleWidth / 2f + density, cardsTop + cardHeight * 0.72f, paint)
            elapsed += duration
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val lane = RectF(leftMargin, audioTop,
            max(width - leftMargin, leftMargin + audioDurationMs / 1000f * pixelsPerSecond),
            audioTop + audioHeight)
        paint.color = Color.rgb(43, 83, 47)
        canvas.drawRect(lane, paint)

        // Waveform data scrolls with time; track labels and status graphics stay in the viewport.
        val scroll = parent as? HorizontalScrollView
        val viewportLeft = (scroll?.scrollX ?: 0).toFloat()
        val viewportWidth = (scroll?.width ?: width).toFloat().coerceAtLeast(1f)
        val viewportCenter = viewportLeft + viewportWidth / 2f
        val viewportRight = viewportLeft + viewportWidth

        if (waveform.isNotEmpty() && audioDurationMs > 0) {
            paint.color = Color.rgb(139, 185, 151)
            val center = audioTop + audioHeight / 2f
            val step = lane.width() / waveform.size
            waveform.forEachIndexed { index, peak ->
                val barHeight = max(1f * density, peak * (audioHeight - 5f * density))
                val x = leftMargin + index * step
                canvas.drawRect(x, center - barHeight / 2f,
                    x + max(1f, step * 0.68f), center + barHeight / 2f, paint)
            }
        }

        if (audioLoading) {
            paint.textSize = 11f * density
            paint.color = Color.rgb(180, 193, 183)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Cargando audio y analizando forma de onda…", viewportCenter,
                audioTop + audioHeight / 2f - 5f * density, paint)
            val barLeft = viewportLeft + viewportWidth * 0.15f
            val barRight = min(viewportRight - 8f * density, viewportLeft + viewportWidth * 0.85f)
            paint.color = Color.rgb(115, 135, 150)
            canvas.drawRoundRect(RectF(barLeft, audioTop + audioHeight / 2f + 5f * density,
                barRight, audioTop + audioHeight / 2f + 9f * density), 2f * density, 2f * density, paint)
            paint.color = Color.rgb(40, 170, 210)
            val progressWidth = ((SystemClock.uptimeMillis() / 8L) % 100L) / 100f * (barRight - barLeft)
            canvas.drawRoundRect(RectF(barLeft, audioTop + audioHeight / 2f + 5f * density,
                barLeft + progressWidth, audioTop + audioHeight / 2f + 9f * density), 2f * density, 2f * density, paint)
            paint.textAlign = Paint.Align.LEFT
            postInvalidateDelayed(80)
        } else if (audioDurationMs > 0 && waveform.isEmpty()) {
            paint.textSize = 11f * density
            paint.color = Color.rgb(180, 193, 183)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Generando forma de onda…", viewportCenter,
                audioTop + audioHeight / 2f + 4f * density, paint)
            paint.textAlign = Paint.Align.LEFT
        } else if (audioDurationMs <= 0) {
            val audioId = resources.getIdentifier("ic_load_audio", "drawable", context.packageName)
            if (audioId != 0) {
                val bitmap = BitmapFactory.decodeResource(resources, audioId)
                if (bitmap != null) {
                    val targetW = 104f * density
                    val targetH = targetW * bitmap.height / bitmap.width
                    val dst = RectF(viewportCenter - targetW / 2f,
                        audioTop + audioHeight / 2f - targetH / 2f,
                        viewportCenter + targetW / 2f,
                        audioTop + audioHeight / 2f + targetH / 2f)
                    canvas.drawBitmap(bitmap, null, dst, paint)
                    bitmap.recycle()
                }
            }
        }
    }

    private fun drawPlayhead(canvas: Canvas) {
        val x = leftMargin + playheadMs / 1000f * pixelsPerSecond
        paint.color = Color.rgb(255, 92, 75)
        paint.strokeWidth = 2f * density
        canvas.drawLine(x, 0f, x, audioTop + audioHeight + 5f * density, paint)
        paint.color = Color.rgb(255, 92, 75)
        val triangle = Path().apply {
            moveTo(x - 5f * density, 0f)
            lineTo(x + 5f * density, 0f)
            lineTo(x, 7f * density)
            close()
        }
        canvas.drawPath(triangle, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1 || scaleDetector.isInProgress) {
            gestureMode = GestureMode.NONE
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                activeIndex = hitFrame(event.x, event.y)
                if (splitMode && activeIndex >= 0) {
                    val rect = frameRect(activeIndex)
                    val offsetMs = ((event.x - rect.left) / pixelsPerSecond * 1000f).toInt()
                    val duration = durations[activeIndex]
                    if (offsetMs >= 100 && offsetMs <= duration - 100) {
                        onSplitFrame?.invoke(activeIndex, offsetMs)
                    } else {
                        Toast.makeText(context, "El corte debe dejar al menos 0,1 segundos en cada parte", Toast.LENGTH_SHORT).show()
                    }
                    gestureMode = GestureMode.NONE
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val playheadX = leftMargin + playheadMs / 1000f * pixelsPerSecond
                val nearPlayhead = kotlin.math.abs(event.x - playheadX) <= 18f * density

                // The audio lane and playhead handle always scrub the complete audio duration,
                // including sections where no storyboard card exists.
                if (audioDurationMs <= 0 && event.y in audioTop..(audioTop + audioHeight)) {
                    onRequestAudio?.invoke()
                    gestureMode = GestureMode.NONE
                    return true
                }
                if (event.y in audioTop..(audioTop + audioHeight)) {
                    // A tap on the audio lane seeks; a horizontal drag pans the timeline.
                    // This prevents dragging the audio strip from pulling the playhead.
                    gestureMode = GestureMode.AUDIO_PENDING
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (nearPlayhead || event.y <= rulerHeight) {
                    gestureMode = GestureMode.SCRUB
                    seekFromTimelineX(event.x)
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (activeIndex >= 0) {
                    val rect = frameRect(activeIndex)
                    if (event.x >= rect.right - handleWidth * 1.4f) {
                        gestureMode = GestureMode.RESIZE
                        originalDuration = durations[activeIndex]
                        originalNextDuration = durations.getOrElse(activeIndex + 1) { 0 }
                        lastDuration = originalDuration
                        lastNextDuration = originalNextDuration
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (event.x <= rect.left + 16f * density) {
                        gestureMode = GestureMode.MOVE
                        dragTarget = activeIndex
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        gestureMode = GestureMode.NONE
                        onSelectFrame?.invoke(activeIndex)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.SCRUB -> seekFromTimelineX(event.x)
                    GestureMode.AUDIO_PENDING -> {
                        val deltaFromDown = event.x - downX
                        if (kotlin.math.abs(deltaFromDown) >= 8f * density) {
                            gestureMode = GestureMode.PAN_AUDIO
                            val scroll = parent as? HorizontalScrollView
                            scroll?.scrollBy((lastTouchX - event.x).toInt(), 0)
                        }
                        lastTouchX = event.x
                    }
                    GestureMode.PAN_AUDIO -> {
                        val scroll = parent as? HorizontalScrollView
                        scroll?.scrollBy((lastTouchX - event.x).toInt(), 0)
                        lastTouchX = event.x
                    }
                    GestureMode.RESIZE -> {
                        val deltaMs = ((event.x - downX) / pixelsPerSecond * 1000f).toInt()
                        val minDuration = 100
                        val newDuration = (originalDuration + deltaMs).coerceAtLeast(minDuration)
                        if (activeIndex < durations.lastIndex) {
                            // Move only the cut between these two shots: the next shot's end
                            // and every later shot's absolute position remain fixed.
                            val minimumNext = min(MIN_ADJACENT_SHOT_MS, originalNextDuration)
                            val transitionLimit = max(0, originalNextDuration - minimumNext)
                            val boundedDelta = deltaMs.coerceAtLeast(minDuration - originalDuration)
                            val adjustedCurrent = originalDuration + boundedDelta
                            val adjustedNext = if (boundedDelta > transitionLimit) {
                                minimumNext
                            } else {
                                originalNextDuration - boundedDelta
                            }
                            if (adjustedCurrent != lastDuration || adjustedNext != lastNextDuration) {
                                lastDuration = adjustedCurrent
                                lastNextDuration = adjustedNext
                                onBoundaryChanged?.invoke(activeIndex, adjustedCurrent, adjustedNext)
                            }
                        } else if (newDuration != lastDuration) {
                            lastDuration = newDuration
                            onDurationChanged?.invoke(activeIndex, newDuration)
                        }
                    }
                    GestureMode.MOVE -> {
                        val candidate = hitFrame(event.x, cardsTop + cardHeight / 2f)
                        if (candidate >= 0) dragTarget = candidate
                        invalidate()
                    }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (gestureMode == GestureMode.SCRUB) seekFromTimelineX(event.x)
                    else if (gestureMode == GestureMode.AUDIO_PENDING) seekFromTimelineX(downX)
                }
                if (gestureMode == GestureMode.RESIZE) onResizeFinished?.invoke()
                if (gestureMode == GestureMode.MOVE && activeIndex >= 0 && dragTarget >= 0 && dragTarget != activeIndex) {
                    onMoveFrame?.invoke(activeIndex, dragTarget)
                }
                gestureMode = GestureMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun seekFromTimelineX(x: Float) {
        val maximum = max(audioDurationMs, durations.sum())
        val position = ((x - leftMargin) / pixelsPerSecond * 1000f)
            .toInt().coerceIn(0, maximum.coerceAtLeast(0))
        playheadMs = position
        onSeek?.invoke(position)
        invalidate()
    }

    private fun frameRect(index: Int): RectF {
        var elapsed = 0
        for (i in 0 until index) elapsed += durations[i]
        val x = leftMargin + elapsed / 1000f * pixelsPerSecond
        val w = max(55f * density, durations[index] / 1000f * pixelsPerSecond)
        return RectF(x, cardsTop, x + w, cardsTop + cardHeight)
    }

    private fun hitFrame(x: Float, y: Float): Int {
        if (y < cardsTop || y > cardsTop + cardHeight) return -1
        durations.indices.forEach { index ->
            if (frameRect(index).contains(x, y)) return index
        }
        return -1
    }

    private fun formatDuration(ms: Int): String =
        String.format(java.util.Locale.US, "%.2f s", ms / 1000f)
}

private class HueSatValPickerView(
    context: android.content.Context,
    initialHue: Float,
    initialSaturation: Float,
    initialValue: Float,
    private val onColorChanged: (Float, Float, Float) -> Unit
) : View(context) {
    private var hue = initialHue
    private var saturation = initialSaturation
    private var value = initialValue
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var hueBar = RectF()
    private var square = RectF()

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 12f * density
        val barHeight = 24f * density
        hueBar = RectF(inset, inset, width - inset, inset + barHeight)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            hueBar.left, 0f, hueBar.right, 0f,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(hueBar, paint)
        paint.shader = null
        val hueX = hueBar.left + (hue / 360f) * hueBar.width()
        paint.color = Color.WHITE
        canvas.drawRect(hueX - 2f * density, hueBar.top - 2f * density,
            hueX + 2f * density, hueBar.bottom + 2f * density, paint)
        paint.color = Color.BLACK
        paint.strokeWidth = 1f * density
        paint.style = Paint.Style.STROKE
        canvas.drawRect(hueBar, paint)
        paint.style = Paint.Style.FILL

        val availableTop = hueBar.bottom + 12f * density
        val availableHeight = (height - availableTop - 2f * density).coerceAtLeast(1f)
        val squareWidth = ((width - 36f * density) * (2f / 3f)).coerceAtLeast(1f)
        val squareHeight = min(squareWidth * 2.22f, availableHeight * (2f / 3f)).coerceAtLeast(1f)
        val left = (width - squareWidth) / 2f
        square = RectF(left, availableTop, left + squareWidth, availableTop + squareHeight)
        paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawRect(square, paint)
        paint.shader = LinearGradient(square.left, square.top, square.right, square.top,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(square, paint)
        paint.shader = null
        paint.shader = LinearGradient(square.left, square.top, square.left, square.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRect(square, paint)
        paint.shader = null

        val sx = square.left + saturation * square.width()
        val sy = square.bottom - value * square.height()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        canvas.drawCircle(sx, sy, 7f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(115, 115, 115)
        paint.strokeWidth = 1f * density
        canvas.drawRect(square, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) return true
        when {
            hueBar.contains(event.x, event.y) -> {
                hue = (((event.x - hueBar.left) / hueBar.width()) * 360f).coerceIn(0f, 360f)
            }
            square.contains(event.x, event.y) -> {
                saturation = ((event.x - square.left) / square.width()).coerceIn(0f, 1f)
                value = (1f - (event.y - square.top) / square.height()).coerceIn(0f, 1f)
            }
            else -> return true
        }
        invalidate()
        onColorChanged(hue, saturation, value)
        return true
    }
}

private class VerticalSliderView(
    context: android.content.Context,
    private val shortLabel: String,
    private val minimum: Int,
    private val maximum: Int,
    initialValue: Int,
    private val onValueChanged: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var value = initialValue.coerceIn(minimum, maximum)
    private var showValue = false
    private val labelHeight = 20f * density
    private val thumbRadius = 6f * density

    fun setValue(next: Int) { value = next.coerceIn(minimum, maximum); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = 6f * density
        val top = 15f * density
        val bottom = height - 15f * density
        paint.color = Color.rgb(135, 135, 135)
        paint.strokeWidth = 3f * density
        canvas.drawLine(cx, top, cx, bottom, paint)
        val fraction = if (maximum == minimum) 0f else (value - minimum).toFloat() / (maximum - minimum)
        val y = bottom - fraction * (bottom - top)
        paint.color = Color.rgb(190, 190, 190)
        canvas.drawRoundRect(cx - 4f * density, y - 14f * density, cx + 4f * density,
            y + 14f * density, 4f * density, 4f * density, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 9f * density
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        if (shortLabel.isNotBlank()) canvas.drawText(shortLabel, cx, 11f * density, paint)
        if (showValue) {
            paint.textSize = 9f * density
            paint.color = Color.WHITE
            // The value is beside the thumb, not above it.
            canvas.drawText(value.toString(), cx + thumbRadius + 4f * density,
                (y + 3f * density).coerceIn(15f * density, height - 15f * density), paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            showValue = true
            val top = 15f * density
            val bottom = height - 15f * density
            val fraction = (1f - ((event.y - top) / (bottom - top).coerceAtLeast(1f))).coerceIn(0f, 1f)
            val next = (minimum + fraction * (maximum - minimum)).toInt().coerceIn(minimum, maximum)
            if (next != value) {
                value = next
                invalidate()
                onValueChanged(value)
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            showValue = false
            invalidate()
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        return true
    }
}

private data class Stroke(
    val points: MutableList<PointF>,
    val widths: MutableList<Float>,
    val color: Int,
    val erase: Boolean = false
)

class DrawingView(context: android.content.Context) : View(context) {
    private data class ClearSnapshot(
        val strokes: List<Stroke>,
        val image: Bitmap?
    )

    private val clearUndoSnapshots = mutableMapOf<Int, MutableList<ClearSnapshot>>()
    private val clearRedoSnapshots = mutableMapOf<Int, MutableList<ClearSnapshot>>()

    init {
        setBackgroundColor(Color.WHITE)
    }

    var onChanged: (() -> Unit)? = null
    var onHistoryAction: (() -> Unit)? = null
    var onColorUsed: ((Int) -> Unit)? = null
    private val frames = mutableMapOf<Int, MutableList<Stroke>>()
    private val redoFrames = mutableMapOf<Int, MutableList<Stroke>>()
    private val notes = mutableMapOf<Int, MutableList<String>>()
    private val noteColors = mutableMapOf<Int, Int>()
    private var frame = 0
    private var current: Stroke? = null
    private var color = Color.BLACK
    private var brush = 4f
    private var eraserBrush = 20f
    private val importedImages = mutableMapOf<Int, Bitmap>()
    // Imported bitmaps are immutable after fitting; reuse their PNG/Base64 payload across
    // history snapshots instead of recompressing the same image on every edit.
    private val encodedImageCache = java.util.WeakHashMap<Bitmap, String>()
    // Keep a few recently decoded imports alive so undo/redo does not decode the same
    // large PNG/Base64 payload on every restoration.
    private data class DecodedImageEntry(val encoded: String, val bitmap: Bitmap)
    private val decodedImageCache = object : LinkedHashMap<String, DecodedImageEntry>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DecodedImageEntry>?): Boolean = size > 3
    }
    private val renderedFrameCache = linkedMapOf<Int, Bitmap>()
    private val rasterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var hue = 0f
    private var saturation = 1f
    private var value = 0f
    private var brushOpacity = 255
    private var pressureSensitivity = true
    private var eraser = false

    // Editable lasso selection: original strokes remain vector-like point/pressure data.
    private var lassoEnabled = false
    private val lassoPoints = mutableListOf<PointF>()
    private val selectedStrokes = mutableListOf<Stroke>()
    private var selectionCenterX = 0f
    private var selectionCenterY = 0f
    private var selectionTranslateX = 0f
    private var selectionTranslateY = 0f
    private var selectionRotation = 0f
    private var selectionScale = 1f
    private var selectionLastX = 0f
    private var selectionLastY = 0f
    private var selectionLastSpan = 0f
    private var selectionLastAngle = 0f
    private var selectionOriginals = mutableListOf<Stroke>()
    private var selectionFrameBackup: MutableList<Stroke>? = null
    private val selectionParts = mutableListOf<Pair<Boolean, Stroke>>()

    // Viewport transform: drawings remain stored in their original 1920x1080 coordinates.
    private var viewportScale = 1f
    private var viewportRotation = 0f
    private var viewportPanX = 0f
    private var viewportPanY = 0f
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var previousSpan = 0f
    private var previousAngle = 0f
    private var previousMidX = 0f
    private var previousMidY = 0f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 28f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        style = Paint.Style.FILL
    }

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isFocusable = true
    }

    private fun invalidateFrameCache(index: Int = frame) {
        val removed = renderedFrameCache.remove(index)
        if (removed != null) {
            if (!removed.isRecycled) removed.recycle()
        }
    }

    private fun invalidateAllFrameCaches() {
        renderedFrameCache.values.forEach { if (!it.isRecycled) it.recycle() }
        renderedFrameCache.clear()
    }

    fun releaseCachedBitmaps() {
        invalidateAllFrameCaches()
        encodedImageCache.clear()
        decodedImageCache.clear()
    }

    private fun cachedFrameBitmap(): Bitmap {
        renderedFrameCache[frame]?.let { if (!it.isRecycled) return it }
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val cacheCanvas = Canvas(bitmap)
        cacheCanvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        importedImages[frame]?.let { image ->
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dst = RectF((1920-image.width)/2f, (1080-image.height)/2f,
                (1920+image.width)/2f, (1080+image.height)/2f)
            cacheCanvas.drawBitmap(image, null, dst, imagePaint)
        }
        frames[frame].orEmpty().forEach { stroke ->
            if (stroke !== current && stroke !in selectedStrokes) drawStroke(cacheCanvas, stroke)
        }
        var noteY = 1030f
        notes[frame]?.firstOrNull()?.let { note ->
            textPaint.textSize = 34f
            textPaint.color = Color.RED
            cacheCanvas.drawText(note, 24f, noteY, textPaint)
            textPaint.color = Color.DKGRAY
        }
        renderedFrameCache[frame] = bitmap
        while (renderedFrameCache.size > 2) {
            val oldestKey = renderedFrameCache.keys.first()
            invalidateFrameCache(oldestKey)
        }
        return bitmap
    }

    private fun strokes() = frames.getOrPut(frame) { mutableListOf() }
    fun setFrame(index: Int) { cancelSelection(); frame = index; invalidate() }
    fun clearProject() {
        frames.clear()
        redoFrames.clear()
        clearUndoSnapshots.clear()
        clearRedoSnapshots.clear()
        notes.clear()
        noteColors.clear()
        importedImages.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        importedImages.clear()
        encodedImageCache.clear()
        decodedImageCache.clear()
        invalidateAllFrameCaches()
        current = null
        cancelSelection()
        invalidate()
        onChanged?.invoke()
    }
    fun setEraser(value: Boolean) { eraser = value }
    fun isEraser(): Boolean = eraser
    fun activeBrushSize(): Float = if (eraser) eraserBrush else brush
    fun setActiveBrushSize(size: Float) { if (eraser) eraserBrush = size.coerceIn(1f,200f) else brush = size.coerceIn(1f,200f) }
    fun setColor(value: Int) { color = value; eraser = false; val hsv=FloatArray(3); Color.colorToHSV(value,hsv); hue=hsv[0]; saturation=hsv[1]; this.value=hsv[2] }
    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue,saturation,value))
    fun currentHue() = hue
    fun currentSaturation() = saturation*100f
    fun currentValue() = value*100f
    fun setColorFromHsv(h:Float,s:Float,v:Float) { hue=h.coerceIn(0f,359f); saturation=s.coerceIn(0f,1f); value=v.coerceIn(0f,1f); color=currentColor(); eraser=false; invalidate() }
    fun changeBrush(delta: Float) { setActiveBrushSize(activeBrushSize() + delta) }
    fun brushSize(): Float = activeBrushSize()
    fun addImportedImage(bitmap: Bitmap) {
        val maxW=1920; val maxH=1080
        val scale=min(maxW.toFloat()/bitmap.width, maxH.toFloat()/bitmap.height)
        val w=max(1,(bitmap.width*scale).toInt()); val h=max(1,(bitmap.height*scale).toInt())
        val fitted=Bitmap.createScaledBitmap(bitmap,w,h,true)
        importedImages.put(frame,fitted)?.let { previous ->
            if (previous !== fitted && !previous.isRecycled) previous.recycle()
        }
        invalidateFrameCache(); invalidate(); onChanged?.invoke(); onHistoryAction?.invoke()
    }
    fun brushOpacity(): Int = (brushOpacity * 100f / 255f).roundToInt().coerceIn(0, 100)
    fun pressureEnabled(): Boolean = pressureSensitivity
    fun configureBrush(size: Float, opacity: Int, pressure: Boolean) {
        setActiveBrushSize(size)
        // Opacity is strictly the alpha channel (0..255); it never changes
        // stylus sampling density, point spacing, or pressure interpolation.
        brushOpacity = opacity.coerceIn(0, 255)
        pressureSensitivity = pressure
    }

    fun undo() {
        val list = strokes()
        if (list.isNotEmpty()) {
            val removed = list.removeAt(list.lastIndex)
            redoFrames.getOrPut(frame) { mutableListOf() }.add(removed)
            invalidateFrameCache()
            invalidate()
            onChanged?.invoke()
            return
        }

        val clearStack = clearUndoSnapshots[frame]
        if (!clearStack.isNullOrEmpty()) {
            val snapshot = clearStack.removeAt(clearStack.lastIndex)
            frames[frame] = snapshot.strokes.toMutableList()
            if (snapshot.image != null) importedImages[frame] = snapshot.image
            else importedImages.remove(frame)
            clearRedoSnapshots.getOrPut(frame) { mutableListOf() }.add(snapshot)
            invalidateFrameCache()
            invalidate()
            onChanged?.invoke()
        }
    }

    fun redo() {
        val stack = redoFrames[frame]
        if (!stack.isNullOrEmpty()) {
            strokes().add(stack.removeAt(stack.lastIndex))
            invalidateFrameCache()
            invalidate()
            onChanged?.invoke()
            return
        }

        val clearStack = clearRedoSnapshots[frame]
        if (!clearStack.isNullOrEmpty()) {
            val snapshot = clearStack.removeAt(clearStack.lastIndex)
            clearUndoSnapshots.getOrPut(frame) { mutableListOf() }.add(snapshot)
            frames.remove(frame)
            importedImages.remove(frame)
            invalidateFrameCache()
            invalidate()
            onChanged?.invoke()
        }
    }

    fun clear() {
        val existingStrokes = frames[frame].orEmpty()
        val existingImage = importedImages[frame]
        if (existingStrokes.isEmpty() && existingImage == null) return

        val snapshot = ClearSnapshot(existingStrokes.toList(), existingImage)
        clearUndoSnapshots.getOrPut(frame) { mutableListOf() }.add(snapshot)
        clearRedoSnapshots.remove(frame)

        frames.remove(frame)
        redoFrames.remove(frame)
        importedImages.remove(frame)
        // Keep notes and noteColors: clearing the canvas must not erase its text.
        invalidateFrameCache()
        invalidate()
        onChanged?.invoke()
        onHistoryAction?.invoke()
    }

    fun currentNote(): String = notes[frame]?.firstOrNull().orEmpty()

    fun setFrameText(value: String, textColor: Int) {
        if (value.isBlank()) {
            notes.remove(frame)
            noteColors.remove(frame)
        } else {
            notes[frame] = mutableListOf(value)
            noteColors[frame] = Color.RED
        }
        invalidateFrameCache()
        invalidate()
        onChanged?.invoke()
        onHistoryAction?.invoke()
    }

    fun duplicateFrame(source: Int, target: Int) {
        invalidateAllFrameCaches()
        val sourceStrokes = frames[source]?.let { cloneStrokes(it) }
        val sourceNotes = notes[source]?.take(1)?.toMutableList()
        val sourceNoteColor = noteColors[source]
        shiftFramesForInsert(target)
        if (sourceStrokes != null) frames[target] = sourceStrokes
        if (sourceNotes != null) notes[target] = sourceNotes
        sourceNoteColor?.let { noteColors[target] = it }
        importedImages[source]?.let { importedImages[target] = it.copy(it.config ?: Bitmap.Config.ARGB_8888, true) }
    }

    private fun cloneStrokes(source: List<Stroke>) = source.map { s ->
        Stroke(
            s.points.map { PointF(it.x, it.y) }.toMutableList(),
            s.widths.toMutableList(),
            s.color,
            s.erase
        )
    }.toMutableList()

    private fun shiftFramesForInsert(at: Int) {
        val newFrames = mutableMapOf<Int, MutableList<Stroke>>()
        frames.forEach { (key, value) -> newFrames[if (key >= at) key + 1 else key] = value }
        frames.clear()
        frames.putAll(newFrames)
        val shiftedImages = mutableMapOf<Int, Bitmap>(); importedImages.forEach { (k,v) -> shiftedImages[if(k>=at) k+1 else k]=v }; importedImages.clear(); importedImages.putAll(shiftedImages)
        val newNotes = mutableMapOf<Int, MutableList<String>>()
        notes.forEach { (key, value) -> newNotes[if (key >= at) key + 1 else key] = value }
        notes.clear()
        notes.putAll(newNotes)
        val shiftedNoteColors = mutableMapOf<Int, Int>()
        noteColors.forEach { (key, value) -> shiftedNoteColors[if (key >= at) key + 1 else key] = value }
        noteColors.clear()
        noteColors.putAll(shiftedNoteColors)
    }

    fun removeFrame(index: Int) {
        invalidateAllFrameCaches()
        val newFrames = mutableMapOf<Int, MutableList<Stroke>>()
        frames.forEach { (key, value) -> if (key != index) newFrames[if (key > index) key - 1 else key] = value }
        frames.clear()
        frames.putAll(newFrames)
        val shiftedImages = mutableMapOf<Int, Bitmap>(); importedImages.forEach { (k,v) -> if(k!=index) shiftedImages[if(k>index) k-1 else k]=v }; importedImages.clear(); importedImages.putAll(shiftedImages)
        val newNotes = mutableMapOf<Int, MutableList<String>>()
        notes.forEach { (key, value) -> if (key != index) newNotes[if (key > index) key - 1 else key] = value }
        notes.clear()
        notes.putAll(newNotes)
        val shiftedNoteColors = mutableMapOf<Int, Int>()
        noteColors.forEach { (key, value) -> if (key != index) shiftedNoteColors[if (key > index) key - 1 else key] = value }
        noteColors.clear()
        noteColors.putAll(shiftedNoteColors)
        invalidate()
    }

    fun moveFrame(from: Int, to: Int) {
        invalidateAllFrameCaches()
        if (from == to) return
        val allKeys = (frames.keys + notes.keys + importedImages.keys).toSet()
        val newFrames = mutableMapOf<Int, MutableList<Stroke>>()
        val newNotes = mutableMapOf<Int, MutableList<String>>()
        allKeys.forEach { old ->
            val new = when {
                old == from -> to
                from < to && old in (from + 1)..to -> old - 1
                from > to && old in to until from -> old + 1
                else -> old
            }
            frames[old]?.let { newFrames[new] = it }
            notes[old]?.let { newNotes[new] = it }
        }
        frames.clear(); frames.putAll(newFrames)
        val movedImages = mutableMapOf<Int, Bitmap>(); importedImages.forEach { (old, bmp) -> val new = when { old==from -> to; from<to && old in (from+1)..to -> old-1; from>to && old in to until from -> old+1; else -> old }; movedImages[new]=bmp }; importedImages.clear(); importedImages.putAll(movedImages)
        notes.clear(); notes.putAll(newNotes)
        val movedNoteColors = mutableMapOf<Int, Int>()
        noteColors.forEach { (old, value) ->
            val new = when {
                old == from -> to
                from < to && old in (from + 1)..to -> old - 1
                from > to && old in to until from -> old + 1
                else -> old
            }
            movedNoteColors[new] = value
        }
        noteColors.clear(); noteColors.putAll(movedNoteColors)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(45, 50, 57))
        val targetW = width * 0.94f
        val targetH = height * 0.90f
        val boardScale = min(targetW / 16f, targetH / 9f)
        val w = 16f * boardScale
        val h = 9f * boardScale
        val left = (width - w) / 2f
        val top = (height - h) / 2f

        canvas.save()
        canvas.translate(width / 2f + viewportPanX, height / 2f + viewportPanY)
        canvas.rotate(viewportRotation)
        canvas.scale(viewportScale, viewportScale)
        canvas.translate(-width / 2f, -height / 2f)

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(boardScale / 120f, boardScale / 120f)
        val boardBounds = RectF(0f, 0f, 1920f, 1080f)
        // The white sheet stays underneath the transparent ink layer. CLEAR erasing
        // therefore removes only drawing pixels and reveals pure white paper.
        canvas.drawRect(boardBounds, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
        val layer = canvas.saveLayer(boardBounds, null)
        canvas.drawBitmap(cachedFrameBitmap(), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        // Render the in-progress stroke as one smoothed path, not as overlapping
        // alpha-blended samples. This keeps the selected color and transparency uniform.
        current?.let { drawStroke(canvas, it) }
        if (selectedStrokes.isNotEmpty()) {
            canvas.save()
            canvas.translate(selectionTranslateX, selectionTranslateY)
            canvas.rotate(selectionRotation, selectionCenterX, selectionCenterY)
            canvas.scale(selectionScale, selectionScale, selectionCenterX, selectionCenterY)
            selectedStrokes.forEach { drawStroke(canvas, it) }
            val bounds = selectionBounds()
            val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 155, 235)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
            }
            canvas.drawRect(bounds, selectionPaint)
            canvas.restore()
        }
        if (lassoEnabled && lassoPoints.size > 1) {
            val path = Path().apply {
                moveTo(lassoPoints.first().x, lassoPoints.first().y)
                lassoPoints.drop(1).forEach { lineTo(it.x, it.y) }
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 155, 235)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 7f), 0f)
            })
        }
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    fun startLassoSelection() {
        lassoEnabled = true
        lassoPoints.clear()
        selectedStrokes.clear()
        selectionOriginals.clear()
        selectionFrameBackup = null
        selectionParts.clear()
        selectionTranslateX = 0f
        selectionTranslateY = 0f
        selectionRotation = 0f
        selectionScale = 1f
        invalidateFrameCache()
        invalidate()
    }

    fun hasSelection(): Boolean = selectedStrokes.isNotEmpty()

    fun confirmSelection() {
        if (selectedStrokes.isEmpty()) {
            lassoEnabled = false
            lassoPoints.clear()
            invalidate()
            return
        }
        selectedStrokes.forEach { stroke ->
            stroke.points.forEach { point ->
                val x = point.x - selectionCenterX
                val y = point.y - selectionCenterY
                val scaledX = x * selectionScale
                val scaledY = y * selectionScale
                val radians = Math.toRadians(selectionRotation.toDouble())
                val cos = kotlin.math.cos(radians).toFloat()
                val sin = kotlin.math.sin(radians).toFloat()
                point.x = selectionCenterX + scaledX * cos - scaledY * sin + selectionTranslateX
                point.y = selectionCenterY + scaledX * sin + scaledY * cos + selectionTranslateY
            }
            if (selectionScale != 1f) {
                for (i in stroke.widths.indices) stroke.widths[i] = (stroke.widths[i] * selectionScale).coerceAtLeast(0.5f)
            }
        }
        if (selectionParts.isNotEmpty()) {
            frames[frame] = selectionParts.map { it.second }.toMutableList()
        }
        selectedStrokes.clear()
        selectionOriginals.clear()
        selectionFrameBackup = null
        selectionParts.clear()
        lassoPoints.clear()
        lassoEnabled = false
        selectionTranslateX = 0f
        selectionTranslateY = 0f
        selectionRotation = 0f
        selectionScale = 1f
        invalidateFrameCache()
        invalidate()
        onChanged?.invoke()
        onHistoryAction?.invoke()
    }

    fun cancelSelection() {
        selectionFrameBackup?.let { backup -> frames[frame] = cloneStrokes(backup) }
        selectedStrokes.clear()
        selectionOriginals.clear()
        selectionFrameBackup = null
        selectionParts.clear()
        lassoPoints.clear()
        lassoEnabled = false
        selectionTranslateX = 0f
        selectionTranslateY = 0f
        selectionRotation = 0f
        selectionScale = 1f
        invalidateFrameCache()
        invalidate()
    }

    private fun selectionBounds(): RectF {
        val pts = selectedStrokes.flatMap { it.points }
        if (pts.isEmpty()) return RectF()
        val left = pts.minOf { it.x }; val right = pts.maxOf { it.x }
        val top = pts.minOf { it.y }; val bottom = pts.maxOf { it.y }
        return RectF(left, top, right, bottom)
    }

    private fun pointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if (((a.y > point.y) != (b.y > point.y)) &&
                (point.x < (b.x - a.x) * (point.y - a.y) / ((b.y - a.y).takeIf { it != 0f } ?: 0.0001f) + a.x)) inside = !inside
            j = i
        }
        return inside
    }

    private fun finishLasso() {
        lassoEnabled = false
        if (lassoPoints.size < 3) { lassoPoints.clear(); invalidate(); return }
        val originalFrame = frames[frame].orEmpty()
        val backup = cloneStrokes(originalFrame)
        val replacement = mutableListOf<Stroke>()
        val parts = mutableListOf<Pair<Boolean, Stroke>>()
        val selected = mutableListOf<Stroke>()

        originalFrame.forEach { stroke ->
            if (stroke.erase || stroke.points.isEmpty()) {
                parts.add(false to stroke)
                replacement.add(stroke)
            } else {
                var segmentStart = 0
                var segmentInside = pointInPolygon(stroke.points[0], lassoPoints)
                fun flushSegment(endExclusive: Int) {
                    if (endExclusive <= segmentStart) return
                    val segment = Stroke(
                        stroke.points.subList(segmentStart, endExclusive).map { PointF(it.x, it.y) }.toMutableList(),
                        stroke.widths.subList(segmentStart, min(endExclusive, stroke.widths.size)).toMutableList(),
                        stroke.color,
                        stroke.erase
                    )
                    if (segment.widths.size < segment.points.size) {
                        while (segment.widths.size < segment.points.size) segment.widths.add(segment.widths.lastOrNull() ?: 4f)
                    }
                    parts.add(segmentInside to segment)
                    if (segmentInside) selected.add(segment) else replacement.add(segment)
                }
                for (pointIndex in 1 until stroke.points.size) {
                    val inside = pointInPolygon(stroke.points[pointIndex], lassoPoints)
                    if (inside != segmentInside) {
                        flushSegment(pointIndex)
                        segmentStart = pointIndex
                        segmentInside = inside
                    }
                }
                flushSegment(stroke.points.size)
            }
        }

        if (selected.isEmpty()) {
            lassoPoints.clear()
            invalidate()
            return
        }
        selectionFrameBackup = backup
        selectionParts.clear()
        selectionParts.addAll(parts)
        frames[frame] = replacement
        selectedStrokes.clear()
        selectedStrokes.addAll(selected)
        selectionOriginals = selected.map { st ->
            Stroke(st.points.map { PointF(it.x, it.y) }.toMutableList(), st.widths.toMutableList(), st.color, st.erase)
        }.toMutableList()
        val bounds = selectionBounds()
        selectionCenterX = bounds.centerX()
        selectionCenterY = bounds.centerY()
        selectionTranslateX = 0f; selectionTranslateY = 0f
        selectionRotation = 0f; selectionScale = 1f
        lassoPoints.clear()
        invalidateFrameCache()
        invalidate()
    }

    private fun viewToDrawingPoint(
        viewX: Float,
        viewY: Float,
        left: Float,
        top: Float,
        boardScale: Float
    ): PointF {
        val dx = (viewX - width / 2f - viewportPanX) / viewportScale
        val dy = (viewY - height / 2f - viewportPanY) / viewportScale
        val radians = Math.toRadians(viewportRotation.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val unrotatedX = cos * dx + sin * dy + width / 2f
        val unrotatedY = -sin * dx + cos * dy + height / 2f
        return PointF(
            ((unrotatedX - left) / boardScale) * 120f,
            ((unrotatedY - top) / boardScale) * 120f
        )
    }

    private fun updateTwoFingerTransform(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x1 = event.getX(0)
        val y1 = event.getY(0)
        val x2 = event.getX(1)
        val y2 = event.getY(1)
        val midX = (x1 + x2) / 2f
        val midY = (y1 + y2) / 2f
        val dx = x2 - x1
        val dy = y2 - y1
        val span = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

        if (previousSpan > 0f) {
            viewportScale = (viewportScale * (span / previousSpan)).coerceIn(0.25f, 6f)
            var angleDelta = angle - previousAngle
            while (angleDelta > 180f) angleDelta -= 360f
            while (angleDelta < -180f) angleDelta += 360f
            viewportRotation += angleDelta
            viewportPanX += midX - previousMidX
            viewportPanY += midY - previousMidY
            invalidate()
        }
        previousSpan = span
        previousAngle = angle
        previousMidX = midX
        previousMidY = midY
    }

    fun resetViewport() {
        viewportScale = 1f
        viewportRotation = 0f
        viewportPanX = 0f
        viewportPanY = 0f
        invalidate()
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        if (stroke.erase) {
            drawStrokeRaw(canvas, stroke)
            return
        }
        val alpha = Color.alpha(stroke.color)
        if (alpha >= 255) {
            drawStrokeRaw(canvas, stroke)
            return
        }
        // Render all connected samples opaque into one temporary layer, then apply
        // the requested transparency once to the completed stroke. This prevents
        // joins and dense samples from becoming darker/greyer than the chosen color.
        val opaqueStroke = stroke.copy(color = Color.rgb(
            Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color)
        ))
        val padding = (stroke.widths.maxOrNull() ?: 4f) * 2f + 4f
        val layerBounds = RectF(
            (stroke.points.minOf { it.x } - padding).coerceAtLeast(0f),
            (stroke.points.minOf { it.y } - padding).coerceAtLeast(0f),
            (stroke.points.maxOf { it.x } + padding).coerceAtMost(1920f),
            (stroke.points.maxOf { it.y } + padding).coerceAtMost(1080f)
        )
        val alphaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.alpha = alpha }
        // The compositing paint belongs to saveLayer; restoreToCount only accepts
        // the save count. Alpha is applied once to the completed stroke layer.
        val layer = canvas.saveLayer(layerBounds, alphaPaint)
        drawStrokeRaw(canvas, opaqueStroke)
        canvas.restoreToCount(layer)
    }

    private fun drawStrokeRaw(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = stroke.color
            isDither = true
            if (stroke.erase) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
        val baseWidth = (stroke.widths.firstOrNull() ?: 4f).coerceAtLeast(0.5f) * 2f
        if (stroke.points.size == 1) {
            val point = stroke.points[0]
            canvas.drawCircle(point.x, point.y, baseWidth / 2f, fillPaint)
            return
        }

        // Build a smoothed centerline through midpoint quadratic segments.
        val smoothed = Path().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            for (i in 1 until stroke.points.lastIndex) {
                val currentPoint = stroke.points[i]
                val nextPoint = stroke.points[i + 1]
                val midX = (currentPoint.x + nextPoint.x) * 0.5f
                val midY = (currentPoint.y + nextPoint.y) * 0.5f
                quadTo(currentPoint.x, currentPoint.y, midX, midY)
            }
            val last = stroke.points.last()
            lineTo(last.x, last.y)
        }

        // Sample the smoothed path and interpolate pressure width along it.
        val measure = PathMeasure(smoothed, false)
        val length = measure.length
        val samples = max(2, min(420, (length / 7f).toInt() + 1))
        val position = FloatArray(2)
        val tangent = FloatArray(2)
        var previousPoint: PointF? = null
        var previousWidth = baseWidth
        for (sample in 0..samples) {
            val distance = length * sample / samples
            measure.getPosTan(distance, position, tangent)
            val point = PointF(position[0], position[1])
            val ratio = if (samples == 0) 0f else sample.toFloat() / samples
            val rawIndex = ratio * (stroke.points.size - 1)
            val lower = rawIndex.toInt().coerceIn(0, stroke.points.lastIndex)
            val upper = (lower + 1).coerceAtMost(stroke.points.lastIndex)
            val fraction = rawIndex - lower
            val w0 = stroke.widths.getOrElse(lower) { 4f }
            val w1 = stroke.widths.getOrElse(upper) { w0 }
            val width = ((w0 + (w1 - w0) * fraction).coerceAtLeast(0.5f)) * 2f
            val previous = previousPoint
            if (previous != null) {
                paint.strokeWidth = (previousWidth + width) * 0.5f
                canvas.drawLine(previous.x, previous.y, point.x, point.y, paint)
            } else {
                canvas.drawCircle(point.x, point.y, width * 0.5f, fillPaint)
            }
            previousPoint = point
            previousWidth = width
        }
        previousPoint?.let { canvas.drawCircle(it.x, it.y, previousWidth * 0.5f, fillPaint) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val tool = event.getToolType(actionIndex)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

        val boardScale = min((width * 0.94f) / 16f, (height * 0.90f) / 9f)
        val boardW = 16f * boardScale
        val boardH = 9f * boardScale
        val left = (width - boardW) / 2f
        val top = (height - boardH) / 2f

        if (lassoEnabled || selectedStrokes.isNotEmpty()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (lassoEnabled) {
                        lassoPoints.clear()
                        lassoPoints.add(viewToDrawingPoint(event.x, event.y, left, top, boardScale))
                    } else {
                        selectionLastX = event.x; selectionLastY = event.y
                        selectionLastSpan = 0f
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (selectedStrokes.isNotEmpty() && event.pointerCount >= 2) {
                        val p0 = viewToDrawingPoint(event.getX(0), event.getY(0), left, top, boardScale)
                        val p1 = viewToDrawingPoint(event.getX(1), event.getY(1), left, top, boardScale)
                        selectionLastX = (p0.x + p1.x) / 2f
                        selectionLastY = (p0.y + p1.y) / 2f
                        selectionLastSpan = kotlin.math.hypot((p1.x-p0.x).toDouble(), (p1.y-p0.y).toDouble()).toFloat()
                        selectionLastAngle = Math.toDegrees(kotlin.math.atan2((p1.y-p0.y).toDouble(), (p1.x-p0.x).toDouble())).toFloat()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lassoEnabled) {
                        val point = viewToDrawingPoint(event.x, event.y, left, top, boardScale)
                        val last = lassoPoints.lastOrNull()
                        if (last == null || kotlin.math.hypot((point.x-last.x).toDouble(), (point.y-last.y).toDouble()) >= 2.5) lassoPoints.add(point)
                    } else if (selectedStrokes.isNotEmpty() && event.pointerCount >= 2) {
                        val p0 = viewToDrawingPoint(event.getX(0), event.getY(0), left, top, boardScale)
                        val p1 = viewToDrawingPoint(event.getX(1), event.getY(1), left, top, boardScale)
                        val cx = (p0.x+p1.x)/2f; val cy = (p0.y+p1.y)/2f
                        val span = kotlin.math.hypot((p1.x-p0.x).toDouble(), (p1.y-p0.y).toDouble()).toFloat()
                        val angle = Math.toDegrees(kotlin.math.atan2((p1.y-p0.y).toDouble(), (p1.x-p0.x).toDouble())).toFloat()
                        selectionTranslateX += cx-selectionLastX; selectionTranslateY += cy-selectionLastY
                        if (selectionLastSpan > 0f && span > 0f) selectionScale = (selectionScale * span/selectionLastSpan).coerceIn(0.1f, 8f)
                        var da = angle-selectionLastAngle
                        while (da > 180f) da -= 360f
                        while (da < -180f) da += 360f
                        selectionRotation += da
                        selectionLastX = cx; selectionLastY = cy; selectionLastSpan = span; selectionLastAngle = angle
                    } else if (selectedStrokes.isNotEmpty()) {
                        val point = viewToDrawingPoint(event.x, event.y, left, top, boardScale)
                        val previous = viewToDrawingPoint(selectionLastX, selectionLastY, left, top, boardScale)
                        selectionTranslateX += point.x-previous.x
                        selectionTranslateY += point.y-previous.y
                        selectionLastX = event.x; selectionLastY = event.y
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (lassoEnabled) finishLasso()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }

        if (!stylus) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    current = null
                    lastFingerX = event.x
                    lastFingerY = event.y
                    previousSpan = 0f
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        previousSpan = 0f
                        updateTwoFingerTransform(event)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        updateTwoFingerTransform(event)
                    } else {
                        viewportPanX += event.x - lastFingerX
                        viewportPanY += event.y - lastFingerY
                        lastFingerX = event.x
                        lastFingerY = event.y
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    previousSpan = 0f
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    if (remaining < event.pointerCount) {
                        lastFingerX = event.getX(remaining)
                        lastFingerY = event.getY(remaining)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    previousSpan = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = viewToDrawingPoint(event.x, event.y, left, top, boardScale)
                if (point.x !in 0f..1920f || point.y !in 0f..1080f) return true
                val eraseStroke = eraser || tool == MotionEvent.TOOL_TYPE_ERASER
                if (!eraseStroke) onColorUsed?.invoke(color)
                val strokeColor = Color.argb(
                    brushOpacity,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                val initialPressure = event.pressure.coerceIn(0.05f, 1f)
                val initialWidth = if (pressureSensitivity) activeBrushSize() * (0.18f + initialPressure * initialPressure * 1.12f) else activeBrushSize()
                redoFrames.remove(frame)
                clearRedoSnapshots.remove(frame)
                current = Stroke(mutableListOf(point), mutableListOf(initialWidth), strokeColor, eraseStroke)
                strokes().add(current!!)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                for (pointer in 0 until event.pointerCount) {
                    val pointerTool = event.getToolType(pointer)
                    if (pointerTool != MotionEvent.TOOL_TYPE_STYLUS && pointerTool != MotionEvent.TOOL_TYPE_ERASER) continue
                    fun appendSample(x: Float, y: Float, rawPressure: Float) {
                        val pressure = rawPressure.coerceIn(0.05f, 1f)
                        val point = viewToDrawingPoint(x, y, left, top, boardScale)
                        if (point.x in 0f..1920f && point.y in 0f..1080f) {
                            val prior = stroke.points.lastOrNull()
                            val distance = if (prior == null) 999f else kotlin.math.hypot((point.x - prior.x).toDouble(), (point.y - prior.y).toDouble()).toFloat()
                            if (distance >= 0.35f) {
                                val pressureFactor = if (pressureSensitivity) {
                                    val curved = pressure * pressure
                                    0.18f + curved * 1.12f
                                } else 1f
                                val nextWidth = (activeBrushSize() * pressureFactor).coerceAtLeast(0.5f)
                                stroke.points.add(point)
                                stroke.widths.add(nextWidth)
                            }
                        }
                    }
                    for (history in 0 until event.historySize) {
                        appendSample(
                            event.getHistoricalX(pointer, history),
                            event.getHistoricalY(pointer, history),
                            event.getHistoricalPressure(pointer, history)
                        )
                    }
                    appendSample(event.getX(pointer), event.getY(pointer), event.getPressure(pointer))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidateFrameCache()
                invalidate()
                onChanged?.invoke()
                onHistoryAction?.invoke()
                return true
            }
        }
        return true
    }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("brushSize", brush.toDouble())
        root.put("eraserSize", eraserBrush.toDouble())
        root.put("hue", hue.toDouble()); root.put("saturation", saturation.toDouble()); root.put("value", value.toDouble())
        root.put("brushColor", color)
        root.put("brushOpacity", brushOpacity)
        root.put("pressureSensitivity", pressureSensitivity)
        val frameArray = JSONArray()
        val indices = (frames.keys + notes.keys + importedImages.keys).toSet().sorted()
        indices.forEach { index ->
            val frameObject = JSONObject()
            frameObject.put("index", index)
            val strokeArray = JSONArray()
            frames[index].orEmpty().forEach { stroke ->
                val strokeObject = JSONObject()
                strokeObject.put("color", stroke.color)
                strokeObject.put("erase", stroke.erase)
                val points = JSONArray()
                stroke.points.forEachIndexed { pointIndex, point ->
                    val pointObject = JSONObject()
                    pointObject.put("x", point.x.toDouble())
                    pointObject.put("y", point.y.toDouble())
                    pointObject.put("width", stroke.widths.getOrElse(pointIndex) { 4f }.toDouble())
                    points.put(pointObject)
                }
                strokeObject.put("points", points)
                strokeArray.put(strokeObject)
            }
            frameObject.put("strokes", strokeArray)
            importedImages[index]?.let { bitmap ->
                val encoded = encodedImageCache.getOrPut(bitmap) {
                    val bytes = java.io.ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
                frameObject.put("importedImage", encoded)
            }
            val noteArray = JSONArray()
            notes[index]?.firstOrNull()?.let { noteArray.put(it) }
            frameObject.put("notes", noteArray)
            noteColors[index]?.let { frameObject.put("noteColor", it) }
            frameArray.put(frameObject)
        }
        root.put("frames", frameArray)
        return root
    }

    fun loadJson(root: JSONObject) {
        invalidateAllFrameCaches()
        frames.clear()
        redoFrames.clear()
        clearUndoSnapshots.clear()
        clearRedoSnapshots.clear()
        notes.clear()
        noteColors.clear()
        importedImages.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        importedImages.clear()
        encodedImageCache.clear()
        decodedImageCache.clear()
        brush = root.optDouble("brushSize", 4.0).toFloat().coerceIn(1f, 200f)
        eraserBrush = root.optDouble("eraserSize", 20.0).toFloat().coerceIn(1f,200f)
        color = root.optInt("brushColor", Color.BLACK)
        if (root.has("hue") && root.has("saturation") && root.has("value")) {
            hue=root.optDouble("hue",0.0).toFloat(); saturation=root.optDouble("saturation",1.0).toFloat(); value=root.optDouble("value",0.0).toFloat()
        } else {
            val hsv=FloatArray(3); Color.colorToHSV(color,hsv); hue=hsv[0]; saturation=hsv[1]; value=hsv[2]
        }
        brushOpacity = root.optInt("brushOpacity", 255).coerceIn(0, 255)
        pressureSensitivity = root.optBoolean("pressureSensitivity", true)
        val array = root.optJSONArray("frames") ?: JSONArray()
        for (i in 0 until array.length()) {
            val frameObject = array.optJSONObject(i) ?: continue
            val index = frameObject.optInt("index", i)
            val strokeArray = frameObject.optJSONArray("strokes") ?: JSONArray()
            val loadedStrokes = mutableListOf<Stroke>()
            for (j in 0 until strokeArray.length()) {
                val strokeObject = strokeArray.optJSONObject(j) ?: continue
                val pointArray = strokeObject.optJSONArray("points") ?: JSONArray()
                val points = mutableListOf<PointF>()
                val widths = mutableListOf<Float>()
                for (k in 0 until pointArray.length()) {
                    val point = pointArray.optJSONObject(k) ?: continue
                    points.add(PointF(point.optDouble("x").toFloat(), point.optDouble("y").toFloat()))
                    widths.add(point.optDouble("width", 4.0).toFloat())
                }
                if (points.isNotEmpty()) loadedStrokes.add(
                    Stroke(points, widths, strokeObject.optInt("color", Color.BLACK), strokeObject.optBoolean("erase", false))
                )
            }
            if (loadedStrokes.isNotEmpty()) frames[index] = loadedStrokes
            val imageData = frameObject.optString("importedImage", "")
            if (imageData.isNotBlank()) try {
                val cacheKey = "${imageData.hashCode()}:${imageData.length}"
                val cached = decodedImageCache[cacheKey]
                val bitmap = if (cached != null && cached.encoded == imageData && !cached.bitmap.isRecycled) {
                    cached.bitmap
                } else {
                    val imageBytes = Base64.decode(imageData, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)?.also {
                        decodedImageCache[cacheKey] = DecodedImageEntry(imageData, it)
                    }
                }
                if (bitmap != null) importedImages[index] = bitmap
            } catch (_: Exception) { }
            val noteArray = frameObject.optJSONArray("notes") ?: JSONArray()
            if (noteArray.length() > 0) {
                notes[index] = mutableListOf(noteArray.optString(0))
                noteColors[index] = frameObject.optInt("noteColor", Color.DKGRAY)
            }
        }
        invalidate()
    }

    fun drawFrameToCanvas(index: Int, canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        frames[index].orEmpty().forEach { drawStroke(canvas, it) }
        notes[index]?.firstOrNull()?.let {
            textPaint.textSize = 34f
            textPaint.color = Color.RED
            canvas.drawText(it, 24f, 1030f, textPaint)
            textPaint.color = Color.DKGRAY
        }
    }

    fun exportCurrent(output: OutputStream) {
        exportFrame(frame, output)
    }

    fun exportFrame(index: Int, output: OutputStream) {
        val bitmap = renderFrame(index)
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IllegalStateException("Android no pudo codificar el PNG del plano ${index + 1}")
            }
            output.flush()
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderFrame(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        frames[index].orEmpty().forEach { drawStroke(canvas, it) }
        notes[index]?.firstOrNull()?.let {
            textPaint.textSize = 34f
            textPaint.color = Color.RED
            canvas.drawText(it, 24f, 1030f, textPaint)
            textPaint.color = Color.DKGRAY
        }
        return bitmap
    }
}