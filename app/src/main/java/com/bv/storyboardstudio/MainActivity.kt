package com.bv.storyboardstudio

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.graphics.drawable.GradientDrawable
import java.io.*
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.*

class MainActivity : Activity() {
    companion object {
        private const val PAGE_W = 1920
        private const val PAGE_H = 1080
        private const val REQ_SAVE = 100
        private const val REQ_OPEN = 101
        private const val REQ_IMAGE = 102
        private const val REQ_PNG = 103
        private const val REQ_PDF = 104
    }

    private lateinit var board: DrawingBoard
    private lateinit var status: TextView
    private lateinit var frameStrip: LinearLayout
    private val frames = mutableListOf<Bitmap>()
    private val undoStacks = mutableListOf<ArrayDeque<Bitmap>>()
    private val redoStacks = mutableListOf<ArrayDeque<Bitmap>>()
    private var currentFrame = 0
    private var tool = DrawingBoard.Tool.PEN
    private var brushColor = Color.BLACK
    private var brushSize = 6f
    private val colors = intArrayOf(
        Color.BLACK, Color.rgb(220, 40, 40), Color.rgb(35, 90, 220),
        Color.rgb(20, 145, 75), Color.rgb(245, 190, 25), Color.rgb(130, 65, 180), Color.WHITE
    )
    private var colorIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addFrameData(Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888))
        board = DrawingBoard(this).apply {
            onEditStart = { pushUndo() }
            onSelectionChanged = { updateStatus() }
            setBitmap(frames[0])
            setBrush(brushSize, brushColor)
            setTool(tool)
        }
        buildInterface()
    }

    private fun addFrameData(bitmap: Bitmap) {
        frames.add(bitmap)
        undoStacks.add(ArrayDeque())
        redoStacks.add(ArrayDeque())
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(27, 29, 33))
        }
        val toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.rgb(40, 43, 49))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(5, 3, 5, 3)
        }
        fun action(label: String, callback: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(10, 2, 10, 2)
            setOnClickListener { callback() }
        }
        toolbar.addView(action("Lápiz") { selectTool(DrawingBoard.Tool.PEN) })
        toolbar.addView(action("Goma") { selectTool(DrawingBoard.Tool.ERASER) })
        toolbar.addView(action("Lazo") { selectTool(DrawingBoard.Tool.LASSO) })
        toolbar.addView(action("Aplicar selección") { board.applySelection(); updateStatus() })
        toolbar.addView(action("Deshacer") { undo() })
        toolbar.addView(action("Rehacer") { redo() })
        toolbar.addView(action("Importar imagen") { chooseImage() })
        toolbar.addView(action("Guardar sesión") { createFile("Storyboard.story", "application/zip", REQ_SAVE) })
        toolbar.addView(action("Abrir sesión") { openFile("application/zip", REQ_OPEN) })
        toolbar.addView(action("Exportar PNG") { createFile("Storyboard.png", "image/png", REQ_PNG) })
        toolbar.addView(action("Exportar PDF") { createFile("Storyboard.pdf", "application/pdf", REQ_PDF) })
        toolbarScroll.addView(toolbar)
        root.addView(toolbarScroll, LinearLayout.LayoutParams.MATCH_PARENT, dp(52))

        val work = FrameLayout(this).apply { setBackgroundColor(Color.rgb(32, 34, 39)) }
        work.addView(board, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(work, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(4))
            setBackgroundColor(Color.rgb(40, 43, 49))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(action("−") { setBrushSize(brushSize - 2f) })
        controls.addView(action("+") { setBrushSize(brushSize + 2f) })
        controls.addView(action("Color") { nextColor() })
        controls.addView(action("Nueva viñeta") { addFrame() })
        controls.addView(action("Eliminar viñeta") { removeFrame() })
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(10), 0, dp(8), 0)
        }
        controls.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(controls)
        frameStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(frameStrip)
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        root.addView(footer, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        setContentView(root)
        refreshFrameStrip()
        updateStatus()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun selectTool(value: DrawingBoard.Tool) {
        tool = value
        board.setTool(value)
        updateStatus()
    }

    private fun setBrushSize(value: Float) {
        brushSize = value.coerceIn(1f, 200f)
        board.setBrush(brushSize, brushColor)
        updateStatus()
    }

    private fun nextColor() {
        colorIndex = (colorIndex + 1) % colors.size
        brushColor = colors[colorIndex]
        board.setBrush(brushSize, brushColor)
        updateStatus()
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val label = when (tool) {
            DrawingBoard.Tool.PEN -> "Lápiz"
            DrawingBoard.Tool.ERASER -> "Goma"
            DrawingBoard.Tool.LASSO -> "Lazo"
        }
        status.text = "$label  |  ${brushSize.toInt()} px  |  Viñeta ${currentFrame + 1}/${frames.size}"
    }

    private fun pushUndo() {
        val stack = undoStacks[currentFrame]
        stack.addLast(frames[currentFrame].copy(Bitmap.Config.ARGB_8888, true))
        while (stack.size > 10) stack.removeFirst().recycle()
        clearStack(redoStacks[currentFrame])
    }

    private fun clearStack(stack: ArrayDeque<Bitmap>) {
        while (stack.isNotEmpty()) {
            val bitmap = stack.removeLast()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun undo() {
        board.applySelection()
        val stack = undoStacks[currentFrame]
        if (stack.isEmpty()) return
        val redo = redoStacks[currentFrame]
        redo.addLast(frames[currentFrame].copy(Bitmap.Config.ARGB_8888, true))
        val previous = stack.removeLast()
        frames[currentFrame].recycle()
        frames[currentFrame] = previous
        board.setBitmap(previous)
        updateStatus()
    }

    private fun redo() {
        board.applySelection()
        val stack = redoStacks[currentFrame]
        if (stack.isEmpty()) return
        val undo = undoStacks[currentFrame]
        undo.addLast(frames[currentFrame].copy(Bitmap.Config.ARGB_8888, true))
        while (undo.size > 10) undo.removeFirst().recycle()
        val next = stack.removeLast()
        frames[currentFrame].recycle()
        frames[currentFrame] = next
        board.setBitmap(next)
        updateStatus()
    }

    private fun addFrame() {
        addFrameData(Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888))
        currentFrame = frames.lastIndex
        board.setBitmap(frames[currentFrame])
        refreshFrameStrip()
        updateStatus()
    }

    private fun removeFrame() {
        if (frames.size <= 1) return
        board.applySelection()
        frames.removeAt(currentFrame).recycle()
        clearStack(undoStacks.removeAt(currentFrame))
        clearStack(redoStacks.removeAt(currentFrame))
        currentFrame = currentFrame.coerceAtMost(frames.lastIndex)
        board.setBitmap(frames[currentFrame])
        refreshFrameStrip()
        updateStatus()
    }

    private fun selectFrame(index: Int) {
        if (index !in frames.indices) return
        board.applySelection()
        currentFrame = index
        board.setBitmap(frames[currentFrame])
        refreshFrameStrip()
        updateStatus()
    }

    private fun refreshFrameStrip() {
        if (!::frameStrip.isInitialized) return
        frameStrip.removeAllViews()
        frames.forEachIndexed { index, _ ->
            val button = Button(this).apply {
                text = "Viñeta ${index + 1}"
                isAllCaps = false
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                alpha = if (index == currentFrame) 1f else 0.58f
                setOnClickListener { selectFrame(index) }
            }
            frameStrip.addView(button, LinearLayout.LayoutParams(dp(112), dp(40)))
        }
    }

    private fun chooseImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_IMAGE)
    }

    private fun createFile(name: String, mime: String, request: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, request)
    }

    private fun openFile(mime: String, request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }
        startActivityForResult(intent, request)
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQ_IMAGE -> importImage(uri)
                REQ_SAVE -> saveSession(uri)
                REQ_OPEN -> loadSession(uri)
                REQ_PNG -> exportPng(uri)
                REQ_PDF -> exportPdf(uri)
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Operación fallida: ${error.message ?: "error de archivo"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importImage(uri: Uri) {
        val decoded = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IOException("No se pudo leer la imagen")
        pushUndo()
        val page = RectF(0f, 0f, PAGE_W.toFloat(), PAGE_H.toFloat())
        val ratio = min(page.width() / decoded.width, page.height() / decoded.height)
        val width = decoded.width * ratio
        val height = decoded.height * ratio
        val destination = RectF((PAGE_W - width) / 2f, (PAGE_H - height) / 2f,
            (PAGE_W + width) / 2f, (PAGE_H + height) / 2f)
        Canvas(frames[currentFrame]).drawBitmap(decoded, null, destination,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        board.invalidate()
        Toast.makeText(this, "Imagen importada", Toast.LENGTH_SHORT).show()
    }

    private fun saveSession(uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                frames.forEachIndexed { index, bitmap ->
                    zip.putNextEntry(ZipEntry("frame_${index.toString().padStart(4, '0')}.png"))
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)) {
                        throw IOException("No se pudo codificar la viñeta ${index + 1}")
                    }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("session.info"))
                zip.write("StoryboardStudio\n1\n$PAGE_W\n$PAGE_H\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } ?: throw IOException("No se pudo crear el archivo")
        Toast.makeText(this, "Sesión guardada", Toast.LENGTH_SHORT).show()
    }

    private fun loadSession(uri: Uri) {
        board.applySelection()
        val loaded = mutableListOf<Bitmap>()
        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.matches(Regex("frame_\\d{4}\\.png"))) {
                        val bitmap = BitmapFactory.decodeStream(zip)
                            ?: throw IOException("Viñeta dañada: ${entry.name}")
                        val normalized = if (bitmap.width == PAGE_W && bitmap.height == PAGE_H) bitmap
                        else Bitmap.createScaledBitmap(bitmap, PAGE_W, PAGE_H, true).also { bitmap.recycle() }
                        loaded.add(normalized)
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IOException("No se pudo abrir el archivo")
        if (loaded.isEmpty()) throw IOException("El archivo no contiene viñetas válidas")
        frames.forEach { if (!it.isRecycled) it.recycle() }
        undoStacks.forEach { clearStack(it) }
        redoStacks.forEach { clearStack(it) }
        frames.clear(); undoStacks.clear(); redoStacks.clear()
        loaded.forEach { addFrameData(it) }
        currentFrame = 0
        board.setBitmap(frames[0])
        refreshFrameStrip()
        updateStatus()
        Toast.makeText(this, "Sesión abierta", Toast.LENGTH_SHORT).show()
    }

    private fun exportPng(uri: Uri) {
        contentResolver.openOutputStream(uri)?.use { output ->
            val export = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
            Canvas(export).apply {
                drawColor(Color.WHITE)
                drawBitmap(frames[currentFrame], 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            export.compress(Bitmap.CompressFormat.PNG, 100, output)
            export.recycle()
        } ?: throw IOException("No se pudo crear el PNG")
        Toast.makeText(this, "PNG exportado", Toast.LENGTH_SHORT).show()
    }

    private fun exportPdf(uri: Uri) {
        val document = PdfDocument()
        try {
            frames.forEachIndexed { index, bitmap ->
                val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
                val page = document.startPage(info)
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                document.finishPage(page)
            }
            contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
                ?: throw IOException("No se pudo crear el PDF")
        } finally {
            document.close()
        }
        Toast.makeText(this, "PDF exportado", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (::board.isInitialized) board.release()
        frames.forEach { if (!it.isRecycled) it.recycle() }
        undoStacks.forEach { clearStack(it) }
        redoStacks.forEach { clearStack(it) }
        super.onDestroy()
    }
}

private class DrawingBoard(context: android.content.Context) : View(context) {
    enum class Tool { PEN, ERASER, LASSO }

    companion object {
        private const val PAGE_W = 1920f
        private const val PAGE_H = 1080f
        private const val MIN_PRESSURE = 0.22f
        private const val MAX_PRESSURE = 1.65f
    }

    var onEditStart: (() -> Unit)? = null
    var onSelectionChanged: (() -> Unit)? = null
    private var bitmap: Bitmap? = null
    private var tool = Tool.PEN
    private var brushSize = 6f
    private var brushColor = Color.BLACK
    private var pageRect = RectF()
    private var viewScale = 1f
    private var pageLeft = 0f
    private var pageTop = 0f

    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 1f
    private var lassoPath: Path? = null
    private val lassoPoints = ArrayList<PointF>()

    private var selectedBitmap: Bitmap? = null
    private var selectedRect: RectF? = null
    private var movingSelection = false
    private var resizingSelection = false
    private var selectionDownX = 0f
    private var selectionDownY = 0f
    private var selectionStartRect: RectF? = null

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 115, 235)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 120, 245)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    init { isFocusable = true }

    fun setBitmap(value: Bitmap) {
        applySelection()
        bitmap = value
        invalidate()
    }

    fun setTool(value: Tool) {
        if (tool != value) applySelection()
        tool = value
        invalidate()
    }

    fun setBrush(size: Float, color: Int) {
        brushSize = size.coerceIn(1f, 200f)
        brushColor = color
    }

    fun applySelection() {
        val selected = selectedBitmap ?: return
        val rect = selectedRect ?: return
        val target = bitmap
        if (target != null && !selected.isRecycled) {
            Canvas(target).drawBitmap(selected, null, rect, bitmapPaint)
        }
        selected.recycle()
        selectedBitmap = null
        selectedRect = null
        movingSelection = false
        resizingSelection = false
        invalidate()
        onSelectionChanged?.invoke()
    }

    fun release() {
        applySelection()
        bitmap = null
        lassoPath = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(32, 34, 39))
        val target = bitmap ?: return
        viewScale = min(width / PAGE_W, height / PAGE_H).coerceAtLeast(0.0001f)
        val pageWidth = PAGE_W * viewScale
        val pageHeight = PAGE_H * viewScale
        pageLeft = (width - pageWidth) / 2f
        pageTop = (height - pageHeight) / 2f
        pageRect.set(pageLeft, pageTop, pageLeft + pageWidth, pageTop + pageHeight)
        canvas.drawRect(pageRect, pagePaint.apply { color = Color.WHITE })
        canvas.save()
        canvas.clipRect(pageRect)
        canvas.translate(pageLeft, pageTop)
        canvas.scale(viewScale, viewScale)
        canvas.drawBitmap(target, 0f, 0f, bitmapPaint)
        selectedBitmap?.let { selection ->
            selectedRect?.let { rect ->
                canvas.drawBitmap(selection, null, rect, bitmapPaint)
                canvas.drawRect(rect, selectionPaint)
                val handle = 13f
                canvas.drawCircle(rect.right, rect.bottom, handle, handlePaint)
                canvas.drawCircle(rect.right, rect.bottom, handle, selectionPaint)
            }
        }
        lassoPath?.let { canvas.drawPath(it, lassoPaint) }
        canvas.restore()
    }

    private fun pagePoint(viewX: Float, viewY: Float): PointF? {
        if (viewScale <= 0f || !pageRect.contains(viewX, viewY)) return null
        return PointF((viewX - pageLeft) / viewScale, (viewY - pageTop) / viewScale)
    }

    private fun eventPressure(event: MotionEvent, index: Int): Float {
        val toolType = event.getToolType(index)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return 1f
        val pressure = event.getPressure(index)
        return if (pressure.isFinite() && pressure > 0f) pressure.coerceIn(MIN_PRESSURE, MAX_PRESSURE) else 1f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = bitmap ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = pagePoint(event.x, event.y) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (selectedBitmap != null && tool == Tool.LASSO) {
                    val rect = selectedRect!!
                    selectionDownX = point.x
                    selectionDownY = point.y
                    selectionStartRect = RectF(rect)
                    val handleHit = hypot(point.x - rect.right, point.y - rect.bottom) <= 34f
                    if (handleHit) {
                        resizingSelection = true
                        movingSelection = false
                    } else if (rect.contains(point.x, point.y)) {
                        movingSelection = true
                        resizingSelection = false
                    } else {
                        applySelection()
                        beginLasso(point)
                    }
                } else if (tool == Tool.LASSO) {
                    beginLasso(point)
                } else {
                    onEditStart?.invoke()
                    drawing = true
                    lastX = point.x
                    lastY = point.y
                    lastPressure = eventPressure(event, 0)
                    val paint = strokePaint(tool, lastPressure)
                    if (tool == Tool.PEN) {
                        dotPaint.color = brushColor
                        dotPaint.style = Paint.Style.FILL
                        dotPaint.xfermode = null
                        Canvas(target).drawCircle(lastX, lastY, paint.strokeWidth / 2f, dotPaint)
                    } else {
                        Canvas(target).drawCircle(lastX, lastY, paint.strokeWidth / 2f, clearPaint)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (movingSelection || resizingSelection) {
                    val point = pagePoint(event.x, event.y) ?: return true
                    val start = selectionStartRect ?: return true
                    val dx = point.x - selectionDownX
                    val dy = point.y - selectionDownY
                    val rect = selectedRect ?: return true
                    if (movingSelection) {
                        rect.set(start.left + dx, start.top + dy, start.right + dx, start.bottom + dy)
                        rect.offset(-min(0f, rect.left), -min(0f, rect.top))
                        if (rect.right > PAGE_W) rect.offset(PAGE_W - rect.right, 0f)
                        if (rect.bottom > PAGE_H) rect.offset(0f, PAGE_H - rect.bottom)
                    } else {
                        rect.right = (start.right + dx).coerceIn(start.left + 12f, PAGE_W)
                        rect.bottom = (start.bottom + dy).coerceIn(start.top + 12f, PAGE_H)
                    }
                    invalidate()
                    return true
                }
                if (tool == Tool.LASSO) {
                    val point = pagePoint(event.x, event.y) ?: return true
                    appendLasso(point)
                    invalidate()
                    return true
                }
                if (!drawing) return false
                processStrokeSamples(event, target)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (movingSelection || resizingSelection) {
                    movingSelection = false
                    resizingSelection = false
                    selectionStartRect = null
                    invalidate()
                    return true
                }
                if (tool == Tool.LASSO) {
                    finishLasso(target)
                    invalidate()
                    return true
                }
                if (drawing) {
                    pagePoint(event.x, event.y)?.let { point -> drawSample(target, point.x, point.y, eventPressure(event, 0)) }
                    drawing = false
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                movingSelection = false
                resizingSelection = false
                lassoPath = null
                lassoPoints.clear()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun beginLasso(point: PointF) {
        applySelection()
        onEditStart?.invoke()
        lassoPoints.clear()
        lassoPoints.add(point)
        lassoPath = Path().apply { moveTo(point.x, point.y) }
    }

    private fun appendLasso(point: PointF) {
        val last = lassoPoints.lastOrNull() ?: return
        if (hypot(point.x - last.x, point.y - last.y) < 2f) return
        lassoPoints.add(point)
        lassoPath?.lineTo(point.x, point.y)
    }

    private fun finishLasso(target: Bitmap) {
        val path = lassoPath ?: return
        lassoPath = null
        if (lassoPoints.size < 3) {
            lassoPoints.clear()
            return
        }
        path.close()
        val bounds = RectF()
        path.computeBounds(bounds, true)
        val left = floor(bounds.left).toInt().coerceIn(0, target.width - 1)
        val top = floor(bounds.top).toInt().coerceIn(0, target.height - 1)
        val right = ceil(bounds.right).toInt().coerceIn(left + 1, target.width)
        val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, target.height)
        val crop = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val cropCanvas = Canvas(crop)
        cropCanvas.save()
        cropCanvas.translate(-left.toFloat(), -top.toFloat())
        cropCanvas.clipPath(path)
        cropCanvas.drawBitmap(target, 0f, 0f, bitmapPaint)
        cropCanvas.restore()
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        Canvas(target).drawPath(path, clear)
        selectedBitmap?.recycle()
        selectedBitmap = crop
        selectedRect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        lassoPoints.clear()
        onSelectionChanged?.invoke()
    }

    private fun processStrokeSamples(event: MotionEvent, target: Bitmap) {
        val pointerIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(pointerIndex)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            val historyCount = event.historySize
            for (h in 0 until historyCount) {
                val point = pagePoint(event.getHistoricalX(pointerIndex, h), event.getHistoricalY(pointerIndex, h)) ?: continue
                val pressure = event.getHistoricalPressure(pointerIndex, h).takeIf { it > 0f }?.coerceIn(MIN_PRESSURE, MAX_PRESSURE) ?: 1f
                drawSample(target, point.x, point.y, pressure)
            }
        }
        val point = pagePoint(event.getX(pointerIndex), event.getY(pointerIndex)) ?: return
        drawSample(target, point.x, point.y, eventPressure(event, pointerIndex))
    }

    private fun strokePaint(activeTool: Tool, pressure: Float): Paint {
        val width = brushSize * pressure
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            color = if (activeTool == Tool.ERASER) Color.TRANSPARENT else brushColor
            xfermode = if (activeTool == Tool.ERASER) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        }
    }

    private fun drawSample(target: Bitmap, x: Float, y: Float, pressure: Float) {
        val safePressure = pressure.coerceIn(MIN_PRESSURE, MAX_PRESSURE)
        val width = brushSize * ((lastPressure + safePressure) * 0.5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            color = brushColor
            xfermode = if (tool == Tool.ERASER) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        }
        Canvas(target).drawLine(lastX, lastY, x, y, paint)
        lastX = x
        lastY = y
        lastPressure = safePressure
    }
}
