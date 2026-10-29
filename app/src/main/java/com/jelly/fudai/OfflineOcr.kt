package com.jelly.fudai

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class OfflineOcr {
    class Item(@JvmField val text: String, @JvmField val bounds: Rect)
    class Result(@JvmField val text: String, @JvmField val items: List<Item>)
    fun interface Callback { fun onResult(result: Result) }
    private val recognizer: TextRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val inFlight = AtomicBoolean(false)
    fun recognize(bitmap: Bitmap, callback: Callback) {
        // ML Kit retains its InputImage until the asynchronous task ends.
        // Never let independent FSM/watchdog paths pile up full-screen images.
        if (!inFlight.compareAndSet(false, true)) {
            callback.onResult(Result("", emptyList()))
            return
        }
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val items = mutableListOf<Item>()
                result.textBlocks.forEach { block -> block.lines.forEach { line ->
                    line.boundingBox?.let { items += Item(line.text, it) }
                    line.elements.forEach { it.boundingBox?.let { box -> items += Item(it.text, box) } }
                } }
                inFlight.set(false)
                callback.onResult(Result(result.text, items.toList()))
            }.addOnFailureListener {
                inFlight.set(false)
                callback.onResult(Result("", emptyList()))
            }.addOnCanceledListener {
                inFlight.set(false)
                callback.onResult(Result("", emptyList()))
            }
    }
    fun close() { recognizer.close() }
}
