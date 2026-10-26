package com.cardroid.lib.airplay

/** Inverts Android's fit-center projection so black bars are never touch targets. */
object RemoteTouch {
    data class Contact(val id: Int, val x: Float, val y: Float, val down: Boolean)
    fun decode(report: ByteArray, receiverWidth: Int, receiverHeight: Int,
               canvasWidth: Int, canvasHeight: Int, sourceWidth: Int, sourceHeight: Int): List<Contact> {
        require(report.size == 12)
        require(listOf(receiverWidth,receiverHeight,canvasWidth,canvasHeight,sourceWidth,sourceHeight).all { it > 0 })
        val scale = minOf(canvasWidth.toDouble()/sourceWidth,canvasHeight.toDouble()/sourceHeight)
        val left = (canvasWidth-sourceWidth*scale)/2
        val top = (canvasHeight-sourceHeight*scale)/2
        fun u16(offset: Int) = (report[offset].toInt() and 255) or ((report[offset+1].toInt() and 255) shl 8)
        return (0..1).map { slot ->
            val off=slot*6
            val x=(u16(off+2).toDouble()/receiverWidth*canvasWidth-left)/scale
            val y=(u16(off+4).toDouble()/receiverHeight*canvasHeight-top)/scale
            val inside=x>=0 && y>=0 && x<sourceWidth && y<sourceHeight
            Contact(report[off].toInt() and 255,x.coerceIn(0.0,sourceWidth-1.0).toFloat(),
                y.coerceIn(0.0,sourceHeight-1.0).toFloat(),report[off+1].toInt() and 1 != 0 && inside)
        }
    }

    /** Helper-display mode: display == canvas, no letterbox. Returns both report slots in display pixels, clamped to [0, dim-1]; down = touch bit. */
    fun decodeDirect(report: ByteArray, receiverWidth: Int, receiverHeight: Int, displayWidth: Int, displayHeight: Int): List<Contact> {
        require(report.size == 12)
        require(listOf(receiverWidth,receiverHeight,displayWidth,displayHeight).all { it > 0 })
        fun u16(offset: Int) = (report[offset].toInt() and 255) or ((report[offset+1].toInt() and 255) shl 8)
        return (0..1).map { slot ->
            val off=slot*6
            val x=Math.round(u16(off+2).toDouble()*displayWidth/receiverWidth).toInt().coerceIn(0,displayWidth-1)
            val y=Math.round(u16(off+4).toDouble()*displayHeight/receiverHeight).toInt().coerceIn(0,displayHeight-1)
            Contact(report[off].toInt() and 255,x.toFloat(),y.toFloat(),report[off+1].toInt() and 1 != 0)
        }
    }

    /**
     * Descriptor-backed LYNK/ECARX single-contact report.  The descriptor is
     * deliberately supplied by the session registry; this method never
     * guesses that an arbitrary five-byte report is touch input.
     */
    fun decodeSingleDirect(report: ByteArray, descriptor: TouchDescriptor,
                           displayWidth: Int, displayHeight: Int): List<Contact> {
        require(report.size == descriptor.reportBytes)
        require(displayWidth > 0 && displayHeight > 0 && descriptor.xMax > 0 && descriptor.yMax > 0)
        val x = u16(report, 1).coerceIn(0, descriptor.xMax)
        val y = u16(report, 3).coerceIn(0, descriptor.yMax)
        val px = (x.toDouble() * (displayWidth - 1).toDouble() / descriptor.xMax).toFloat()
        val py = (y.toDouble() * (displayHeight - 1).toDouble() / descriptor.yMax).toFloat()
        val down = report[0].toInt() and 1 != 0
        // Contact id 0 is descriptor-derived: the LYNK report has no ReportID
        // and carries exactly one finger collection.
        return listOf(Contact(0, px, py, down))
    }

    /** Descriptor-backed single-contact report mapped through the mirror fit. */
    fun decodeSingle(report: ByteArray, descriptor: TouchDescriptor,
                     receiverWidth: Int, receiverHeight: Int,
                     canvasWidth: Int, canvasHeight: Int,
                     sourceWidth: Int, sourceHeight: Int): List<Contact> {
        require(report.size == descriptor.reportBytes)
        require(listOf(receiverWidth, receiverHeight, canvasWidth, canvasHeight, sourceWidth, sourceHeight).all { it > 0 })
        require(descriptor.xMax > 0 && descriptor.yMax > 0)
        val rx = u16(report, 1).coerceIn(0, descriptor.xMax).toDouble() / descriptor.xMax * receiverWidth
        val ry = u16(report, 3).coerceIn(0, descriptor.yMax).toDouble() / descriptor.yMax * receiverHeight
        val scale = minOf(canvasWidth.toDouble() / sourceWidth, canvasHeight.toDouble() / sourceHeight)
        val left = (canvasWidth - sourceWidth * scale) / 2
        val top = (canvasHeight - sourceHeight * scale) / 2
        val x = (rx * canvasWidth / receiverWidth - left) / scale
        val y = (ry * canvasHeight / receiverHeight - top) / scale
        val inside = x >= 0 && y >= 0 && x < sourceWidth && y < sourceHeight
        return listOf(Contact(0,
            x.coerceIn(0.0, sourceWidth - 1.0).toFloat(),
            y.coerceIn(0.0, sourceHeight - 1.0).toFloat(),
            (report[0].toInt() and 1 != 0) && inside))
    }

    private fun u16(report: ByteArray, offset: Int): Int =
        (report[offset].toInt() and 0xFF) or ((report[offset + 1].toInt() and 0xFF) shl 8)
}
