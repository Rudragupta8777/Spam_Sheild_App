package com.spamshield.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts that [TextFeaturizer] reproduces, byte for byte, the feature vectors produced by
 * ml_pipeline/text_features.py.
 *
 * This test is the reason the previous failure cannot silently recur. Training and inference are
 * separate implementations of one contract in two languages; last time they drifted, the model
 * scored 99% on the desktop while throwing "gather index out of bounds" on 21.5% of messages on
 * the phone, and nothing in the pipeline noticed.
 *
 * Regenerate the fixture with:  python ml_pipeline/export_parity_vectors.py
 */
class TextFeaturizerParityTest {

    private val golden: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("parity_vectors.json")
            ?: error("parity_vectors.json missing - run: python ml_pipeline/export_parity_vectors.py")
        JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun `contract constants match the training pipeline`() {
        val c = golden.getJSONObject("contract")
        assertEquals(c.getInt("num_buckets"), TextFeaturizer.NUM_BUCKETS)
        assertEquals(c.getInt("max_features"), TextFeaturizer.MAX_FEATURES)
        assertEquals(
            "FEATURE_VERSION must match text_features.py, or a model trained under different " +
                    "tokenization semantics could be loaded without either side noticing",
            c.getInt("feature_version"), TextFeaturizer.FEATURE_VERSION
        )
    }

    @Test
    fun `mask_digits matches python`() {
        val cases = golden.getJSONObject("mask_digits")
        for (key in cases.keys()) {
            assertEquals(
                "maskDigits() differs for: ${describe(key)}",
                cases.getString(key),
                TextFeaturizer.maskDigits(key)
            )
        }
    }

    @Test
    fun `fnv1a hash matches python`() {
        val hashes = golden.getJSONObject("fnv1a")
        for (key in hashes.keys()) {
            assertEquals(
                "FNV-1a mismatch for ${key.toByteArray().toList()}",
                hashes.getLong(key),
                TextFeaturizer.fnv1a(key).toLong()
            )
        }
    }

    @Test
    fun `normalization matches python across scripts`() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            assertEquals(
                "normalize() differs for: ${describe(text)}",
                case.getString("normalized"),
                TextFeaturizer.normalize(text)
            )
        }
    }

    @Test
    fun `per-case digit masking matches python`() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            assertEquals(
                "maskDigits() differs for: ${describe(text)}",
                case.getString("masked"),
                TextFeaturizer.maskDigits(text)
            )
        }
    }

    @Test
    fun `feature vectors match python exactly`() {
        val cases = golden.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val text = case.getString("text")
            val expected = case.getJSONArray("features")
            val actual = TextFeaturizer.featurize(text)

            assertEquals("length differs for: ${describe(text)}", expected.length(), actual.size)
            for (j in 0 until expected.length()) {
                assertEquals(
                    "feature[$j] differs for: ${describe(text)}",
                    expected.getInt(j), actual[j]
                )
            }
        }
    }

    /**
     * The specific failure mode that took the old model down: an id at or beyond the embedding's
     * row count. Hashing makes it arithmetically impossible, and this pins that guarantee.
     */
    @Test
    fun `no feature id can ever fall outside the embedding range`() {
        val probes = listOf(
            "", "   ", "hello",
            "आपका बैंक खाता आज बंद हो जाएगा, तुरंत KYC पूरा करें",
            "உங்கள் கணக்கு முடக்கப்படும்",
            "C0ngratu1ati0ns! U w0n Rs 50,000 c1ick n0w",
            "𝐖𝐈𝐍 𝐁𝐈𝐆 now", "emoji 🎉🎁 win prize",
            "x".repeat(5000)
        )
        for (text in probes) {
            for (id in TextFeaturizer.featurize(text)) {
                assertTrue(
                    "id $id out of range for ${describe(text)}",
                    id >= 0 && id < TextFeaturizer.NUM_BUCKETS
                )
            }
        }
    }

    @Test
    fun `output is always exactly MAX_FEATURES long`() {
        for (text in listOf("", "hi", "word ".repeat(500))) {
            assertEquals(TextFeaturizer.MAX_FEATURES, TextFeaturizer.featurize(text).size)
        }
    }

    private fun describe(text: String) =
        "\"${text.take(40)}\" (codepoints=${text.codePoints().toArray().take(12)})"
}
