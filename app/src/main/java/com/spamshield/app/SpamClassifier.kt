package com.spamshield.app

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ClassificationResult(val isSpam: Boolean, val confidence: Float)

class SpamClassifier(context: Context) {
    private var interpreter: Interpreter? = null
    private var vocab: Map<String, Int> = emptyMap()
    private val maxLength = 50

    init {
        // 1. Load the TFLite Model from memory safely
        val assetFileDescriptor = context.assets.openFd("spam_detector.tflite")
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val mappedByteBuffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        interpreter = Interpreter(mappedByteBuffer)

        // 2. Load and parse the Keras Tokenizer
        val jsonString = context.assets.open("tokenizer.json").bufferedReader().use { it.readText() }
        val jsonObject = Gson().fromJson<Map<String, Any>>(jsonString, object : TypeToken<Map<String, Any>>() {}.type)

        // Keras serializes the word_index as a stringified JSON inside the config object
        val config = jsonObject["config"] as? Map<String, Any>
        val wordIndexString = config?.get("word_index") as? String
        if (wordIndexString != null) {
            vocab = Gson().fromJson(wordIndexString, object : TypeToken<Map<String, Int>>() {}.type)
        }
    }

    /** Convenience wrapper for callers that only care about the yes/no verdict. */
    fun classifyText(text: String): Boolean = classify(text).isSpam

    fun classify(text: String): ClassificationResult {
        // Tokenize and pad the incoming SMS
        val words = text.lowercase().replace(Regex("[^a-z0-9 ]"), "").split("\\s+".toRegex())
        val sequence = FloatArray(maxLength)

        for (i in 0 until minOf(words.size, maxLength)) {
            val word = words[i]
            // Default to <OOV> token index (1) if the word wasn't in our training data
            sequence[i] = vocab[word]?.toFloat() ?: 1f
        }

        // TFLite requires specific multi-dimensional arrays for input and output
        val input = arrayOf(sequence)
        val output = arrayOf(FloatArray(1))

        interpreter?.run(input, output)

        val spamProbability = output[0][0]
        return ClassificationResult(isSpam = spamProbability > 0.5f, confidence = spamProbability)
    }

    fun close() {
        interpreter?.close()
    }
}