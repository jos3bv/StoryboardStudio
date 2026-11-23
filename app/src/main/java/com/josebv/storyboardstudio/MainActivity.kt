package com.josebv.storyboardstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.pdf.PdfDocument
import android.graphics.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var drawing: DrawingView
    private lateinit var timeline: LinearLayout
    private lateinit var status: TextView
    private var mediaPlayer: MediaPlayer? = null
    private var audioUri: Uri? = null
    private var frameCount = 1
    private var currentFrame = 0
    private var eraser = false
    private var projectName = "Mi storyboard"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildInterface()
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(29, 33, 39)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(12, 7, 12, 7); setBackgroundColor(Color.rgb(23, 26, 31)) }
        val title = TextView(this).apply { text = "STORYBOARD STUDIO"; setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD }
        top.addView(title, LinearLayout.LayoutParams(0, 48, 1f))
        addButton(top, "Lápiz") { eraser = false; drawing.setEraser(false); setStatus("Lápiz activo · usa el S Pen") }
        addButton(top, "Borrador") { eraser = true; drawing.setEraser(true); setStatus("Borrador activo") }
        addButton(top, "Deshacer") { drawing.undo(); setStatus("Último trazo deshecho") }
        addButton(top, "Limpiar") { confirmClear() }
        addButton(top, "Texto") { addTextNote() }
        addButton(top, "Audio MP3") { chooseAudio() }
        addButton(top, "Guardar") { saveProject() }
        root.addView(top)

        val work = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(10, 8, 10, 8) }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 4, 8, 4) }
        addToolLabel(tools, "HERRAMIENTAS")
        addButtonVertical(tools, "Pincel −") { drawing.changeBrush(-2f) }
        addButtonVertical(tools, "Pincel +") { drawing.changeBrush(2f) }
        addButtonVertical(tools, "Negro") { drawing.setColor(Color.BLACK) }
        addButtonVertical(tools, "Rojo") { drawing.setColor(Color.rgb(190, 55, 55)) }
        addButtonVertical(tools, "Azul") { drawing.setColor(Color.rgb(55, 95, 170)) }
        addButtonVertical(tools, "Nota") { addTextNote() }
        work.addView(tools, LinearLayout.LayoutParams(104, -1))

        drawing = DrawingView(this)
        work.addView(drawing, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(4, 0, 4, 0) })

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 8, 4, 4); setBackgroundColor(Color.rgb(37, 42, 49)) }
        addToolLabel(side, "PLANO")
        status = TextView(this).apply { text = "Plano 01"; setTextColor(Color.WHITE); textSize = 16f; setPadding(4, 12, 4, 12) }
        side.addView(status)
        addButtonVertical(side, "＋ Nuevo plano") { addFrame() }
        addButtonVertical(side, "Duplicar") { duplicateFrame() }
        addButtonVertical(side, "Exportar PNG") { exportCurrentPng() }
        addButtonVertical(side, "Exportar PDF") { exportPdf() }
        addToolLabel(side, "CAPAS")
        val layerInfo = TextView(this).apply { text = "• Dibujo\n• Anotaciones\n\nCapas independientes: próxima etapa"; setTextColor(Color.LTGRAY); textSize = 12f; setPadding(4, 8, 4, 8) }
        side.addView(layerInfo)
        work.addView(side, LinearLayout.LayoutParams(148, -1))
        root.addView(work, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 5, 10, 8); setBackgroundColor(Color.rgb(23, 26, 31)) }
        val timelineHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addToolLabel(timelineHead, "STORYBOARD / TIMELINE")
        val spacer = Space(this); timelineHead.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        addButton(timelineHead, "▶ / ❚❚ Audio") { toggleAudio() }
        addButton(timelineHead, "Importar audio") { chooseAudio() }
        bottom.addView(timelineHead)
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        timeline = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(2, 3, 2, 3) }
        scroll.addView(timeline)
        bottom.addView(scroll, LinearLayout.LayoutParams(-1, 66))
        root.addView(bottom)
        setContentView(root)
        refreshTimeline()
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        val b = Button(this).apply { text = label; textSize = 10f; isAllCaps = false; setOnClickListener { action() }; minWidth = 0; minimumWidth = 0; setPadding(7, 0, 7, 0) }
        parent.addView(b, LinearLayout.LayoutParams(-2, 44).apply { setMargins(2, 0, 2, 0) })
    }
    private fun addButtonVertical(parent: LinearLayout, label: String, action: () -> Unit) {
        val b = Button(this).apply { text = label; textSize = 11f; isAllCaps = false; setOnClickListener { action() }; setPadding(2, 0, 2, 0) }
        parent.addView(b, LinearLayout.LayoutParams(-1, 42).apply { setMargins(0, 2, 0, 2) })
    }
    private fun addToolLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply { this.text = text; setTextColor(Color.rgb(160, 177, 197)); textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setPadding(4, 5, 4, 5) })
    }
    private fun setStatus(text: String) { status.text = text }

    private fun addFrame() {
        frameCount++; currentFrame = frameCount - 1; drawing.setFrame(currentFrame); refreshTimeline(); setStatus("Plano ${currentFrame + 1}")
    }
    private fun duplicateFrame() {
        drawing.duplicateFrame(currentFrame, frameCount); currentFrame = frameCount; frameCount++; drawing.setFrame(currentFrame); refreshTimeline(); setStatus("Plano ${currentFrame + 1} · duplicado")
    }
    private fun selectFrame(index: Int) { currentFrame = index; drawing.setFrame(index); refreshTimeline(); setStatus("Plano ${index + 1}") }
    private fun refreshTimeline() {
        if (!::timeline.isInitialized) return
        timeline.removeAllViews()
        for (i in 0 until frameCount) {
            val b = Button(this).apply {
                text = "${if (i == currentFrame) "● " else ""}PLANO ${i + 1}\n3.0 s"
                textSize = 10f; isAllCaps = false
                setBackgroundColor(if (i == currentFrame) Color.rgb(74, 99, 128) else Color.rgb(54, 61, 70))
                setTextColor(Color.WHITE); setOnClickListener { selectFrame(i) }
            }
            timeline.addView(b, LinearLayout.LayoutParams(112, 54).apply { setMargins(3, 2, 3, 2) })
        }
    }
    private fun confirmClear() { AlertDialog.Builder(this).setTitle("Limpiar plano").setMessage("¿Borrar todos los trazos del plano actual?").setNegativeButton("Cancelar", null).setPositiveButton("Borrar") { _, _ -> drawing.clear(); }.show() }
    private fun addTextNote() {
        val input = EditText(this).apply { hint = "Escribe una nota o diálogo" }
        AlertDialog.Builder(this).setTitle("Añadir anotación").setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Añadir") { _, _ ->
            val value = input.text.toString().trim(); if (value.isNotEmpty()) drawing.addText(value)
        }.show()
    }
    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*" }
        startActivityForResult(intent, 101)
    }
    @Deprecated("Legacy callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            audioUri = data?.data
            try { mediaPlayer?.release(); mediaPlayer = MediaPlayer().apply { setDataSource(this@MainActivity, audioUri!!); prepare() }; setStatus("Audio cargado") }
            catch (e: Exception) { setStatus("No se pudo abrir el audio") }
        }
    }
    private fun toggleAudio() {
        val player = mediaPlayer
        if (player == null) { setStatus("Primero importa un MP3"); return }
        if (player.isPlaying) player.pause() else player.start()
    }
    private fun saveProject() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/$projectName"); dir.mkdirs()
            drawing.exportAll(dir, frameCount)
            File(dir, "project.txt").writeText("Storyboard Studio\nProyecto: $projectName\nPlanos: $frameCount\nFormato de trabajo: 16:9\nAudio URI: ${audioUri ?: "sin audio"}\n")
            setStatus("Guardado en ${dir.name}")
            Toast.makeText(this, "Proyecto guardado en el almacenamiento de la aplicación", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { setStatus("Error al guardar: ${e.message}") }
    }
    private fun exportPdf() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/Exports"); dir.mkdirs()
            val file = File(dir, "storyboard.pdf")
            val pdf = PdfDocument()
            for (i in 0 until frameCount) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(1920, 1080, i + 1).create())
                drawing.drawFrameToCanvas(i, page.canvas)
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }; pdf.close()
            Toast.makeText(this, "PDF exportado: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { setStatus("No se pudo exportar PDF: ${e.message}") }
    }
    private fun exportCurrentPng() {
        try {
            val dir = File(getExternalFilesDir(null), "StoryboardStudio/Exports"); dir.mkdirs()
            val file = File(dir, "plano_${currentFrame + 1}.png"); drawing.exportCurrent(file)
            Toast.makeText(this, "PNG guardado: ${file.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { setStatus("No se pudo exportar PNG") }
    }
    override fun onDestroy() { mediaPlayer?.release(); mediaPlayer = null; super.onDestroy() }
}

private data class Stroke(val points: MutableList<PointF>, val widths: MutableList<Float>, val color: Int, val erase: Boolean = false)

class DrawingView(context: android.content.Context) : View(context) {
    private val frames = mutableMapOf<Int, MutableList<Stroke>>()
    private val notes = mutableMapOf<Int, MutableList<String>>()
    private var frame = 0
    private var current: Stroke? = null
    private var color = Color.BLACK
    private var brush = 4f
    private var eraser = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 28f; style = Paint.Style.FILL }
    private val undo = mutableMapOf<Int, MutableList<Stroke>>()

    init { setLayerType(View.LAYER_TYPE_SOFTWARE, null); isFocusable = true }
    private fun strokes() = frames.getOrPut(frame) { mutableListOf() }
    fun setFrame(index: Int) { frame = index; invalidate() }
    fun setEraser(value: Boolean) { eraser = value }
    fun setColor(value: Int) { color = value; eraser = false }
    fun changeBrush(delta: Float) { brush = (brush + delta).coerceIn(1f, 40f) }
    fun undo() { val list = strokes(); if (list.isNotEmpty()) { undo.getOrPut(frame) { mutableListOf() }.add(list.removeAt(list.lastIndex)); invalidate() } }
    fun clear() { strokes().clear(); notes[frame]?.clear(); invalidate() }
    fun addText(value: String) { notes.getOrPut(frame) { mutableListOf() }.add(value); invalidate() }
    fun duplicateFrame(source: Int, target: Int) { frames[source]?.let { frames[target] = it.map { s -> Stroke(s.points.map { p -> PointF(p.x, p.y) }.toMutableList(), s.widths.toMutableList(), s.color, s.erase) }.toMutableList() }; notes[source]?.let { notes[target] = it.toMutableList() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(45, 50, 57))
        val targetW = width * 0.94f; val targetH = height * 0.90f
        val scale = min(targetW / 16f, targetH / 9f)
        val w = 16f * scale; val h = 9f * scale
        val left = (width - w) / 2f; val top = (height - h) / 2f
        canvas.save(); canvas.translate(left, top); canvas.scale(scale / 120f, scale / 120f)
        val bg = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, 1920f, 1080f, bg)
        for (stroke in frames[frame].orEmpty()) drawStroke(canvas, stroke)
        var y = 1030f
        for (note in notes[frame].orEmpty()) { textPaint.textSize = 34f; canvas.drawText(note, 24f, y, textPaint); y -= 42f }
        canvas.restore()
        val border = Paint().apply { color = Color.rgb(130, 145, 160); style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect(left, top, left + w, top + h, border)
    }
    private fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = if (stroke.erase) Color.WHITE else stroke.color; strokeWidth = (stroke.widths.firstOrNull() ?: 4f) * 2f }
        for (i in 1 until stroke.points.size) {
            p.strokeWidth = (stroke.widths.getOrElse(i) { 4f }) * 2f
            canvas.drawLine(stroke.points[i - 1].x, stroke.points[i - 1].y, stroke.points[i].x, stroke.points[i].y, p)
        }
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val stylus = event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER
        if (!stylus && event.pointerCount > 1) return true
        val scale = min((width * 0.94f) / 16f, (height * 0.90f) / 9f)
        val w = 16f * scale; val h = 9f * scale; val left = (width - w) / 2f; val top = (height - h) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val index = if (event.actionMasked == MotionEvent.ACTION_UP) 0 else event.actionIndex
                val x = ((event.getX(index) - left) / scale) * 1920f / 16f
                val y = ((event.getY(index) - top) / scale) * 1080f / 9f
                if (x !in 0f..1920f || y !in 0f..1080f) { if (event.actionMasked == MotionEvent.ACTION_UP) current = null; return true }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { current = Stroke(mutableListOf(PointF(x, y)), mutableListOf(brush), color, eraser || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER); strokes().add(current!!) }
                    MotionEvent.ACTION_MOVE -> {
                        val s = current ?: return true
                        for (i in 0 until event.historySize) { s.points.add(PointF((event.getHistoricalX(index, i) - left) / scale * 120f, (event.getHistoricalY(index, i) - top) / scale * 120f)); s.widths.add(brush * (0.35f + event.getHistoricalPressure(index, i).coerceIn(0.1f, 1f))) }
                        val pressure = if (stylus) event.getPressure(index).coerceIn(0.1f, 1f) else 0.7f
                        s.points.add(PointF(x, y)); s.widths.add(brush * (0.35f + pressure))
                    }
                    MotionEvent.ACTION_UP -> { current = null }
                }
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { current = null; return true }
        }
        return true
    }
    fun drawFrameToCanvas(index: Int, canvas: Canvas) { canvas.drawColor(Color.WHITE); frames[index].orEmpty().forEach { drawStroke(canvas, it) }; var y = 1030f; notes[index].orEmpty().forEach { textPaint.textSize = 34f; canvas.drawText(it, 24f, y, textPaint); y -= 42f } }
    fun exportCurrent(file: File) { val bmp = renderFrame(frame); FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
    fun exportAll(dir: File, count: Int) { for (i in 0 until count) { val bmp = renderFrame(i); FileOutputStream(File(dir, "plano_${i + 1}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() } }
    private fun renderFrame(index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); c.drawColor(Color.WHITE)
        frames[index].orEmpty().forEach { drawStroke(c, it) }
        var y = 1030f; notes[index].orEmpty().forEach { textPaint.textSize = 34f; c.drawText(it, 24f, y, textPaint); y -= 42f }
        return bmp
    }
}
