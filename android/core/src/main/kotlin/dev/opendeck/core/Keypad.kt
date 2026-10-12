package dev.opendeck.core

data class Layout(val cols: Int, val rows: Int)

/** Grid math and layouts (spec §5.5, §5.7, §6.3, §6.4). */
object Keypad {
    const val MIN_DIM = 1
    const val MAX_DIM = 8
    const val DEFAULT_ICON_SIZE = 144

    val DEFAULT_LAYOUT = Layout(5, 3)
    val PRESETS = listOf(DEFAULT_LAYOUT, Layout(3, 2), Layout(8, 4))

    fun isValid(layout: Layout) = layout.cols in MIN_DIM..MAX_DIM && layout.rows in MIN_DIM..MAX_DIM

    /** Row-major, 0-based, relative to the logical grid. */
    fun index(row: Int, col: Int, cols: Int): Int = row * cols + col

    fun keypadConfig(layout: Layout, iconSize: Int) = KeypadConfig(
        supportedIconImageFormats = listOf(ImageFormat.LZ4, ImageFormat.LZF),
        iconSize = Size(iconSize, iconSize),
        pressedIconSize = Size(iconSize, iconSize), // unset makes the server also darken pressed icons (SPEC §5.5)
        highDPI = true,
        overscale = false,
        layoutCrop = Size(layout.cols, layout.rows),
        enableContextAPI = true,
    )
}
