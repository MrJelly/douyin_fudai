package com.jelly.fudai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class SuperFudaiTemplateMatcher(context: Context) {

    companion object {
        // Real rooms render the same super-bag with different compression,
        // brightness and small horizontal offsets. Keep the template as the
        // primary discriminator, but make the color gate tolerant enough not
        // to reject a valid, dim/partially covered icon.
        private const val MATCH_MAX_DISTANCE = 18f
        private const val MIN_PURPLE_RATIO = 0.10f
    }

    private val templateRgb: Mat?
    private val templateMask: Mat?
    private val ready: Boolean

    var lastScore: Float = Float.MAX_VALUE
        private set
    var lastCenterY: Int = -1
        private set
    private var lastPurpleRatio: Float = 0f

    init {
        var loaded = false
        var source: Bitmap? = null
        var rgba: Mat? = null
        var rgb: Mat? = null
        var mask: Mat? = null
        var gray: Mat? = null
        var foreground: Mat? = null
        try {
            // The official Maven Android AAR packages libopencv_java4.so.
            System.loadLibrary("opencv_java4")
            rgba = Mat()
            rgb = Mat()
            mask = Mat()
            source = BitmapFactory.decodeResource(
                context.resources,
                R.drawable.super_fudai_template_user
            )
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Core.extractChannel(rgba, mask, 3)
            Imgproc.threshold(mask, mask, 100.0, 255.0, Imgproc.THRESH_BINARY)
            // The supplied reference PNG has an opaque black background.
            // Treating alpha as the whole mask made live-video pixels around
            // the icon dominate the score and allowed unrelated red widgets
            // to look deceptively similar. Match only the visible icon.
            gray = Mat()
            foreground = Mat()
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.threshold(gray, foreground, 12.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(mask, foreground, mask)
            loaded = !rgb.empty() && !mask.empty()
        } catch (error: Throwable) {
            Log.e("FudaiOpenCV", "OpenCV template initialization failed", error)
            rgb?.release()
            mask?.release()
            rgb = null
            mask = null
        } finally {
            foreground?.release()
            gray?.release()
            rgba?.release()
            source?.recycle()
        }
        templateRgb = rgb
        templateMask = mask
        ready = loaded
    }

    fun matches(screen: Bitmap, slot: Int): Boolean {
        lastScore = Float.MAX_VALUE
        lastCenterY = -1
        lastPurpleRatio = 0f
        if (!ready || screen.isRecycled) return false

        val width = screen.width
        val height = screen.height
        val centerRef = 80 + slot * 126
        val roiLeft = maxOf(0, (centerRef - 88) * width / 1080)
        val roiRight = minOf(width, (centerRef + 88) * width / 1080)
        // Lucky bags are below the broadcaster/avatar row. Keeping that row
        // out of the search prevents circular avatars from becoming targets.
        val roiTop = 240 * height / 2400
        val roiBottom = minOf(height, 530 * height / 2400)
        if (roiRight <= roiLeft || roiBottom <= roiTop) return false

        val crop = Bitmap.createBitmap(screen, roiLeft, roiTop, roiRight - roiLeft, roiBottom - roiTop)
        val roiRgba = Mat()
        val roiRgb = Mat()
        try {
            Utils.bitmapToMat(crop, roiRgba)
            Imgproc.cvtColor(roiRgba, roiRgb, Imgproc.COLOR_RGBA2RGB)

            // Fast pre-check: purple/pink region mining (125..162)
            val hsvFast = Mat()
            val purpleFast = Mat()
            val purplePixels = try {
                Imgproc.cvtColor(roiRgb, hsvFast, Imgproc.COLOR_RGB2HSV)
                Core.inRange(hsvFast, Scalar(118.0, 35.0, 35.0), Scalar(172.0, 255.0, 255.0), purpleFast)
                Core.countNonZero(purpleFast)
            } finally {
                purpleFast.release()
                hsvFast.release()
            }
            if (purplePixels < 5) return false

            val targetWidths = intArrayOf(
                52 * width / 1080, 60 * width / 1080,
                68 * width / 1080, 76 * width / 1080,
                84 * width / 1080, 94 * width / 1080, 106 * width / 1080
            )
            for (targetWidth in targetWidths) {
                if (targetWidth < 20 || targetWidth >= roiRgb.cols()) continue
                val targetHeight = maxOf(1, Math.round(targetWidth * templateRgb!!.rows() / templateRgb.cols().toFloat()))
                if (targetHeight >= roiRgb.rows()) continue

                val scaled = Mat()
                val mask = Mat()
                val result = Mat()
                try {
                    Imgproc.resize(templateRgb, scaled, Size(targetWidth.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.resize(templateMask, mask, Size(targetWidth.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
                    result.create(roiRgb.rows() - targetHeight + 1, roiRgb.cols() - targetWidth + 1, CvType.CV_32FC1)
                    Imgproc.matchTemplate(roiRgb, scaled, result, Imgproc.TM_SQDIFF_NORMED, mask)
                    val extrema = Core.minMaxLoc(result)
                    if (!extrema.minVal.isFinite()) continue
                    val distance = (extrema.minVal * 100.0).toFloat()
                    if (distance < lastScore) {
                        val matchX = extrema.minLoc.x.toInt()
                        val matchY = extrema.minLoc.y.toInt()
                        val matched = roiRgb.submat(matchY, matchY + targetHeight, matchX, matchX + targetWidth)
                        val hsv = Mat()
                        val purple = Mat()
                        val red1 = Mat()
                        val red2 = Mat()
                        try {
                            Imgproc.cvtColor(matched, hsv, Imgproc.COLOR_RGB2HSV)
                            Core.inRange(hsv, Scalar(118.0, 35.0, 35.0), Scalar(172.0, 255.0, 255.0), purple)
                            Core.inRange(hsv, Scalar(0.0, 60.0, 60.0), Scalar(12.0, 255.0, 255.0), red1)
                            Core.inRange(hsv, Scalar(168.0, 60.0, 60.0), Scalar(180.0, 255.0, 255.0), red2)
                            val pCount = Core.countNonZero(purple)
                            val rCount = Core.countNonZero(red1) + Core.countNonZero(red2)
                            if (pCount >= 8 && rCount < pCount * 1.5f) {
                                lastScore = distance
                                lastCenterY = roiTop + extrema.minLoc.y.toInt() + targetHeight / 2
                                lastPurpleRatio = pCount.toFloat() / (targetWidth * targetHeight).toFloat()
                            }
                        } finally {
                            purple.release()
                            red1.release()
                            red2.release()
                            hsv.release()
                            matched.release()
                        }
                    }
                } finally {
                    scaled.release()
                    mask.release()
                    result.release()
                }
            }
        } catch (error: Throwable) {
            Log.e("FudaiOpenCV", "OpenCV template matching failed", error)
            lastScore = Float.MAX_VALUE
            lastCenterY = -1
        } finally {
            roiRgba.release()
            roiRgb.release()
            crop.recycle()
        }
        if (lastScore > MATCH_MAX_DISTANCE || lastPurpleRatio < MIN_PURPLE_RATIO) {
            lastScore = Float.MAX_VALUE
            lastCenterY = -1
            return false
        }
        return true
    }

    fun close() {
        templateRgb?.release()
        templateMask?.release()
    }
}
