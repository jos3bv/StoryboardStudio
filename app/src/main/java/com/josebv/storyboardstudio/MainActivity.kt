package com.josebv.storyboardstudio

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
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var drawing: DrawingView
    private lateinit var timelineView: TimelineView
    private lateinit var status: TextView
    private lateinit var durationLabel: TextView
    private lateinit var playPauseButton: Button
    private var mediaPlayer: MediaPlayer? = null
    private var sequencePlaying = false
    private var sequenceStartUptime = 0L
    private var sequenceStartPosition = 0
    private val appStorageDirectory by lazy {
        File(getExternalFilesDir(null) ?: filesDir, "StoryboardStudio")
    }
    private val sessionFile by lazy { File(appStorageDirectory, "Autosave/session.json") }
    private val sessionsDirectory by lazy { File(appStorageDirectory, "Sessions") }
    private val exportsDirectory by lazy { File(appStorageDirectory, "Exports/PNG") }
    private val pdfDirectory by lazy { File(appStorageDirectory, "Exports/PDF") }
    private var restoringSession = false
    private var audioUri: Uri? = null
    private var audioDurationMs = 0
    private var waveform = FloatArray(0)

    private val frameDurations = mutableListOf(3000)
    private var currentFrame = 0
    private var projectName = "Mi storyboard"
    private var updatingFromPlayback = false

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
        prepareStorageFolders()
        migratePreviousStorage()
        loadSession()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(29, 33, 39))
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.rgb(23, 26, 31))
        }
        val title = TextView(this).apply {
            text = "STORYBOARD STUDIO"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f))
        addButton(top, "Archivo") { showSessionDialog() }
        addButton(top, "💾") { saveProject() }
        root.addView(top)

        val work = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(6), dp(8), dp(6))
        }
        addToolLabel(tools, "DIBUJO")
        addButtonVertical(tools, "−") { drawing.changeBrush(-2f) }
        addButtonVertical(tools, "＋") { drawing.changeBrush(2f) }
        addButtonVertical(tools, "Pincel…") { showBrushSettings() }
        addButtonVertical(tools, "✎") { drawing.setEraser(false) }
        addButtonVertical(tools, "⌫") { drawing.setEraser(true) }
        addButtonVertical(tools, "↶") { drawing.undo() }
        addButtonVertical(tools, "▧") { confirmClear() }
        addButtonVertical(tools, "T") { addTextNote() }
        addButtonVertical(tools, "⌂ Vista") { drawing.resetViewport() }
        work.addView(tools, LinearLayout.LayoutParams(dp(92), -1))

        drawing = DrawingView(this).apply {
            onChanged = { scheduleAutosave() }
        }
        work.addView(drawing, LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(5), 0, dp(5), 0)
        })

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(37, 42, 49))
        }
        addToolLabel(side, "PLANO ACTUAL")
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(3), dp(5), dp(3), dp(3))
        }
        side.addView(status)
        durationLabel = TextView(this).apply {
            setTextColor(Color.rgb(190, 204, 220))
            textSize = 12f
            setPadding(dp(3), dp(3), dp(3), dp(5))
        }
        side.addView(durationLabel)
        addButtonVertical(side, "− 0,5 s") { changeDuration(-500) }
        addButtonVertical(side, "＋ 0,5 s") { changeDuration(500) }
        addButtonVertical(side, "Exportar plano PNG") { exportCurrentPng() }
        addButtonVertical(side, "Exportar todos PNG") { exportAllPng() }
        addButtonVertical(side, "Exportar storyboard PDF") { exportPdf() }
        work.addView(side, LinearLayout.LayoutParams(dp(154), -1))

        root.addView(work, LinearLayout.LayoutParams(-1, 0, 1f))

        val timelinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            setBackgroundColor(Color.rgb(20, 23, 28))
        }
        val timelineToolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val timelineToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Navigation and playback stay on the left.
        addButton(timelineToolbar, "⏮") { seekToTimelinePosition(0) }
        addButton(timelineToolbar, "◀|") {
            stopPlayback()
            selectFrame((currentFrame - 1).coerceAtLeast(0), true)
        }
        playPauseButton = addButtonReturning(timelineToolbar, "▶") { toggleAudio() }
        addButton(timelineToolbar, "|▶") {
            stopPlayback()
            selectFrame((currentFrame + 1).coerceAtMost(frameDurations.lastIndex), true)
        }
        addButton(timelineToolbar, "⏭") { seekToTimelinePosition(totalDuration()) }
        addButton(timelineToolbar, "－") { timelineView.changeZoom(0.8f) }
        addButton(timelineToolbar, "＋") { timelineView.changeZoom(1.25f) }
        addButton(timelineToolbar, "◎") { timelineView.centerOnPlayhead() }
        addButton(timelineToolbar, "←") { moveSelectedFrame(-1) }
        addButton(timelineToolbar, "→") { moveSelectedFrame(1) }

        val separator = View(this).apply {
            setBackgroundColor(Color.rgb(75, 83, 94))
        }
        timelineToolbar.addView(separator, LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            setMargins(dp(6), 0, dp(6), 0)
        })
        // Shot and audio actions stay on the right.
        addButton(timelineToolbar, "+ Toma") { addFrame() }
        addButton(timelineToolbar, "Duplicar") { duplicateFrame() }
        addButton(timelineToolbar, "Eliminar") { deleteFrame() }
        addButton(timelineToolbar, "Duración…") { editDuration() }
        addButton(timelineToolbar, "Cargar audio…") { chooseAudio() }
        timelineToolbarScroll.addView(timelineToolbar, ViewGroup.LayoutParams(-2, -2))
        timelinePanel.addView(timelineToolbarScroll, LinearLayout.LayoutParams(-1, dp(38)))

        val horizontalScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
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
            onSeek = { position ->
                mediaPlayer?.let {
                    it.seekTo(position.coerceIn(0, max(0, audioDurationMs)))
                    timelineView.setPlayhead(position)
                    val idx = frameAtTime(position)
                    selectFrame(idx, false)
                }
            }
            onMoveFrame = { from, to -> moveFrame(from, to) }
        }
        horizontalScroll.addView(timelineView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(146)
        ))
        timelinePanel.addView(horizontalScroll, LinearLayout.LayoutParams(-1, dp(152)))
        root.addView(timelinePanel)

        setContentView(root)
        updateFrameStatus()
        updateTimeline()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        parent.addView(button, LinearLayout.LayoutParams(-2, dp(32)).apply {
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
            typeface = Typeface.DEFAULT_BOLD
            setPadding(3, 4, 3, 4)
        })
    }

    private fun setStatus(message: String) {
        status.text = message
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
        status.text = "Plano ${currentFrame + 1} / ${frameDurations.size}"
        durationLabel.text = "Duración: ${formatTime(duration)}"
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
        scheduleAutosave()
    }

    private fun deleteFrame() {
        if (frameDurations.size <= 1) {
            Toast.makeText(this, "El proyecto debe conservar al menos un plano", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar plano")
            .setMessage("¿Eliminar el plano ${currentFrame + 1}? Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Eliminar") { _, _ ->
                frameDurations.removeAt(currentFrame)
                drawing.removeFrame(currentFrame)
                currentFrame = currentFrame.coerceAtMost(frameDurations.lastIndex)
                drawing.setFrame(currentFrame)
                updateFrameStatus()
                updateTimeline()
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
        scheduleAutosave()
    }

    private fun changeDuration(delta: Int) {
        val minDuration = 100
        frameDurations[currentFrame] = (frameDurations[currentFrame] + delta).coerceAtLeast(minDuration)
        updateFrameStatus()
        updateTimeline()
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
                    scheduleAutosave()
                }
            }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Limpiar plano")
            .setMessage("¿Borrar todos los trazos del plano actual?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ -> drawing.clear(); scheduleAutosave() }.show()
    }

    private fun addTextNote() {
        val input = EditText(this).apply { hint = "Escribe una nota o diálogo" }
        AlertDialog.Builder(this).setTitle("Añadir anotación").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Añadir") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) { drawing.addText(value); scheduleAutosave() }
            }.show()
    }

    private fun showBrushSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        val sizeLabel = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        val sizeSeek = SeekBar(this).apply { max = 39; progress = (drawing.brushSize() - 1f).toInt().coerceIn(0, 39) }
        val opacityLabel = TextView(this).apply { textSize = 15f; setTextColor(Color.WHITE) }
        val opacitySeek = SeekBar(this).apply { max = 100; progress = drawing.brushOpacity() }
        val pressureCheck = CheckBox(this).apply {
            text = "Usar sensibilidad a la presión del S Pen"
            isChecked = drawing.pressureEnabled()
            setTextColor(Color.WHITE)
        }
        fun updateLabels() {
            sizeLabel.text = "Tamaño: ${sizeSeek.progress + 1}"
            opacityLabel.text = "Opacidad: ${opacitySeek.progress}%"
        }
        sizeSeek.setOnSeekBarChangeListener(simpleSeekListener { updateLabels() })
        opacitySeek.setOnSeekBarChangeListener(simpleSeekListener { updateLabels() })
        updateLabels()
        content.addView(sizeLabel)
        content.addView(sizeSeek)
        content.addView(opacityLabel)
        content.addView(opacitySeek)
        content.addView(pressureCheck)
        AlertDialog.Builder(this)
            .setTitle("Configuración del pincel")
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                drawing.configureBrush(
                    (sizeSeek.progress + 1).toFloat(),
                    (opacitySeek.progress * 255 / 100),
                    pressureCheck.isChecked
                )
                scheduleAutosave()
            }
            .show()
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
                    Toast.makeText(this, "Sesión cargada", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    setStatus("No se pudo abrir la sesión: ${e.message}")
                }
            }
        }
    }
    private fun extractWaveform(uri: Uri): FloatArray {
        val bucketCount = 1400
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
            updateTimeline()
            Thread {
                val extracted = extractWaveform(uri)
                runOnUiThread {
                    waveform = extracted
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
            playPauseButton.text = "❚❚"
            handler.removeCallbacks(playbackUpdater)
            handler.post(playbackUpdater)
        } else {
            sequencePlaying = true
            sequenceStartPosition = frameStartTime(currentFrame)
            sequenceStartUptime = SystemClock.uptimeMillis()
            playPauseButton.text = "❚❚"
            handler.removeCallbacks(playbackUpdater)
            handler.post(playbackUpdater)
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        sequencePlaying = false
        handler.removeCallbacks(playbackUpdater)
        if (::playPauseButton.isInitialized) playPauseButton.text = "▶"
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

    private val autosaveRunnable = Runnable { saveSession(showMessage = false) }

    private fun saveProject() {
        saveSession(showMessage = true)
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
            mediaPlayer?.release()
            mediaPlayer = null
            audioUri = null
            audioDurationMs = 0
            waveform = FloatArray(0)

            projectName = root.optString("projectName", "Mi storyboard")
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

            val savedAudio = root.optString("audioUri", "")
            if (savedAudio.isNotBlank() && savedAudio != "null") {
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
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = File(sessionsDirectory, "${base}_$stamp.storyboard.json")
            file.writeText(buildSessionJson().toString(2))
            setStatus("Sesión guardada: ${sessionsDirectory.absolutePath}")
            Toast.makeText(this, "Sesión guardada en Sessions/${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("No se pudo guardar la copia: ${e.message}")
        }
    }

    private fun showLocalSessionsDialog() {
        val files = sessionsDirectory.listFiles { file ->
            file.isFile && file.name.endsWith(".storyboard.json")
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Sesiones guardadas")
                .setMessage("Todavía no hay copias guardadas en la aplicación.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }
        val labels = files.map { file ->
            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(file.lastModified()))
            "${file.nameWithoutExtension}\n$date"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Abrir sesión")
            .setItems(labels) { _, which ->
                try {
                    saveSession(showMessage = false)
                    saveLocalSnapshot()
                    applySessionJson(JSONObject(files[which].readText()))
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
            .setTitle("Guardar sesión como archivo")
            .setMessage("Elige un nombre para identificar este storyboard.")
            .setView(nameInput)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotBlank()) projectName = name
                val safeName = projectName.trim().ifBlank { "Mi storyboard" }
                    .replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ _-]"), "")
                    .replace(" ", "_")
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "$safeName.storyboard.json")
                }
                startActivityForResult(intent, 102)
                scheduleAutosave()
            }
            .show()
    }
    private fun openSessionFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 103)
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
            if (oldSessions.isDirectory) {
                sessionsDirectory.mkdirs()
                oldSessions.listFiles()?.filter { it.isFile }?.forEach { old ->
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
                    Environment.DIRECTORY_DOCUMENTS + "/StoryboardStudio/" + subfolder
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
                "StoryboardStudio/$subfolder")
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
                "storyboard_${System.currentTimeMillis()}.pdf",
                "application/pdf",
                "PDF"
            ) { output -> pdf.writeTo(output) }
            setStatus("PDF guardado en Documentos/StoryboardStudio/PDF")
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
            val name = "plano_${(currentFrame + 1).toString().padStart(3, '0')}.png"
            writeToDocuments(name, "image/png", "PNG") { output -> drawing.exportCurrent(output) }
            setStatus("PNG guardado en Documentos/StoryboardStudio/PNG")
            Toast.makeText(this, "Plano ${currentFrame + 1} exportado a Documentos", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Error al exportar PNG: ${e.message}")
            Toast.makeText(this, "Falló la exportación PNG: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportAllPng() {
        try {
            for (i in frameDurations.indices) {
                val name = "plano_${(i + 1).toString().padStart(3, '0')}.png"
                writeToDocuments(name, "image/png", "PNG") { output -> drawing.exportFrame(i, output) }
            }
            setStatus("${frameDurations.size} PNG guardados en Documentos/StoryboardStudio/PNG")
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

    override fun onPause() {
        saveSession(showMessage = false)
        super.onPause()
    }

    override fun onDestroy() {
        saveSession(showMessage = false)
        stopPlayback()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}

class TimelineView(context: android.content.Context) : View(context) {
    var onSelectFrame: ((Int) -> Unit)? = null
    var onDurationChanged: ((Int, Int) -> Unit)? = null
    var onSeek: ((Int) -> Unit)? = null
    var onMoveFrame: ((Int, Int) -> Unit)? = null

    private var durations = listOf(3000)
    private var selected = 0
    private var waveform = FloatArray(0)
    private var audioDurationMs = 0
    private var playheadMs = 0
    private var pixelsPerSecond = 85f
    private val density = resources.displayMetrics.density
    private val leftMargin = 18f * density
    private val rulerHeight = 29f * density
    private val cardsTop = 36f * density
    private val cardHeight = 58f * density
    private val audioTop = 101f * density
    private val audioHeight = 34f * density
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
    private var originalDuration = 0
    private var lastDuration = 0
    private var dragTarget = -1

    private enum class GestureMode { NONE, RESIZE, MOVE }

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

    fun setPlayhead(position: Int) {
        playheadMs = position.coerceAtLeast(0)
        invalidate()
    }

    fun setSelectedFrame(index: Int) {
        selected = index.coerceIn(0, max(0, durations.lastIndex))
        invalidate()
    }

    fun changeZoom(factor: Float) {
        pixelsPerSecond = (pixelsPerSecond * factor).coerceIn(35f, 240f)
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
        val contentWidth = leftMargin * 2 + contentDurationMs() / 1000f * pixelsPerSecond + 80f * density
        val desiredWidth = max(MeasureSpec.getSize(widthMeasureSpec).toFloat(), contentWidth).toInt()
        val desiredHeight = (146f * density).toInt()
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
        var second = 0
        val totalSeconds = ceil(contentDurationMs() / 1000f).toInt()
        while (second <= totalSeconds) {
            val x = leftMargin + second * pixelsPerSecond
            canvas.drawLine(x, rulerHeight - 5f * density, x, rulerHeight, paint)
            if (second % 2 == 0) canvas.drawText("${second}s", x + 3f * density, 10f * density, paint)
            second++
        }
    }

    private fun drawCards(canvas: Canvas) {
        var elapsed = 0
        durations.forEachIndexed { index, duration ->
            val x = leftMargin + elapsed / 1000f * pixelsPerSecond
            val w = max(55f * density, duration / 1000f * pixelsPerSecond)
            val rect = RectF(x, cardsTop, x + w, cardsTop + cardHeight)
            paint.color = if (index == selected) Color.rgb(90, 119, 153) else Color.rgb(57, 65, 76)
            canvas.drawRoundRect(rect, 4f * density, 4f * density, paint)
            paint.color = if (index == selected) Color.WHITE else Color.rgb(215, 222, 231)
            paint.textSize = 12f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("PLANO ${index + 1}", x + 7f * density, cardsTop + 17f * density, paint)
            paint.textSize = 11f * density
            paint.typeface = Typeface.DEFAULT
            canvas.drawText(formatDuration(duration), x + 7f * density, cardsTop + 34f * density, paint)
            paint.color = Color.argb(150, 255, 255, 255)
            canvas.drawRect(x + w - handleWidth, cardsTop, x + w, cardsTop + cardHeight, paint)
            paint.color = Color.rgb(32, 37, 44)
            canvas.drawRect(x + w - handleWidth / 2f - density, cardsTop + 13f * density,
                x + w - handleWidth / 2f + density, cardsTop + 35f * density, paint)
            elapsed += duration
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val lane = RectF(leftMargin, audioTop, leftMargin + max(1f, audioDurationMs / 1000f * pixelsPerSecond), audioTop + audioHeight)
        paint.color = Color.rgb(43, 54, 51)
        canvas.drawRoundRect(lane, 3f * density, 3f * density, paint)
        paint.color = Color.rgb(139, 185, 151)
        if (waveform.isNotEmpty() && audioDurationMs > 0) {
            val center = audioTop + audioHeight / 2f
            val step = lane.width() / waveform.size
            waveform.forEachIndexed { index, peak ->
                val barHeight = max(1f * density, peak * (audioHeight - 5f * density))
                val x = leftMargin + index * step
                canvas.drawRect(x, center - barHeight / 2f, x + max(1f, step * 0.68f), center + barHeight / 2f, paint)
            }
        } else {
            paint.textSize = 11f * density
            paint.color = Color.rgb(180, 193, 183)
            canvas.drawText(if (audioDurationMs > 0) "Generando forma de onda…" else "AUDIO · importa un MP3 para visualizar la onda", leftMargin + 7f * density, audioTop + 18f * density, paint)
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
                activeIndex = hitFrame(event.x, event.y)
                if (activeIndex >= 0) {
                    val rect = frameRect(activeIndex)
                    if (event.x >= rect.right - handleWidth * 1.4f) {
                        gestureMode = GestureMode.RESIZE
                        originalDuration = durations[activeIndex]
                        lastDuration = originalDuration
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (event.x <= rect.left + 16f * density) {
                        gestureMode = GestureMode.MOVE
                        dragTarget = activeIndex
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        gestureMode = GestureMode.NONE
                        onSelectFrame?.invoke(activeIndex)
                    }
                } else if (event.y <= rulerHeight || event.y in audioTop..(audioTop + audioHeight)) {
                    val ms = ((event.x - leftMargin) / pixelsPerSecond * 1000f).toInt().coerceAtLeast(0)
                    onSeek?.invoke(ms)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gestureMode) {
                    GestureMode.RESIZE -> {
                        val deltaMs = ((event.x - downX) / pixelsPerSecond * 1000f).toInt()
                        val newDuration = (originalDuration + deltaMs).coerceAtLeast(100)
                        if (newDuration != lastDuration) {
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

private data class Stroke(
    val points: MutableList<PointF>,
    val widths: MutableList<Float>,
    val color: Int,
    val erase: Boolean = false
)

class DrawingView(context: android.content.Context) : View(context) {
    var onChanged: (() -> Unit)? = null
    private val frames = mutableMapOf<Int, MutableList<Stroke>>()
    private val notes = mutableMapOf<Int, MutableList<String>>()
    private var frame = 0
    private var current: Stroke? = null
    private var color = Color.BLACK
    private var brush = 4f
    private var brushOpacity = 255
    private var pressureSensitivity = true
    private var eraser = false

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
        style = Paint.Style.FILL
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isFocusable = true
    }

    private fun strokes() = frames.getOrPut(frame) { mutableListOf() }
    fun setFrame(index: Int) { frame = index; invalidate() }
    fun clearProject() {
        frames.clear()
        notes.clear()
        current = null
        invalidate()
        onChanged?.invoke()
    }
    fun setEraser(value: Boolean) { eraser = value }
    fun setColor(value: Int) { color = value; eraser = false }
    fun changeBrush(delta: Float) { brush = (brush + delta).coerceIn(1f, 40f) }
    fun brushSize(): Float = brush
    fun brushOpacity(): Int = (brushOpacity * 100 / 255).coerceIn(0, 100)
    fun pressureEnabled(): Boolean = pressureSensitivity
    fun configureBrush(size: Float, opacity: Int, pressure: Boolean) {
        brush = size.coerceIn(1f, 40f)
        brushOpacity = opacity.coerceIn(0, 255)
        pressureSensitivity = pressure
    }

    fun undo() {
        val list = strokes()
        if (list.isNotEmpty()) {
            list.removeAt(list.lastIndex)
            invalidate()
            onChanged?.invoke()
        }
    }

    fun clear() {
        strokes().clear()
        notes[frame]?.clear()
        invalidate()
        onChanged?.invoke()
    }

    fun addText(value: String) {
        notes.getOrPut(frame) { mutableListOf() }.add(value)
        invalidate()
        onChanged?.invoke()
    }

    fun duplicateFrame(source: Int, target: Int) {
        val sourceStrokes = frames[source]?.let { cloneStrokes(it) }
        val sourceNotes = notes[source]?.toMutableList()
        shiftFramesForInsert(target)
        if (sourceStrokes != null) frames[target] = sourceStrokes
        if (sourceNotes != null) notes[target] = sourceNotes
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
        val newNotes = mutableMapOf<Int, MutableList<String>>()
        notes.forEach { (key, value) -> newNotes[if (key >= at) key + 1 else key] = value }
        notes.clear()
        notes.putAll(newNotes)
    }

    fun removeFrame(index: Int) {
        val newFrames = mutableMapOf<Int, MutableList<Stroke>>()
        frames.forEach { (key, value) -> if (key != index) newFrames[if (key > index) key - 1 else key] = value }
        frames.clear()
        frames.putAll(newFrames)
        val newNotes = mutableMapOf<Int, MutableList<String>>()
        notes.forEach { (key, value) -> if (key != index) newNotes[if (key > index) key - 1 else key] = value }
        notes.clear()
        notes.putAll(newNotes)
        invalidate()
    }

    fun moveFrame(from: Int, to: Int) {
        if (from == to) return
        val allKeys = (frames.keys + notes.keys).toSet()
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
        notes.clear(); notes.putAll(newNotes)
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
        val bg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, 1920f, 1080f, bg)
        for (stroke in frames[frame].orEmpty()) drawStroke(canvas, stroke)
        var y = 1030f
        for (note in notes[frame].orEmpty()) {
            textPaint.textSize = 34f
            canvas.drawText(note, 24f, y, textPaint)
            y -= 42f
        }
        canvas.restore()

        val border = Paint().apply {
            color = Color.rgb(130, 145, 160)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(left, top, left + w, top + h, border)
        canvas.restore()
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (stroke.erase) Color.WHITE else stroke.color
            isDither = true
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
        val samples = max(2, min(2400, (length / 3f).toInt() + 1))
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
                val strokeColor = Color.argb(
                    brushOpacity,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
                val initialPressure = event.pressure.coerceIn(0.05f, 1f)
                val initialWidth = if (pressureSensitivity) brush * (0.18f + initialPressure * initialPressure * 1.12f) else brush
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
                                stroke.points.add(point)
                                val pressureFactor = if (pressureSensitivity) {
                                    val curved = pressure * pressure
                                    0.18f + curved * 1.12f
                                } else 1f
                                stroke.widths.add((brush * pressureFactor).coerceAtLeast(0.5f))
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
                invalidate()
                onChanged?.invoke()
                return true
            }
        }
        return true
    }

    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("brushSize", brush.toDouble())
        root.put("brushOpacity", brushOpacity)
        root.put("pressureSensitivity", pressureSensitivity)
        val frameArray = JSONArray()
        val indices = (frames.keys + notes.keys).toSet().sorted()
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
            val noteArray = JSONArray()
            notes[index].orEmpty().forEach { noteArray.put(it) }
            frameObject.put("notes", noteArray)
            frameArray.put(frameObject)
        }
        root.put("frames", frameArray)
        return root
    }

    fun loadJson(root: JSONObject) {
        frames.clear()
        notes.clear()
        brush = root.optDouble("brushSize", 4.0).toFloat().coerceIn(1f, 40f)
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
            val noteArray = frameObject.optJSONArray("notes") ?: JSONArray()
            val loadedNotes = mutableListOf<String>()
            for (k in 0 until noteArray.length()) loadedNotes.add(noteArray.optString(k))
            if (loadedNotes.isNotEmpty()) notes[index] = loadedNotes
        }
        invalidate()
    }

    fun drawFrameToCanvas(index: Int, canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        frames[index].orEmpty().forEach { drawStroke(canvas, it) }
        var y = 1030f
        notes[index].orEmpty().forEach {
            textPaint.textSize = 34f
            canvas.drawText(it, 24f, y, textPaint)
            y -= 42f
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
        var y = 1030f
        notes[index].orEmpty().forEach {
            textPaint.textSize = 34f
            canvas.drawText(it, 24f, y, textPaint)
            y -= 42f
        }
        return bitmap
    }
}
