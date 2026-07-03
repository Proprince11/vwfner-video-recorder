package com.camerarecorder.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.camerarecorder.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ═══════════════════════════════════════════════════════════════
 * WHAT THIS CODE DOES:
 * ═══════════════════════════════════════════════════════════════
 * This is the main (and only) screen of the camera app.
 * It handles:
 *   1. Showing the live camera preview (what the camera sees)
 *   2. Recording video when you tap the record button
 *   3. Switching between front and back cameras
 *   4. Showing a timer while recording
 *   5. Saving recorded videos to the phone's gallery
 *
 * WHY IT EXISTS:
 *   This is the core of the app — without this, nothing works.
 *
 * HOW IT FITS INTO THE PRODUCT:
 *   This is the entire product — a single-screen camera app
 *   focused on video recording.
 * ═══════════════════════════════════════════════════════════════
 */
class MainActivity : AppCompatActivity() {

    // ─── WHAT THESE VARIABLES DO ───────────────────────────────
    // Think of these as the "state" of the app — what's happening right now

    // View Binding: connects our Kotlin code to the XML layout buttons/views
    private lateinit var binding: ActivityMainBinding

    // Which camera are we using? Back camera by default
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Are we currently recording? Used to toggle record on/off
    private var isRecording = false

    // The active recording session (null when not recording)
    private var activeRecording: Recording? = null

    // CameraX video capture handler
    private var videoCapture: VideoCapture<Recorder>? = null

    // Background thread for camera work (so the app doesn't freeze)
    private lateinit var cameraExecutor: ExecutorService

    // Timer tracking
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private var dotAnimator: ObjectAnimator? = null

    // Timer update task — runs every second to update the display
    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val seconds = (elapsed / 1000) % 60
            val minutes = (elapsed / 1000) / 60
            binding.timerText.text = String.format("%02d:%02d", minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    // ─── APP LIFECYCLE ─────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Connect this code to the XML layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Create a background thread for camera operations
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Step 1: Check if we have camera permission
        if (allPermissionsGranted()) {
            // We have permission — start the camera!
            startCamera()
        } else {
            // Ask the user for permission
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Step 2: Set up button click listeners
        setupControls()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up: stop the background thread when app closes
        cameraExecutor.shutdown()
        timerHandler.removeCallbacks(timerRunnable)
        dotAnimator?.cancel()
    }

    override fun onPause() {
        super.onPause()
        // Stop recording if the app is backgrounded to prevent "ghost recording"
        if (isRecording) {
            stopRecording()
        }
    }

    // ─── CAMERA SETUP ──────────────────────────────────────────
    /**
     * WHAT: Initializes the camera and connects it to the preview on screen
     * WHY: Without this, you'd see a black screen
     * HOW: Uses Google's CameraX library which handles all the
     *      complex camera hardware stuff for us
     */
    private fun startCamera() {
        // Get the camera provider (CameraX manages camera access for us)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // === PREVIEW: Shows what the camera sees on screen ===
            val preview = Preview.Builder()
                .build()
                .also {
                    // Connect the preview to our PreviewView in the layout
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            // === VIDEO CAPTURE: Handles recording video ===
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.FHD)  // Force 1080p
                )
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // === CAMERA SELECTION: Front or back camera ===
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                // Disconnect any previous camera connection
                cameraProvider.unbindAll()

                // Connect everything: preview + video capture to the chosen camera
                val camera = cameraProvider.bindToLifecycle(
                    this,           // Lifecycle owner (this activity)
                    cameraSelector, // Which camera to use
                    preview,        // Show preview on screen
                    videoCapture    // Enable video recording
                )

                // Try to force 60 FPS using Camera2Interop
                try {
                    val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)
                    val options = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
                        .build()
                    camera2CameraControl.setCaptureRequestOptions(options)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not force 60 FPS: ${e.message}")
                }

