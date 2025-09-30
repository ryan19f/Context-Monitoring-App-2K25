package edu.asu.cse535.contextmonitor.helpers

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.get
import java.io.File
import kotlin.math.*

object HeartRateHelperbckup {
    private const val TAG = "HeartRateHelper"

    // ------------------- PUBLIC ENTRY -------------------

    fun computeHeartRateFromVideo(ctx: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()

        // Open robustly: path -> (ctx, uri) -> file descriptor
        val opened = trySetDataSourceAllWays(retriever, ctx, uri)
        if (!opened) {
            Log.w(TAG, "Could not setDataSource for uri=$uri")
            return 0
        }

        // Tiny retry for duration metadata (can lag right after recording)
        var durationMs = 0L
        repeat(6) { // ~600 ms max
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs > 0L) return@repeat
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
        if (durationMs <= 0L) {
            Log.w(TAG, "Duration unavailable; proceeding with fixed sampling window.")
        }

        // ---- Time-based sampling (~30 Hz). If duration unknown, sample fixed 12 s.
        val analyzeMs: Long = if (durationMs > 0L) min(15_000L, durationMs) else 12_000L
        val frames = ArrayList<Bitmap>()
        val timesUs = ArrayList<Long>()

        try {
            val stepUs = 33_333L              // ~30 fps
            var tUs   = 500_000L              // skip first 0.5 s to let AE settle
            val endUs = tUs + analyzeMs * 1_000L
            var lastHash = 0L

            while (tUs < endUs) {
                val bmp = grabFrameAt(retriever, tUs) // tolerant frame getter with SYNC fallbacks
                if (bmp != null) {
                    val h = quickRoiHash(bmp)        // small ROI hash to drop identical frames
                    if (h != lastHash) {
                        frames.add(bmp)
                        timesUs.add(tUs)
                        lastHash = h
                    }
                }
                tUs += stepUs
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame sampling error: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        return postProcessAndEstimateBpm(frames, timesUs)
    }

    // ------------------- OPENING HELPERS -------------------

    private fun trySetDataSourceAllWays(
        retriever: MediaMetadataRetriever,
        ctx: Context,
        uri: Uri
    ): Boolean {
        // 1) If it's a file:// Uri, use the path
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrBlank() && File(path).exists()) {
                return try { retriever.setDataSource(path); true } catch (_: Exception) { false }
            }
        }

        // Try to resolve path via MediaStore (legacy behavior; may fail with scoped storage)
        try {
            val proj = arrayOf(MediaStore.Video.Media.DATA)
            ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                if (c.moveToFirst()) {
                    val p = c.getString(idx)
                    if (!p.isNullOrBlank() && File(p).exists()) {
                        retriever.setDataSource(p)
                        return true
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        // 2) (context, uri)
        try {
            retriever.setDataSource(ctx, uri)
            return true
        } catch (_: Exception) {}

        // 3) file descriptor
        return try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
        } catch (_: Exception) { false }
    }

    // Tolerant frame getter: try sync-only fallbacks to avoid NULL frames on some vendors
    private fun grabFrameAt(retriever: MediaMetadataRetriever, tUs: Long): Bitmap? {
        // Many devices only expose sync (I) frames to MMR
        var bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (bmp != null) return bmp

        // Some allow closest (may hit P/B frames)
        bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
        if (bmp != null) return bmp

        // As last resorts, previous/next sync
        bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
        if (bmp != null) return bmp

        bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_NEXT_SYNC)
        return bmp
    }

    // ------------------- CORE PROCESSING -------------------

    private fun postProcessAndEstimateBpm(frames: List<Bitmap>, timesUs: List<Long>): Int {
        if (frames.size < 24 || timesUs.size != frames.size) {
            Log.w(TAG, "Insufficient frames (${frames.size}) or mismatched timestamps.")
            return 0
        }

        // Build absolute seconds and check duration spanned by our samples
        val tSec = DoubleArray(timesUs.size) { i -> timesUs[i] / 1_000_000.0 }
        val durationSec = tSec.last() - tSec.first()
        if (durationSec < 6.0) {
            Log.w(TAG, "Duration too short: ${"%.2f".format(durationSec)}s")
            return 0
        }

        // Candidate ROIs (center + 4 offsets) to avoid flashlight hotspot or dead areas
        val rois = candidateRois(frames[0], gridOffset = 0.12)

        var bestScore = -1.0
        var bestSig: DoubleArray? = null
        var bestFs = 30.0
        var bestTag = ""

        for ((idx, roi) in rois.withIndex()) {
            val (g, r) = roiSeries(frames, roi) // green & red series (saturation-aware)
            if (g.isEmpty()) continue
            val gr = minus(g, r)

            // Preprocess each candidate channel
            val cands = listOf(
                "G@roi$idx"   to preprocess(g,  tSec, fsTarget = 30.0),
                "R@roi$idx"   to preprocess(r,  tSec, fsTarget = 30.0),
                "G-R@roi$idx" to preprocess(gr, tSec, fsTarget = 30.0)
            )

            for ((tag, pair) in cands) {
                val (fs, sig) = pair
                if (sig.isEmpty() || stddev(sig) < 1e-6) continue
                val s = acStrength(sig, fs, 40.0, 210.0)
                if (s > bestScore) {
                    bestScore = s
                    bestSig = sig
                    bestFs = fs
                    bestTag = tag
                }
            }
        }

        if (bestSig == null) {
            Log.w(TAG, "No usable ROI/channel (flat signal across candidates).")
            return 0
        }

        // Primary: autocorrelation BPM
        val acBpm = bpmByAutocorr(bestSig!!, bestFs, 40.0, 210.0)
        if (acBpm in 40..210) {
            Log.i(TAG, "BPM(AC)=$acBpm using $bestTag score=${"%.3f".format(bestScore)}")
            return acBpm
        }

        // Fallback: Goertzel narrow sweep
        val spBpm = bpmByGoertzel(bestSig!!, bestFs, 40.0, 210.0)
        if (spBpm in 40..210) {
            Log.i(TAG, "BPM(Goertzel)=$spBpm using $bestTag score=${"%.3f".format(bestScore)}")
            return spBpm
        }

        // Final fallback: simple peak counting
        val pkBpm = bpmByPeakCount(bestSig!!, bestFs)
        if (pkBpm in 40..210) {
            Log.i(TAG, "BPM(Peaks)=$pkBpm using $bestTag score=${"%.3f".format(bestScore)}")
            return pkBpm
        }

        Log.w(TAG, "No valid BPM (AC=$acBpm, Goertzel=$spBpm) best=$bestTag score=${"%.3f".format(bestScore)}")
        return 0
    }

    // ------------------- ROI & SERIES -------------------

    private data class Roi(val x0:Int, val y0:Int, val x1:Int, val y1:Int)

    private fun candidateRois(bmp: Bitmap, gridOffset: Double): List<Roi> {
        val w = bmp.width
        val h = bmp.height
        val cx = w / 2.0
        val cy = h / 2.0
        val half = max(24.0, min(w, h) / 8.0) // ~1/4 of min dimension (full box ≈ 1/4)

        fun box(dx: Double, dy: Double): Roi {
            val cx2 = (cx + dx * w).roundToInt()
            val cy2 = (cy + dy * h).roundToInt()
            val x0 = max(0, cx2 - half.roundToInt())
            val y0 = max(0, cy2 - half.roundToInt())
            val x1 = min(w - 1, cx2 + half.roundToInt())
            val y1 = min(h - 1, cy2 + half.roundToInt())
            return Roi(x0, y0, x1, y1)
        }

        val o = gridOffset
        return listOf(
            box( 0.0,  0.0),
            box( o,   0.0),
            box(-o,   0.0),
            box( 0.0,  o),
            box( 0.0, -o)
        )
    }

    // Extract mean G and R over ROI for each frame; skip saturated green pixels
    private fun roiSeries(frames: List<Bitmap>, roi: Roi): Pair<DoubleArray, DoubleArray> {
        val g = DoubleArray(frames.size)
        val r = DoubleArray(frames.size)
        for (k in frames.indices) {
            val bmp = frames[k]
            val x0 = roi.x0; val y0 = roi.y0; val x1 = roi.x1; val y1 = roi.y1

            var gs = 0L; var rs = 0L; var n = 0
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val c = bmp[x, y]                 // KTX extension
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8)  and 0xFF
                    if (gg < 250) { gs += gg; rs += rr; n++ } // avoid blown-out greens
                    x += 2
                }
                y += 2
            }

