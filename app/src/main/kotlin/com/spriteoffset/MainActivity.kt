package com.spriteoffset

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var preview: SpritePreviewView
    private lateinit var imageInfo: TextView
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private var offsetX = 0
    private var offsetY = 0
    private var updatingFields = false
    private var currentBitmap: Bitmap? = null
    private var currentDoomPatch: ByteArray? = null
    private var currentDisplayName = "sprite"
    private var pendingPatchBytes: ByteArray? = null
    private var pendingPngBitmap: Bitmap? = null
    private lateinit var savePatchButton: Button
    private lateinit var paletteSpinner: Spinner
    private lateinit var paletteInfo: TextView
    private var playpalPalettes: List<IntArray> = emptyList()
    private var selectedPaletteIndex = -1
    private var playpalSourceName = "PLAYPAL"
    private var currentDoomPatchName = "sprite"
    private var updatingPaletteSpinner = false

    companion object {
        private const val REQUEST_IMAGE = 41
        private const val REQUEST_SAVE_PATCH = 42
        private const val REQUEST_EXPORT_PNG = 43
        private const val REQUEST_PLAYPAL = 44
        private const val MAX_OFFSET = Int.MAX_VALUE
        private const val MIN_OFFSET = Int.MIN_VALUE
        private const val MAX_INPUT_BYTES = 64 * 1024 * 1024
        private const val MAX_IMAGE_PIXELS = 32_000_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 24, 39)
        window.navigationBarColor = Color.rgb(17, 24, 39)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(17, 24, 39))
            setFillViewport(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(TextView(this).apply {
            text = "Sprite Offset"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Visor y editor de offsets para sprites"
            textSize = 13f
            setTextColor(Color.rgb(166, 177, 196))
            setPadding(0, dp(3), 0, dp(14))
        })

        val openButton = makeButton("Abrir imagen")
        content.addView(openButton, matchWrap())
        openButton.setOnClickListener { openImagePicker() }

        imageInfo = TextView(this).apply {
            text = "Ninguna imagen seleccionada"
            textSize = 12f
            setTextColor(Color.rgb(180, 192, 210))
            setPadding(0, dp(9), 0, dp(9))
        }
        content.addView(imageInfo)

        content.addView(sectionLabel("Paleta de colores"))
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val openPaletteButton = makeButton("Abrir PLAYPAL")
        paletteRow.addView(openPaletteButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
        ))
        openPaletteButton.setOnClickListener { openPlaypalPicker() }

        paletteSpinner = Spinner(this)
        paletteSpinner.adapter = paletteAdapter(listOf("Escala de grises"))
        paletteRow.addView(paletteSpinner, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(8)
        })
        content.addView(paletteRow)

        paletteInfo = TextView(this).apply {
            text = "Sin PLAYPAL cargado · vista en escala de grises"
            textSize = 12f
            setTextColor(Color.rgb(155, 169, 190))
            setPadding(0, dp(2), 0, dp(8))
        }
        content.addView(paletteInfo)

        paletteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingPaletteSpinner) return
                val nextIndex = if (position == 0) -1 else position - 1
                if (nextIndex != selectedPaletteIndex) {
                    selectedPaletteIndex = nextIndex
                    updatePaletteInfo()
                    if (currentDoomPatch != null) renderCurrentDoomPatch(resetOffsets = false)
                }
            }
        }

        val modeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        modeRow.addView(makeLabel("Vista"))
        val spinner = Spinner(this)
        val modes = listOf("Auto", "Graphic", "Sprite", "HUD")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        modeRow.addView(spinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(modeRow)

        preview = SpritePreviewView(this, scroll).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)
            ).apply { topMargin = dp(5); bottomMargin = dp(12) }
            onOffsetsChanged = { x, y ->
                applyPreviewOffsets(x, y)
            }
        }
        content.addView(preview)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                preview.previewMode = modes[position]
            }
        }

        content.addView(sectionLabel("Offsets"))
        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        offsetRow.addView(makeOffsetGroup("X", true), LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            marginEnd = dp(8)
        })
        offsetRow.addView(makeOffsetGroup("Y", false), LinearLayout.LayoutParams(0, dp(58), 1f))
        content.addView(offsetRow)

        content.addView(sectionLabel("Zoom"))
        val zoomLabel = TextView(this).apply {
            text = "100%"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val zoomSeek = SeekBar(this).apply {
            max = 375
            progress = 75
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val percent = progress + 25
                    preview.zoomPercent = percent
                    zoomLabel.text = "$percent%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(zoomSeek, LinearLayout.LayoutParams(0, dp(48), 1f))
        zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(zoomRow)

        content.addView(sectionLabel("Guardar y exportar"))
        savePatchButton = makeButton("Guardar offsets como Doom Patch")
        savePatchButton.isEnabled = false
        content.addView(savePatchButton, matchWrap())
        savePatchButton.setOnClickListener { saveDoomPatch() }

        val exportButton = makeButton("Exportar vista a PNG")
        content.addView(exportButton, matchWrap())
        exportButton.setOnClickListener { exportPng() }

        content.addView(TextView(this).apply {
            text = "Carga un lump PLAYPAL extraído o un archivo IWAD/PWAD que lo contenga. Puedes elegir cualquiera de sus paletas; los espacios transparentes del Doom Patch se conservan. El guardado del Patch no altera sus píxeles comprimidos y el PNG exportado conserva los offsets grAb."
            textSize = 12f
            setTextColor(Color.rgb(155, 169, 190))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun makeOffsetGroup(label: String, isX: Boolean): View {
        val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        group.addView(makeLabel(label))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(8), 0, dp(4), 0)
            setText(if (isX) offsetX.toString() else offsetY.toString())
        }
        if (isX) xInput = field else yInput = field
        row.addView(field, LinearLayout.LayoutParams(0, dp(46), 1f))
        val minus = makeButton("−").apply { textSize = 18f }
        val plus = makeButton("+").apply { textSize = 18f }
        row.addView(minus, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(plus, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })
        minus.setOnClickListener { setOffset(isX, (if (isX) offsetX else offsetY) - 1) }
        plus.setOnClickListener { setOffset(isX, (if (isX) offsetX else offsetY) + 1) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!updatingFields) setOffset(isX, s?.toString()?.toIntOrNull() ?: 0, false)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        group.addView(row)
        return group
    }

    private fun setOffset(isX: Boolean, value: Int, updateField: Boolean = true) {
        val safe = value.coerceIn(MIN_OFFSET, MAX_OFFSET)
        if (isX) offsetX = safe else offsetY = safe
        preview.offsetX = offsetX
        preview.offsetY = offsetY
        if (updateField) syncOffsetFields()
    }

    private fun applyPreviewOffsets(x: Int, y: Int) {
        offsetX = x
        offsetY = y
        preview.offsetX = x
        preview.offsetY = y
        syncOffsetFields()
    }

    private fun syncOffsetFields() {
        if (!::xInput.isInitialized || !::yInput.isInitialized) return
        updatingFields = true
        if (xInput.text.toString() != offsetX.toString()) xInput.setText(offsetX.toString())
        if (yInput.text.toString() != offsetY.toString()) yInput.setText(offsetY.toString())
        updatingFields = false
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Document picker callback kept compatible with Android 8")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMAGE -> openSelectedDocument(uri)
            REQUEST_PLAYPAL -> openPlaypalDocument(uri)
            REQUEST_SAVE_PATCH -> writePendingBytes(uri, pendingPatchBytes, "Doom Patch")
            REQUEST_EXPORT_PNG -> writePendingPng(uri)
        }
    }

    private fun readDocument(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_INPUT_BYTES) { "El archivo supera el límite de 64 MB" }
                output.write(buffer, 0, count)
            }
        } ?: throw IllegalArgumentException("No se pudo leer el archivo")
        return output.toByteArray()
    }

    private fun decodeRaster(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_IMAGE_PIXELS) {
            "La imagen es demasiado grande"
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun openSelectedDocument(uri: Uri) {
        try {
            val bytes = readDocument(uri)
            val name = queryDisplayName(uri) ?: "Imagen"
            val raster = decodeRaster(bytes)
            currentDisplayName = name.substringBeforeLast('.', name).ifBlank { "sprite" }

            if (raster != null) {
                currentBitmap = raster
                currentDoomPatch = null
                val pngOffsets = PngOffsetChunk.readOffsets(bytes)
                offsetX = pngOffsets?.first ?: 0
                offsetY = pngOffsets?.second ?: 0
                val formatLabel = if (pngOffsets != null) {
                    "PNG · grAb offsets"
                } else {
                    "Imagen"
                }
                imageInfo.text = name + " · " + raster.width + " × " + raster.height + " px · " + formatLabel
                savePatchButton.isEnabled = false
                preview.image = raster
                setOffset(true, offsetX)
                setOffset(false, offsetY)
                syncOffsetFields()
            } else {
                currentDoomPatch = bytes
                currentDoomPatchName = name
                renderCurrentDoomPatch(resetOffsets = true)
            }
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo abrir el archivo: " + error.message, Toast.LENGTH_LONG).show()
        } catch (error: OutOfMemoryError) {
            Toast.makeText(this, "La imagen es demasiado grande para este dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun openPlaypalPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PLAYPAL)
    }

    private fun openPlaypalDocument(uri: Uri) {
        try {
            val bytes = readDocument(uri)
            val palettes = DoomPlaypalCodec.parse(bytes)
            val name = queryDisplayName(uri) ?: "PLAYPAL"

            playpalPalettes = palettes
            playpalSourceName = name
            selectedPaletteIndex = 0
            updatePaletteSpinnerItems()

            if (currentDoomPatch != null) {
                renderCurrentDoomPatch(resetOffsets = false)
            }
            Toast.makeText(
                this,
                "PLAYPAL cargado: ${palettes.size} paletas",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "No se pudo leer PLAYPAL: " + error.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun paletteAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun updatePaletteSpinnerItems() {
        val items = mutableListOf("Escala de grises")
        playpalPalettes.indices.forEach { index ->
            items.add("PLAYPAL · Paleta $index")
        }

        updatingPaletteSpinner = true
        paletteSpinner.adapter = paletteAdapter(items)
        paletteSpinner.setSelection(
            if (selectedPaletteIndex < 0) 0 else selectedPaletteIndex + 1,
            false
        )
        updatingPaletteSpinner = false
        updatePaletteInfo()
    }

    private fun updatePaletteInfo() {
        if (!::paletteInfo.isInitialized) return
        paletteInfo.text = when {
            playpalPalettes.isEmpty() ->
                "Sin PLAYPAL cargado · vista en escala de grises"
            selectedPaletteIndex < 0 ->
                "$playpalSourceName · vista en escala de grises"
            else ->
                "$playpalSourceName · paleta $selectedPaletteIndex de ${playpalPalettes.size - 1}"
        }
    }

    private fun renderCurrentDoomPatch(resetOffsets: Boolean) {
        val bytes = currentDoomPatch ?: return
        val palette = playpalPalettes.getOrNull(selectedPaletteIndex)
        val patch = DoomPatchCodec.decode(bytes, palette)
        currentBitmap = patch.bitmap
        preview.image = patch.bitmap

        if (resetOffsets) {
            offsetX = patch.leftOffset
            offsetY = patch.topOffset
            setOffset(true, offsetX)
            setOffset(false, offsetY)
            syncOffsetFields()
        }

        val paletteLabel = if (palette == null) {
            "escala de grises"
        } else {
            "PLAYPAL $selectedPaletteIndex"
        }
        imageInfo.text = currentDoomPatchName + " · " +
            patch.width + " × " + patch.height + " px · Doom Patch · " + paletteLabel
        savePatchButton.isEnabled = true
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun saveDoomPatch() {
        val original = currentDoomPatch ?: return
        try {
            pendingPatchBytes = DoomPatchCodec.withOffsets(original, offsetX, offsetY)
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, currentDisplayName + "_offsets.lmp")
            }
            startActivityForResult(intent, REQUEST_SAVE_PATCH)
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo preparar el Doom Patch: " + error.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPng() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Toast.makeText(this, "Primero abre una imagen", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPngBitmap = bitmap
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            putExtra(Intent.EXTRA_TITLE, currentDisplayName + "_preview.png")
        }
        startActivityForResult(intent, REQUEST_EXPORT_PNG)
    }

    private fun writePendingBytes(uri: Uri, bytes: ByteArray?, label: String) {
        if (bytes == null) return
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalArgumentException("No se pudo crear el archivo")
            Toast.makeText(this, label + " guardado correctamente", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Error al guardar: " + error.message, Toast.LENGTH_LONG).show()
        } finally {
            pendingPatchBytes = null
        }
    }

    private fun writePendingPng(uri: Uri) {
        val bitmap = pendingPngBitmap ?: return
        try {
            val bitmapStream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bitmapStream)) {
                throw IllegalStateException("No se pudo codificar el PNG")
            }
            val pngWithOffsets = PngOffsetChunk.withOffsets(
                bitmapStream.toByteArray(),
                offsetX,
                offsetY
            )
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(pngWithOffsets)
            } ?: throw IllegalArgumentException("No se pudo crear el archivo")
            Toast.makeText(this, "PNG exportado correctamente con offsets grAb", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Error al exportar PNG: " + error.message, Toast.LENGTH_LONG).show()
        } finally {
            pendingPngBitmap = null
        }
    }

    private fun makeButton(text: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
    }

    private fun makeLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(201, 211, 226))
        setPadding(0, dp(2), dp(7), dp(2))
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(12), 0, dp(3))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private class SpritePreviewView(
    context: android.content.Context,
    private val parentScrollView: ScrollView
) : View(context) {
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }
    var previewMode = "Auto"
        set(value) { field = value; invalidate() }
    var offsetX = 0
        set(value) { field = value; invalidate() }
    var offsetY = 0
        set(value) { field = value; invalidate() }
    var zoomPercent = 100
        set(value) { field = value; invalidate() }
    var onOffsetsChanged: ((Int, Int) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 30, 38, 49)
        strokeWidth = dp(1.2f)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 220, 226, 236)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private var dragging = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragScale = 1f
    private var fractionalOffsetX = 0f
    private var fractionalOffsetY = 0f
    private var lastRenderScale = 1f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCheckerboard(canvas)
        val bitmap = image
        if (bitmap == null) {
            fillPaint.color = Color.rgb(190, 200, 216)
            fillPaint.textSize = dp(14f)
            fillPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Abre una imagen para comenzar", width / 2f, height / 2f, fillPaint)
            return
        }
        val zoom = zoomPercent / 100f
        when (previewMode) {
            "Graphic" -> drawGraphic(canvas, bitmap, zoom)
            "HUD" -> drawHud(canvas, bitmap, zoom)
            else -> drawSprite(canvas, bitmap, zoom)
        }
    }

    private fun drawCheckerboard(canvas: Canvas) {
        canvas.drawColor(Color.rgb(65, 72, 86))
        val cell = dp(18f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.rgb(88, 96, 111)
        var y = 0f
        var row = 0
        while (y < height) {
            var x = 0f
            var column = 0
            while (x < width) {
                if ((row + column) % 2 == 0) canvas.drawRect(x, y, x + cell, y + cell, fillPaint)
                x += cell
                column++
            }
            y += cell
            row++
        }
    }

    private fun drawGraphic(canvas: Canvas, bitmap: Bitmap, zoom: Float) {
        val scale = fitScale(bitmap, width * 0.82f, height * 0.82f) * zoom
        lastRenderScale = scale
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + w, top + h), bitmapPaint)
        drawCrosshair(canvas, width / 2f, height / 2f, false)
    }

    private fun drawSprite(canvas: Canvas, bitmap: Bitmap, zoom: Float) {
        val scale = fitScale(bitmap, width * 0.76f, height * 0.76f) * zoom
        lastRenderScale = scale
        val ax = width / 2f
        val ay = height / 2f
        val left = ax - offsetX * scale
        val top = ay - offsetY * scale
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), bitmapPaint)
        drawCrosshair(canvas, ax, ay, true)
    }

    private fun drawHud(canvas: Canvas, bitmap: Bitmap, zoom: Float) {
        val scale = min(width * 0.88f / 320f, height * 0.82f / 200f) * zoom
        lastRenderScale = scale
        val fw = 320f * scale
        val fh = 200f * scale
        val left = (width - fw) / 2f
        val top = (height - fh) / 2f
        val right = left + fw
        val bottom = top + fh
        canvas.drawRect(left, top, right, bottom, borderPaint)
        val ax = left + 160f * scale
        val ay = bottom
        val spriteLeft = ax - offsetX * scale
        val spriteTop = ay - offsetY * scale
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawBitmap(
            bitmap, null,
            RectF(spriteLeft, spriteTop, spriteLeft + bitmap.width * scale, spriteTop + bitmap.height * scale),
            bitmapPaint
        )
        canvas.restore()
        drawCrosshair(canvas, ax, ay, true)
    }

    private fun drawCrosshair(canvas: Canvas, x: Float, y: Float, marker: Boolean) {
        canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        if (marker) {
            fillPaint.color = Color.rgb(255, 190, 70)
            canvas.drawCircle(x, y, dp(3.5f), fillPaint)
        }
    }

    private fun fitScale(bitmap: Bitmap, maxWidth: Float, maxHeight: Float): Float =
        min(maxWidth / max(1, bitmap.width), maxHeight / max(1, bitmap.height))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null || previewMode == "Graphic") return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastTouchX = event.x
                lastTouchY = event.y
                dragScale = max(0.0001f, lastRenderScale)
                fractionalOffsetX = 0f
                fractionalOffsetY = 0f

                // The preview lives inside a vertical ScrollView. Lock that
                // parent for the whole drag so it cannot steal the gesture.
                parentScrollView.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false

                // Accumulate fractional image-pixel movement across MotionEvents.
                // This also keeps the preview responsive at low zoom levels.
                fractionalOffsetX += (lastTouchX - event.x) / dragScale
                fractionalOffsetY += (lastTouchY - event.y) / dragScale

                val wholeX = fractionalOffsetX.roundToInt()
                val wholeY = fractionalOffsetY.roundToInt()
                if (wholeX != 0) {
                    offsetX = (offsetX.toLong() + wholeX.toLong())
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    fractionalOffsetX -= wholeX
                }
                if (wholeY != 0) {
                    offsetY = (offsetY.toLong() + wholeY.toLong())
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    fractionalOffsetY -= wholeY
                }

                lastTouchX = event.x
                lastTouchY = event.y

                // Notify the Activity on every move so both numeric fields and
                // the saved/exported offsets are always synchronized.
                onOffsetsChanged?.invoke(offsetX, offsetY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                dragging = false
                isPressed = false
                parentScrollView.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                isPressed = false
                parentScrollView.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