                Log.d(TAG, "Camera started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Camera startup failed: ${e.message}")
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ─── BUTTON CONTROLS ───────────────────────────────────────
    /**
     * WHAT: Sets up what happens when you tap each button
     * WHY: Buttons need to DO something when tapped
     */
    private fun setupControls() {

        // === RECORD BUTTON ===
        // Tap to start recording, tap again to stop
        binding.recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // === SWITCH CAMERA BUTTON ===
        // Flip between front and back cameras
        binding.switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

            // Restart camera with the new lens
            startCamera()

            // Add a nice rotation animation to the button
            it.animate()
                .rotationBy(180f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        // === GALLERY BUTTON ===
        // Opens the phone's gallery to view recorded videos
        binding.galleryButton.setOnClickListener {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── VIDEO RECORDING ───────────────────────────────────────
    /**
     * WHAT: Starts recording a video
     * WHY: This is the main feature of the app!
     * HOW: Uses CameraX's VideoCapture to record video frames
     *      and saves them as an MP4 file in the phone's gallery
     *
     * WHAT COULD BREAK:
     *   - No camera permission → recording won't start
     *   - No audio permission → video records without sound
     *   - Phone storage full → recording fails
     */
    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        // Create a file name with timestamp so each video has a unique name
        val name = "CamRec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        // Set up where to save the video (in the phone's Movies folder)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraRecorder")
            }
        }

        // Configure the output location
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        // Start recording!
        activeRecording = videoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                // Add audio if we have permission
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                // This callback fires when recording events happen
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "Recording started")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error: ${event.error}")
                            Toast.makeText(
                                this,
                                getString(R.string.recording_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.d(TAG, "Video saved: ${event.outputResults.outputUri}")
                            Toast.makeText(
                                this,
                                getString(R.string.recording_stopped),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

        // Update UI to show "recording" state
        isRecording = true
        updateRecordingUI(true)
    }

    /**
     * WHAT: Stops the current video recording
     * WHY: User tapped the record button again to stop
     */
    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        isRecording = false
        updateRecordingUI(false)
    }

    // ─── UI UPDATES ────────────────────────────────────────────
    /**
     * WHAT: Updates the on-screen appearance when recording starts/stops
     * WHY: User needs visual feedback that recording is happening
     * HOW:
     *   - Recording ON: Shows timer, blinking red dot, record button changes to square
     *   - Recording OFF: Hides timer, dot, button goes back to circle
     */
    private fun updateRecordingUI(recording: Boolean) {
        if (recording) {
            // Show the timer and recording dot
            binding.timerText.visibility = View.VISIBLE
            binding.recordingDot.visibility = View.VISIBLE

            // Start the timer
            recordingStartTime = System.currentTimeMillis()
            binding.timerText.text = "00:00"
            timerHandler.post(timerRunnable)

            // Animate the recording dot (blink effect)
            dotAnimator = ObjectAnimator.ofFloat(binding.recordingDot, "alpha", 1f, 0f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }

            // Animate record button: circle → rounded square (like iPhone)
            animateRecordButton(toSquare = true)

            // Disable camera switch while recording
            binding.switchCameraButton.alpha = 0.3f
            binding.switchCameraButton.isEnabled = false

        } else {
            // Hide the timer and recording dot
            binding.timerText.visibility = View.GONE
            binding.recordingDot.visibility = View.GONE

            // Stop the timer
            timerHandler.removeCallbacks(timerRunnable)

            // Stop the dot blink animation
            dotAnimator?.cancel()
            dotAnimator = null

            // Animate record button: square → circle
            animateRecordButton(toSquare = false)

            // Re-enable camera switch
            binding.switchCameraButton.alpha = 1f
            binding.switchCameraButton.isEnabled = true
        }
    }

    /**
     * WHAT: Animates the record button between circle (idle) and square (recording)
     * WHY: Professional camera apps use this visual cue
     */
    private fun animateRecordButton(toSquare: Boolean) {
        val inner = binding.recordButtonInner

        if (toSquare) {
            // Shrink to a rounded square
            inner.animate()
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(200)
                .withStartAction {
                    // Change shape to rounded rectangle
                    inner.background = ContextCompat.getDrawable(this, R.drawable.record_button_stop)
                }
                .start()
        } else {
            // Grow back to full circle
            inner.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .withStartAction {
                    // Change shape back to circle
                    inner.background = ContextCompat.getDrawable(this, R.drawable.record_button_inner)
                }
                .start()
        }
    }

    // ─── PERMISSIONS ───────────────────────────────────────────
    /**
     * WHAT: Checks if we have all needed permissions
     * WHY: Android requires explicit user permission for camera and microphone
     *
     * Think of it like asking "Can I use your camera?" before using it.
     * Android enforces this for user privacy.
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * WHAT: Called after the user responds to the permission popup
     * WHY: We need to react — if they said yes, start the camera.
     *      If they said no, show an error message.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    // ─── CONSTANTS ─────────────────────────────────────────────
    companion object {
        private const val TAG = "CameraRecorder"
        private const val REQUEST_CODE_PERMISSIONS = 10

        // List of permissions we need
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            // Need storage permission on Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