            if (n == 0) {
                // If every pixel was considered "blown out", fall back to using the raw values so we still
                // capture a signal for clips recorded with very strong flash illumination (common on phones).
                y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val c = bmp[x, y]
                        val rr = (c shr 16) and 0xFF
                        val gg = (c shr 8)  and 0xFF
                        gs += gg; rs += rr; n++
                        x += 2
                    }
                    y += 2
                }
            }

            if (n == 0) { g[k] = 0.0; r[k] = 0.0 } else {
                g[k] = gs.toDouble() / n; r[k] = rs.toDouble() / n
            }
        }
        return g to r
    }

    private fun minus(a: DoubleArray, b: DoubleArray): DoubleArray {
        val n = min(a.size, b.size)
        return DoubleArray(n) { i -> a[i] - b[i] }
    }

    // ------------------- PREPROCESSING -------------------

    private fun preprocess(xIn: DoubleArray, tSec: DoubleArray, fsTarget: Double): Pair<Double, DoubleArray> {
        if (xIn.size < 10 || xIn.size != tSec.size) return fsTarget to DoubleArray(0)

        // Detrend (long MA) + light smooth
        val detr = highpass(xIn, max(15, xIn.size / 10))
        val sm   = movAvg(detr, 5)

        // Uniform resample to fsTarget using linear interpolation over (tSec, sm)
        val t0 = tSec.first(); val t1 = tSec.last()
        val n  = max(64, ((t1 - t0) * fsTarget).roundToInt())
        val uni = DoubleArray(n)
        val dt  = 1.0 / fsTarget
        var j = 0
        for (i in 0 until n) {
            val ti = t0 + i * dt
            while (j + 1 < tSec.size && tSec[j + 1] < ti) j++
            val j2 = min(j + 1, tSec.lastIndex)
            val tA = tSec[j]; val tB = tSec[j2]
            val xA = sm[j];   val xB = sm[j2]
            val a  = if (tB > tA) ((ti - tA) / (tB - tA)).coerceIn(0.0, 1.0) else 0.0
            uni[i] = xA + a * (xB - xA)
        }

        // Crude band-limit: HP (~1 s) then short MA LP (~0.2 s), then z-score
        val hp  = highpass(uni, (fsTarget * 1.0).roundToInt().coerceAtLeast(3))
        val lp  = movAvg(hp,   (fsTarget * 0.2).roundToInt().coerceAtLeast(3))
        val out = zscore(lp)
        return fsTarget to out
    }

    // ------------------- ESTIMATORS -------------------

    private fun acStrength(x: DoubleArray, fs: Double, minBpm: Double, maxBpm: Double): Double {
        val minLag = (fs * (60.0 / maxBpm)).roundToInt().coerceAtLeast(1)
        val maxLag = (fs * (60.0 / minBpm)).roundToInt().coerceAtLeast(minLag + 2)
        if (x.size <= maxLag + 2) return 0.0
        val mean = x.average()
        var denom = 0.0; for (v in x) denom += (v - mean) * (v - mean)
        if (denom <= 1e-9) return 0.0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var num = 0.0
            val n = x.size - lag
            var i = 0
            while (i < n) { num += (x[i] - mean) * (x[i + lag] - mean); i++ }
            val ac = num / denom
            if (ac > best) best = ac
        }
        return best
    }

    private fun bpmByAutocorr(x: DoubleArray, fs: Double, minBpm: Double, maxBpm: Double): Int {
        val minLag = (fs * (60.0 / maxBpm)).roundToInt().coerceAtLeast(1)
        val maxLag = (fs * (60.0 / minBpm)).roundToInt().coerceAtLeast(minLag + 2)
        if (x.size <= maxLag + 2) return 0
        val mean = x.average()
        var denom = 0.0; for (v in x) denom += (v - mean) * (v - mean)
        if (denom <= 1e-9) return 0
        var bestLag = -1; var best = 0.0
        for (lag in minLag..maxLag) {
            var num = 0.0
            val n = x.size - lag
            var i = 0
            while (i < n) { num += (x[i] - mean) * (x[i + lag] - mean); i++ }
            val ac = num / denom
            if (lag > minLag && lag < maxLag - 1 && ac > best && ac > 0.05) {
                best = ac; bestLag = lag
            }
        }
        if (bestLag <= 0) return 0
        return (60.0 / (bestLag / fs)).roundToInt()
    }

    private fun bpmByGoertzel(x: DoubleArray, fs: Double, minBpm: Double, maxBpm: Double): Int {
        val minHz = minBpm / 60.0
        val maxHz = maxBpm / 60.0
        var bestHz = 0.0
        var bestP  = 0.0
        var f = minHz
        val stepHz = 0.05 // ~3 bpm resolution
        while (f <= maxHz + 1e-9) {
            val p = goertzel(x, fs, f)
            if (p > bestP) { bestP = p; bestHz = f }
            f += stepHz
        }
        return (bestHz * 60.0).roundToInt()
    }

    private fun bpmByPeakCount(x: DoubleArray, fs: Double): Int {
        val thr = percentile(x, 0.60)
        val minDist = max(1, (0.30 * fs).roundToInt()) // ≥0.30 s between peaks
        val peaks = ArrayList<Int>()
        var last = -10_000
        for (i in 1 until x.lastIndex) {
            if (x[i] > thr && x[i] > x[i - 1] && x[i] >= x[i + 1]) {
                if (i - last >= minDist) { peaks.add(i); last = i }
            }
        }
        if (peaks.size < 3) return 0
        val bpm = ((peaks.size / (x.size / fs)) * 60.0).roundToInt()
        return bpm
    }

    // ------------------- FILTERS & MATH -------------------

    private fun movAvg(src: DoubleArray, win: Int): DoubleArray {
        if (win <= 1) return src.copyOf()
        val out = DoubleArray(src.size)
        var acc = 0.0
        for (i in src.indices) {
            acc += src[i]
            if (i >= win) acc -= src[i - win]
            out[i] = if (i >= win - 1) acc / win else src[i]
        }
        return out
    }

    private fun highpass(src: DoubleArray, win: Int): DoubleArray {
        val trend = movAvg(src, win)
        return DoubleArray(src.size) { i -> src[i] - trend[i] }
    }

    private fun zscore(x: DoubleArray): DoubleArray {
        val mu = x.average()
        var v = 0.0; for (xi in x) v += (xi - mu) * (xi - mu)
        val sd = sqrt(v / x.size.coerceAtLeast(1))
        return if (sd > 1e-9) DoubleArray(x.size) { i -> (x[i] - mu) / sd } else x.copyOf()
    }

    private fun stddev(x: DoubleArray): Double {
        val m = x.average()
        var v = 0.0; for (xi in x) v += (xi - m) * (xi - m)
        return sqrt(v / x.size.coerceAtLeast(1))
    }

    private fun percentile(x: DoubleArray, p: Double): Double {
        if (x.isEmpty()) return 0.0
        val arr = x.copyOf()
        arr.sort()
        val idx = ((arr.size - 1) * p).coerceIn(0.0, (arr.size - 1).toDouble())
        val i0 = floor(idx).toInt(); val i1 = ceil(idx).toInt()
        return if (i0 == i1) arr[i0] else arr[i0] + (idx - i0) * (arr[i1] - arr[i0])
    }

    private fun goertzel(x: DoubleArray, fs: Double, f: Double): Double {
        val w = 2.0 * Math.PI * (f / fs)
        val coeff = 2.0 * cos(w)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (v in x) { s0 = v + coeff * s1 - s2; s2 = s1; s1 = s0 }
        val real = s1 - s2 * cos(w)
        val imag = s2 * sin(w)
        return real * real + imag * imag
    }

    // ------------------- QUICK HASH FOR DEDUPE -------------------

    private fun quickRoiHash(bmp: Bitmap): Long {
        val w = bmp.width; val h = bmp.height
        val cx = w / 2;    val cy = h / 2
        val half = max(10, min(w, h) / 16)
        val x0 = max(0, cx - half); val x1 = min(w - 1, cx + half)
        val y0 = max(0, cy - half); val y1 = min(h - 1, cy + half)

        var acc = 1469598103934665603L // FNV-1a 64-bit offset basis
        for (y in y0..y1 step 5) {
            for (x in x0..x1 step 5) {
                val c = bmp[x, y]
                val g = (c shr 8) and 0xFF
                acc = (acc xor g.toLong()) * 1099511628211L // FNV prime
            }
        }
        return acc
    }
}
