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

class MainActivity : Activity() {
    private lateinit var preview: SpritePreviewView
    private lateinit var imageInfo: TextView
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private var offsetX = 0
    private var offsetY = 0
    private var updatingFields = false

    companion object {
        private const val REQUEST_IMAGE = 41
        private const val MAX_OFFSET = 8192
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

        preview = SpritePreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)
            ).apply { topMargin = dp(5); bottomMargin = dp(12) }
            onOffsetsChanged = { x, y ->
                offsetX = x
                offsetY = y
                syncOffsetFields()
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

        content.addView(TextView(this).apply {
            text = "En Sprite y HUD puedes arrastrar la imagen para ajustar sus offsets. Esta versión es un visor inicial; todavía no escribe formatos Doom."
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
        val safe = value.coerceIn(-MAX_OFFSET, MAX_OFFSET)
        if (isX) offsetX = safe else offsetY = safe
        preview.offsetX = offsetX
        preview.offsetY = offsetY
        if (updateField) syncOffsetFields()
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
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Using the document picker keeps compatibility with Android 8")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IllegalArgumentException("No se pudo leer la imagen")
            preview.image = decoded
            offsetX = 0
            offsetY = 0
            preview.offsetX = 0
            preview.offsetY = 0
            syncOffsetFields()
            val name = queryDisplayName(uri) ?: "Imagen"
            imageInfo.text = "$name · ${decoded.width} × ${decoded.height} px"
        } catch (error: Exception) {
            Toast.makeText(this, "No se pudo abrir la imagen: ${error.message}", Toast.LENGTH_LONG).show()
        } catch (error: OutOfMemoryError) {
            Toast.makeText(this, "La imagen es demasiado grande para este dispositivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
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

private class SpritePreviewView(context: android.content.Context) : View(context) {
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
    private var lastRenderScale = 1f

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
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val scale = max(0.0001f, lastRenderScale)
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                offsetX = (offsetX - dx / scale).roundToInt().coerceIn(-8192, 8192)
                offsetY = (offsetY - dy / scale).roundToInt().coerceIn(-8192, 8192)
                lastTouchX = event.x
                lastTouchY = event.y
                onOffsetsChanged?.invoke(offsetX, offsetY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
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
