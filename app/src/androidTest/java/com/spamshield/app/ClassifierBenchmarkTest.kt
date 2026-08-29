package com.spamshield.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what screening one SMS actually costs on real hardware: cold model load, featurization,
 * and steady-state inference latency.
 *
 * These numbers decide whether running the model inline in a BroadcastReceiver is acceptable. An
 * SMS receiver has roughly 10 seconds before the system considers it stuck, and it runs on every
 * incoming message, so per-message cost has to stay in the low milliseconds.
 *
 * Run: ./gradlew :app:connectedDebugAndroidTest
 * Read: adb logcat -s SpamBench
 */
@RunWith(AndroidJUnit4::class)
class ClassifierBenchmarkTest {

    private val corpus = listOf(
        "Bhai lottery jeet gaya, turant click karo bit.ly/xy12",
        "आपका बैंक खाता आज बंद हो जाएगा, तुरंत KYC पूरा करें",
        "உங்கள் வங்கிக் கணக்கு இன்று முடக்கப்படும், உடனே KYC முடிக்கவும்",
        "Ungal SIM 24 mani nerathil block agum, Aadhaar link pannunga",
        "C0ngratu1ati0ns! U w0n Rs 50,000 c1ick n0w",
        "Your SBI OTP is 448291. Valid 10 min. Do not share.",
        "Aapka Amazon order ship ho gaya, kal tak pahunchega",
        "yaar kal milte hain coffee ke liye",
        "Rs 2,499 debited from your HDFC account ending 4471 on 12 Jan. Bal: Rs 15,000",
        "Dear customer, your electricity will be disconnected tonight. Pay now rb.gy/a1b2c3"
    )

    private fun percentile(sorted: List<Double>, p: Double) =
        sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    @Test
    fun benchmarkEndToEndScreening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // --- cold load: mapping the .tflite + reading model_meta.json ---
        val loadStart = System.nanoTime()
        val classifier = SpamClassifier(context)
        val loadMs = (System.nanoTime() - loadStart) / 1e6

        try {
            // warm up so JIT / delegate init is not attributed to steady-state latency
            repeat(20) { corpus.forEach { classifier.classify(it) } }

            val featurizeUs = ArrayList<Double>()
            val totalMs = ArrayList<Double>()
            val iterations = 200

            repeat(iterations) {
                for (text in corpus) {
                    val f0 = System.nanoTime()
                    TextFeaturizer.featurize(text)
                    featurizeUs.add((System.nanoTime() - f0) / 1e3)

                    val t0 = System.nanoTime()
                    classifier.classify(text)
                    totalMs.add((System.nanoTime() - t0) / 1e6)
                }
            }

            val sortedTotal = totalMs.sorted()
            val sortedFeat = featurizeUs.sorted()
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

            Log.i(TAG, "==================== SpamShield benchmark ====================")
            Log.i(TAG, "device            : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
            Log.i(TAG, "samples           : ${totalMs.size} classifications over ${corpus.size} messages")
            Log.i(TAG, "cold model load   : %.1f ms".format(loadMs))
            Log.i(TAG, "featurize only    : p50 %.0f us | p95 %.0f us".format(
                percentile(sortedFeat, 0.50), percentile(sortedFeat, 0.95)))
            Log.i(TAG, "classify (total)  : p50 %.3f ms | p95 %.3f ms | p99 %.3f ms | max %.3f ms".format(
                percentile(sortedTotal, 0.50), percentile(sortedTotal, 0.95),
                percentile(sortedTotal, 0.99), sortedTotal.last()))
            Log.i(TAG, "throughput        : %.0f msg/sec single-threaded".format(
                1000.0 / percentile(sortedTotal, 0.50)))
            Log.i(TAG, "heap in use       : %.1f MB".format(usedMb))
            Log.i(TAG, "=============================================================")

            // A BroadcastReceiver gets ~10s; anything near that would be a design problem.
            assertTrue(
                "p95 latency ${percentile(sortedTotal, 0.95)}ms is too slow for inline screening",
                percentile(sortedTotal, 0.95) < 100.0
            )
        } finally {
            classifier.close()
        }
    }

    /** Cold-path cost as the receiver actually pays it: construct, classify once, close. */
    @Test
    fun benchmarkColdSingleMessage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timings = ArrayList<Double>()
        repeat(15) {
            val t0 = System.nanoTime()
            val c = SpamClassifier(context)
            c.classify("Bhai lottery jeet gaya, turant click karo bit.ly/xy12")
            c.close()
            timings.add((System.nanoTime() - t0) / 1e6)
        }
        val sorted = timings.sorted()
        Log.i(TAG, "cold path (load+classify+close): p50 %.1f ms | max %.1f ms".format(
            percentile(sorted, 0.50), sorted.last()))
    }

    companion object {
        private const val TAG = "SpamBench"
    }
}
