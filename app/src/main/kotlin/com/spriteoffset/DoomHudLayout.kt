package com.spriteoffset

internal data class HudImageRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * SLADE-compatible HUD coordinates.
 *
 * HUD offsets are measured from the top-left of a 320x200 game screen:
 * image left = -offsetX, image top = -offsetY. The weapon is normally cut
 * off by the status bar at y=168.
 */
internal object DoomHudLayout {
    const val SCREEN_WIDTH = 320f
    const val SCREEN_HEIGHT = 200f
    const val SCREEN_CENTER_X = 160f
    const val SCREEN_CENTER_Y = 100f
    const val STATUS_BAR_TOP = 168f

    fun imageRect(
        frameLeft: Float,
        frameTop: Float,
        scale: Float,
        imageWidth: Int,
        imageHeight: Int,
        offsetX: Int,
        offsetY: Int
    ): HudImageRect {
        val left = frameLeft + (-offsetX) * scale
        val top = frameTop + (-offsetY) * scale
        return HudImageRect(
            left = left,
            top = top,
            right = left + imageWidth * scale,
            bottom = top + imageHeight * scale
        )
    }

    fun centerX(frameLeft: Float, scale: Float): Float =
        frameLeft + SCREEN_CENTER_X * scale

    fun statusBarY(frameTop: Float, scale: Float): Float =
        frameTop + STATUS_BAR_TOP * scale
}
