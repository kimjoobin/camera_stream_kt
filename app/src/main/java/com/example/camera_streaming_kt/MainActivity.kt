package com.example.camera_streaming_kt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.camera_streaming_kt.databinding.ActivityMainBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import org.webrtc.Camera1Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CameraVideoCapturer.CameraEventsHandler
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding : ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var webSocket: WebSocket

    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var videoCapturer: VideoCapturer
    private lateinit var loadVideoSource: org.webrtc.VideoSource
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var eglBase: EglBase



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        // setContentView(R.layout.activity_main)
        setContentView(viewBinding.root)

        // WebRTC 초기화
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
            .builder(this)
            .createInitializationOptions())

        // PeerConnectionFactory 생성
        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        // 카메라 요청
        videoCapturer = createCameraCapture(true)!!

        loadVideoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast)

        localVideoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, loadVideoSource)

        startCamera()
        // Request camera permissions
        /*if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }*/

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // docs: https://developer.android.com/codelabs/camerax-getting-started?hl=ko#3
    private fun startCamera() {
        // 1. CameraProvider 요청
        // ProcessCameraProvider는 Camera의 생명주기를 LifeCycleOwner의 생명주기에 Binding 함
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            // 이미지 분석
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer.setAnalyzer(cameraExecutor) { image ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                sendImageToServer(bytes)
                image.close()
            }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer)

            } catch(exc: Exception) {
                Log.e("Error", "Use case binding failed", exc)
            }

            // 4. Preview를 PreviewView에 연결한다.
            // surfaceProvider는 데이터를 받을 준비가 되었다는 신호를 카메라에게 보내준다.
            // setSurfaceProvider는 PreviewView에 SurfaceProvider를 제공해준다.
            preview.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)

        }, ContextCompat.getMainExecutor(this))
    }

    private fun createCameraCapture(isFront: Boolean): VideoCapturer? {

        val enumerator = Camera1Enumerator(false)
        val deviceNames = enumerator.deviceNames


        // First, try to find front facing camera
        for (deviceName in deviceNames) {
            if (if (isFront) enumerator.isFrontFacing(deviceName) else enumerator.isBackFacing(
                    deviceName
                )
            ) {
                val videoCapturer: VideoCapturer? =
                    enumerator.createCapturer(deviceName, object : CameraEventsHandler {
                        override fun onCameraError(s: String) {
                            Log.w("onCameraError", s)
                        }

                        override fun onCameraDisconnected() {
                            Log.w("onCameraDisconnected", "")
                        }

                        override fun onCameraFreezed(s: String) {
                            Log.w("onCameraFreezed", s)
                        }

                        override fun onCameraOpening(s: String) {
                            Log.w("onCameraOpening", s)
                        }

                        override fun onFirstFrameAvailable() {
                            Log.w("onFirstFrameAvailable", "")
                        }

                        override fun onCameraClosed() {
                            Log.w("onCameraClosed", "")
                        }
                    })
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    private fun sendImageToServer(data: ByteArray) {
        // 이미지를 Flask 서버로 전송하는 로직을 작성
        // HTTP POST 등을 사용하여 데이터를 전송
        val client = OkHttpClient()
        val mediaType = "application/octet-stream".toMediaType()
        val requestBody = data.toRequestBody(mediaType)

        val request = Request.Builder()
            .url("http://192.168.100.3:5000/receive_image") // Flask 서버의 URL
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected code $response")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // 이미지 분석
    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {

        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixels = data.map { it.toInt() and 0xFF }
            val luma = pixels.average()

            listener(luma)

            image.close()
        }
    }

    /*override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }*/

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 11
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

}