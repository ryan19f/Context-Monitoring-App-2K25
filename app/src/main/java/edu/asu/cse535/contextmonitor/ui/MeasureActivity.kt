package edu.asu.cse535.contextmonitor.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import edu.asu.cse535.contextmonitor.databinding.ActivityMeasureBinding
import edu.asu.cse535.contextmonitor.helpers.HeartRateHelper
import edu.asu.cse535.contextmonitor.helpers.RespiratoryHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import java.io.File

// ---------------- ViewModel ----------------
class MeasureViewModel : ViewModel() {
    val heartRate = MutableLiveData<Int?>()
    val respRate = MutableLiveData<Int?>()
    val hrVideoUri = MutableLiveData<Uri?>()
}

// ---------------- Activity ----------------
class MeasureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeasureBinding
    private lateinit var vm: MeasureViewModel

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    // Timers
    private var hrTimer: CountDownTimer? = null
    private var rrTimer: CountDownTimer? = null

    // HR recording state
    private var activeRecording: Recording? = null
    private var hrCurrentFile: File? = null
    private var hrCancelled: Boolean = false
    private var hrAwaitingFinalize: Boolean = false

    // RR sensor state
    private var sensorManager: SensorManager? = null
    private var accelListener: SensorEventListener? = null
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val ts = ArrayList<Long>()
    private var rrCancelled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeasureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm = ViewModelProvider(this)[MeasureViewModel::class.java]

        requestPermissionsIfNeeded { setupCamera() }

        // Progress bars setup
        binding.progressHr.isIndeterminate = false
        binding.progressRr.isIndeterminate = false
        binding.progressHr.max = 45
        binding.progressRr.max = 45
        binding.progressHr.progress = 0
        binding.progressRr.progress = 0

        // Buttons
        binding.btnStartHr.setOnClickListener { recordHr45s() }
        binding.btnStartRr.setOnClickListener { measureRr45s() }
        binding.btnCancelHr.setOnClickListener { cancelHr() }
        binding.btnCancelRr.setOnClickListener { cancelRr() }

        binding.btnNext.setOnClickListener {
            startActivity(Intent(this, SymptomsActivity::class.java).apply {
                putExtra("HR", vm.heartRate.value ?: -1)
                putExtra("RR", vm.respRate.value ?: -1)
            })
        }

        vm.heartRate.observe(this) { updateNextEnabled() }
        vm.respRate.observe(this) { updateNextEnabled() }

        // Sensor manager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onDestroy() {
        hrTimer?.cancel(); hrTimer = null
        rrTimer?.cancel(); rrTimer = null
        // Ensure resources are released
        try { activeRecording?.stop() } catch (_: Exception) {}
        activeRecording = null
        accelListener?.let { sensorManager?.unregisterListener(it) }
        accelListener = null
        super.onDestroy()
    }

    private fun updateNextEnabled() {
        binding.btnNext.isEnabled = (vm.heartRate.value != null && vm.respRate.value != null)
    }

    // ---------------- Permissions ----------------
    private fun requestPermissionsIfNeeded(onGranted: () -> Unit) {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.READ_MEDIA_VIDEO
        else perms += Manifest.permission.READ_EXTERNAL_STORAGE

        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.all { it.value }) onGranted()
            else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
        launcher.launch(perms.toTypedArray())
    }

    // ---------------- CameraX setup ----------------
    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }

            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider!!.unbindAll()
            val camera = cameraProvider!!.bindToLifecycle(this, selector, preview, videoCapture)

            camera.cameraControl.enableTorch(true)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------- Countdown helper ----------------
    private fun startCountdown(
        totalSeconds: Int,
        onTick: (remaining: Int) -> Unit,
        onFinish: () -> Unit
    ): CountDownTimer {
        return object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) = onTick((msLeft / 1000L).toInt())
            override fun onFinish() = onFinish()
        }.also { it.start() }
    }

    // ---------------- Heart Rate (video) ----------------
    private fun recordHr45s() {
        val vCap = videoCapture ?: return

        // Reset cancel flag & setup file
        hrCancelled = false
        hrAwaitingFinalize = false
        val outDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        hrCurrentFile = File(outDir, "hr_${System.currentTimeMillis()}.mp4")
        val outOpts = FileOutputOptions.Builder(hrCurrentFile!!).build()

        // Prepare recording (enable audio only if permission granted)
        var pending = vCap.output.prepareRecording(this, outOpts)
        val hasAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) pending = pending.withAudioEnabled()

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> Unit
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        if (!hrCancelled) {
                            binding.statusHr.text = "Recording error: ${event.error}"
                            resetHrUi()
                        }
                        hrAwaitingFinalize = false
                        return@start
                    }

                    if (!hrCancelled && hrAwaitingFinalize) {
                        val file = hrCurrentFile
                        val uri = event.outputResults.outputUri.takeIf { it != Uri.EMPTY }
                            ?: file?.let { Uri.fromFile(it) }

                        if (uri != null) {
                            startHrAnalysis(uri)
                        } else {
                            binding.statusHr.text = "Recording unavailable"
                            resetHrUi()
                        }
                    }
                    hrAwaitingFinalize = false
                }
            }
        }

        // UI state
        binding.btnStartHr.isEnabled = false
        binding.btnCancelHr.isEnabled = true
        binding.btnCancelHr.visibility = android.view.View.VISIBLE
        binding.progressHr.isIndeterminate = false
        binding.progressHr.progress = 0
        binding.statusHr.text = "Recording… 45s\nCover flash and camera with your fingertip."

        // Countdown 45s → stop → analyze
        hrTimer?.cancel()
        hrTimer = startCountdown(
            totalSeconds = 45,
            onTick = { remaining ->
                val elapsed = 45 - remaining
                binding.progressHr.progress = elapsed
                binding.statusHr.text = "Recording… ${remaining}s\nKeep finger steady on flash + camera."
            },
            onFinish = {
                if (hrCancelled) return@startCountdown // already handled by cancel
                stopHrRecordingAndAnalyze()
            }
        )
    }

    private fun stopHrRecordingAndAnalyze() {
        // Stop recording
        try { activeRecording?.stop() } catch (_: Exception) {}
        activeRecording = null

        val file = hrCurrentFile
        if (file == null || hrCancelled) {
            resetHrUi()
            return
        }

        hrAwaitingFinalize = true
        // Prepare UI while Recorder finalizes file
        binding.statusHr.text = "Finalizing recording…"
        binding.btnCancelHr.isEnabled = false
        binding.progressHr.isIndeterminate = true
    }

    private fun cancelHr() {
        hrCancelled = true
        hrAwaitingFinalize = false
        hrTimer?.cancel(); hrTimer = null
        try { activeRecording?.stop() } catch (_: Exception) {}
        activeRecording = null

        // Optionally delete partial file
        hrCurrentFile?.let { runCatching { if (it.exists()) it.delete() } }
        hrCurrentFile = null

        binding.statusHr.text = "Heart rate measurement cancelled."
        resetHrUi()
    }

    private fun startHrAnalysis(uri: Uri) {
        vm.hrVideoUri.value = uri
        binding.statusHr.text = "Processing heart rate…"
        binding.progressHr.isIndeterminate = true

        lifecycleScope.launch {
            val bpm = withContext(Dispatchers.Default) {
                HeartRateHelper.computeHeartRateFromVideo(this@MeasureActivity, uri)
            }
            if (!hrCancelled) {
                vm.heartRate.postValue(bpm)
                binding.progressHr.isIndeterminate = false
                binding.progressHr.progress = binding.progressHr.max
                binding.statusHr.text = "Heart rate: $bpm bpm"
            }
            resetHrUi(finalizeOnly = true)
        }
    }

    private fun resetHrUi(finalizeOnly: Boolean = false) {
        binding.btnStartHr.isEnabled = true
        binding.btnCancelHr.isEnabled = false
        binding.btnCancelHr.visibility = android.view.View.GONE
        if (!finalizeOnly) {
            binding.progressHr.isIndeterminate = false
            binding.progressHr.progress = 0
        }
    }

    // ---------------- Respiratory Rate (accelerometer) ----------------
    private fun measureRr45s() {
        val sm = sensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Toast.makeText(this, "No accelerometer", Toast.LENGTH_LONG).show(); return
        }

        rrCancelled = false
        xs.clear(); ys.clear(); zs.clear(); ts.clear()

        accelListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                xs += e.values[0]; ys += e.values[1]; zs += e.values[2]; ts += System.nanoTime()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        // UI state
        binding.btnStartRr.isEnabled = false
        binding.btnCancelRr.isEnabled = true
        binding.btnCancelRr.visibility = android.view.View.VISIBLE
        binding.progressRr.isIndeterminate = false
        binding.progressRr.progress = 0
        binding.statusRr.text = "Measuring… 45s\nPlace phone on chest and stay still."

        sm.registerListener(accelListener, acc, SensorManager.SENSOR_DELAY_GAME)

        // Countdown
        rrTimer?.cancel()
        rrTimer = startCountdown(
            totalSeconds = 45,
            onTick = { remaining ->
                val elapsed = 45 - remaining
                binding.progressRr.progress = elapsed
                binding.statusRr.text = "Measuring… ${remaining}s\nBreathe normally, keep still."
            },
            onFinish = {
                if (rrCancelled) return@startCountdown
                stopRrAndAnalyze()
            }
        )
    }

    private fun stopRrAndAnalyze() {
        // Stop sensor
        accelListener?.let { sensorManager?.unregisterListener(it) }
        accelListener = null

        // Analyze
        binding.statusRr.text = "Processing respiratory rate…"
        binding.btnCancelRr.isEnabled = false
        binding.progressRr.isIndeterminate = true

        lifecycleScope.launch {
            val rr = withContext(Dispatchers.Default) {
                RespiratoryHelper.computeRespRateFromAccel(
                    xs.toFloatArray(), ys.toFloatArray(), zs.toFloatArray(), ts.toLongArray()
                )
            }
            if (!rrCancelled) {
                vm.respRate.postValue(rr)
                binding.progressRr.isIndeterminate = false
                binding.progressRr.progress = binding.progressRr.max
                binding.statusRr.text = "Respiratory rate: $rr bpm"
            }
            resetRrUi(finalizeOnly = true)
        }
    }

    private fun cancelRr() {
        rrCancelled = true
        rrTimer?.cancel(); rrTimer = null
        accelListener?.let { sensorManager?.unregisterListener(it) }
        accelListener = null

        binding.statusRr.text = "Respiratory measurement cancelled."
        resetRrUi()
    }

    private fun resetRrUi(finalizeOnly: Boolean = false) {
        binding.btnStartRr.isEnabled = true
        binding.btnCancelRr.isEnabled = false
        binding.btnCancelRr.visibility = android.view.View.GONE
        if (!finalizeOnly) {
            binding.progressRr.isIndeterminate = false
            binding.progressRr.progress = 0
        }
    }
}
