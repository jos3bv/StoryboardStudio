package com.josebv.storyboardstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.io.File
import java.io.FileOutputStream
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

    private var mediaPlayer: MediaPlayer? = null
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
            if (player != null && player.isPlaying) {
                val position = player.currentPosition
                timelineView.setPlayhead(position)
                val frameAtPosition = frameAtTime(position)
                if (frameAtPosition != currentFrame) {
                    updatingFromPlayback = true
                    currentFrame = frameAtPosition
                    drawing.setFrame(currentFrame)
                    updateFrameStatus()
                    updatingFromPlayback = false
                }
                handler.postDelayed(this, 40)
            }
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
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(29, 33, 39))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 3, 8, 3)
            setBackgroundColor(Color.rgb(23, 26, 31))
        }
        val title = TextView(this).apply {
            text = "STORYBOARD STUDIO"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        top.addView(title, LinearLayout.LayoutParams(0, 44, 1f))
        addButton(top, "＋ Plano") { addFrame() }
        addButton(top, "Duplicar") { duplicateFrame() }
        addButton(top, "Eliminar") { deleteFrame() }
        addButton(top, "◀") { selectFrame((currentFrame - 1).coerceAtLeast(0), true) }
        addButton(top, "▶") { selectFrame((currentFrame + 1).coerceAtMost(frameDurations.lastIndex), true) }
        addButton(top, "Duración") { editDuration() }
        addButton(top, "Audio") { chooseAudio() }
        addButton(top, "▶ / ❚❚") { toggleAudio() }
        addButton(top, "Guardar") { saveProject() }
        root.addView(top)

        val work = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 5, 8, 4)
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 6, 2)
        }
        addToolLabel(tools, "DIBUJO")
        addButtonVertical(tools, "Pincel −") { drawing.changeBrush(-2f) }
        addButtonVertical(tools, "Pincel +") { drawing.changeBrush(2f) }
        addButtonVertical(tools, "Lápiz") { drawing.setEraser(false) }
        addButtonVertical(tools, "Borrador") { drawing.setEraser(true) }
        addButtonVertical(tools, "Deshacer") { drawing.undo() }
        addButtonVertical(tools, "Limpiar") { confirmClear() }
        addButtonVertical(tools, "Texto") { addTextNote() }
        work.addView(tools, LinearLayout.LayoutParams(94, -1))

        drawing = DrawingView(this)
        work.addView(drawing, LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(3, 0, 3, 0)
        })

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 4, 2, 2)
            setBackgroundColor(Color.rgb(37, 42, 49))
        }
        addToolLabel(side, "PLANO ACTUAL")
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(3, 8, 3, 4)
        }
        side.addView(status)
        durationLabel = TextView(this).apply {
            setTextColor(Color.rgb(190, 204, 220))
            textSize = 12f
            setPadding(3, 3, 3, 8)
        }
        side.addView(durationLabel)
        addButtonVertical(side, "− 0,5 s") { changeDuration(-500) }
        addButtonVertical(side, "+ 0,5 s") { changeDuration(500) }
        addButtonVertical(side, "Exportar PNG") { exportCurrentPng() }
        addButtonVertical(side, "Exportar PDF") { exportPdf() }
        work.addView(side, LinearLayout.LayoutParams(132, -1))

        root.addView(work, LinearLayout.LayoutParams(-1, 0, 1f))

        val timelinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 3, 8, 5)
            setBackgroundColor(Color.rgb(20, 23, 28))
        }
        val timelineHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addToolLabel(timelineHeader, "TIMELINE  ·  ARRASTRA EL BORDE DERECHO PARA CAMBIAR LA DURACIÓN")
        val spacer = Space(this)
        timelineHeader.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        addButton(timelineHeader, "−") { timelineView.changeZoom(0.8f) }
        addButton(timelineHeader, "+") { timelineView.changeZoom(1.25f) }
        addButton(timelineHeader, "Centrar") { timelineView.centerOnPlayhead() }
        timelinePanel.addView(timelineHeader)

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
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(126)
        ))
        timelinePanel.addView(horizontalScroll, LinearLayout.LayoutParams(-1, dp(132)))
        root.addView(timelinePanel)

        setContentView(root)
        updateFrameStatus()
        updateTimeline()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 9f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(5, 0, 5, 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-2, 40).apply {
            setMargins(2, 0, 2, 0)
        })
    }

    private fun addButtonVertical(parent: LinearLayout, label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(2, 0, 2, 0)
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, 37).apply {
            setMargins(0, 1, 0, 1)
        })
    }

    private fun addToolLabel(parent: LinearLayout, label: String) {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(Color.rgb(160, 177, 197))
            textSize = 9f
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
        if (movePlayhead && mediaPlayer != null) {
            val start = frameStartTime(currentFrame)
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(start.coerceIn(0, max(0, audioDurationMs)))
            timelineView.setPlayhead(start)
        }
        updateFrameStatus()
        updateTimeline()
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
    }

    private fun duplicateFrame() {
        val target = currentFrame + 1
        drawing.duplicateFrame(currentFrame, target)
        frameDurations.add(target, frameDurations[currentFrame])
        currentFrame = target
        drawing.setFrame(currentFrame)
        updateFrameStatus()
        updateTimeline()
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
            }.show()
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
    }

    private fun changeDuration(delta: Int) {
        val minDuration = 100
        frameDurations[currentFrame] = (frameDurations[currentFrame] + delta).coerceAtLeast(minDuration)
        updateFrameStatus()
        updateTimeline()
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
                }
            }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Limpiar plano")
            .setMessage("¿Borrar todos los trazos del plano actual?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ -> drawing.clear() }.show()
    }

    private fun addTextNote() {
        val input = EditText(this).apply { hint = "Escribe una nota o diálogo" }
        AlertDialog.Builder(this).setTitle("Añadir anotación").setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Añadir") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) drawing.addText(value)
            }.show()
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
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                data?.let { resultIntent ->
                    if (resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                audioUri = uri
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@MainActivity, uri)
                    setOnPreparedListener { player ->
                        audioDurationMs = player.duration
                        updateTimeline()
                        setStatus("Audio cargado · ${formatTime(audioDurationMs)}")
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
                setStatus("No se pudo abrir el audio: ${e.message}")
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

    private fun toggleAudio() {
        val player = mediaPlayer
        if (player == null) {
            setStatus("Primero importa un archivo de audio")
            return
        }
        if (player.isPlaying) {
            player.pause()
            handler.removeCallbacks(playbackUpdater)
        } else {
            if (player.currentPosition >= player.duration) player.seekTo(0)
            player.start()
            handler.post(playbackUpdater)
        }
    }

    private fun saveProject() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/$projectName")
            dir.mkdirs()
            drawing.exportAll(dir, frameDurations.size)
            val metadata = buildString {
                appendLine("Storyboard Studio 0.2.0")
                appendLine("Proyecto: $projectName")
                appendLine("Formato: 16:9")
                appendLine("Planos: ${frameDurations.size}")
                frameDurations.forEachIndexed { index, duration ->
                    appendLine("Plano ${index + 1}: ${duration}ms")
                }
                appendLine("Audio URI: ${audioUri ?: "sin audio"}")
            }
            File(dir, "project.txt").writeText(metadata)
            setStatus("Proyecto guardado")
            Toast.makeText(this, "Proyecto guardado en el almacenamiento de la aplicación", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("Error al guardar: ${e.message}")
        }
    }

    private fun exportPdf() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/Exports")
            dir.mkdirs()
            val file = File(dir, "storyboard.pdf")
            val pdf = PdfDocument()
            for (i in frameDurations.indices) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1920, 1080, i + 1).create())
                drawing.drawFrameToCanvas(i, page.canvas)
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()
            Toast.makeText(this, "PDF exportado: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            setStatus("No se pudo exportar PDF: ${e.message}")
        }
    }

    private fun exportCurrentPng() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/Exports")
            dir.mkdirs()
            val file = File(dir, "plano_${currentFrame + 1}.png")
            drawing.exportCurrent(file)
            Toast.makeText(this, "PNG guardado: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            setStatus("No se pudo exportar PNG")
        }
    }

    private fun formatTime(ms: Int): String {
        val seconds = ms / 1000f
        return String.format(java.util.Locale.US, "%.2f s", seconds)
    }

    override fun onDestroy() {
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
    private val rulerHeight = 23f * density
    private val cardsTop = 29f * density
    private val cardHeight = 48f * density
    private val audioTop = 84f * density
    private val audioHeight = 29f * density
    private val handleWidth = 13f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        val desiredHeight = (126f * density).toInt()
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
        paint.textSize = 9f * density
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
            paint.textSize = 10f * density
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("PLANO ${index + 1}", x + 7f * density, cardsTop + 17f * density, paint)
            paint.textSize = 9f * density
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
            paint.textSize = 9f * density
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
    private val frames = mutableMapOf<Int, MutableList<Stroke>>()
    private val notes = mutableMapOf<Int, MutableList<String>>()
    private var frame = 0
    private var current: Stroke? = null
    private var color = Color.BLACK
    private var brush = 4f
    private var eraser = false
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
    fun setEraser(value: Boolean) { eraser = value }
    fun setColor(value: Int) { color = value; eraser = false }
    fun changeBrush(delta: Float) { brush = (brush + delta).coerceIn(1f, 40f) }

    fun undo() {
        val list = strokes()
        if (list.isNotEmpty()) {
            list.removeAt(list.lastIndex)
            invalidate()
        }
    }

    fun clear() {
        strokes().clear()
        notes[frame]?.clear()
        invalidate()
    }

    fun addText(value: String) {
        notes.getOrPut(frame) { mutableListOf() }.add(value)
        invalidate()
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
        val scale = min(targetW / 16f, targetH / 9f)
        val w = 16f * scale
        val h = 9f * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale / 120f, scale / 120f)
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
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = if (stroke.erase) Color.WHITE else stroke.color
        }
        for (i in 1 until stroke.points.size) {
            p.strokeWidth = (stroke.widths.getOrElse(i) { 4f }) * 2f
            canvas.drawLine(
                stroke.points[i - 1].x, stroke.points[i - 1].y,
                stroke.points[i].x, stroke.points[i].y, p
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val tool = event.getToolType(index)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (!stylus && event.pointerCount > 1) return true

        val scale = min((width * 0.94f) / 16f, (height * 0.90f) / 9f)
        val w = 16f * scale
        val h = 9f * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) {
                    return true
                }
                val x = ((event.x - left) / scale) * 120f
                val y = ((event.y - top) / scale) * 120f
                if (x !in 0f..1920f || y !in 0f..1080f) return true
                val eraseStroke = eraser || tool == MotionEvent.TOOL_TYPE_ERASER
                current = Stroke(mutableListOf(PointF(x, y)), mutableListOf(brush), color, eraseStroke)
                strokes().add(current!!)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val stroke = current ?: return true
                for (pointer in 0 until event.pointerCount) {
                    val pointerTool = event.getToolType(pointer)
                    if (pointerTool != MotionEvent.TOOL_TYPE_STYLUS && pointerTool != MotionEvent.TOOL_TYPE_ERASER) continue
                    val pressure = event.getPressure(pointer).coerceIn(0.1f, 1f)
                    val x = ((event.getX(pointer) - left) / scale) * 120f
                    val y = ((event.getY(pointer) - top) / scale) * 120f
                    if (x in 0f..1920f && y in 0f..1080f) {
                        stroke.points.add(PointF(x, y))
                        stroke.widths.add(brush * (0.35f + pressure))
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
                return true
            }
        }
        return true
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

    fun exportCurrent(file: File) {
        val bitmap = renderFrame(frame)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    fun exportAll(dir: File, count: Int) {
        for (i in 0 until count) {
            val bitmap = renderFrame(i)
            FileOutputStream(File(dir, "plano_${i + 1}.png")).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
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
