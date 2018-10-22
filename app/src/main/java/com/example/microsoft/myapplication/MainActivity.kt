package com.example.microsoft.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*


class MainActivity : AppCompatActivity() {
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Video will not have Audio", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(this, "camera cannot be opened without camera permission", Toast.LENGTH_SHORT).show()
        }

        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            isRecording = true
            createVideoFileName()
            Toast.makeText(this, "permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "cannot save videofile to storage", Toast.LENGTH_SHORT).show()
        }

    }

    companion object {
        //step11 create constant for state preview
        private val STATE_PREVIEW = 0
        //step11 create constant for focus lock
        private val STATE_WAIT_LOCK = 1
        //step6 request camera permission request code
        private val REQUEST_CAMERA_PERMISSION = 0

        //step8 request write external storage permission request code
        private val REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION = 1

        //step5 create sparseIntArray for handling orientation changes
        private var ORIENTATIONS = SparseIntArray()

        //step5 calculate the actual rotation from the sensorOrientation and deviceOrientation
        private fun sensorToDeviceRotation(cameraCharacteristics: CameraCharacteristics, deviceOrientation: Int): Int {
            val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            val mDeviceOrientation = ORIENTATIONS.get(deviceOrientation)
            return (sensorOrientation!! + mDeviceOrientation + 360) % 360
        }
    }

    init {
        //step5 initialize the sparseIntArray
        ORIENTATIONS.append(Surface.ROTATION_0, 0)
        ORIENTATIONS.append(Surface.ROTATION_90, 90)
        ORIENTATIONS.append(Surface.ROTATION_180, 180)
        ORIENTATIONS.append(Surface.ROTATION_270, 270)
    }

    //step1 create textureViewListener
    private lateinit var mtextureView: TextureView
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {

        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {

        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            return false
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            setUpCamera(width, height)
            connectCamera()
        }

    }
    //step2 create cameraDevice.StateCallback
    private var mcameraDevice: CameraDevice? = null
    private val mCameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            mcameraDevice = camera
            if (isRecording || isTimeLapse) {
                createVideoFileName()
                startRecord()
                mMediaRecorder.start()
            } else {
                startPreview()
            }
            // Toast.makeText(this@MainActivity,"Camera connection Made",Toast.LENGTH_SHORT).show()

        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            mcameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            mcameraDevice = null
        }

    }
    //step4 create background thread
    private var mBackgroundHandlerThread: HandlerThread? = null
    //step4 create background handler
    private var mBackgroundHandler: Handler? = null
    //step3 get cameraId
    private lateinit var mCameraId: String
    //step6 set preview size
    private lateinit var mPreviewSize: Size
    //step9 get video size
    private lateinit var mVideoSize: Size
    //step11 get Image size
    private lateinit var mImageSize: Size
    //step11 set image reader
    private lateinit var mImageReader: ImageReader
    //step11 Image reader listener
    private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        mBackgroundHandler!!.post(ImageSaver(reader.acquireLatestImage()))
    }

    //step12 create a image saver class
    private inner class ImageSaver(var image: Image) : Runnable {
        override fun run() {
            val byteBuffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            val fileOutputStream = FileOutputStream(mImageFileName)
            try {
                fileOutputStream.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                image.close()
                fileOutputStream.close()
            }

        }

    }

    //step11 preview capture session
    private lateinit var mPreviewCaptureSession: CameraCaptureSession
    //step11 capture callback
    private val mPreviewCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        private fun process(captureResult: CaptureResult) {
            when (mCaptureState) {
                STATE_PREVIEW -> {
                }
                STATE_WAIT_LOCK -> {
                    mCaptureState = STATE_PREVIEW
                    val afState: Int? = captureResult.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == CaptureRequest.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CaptureRequest.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                        Toast.makeText(this@MainActivity, "Focus Locked", Toast.LENGTH_SHORT).show()
                        startStillCaptureRequest()
                    }
                }

            }

        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            process(result)
        }

    }
    //step11 create variable to store capture states
    private var mCaptureState: Int = STATE_PREVIEW
    //step9 create mediaRecorder for recording video
    private lateinit var mMediaRecorder: MediaRecorder
    //step7 create preview sessions
    private lateinit var mCaptureRequestBuilder: CaptureRequest.Builder
    //step8 save video file in mVideoFolder with file name mVideoFileName
    private lateinit var mVideoFolder: File
    private lateinit var mVideoFileName: String
    //step8 recording boolean flag
    private var isRecording = false
    //step13 timeLapse Recording
    private var isTimeLapse = false
    //step9 get total rotation from setUpCamera function
    private var mTotalRotation: Int = 0
    //step12 create folder for image capture
    private lateinit var mImageFolder: File
    //step12 create file for image capture
    private lateinit var mImageFileName: String


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        createVideoFolder()
        createImageFolder()
        mMediaRecorder = MediaRecorder()

        mtextureView = findViewById(R.id.textureView_mainActivity_textureview)
        imageView_mainActivity_videorecord.setOnClickListener {
            if (isRecording) {
                isRecording = false
                mMediaRecorder.stop()
                mMediaRecorder.reset()
                startPreview()
            } else {
                isRecording = true
                checkWriteStoragePermission()
            }
        }
        imageView_mainActivity_cameraButton.setOnClickListener { lockFocus() }
        imageView_mainActivity_timelapse.setOnClickListener {
            if (isTimeLapse) {
                isTimeLapse = false
                isRecording = false
                mMediaRecorder.stop()
                mMediaRecorder.reset()
                startPreview()
            } else {
                isTimeLapse = true
                checkWriteStoragePermission()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (mtextureView.isAvailable) {
            setUpCamera(mtextureView.width, mtextureView.height)
            connectCamera()

        } else {
            mtextureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    //set window to fullscreen mode
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val decorView = window.decorView
        if (hasFocus) {
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    //step2 continued close Camera utility call on onPause method
    private fun closeCamera() {
        if (mcameraDevice != null) {
            mcameraDevice?.close()
            mcameraDevice = null
        }
    }

    //step3 get cameraId from query cameraManager
    private fun setUpCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            for (cameraId: String in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                //return on Front camera
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }
                val map: StreamConfigurationMap? =
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                //step5 swap the height and width if the rotation is 270 or 90
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation: Boolean = mTotalRotation == 90 || mTotalRotation == 270
                var rotatedWidth = width
                var rotatedHeight = height
                //on Rotation swap the values of height and width
                if (swapRotation) {
                    rotatedWidth = height
                    rotatedHeight = width
                }
                //step6 get the output size of the camera
                mPreviewSize =
                        chooseOptimalSize(map!!.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)
                mVideoSize =
                        chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java), rotatedWidth, rotatedHeight)
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight)
                mImageReader = ImageReader.newInstance(mImageSize.width, mImageSize.height, ImageFormat.JPEG, 1)
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
                mCameraId = cameraId
                return
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    //step6 connect to real camera device
    private fun connectCamera() {
        val cameraManager: CameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler)
            } else {
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                    Toast.makeText(this, "Requires camera permission", Toast.LENGTH_SHORT).show()
                }
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.RECORD_AUDIO
                    ), REQUEST_CAMERA_PERMISSION
                )
            }

        } catch (e: Exception) {
        }
    }

    //step10 start record
    private fun startRecord() {
        if (isRecording) {
            setupMediaRecorder()
        } else if (isTimeLapse) {
            setupTimeLapse()
        }

        val surfaceTexture = mtextureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
        val previewSurface = Surface(surfaceTexture)
        val recordSurface = mMediaRecorder.surface
        mCaptureRequestBuilder = mcameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        mCaptureRequestBuilder.addTarget(previewSurface)
        mCaptureRequestBuilder.addTarget(recordSurface)

        mcameraDevice!!.createCaptureSession(
            listOf(previewSurface, recordSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {

                }

                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.setRepeatingRequest(mCaptureRequestBuilder.build(), null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

            },
            null
        )
    }

    //step7 start preview sessions
    private fun startPreview() {
        val surfaceTexture = mtextureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
        val previewSurface = Surface(surfaceTexture)
        mCaptureRequestBuilder = mcameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        mCaptureRequestBuilder.addTarget(previewSurface)

        mcameraDevice!!.createCaptureSession(
            listOf(previewSurface, mImageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Unable to setup Camera", Toast.LENGTH_SHORT).show()
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    mPreviewCaptureSession = session
                    try {
                        mPreviewCaptureSession.setRepeatingRequest(
                            mCaptureRequestBuilder.build(),
                            null,
                            mBackgroundHandler
                        )
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

            },
            null
        )
    }

    //Step12 still capture request
    private fun startStillCaptureRequest() {
        mCaptureRequestBuilder = mcameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        mCaptureRequestBuilder.addTarget(mImageReader.surface)
        mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation)
        val stillCaptureCallback: CameraCaptureSession.CaptureCallback =
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long
                ) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber)
                    createImageFileName()
                }

            }
        mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null)
    }

    //step4 start Background Thread call onResume
    private fun startBackgroundThread() {
        mBackgroundHandlerThread = HandlerThread("Camera2api")
        mBackgroundHandlerThread?.start()
        mBackgroundHandler = Handler(mBackgroundHandlerThread?.looper)
    }

    //step4 stop Background Thread onPause
    private fun stopBackgroundThread() {
        mBackgroundHandlerThread?.quitSafely()
        try {
            mBackgroundHandlerThread?.join()
            mBackgroundHandlerThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    //step6 create helper class to compare size of different preview options
    inner class CompareSizeByArea : Comparator<Size> {
        override fun compare(lhs: Size?, rhs: Size?): Int {
            return java.lang.Long.signum(lhs!!.width.toLong() * lhs.height.toLong() / rhs!!.width.toLong() * rhs.height.toLong())
        }

    }

    //step6 get optimal size
    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val bigEnough = mutableListOf<Size>()
        for (option: Size in choices) {
            if (option.height == option.width * height / width && option.width >= width && option.height >= height) {
                bigEnough.add(option)
            }
        }

        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizeByArea())
        } else {
            choices[0]
        }
    }

    //step8 create folder
    private fun createVideoFolder() {
        val movieFile: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(movieFile, "Camera2VideoImage")
        if (!mVideoFolder.exists()) {
            mVideoFolder.mkdirs()
        }
    }

    //step8 create video FileName
    private fun createVideoFileName(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val pretend: String = "VIDEO_" + timeStamp + "_"
        val videoFile: File = File.createTempFile(pretend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile
    }

    //step12 create folder
    private fun createImageFolder() {
        val imageFile: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "Camera2VideoImage")
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs()
        }
    }

    //step12 create video FileName
    private fun createImageFileName(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val pretend: String = "IMAGE_" + timeStamp + "_"
        val imageFile: File = File.createTempFile(pretend, ".jpg", mImageFolder)
        mImageFileName = imageFile.absolutePath
        return imageFile
    }

    //step8 check external storage write permission
    private fun checkWriteStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (isTimeLapse || isRecording) {
                createVideoFileName()
                startRecord()
                mMediaRecorder.start()
            }

        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(this, "app needs to save videos", Toast.LENGTH_SHORT).show()
            }
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION
            )
        }
    }

    //step9 setup mediaRecorder
    private fun setupMediaRecorder() {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder.setOutputFile(mVideoFileName)
        mMediaRecorder.setVideoEncodingBitRate(1000000)
        mMediaRecorder.setVideoFrameRate(30)
        mMediaRecorder.setVideoSize(mVideoSize.width, mVideoSize.height)
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mMediaRecorder.setOrientationHint(mTotalRotation)
        mMediaRecorder.prepare()

    }

    //step13 setup timeLapse
    private fun setupTimeLapse() {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        val profile = CamcorderProfile.get(setTimeLapseVideoSize())
        mMediaRecorder.setProfile(profile)
        mMediaRecorder.setOutputFile(mVideoFileName)
        mMediaRecorder.setCaptureRate(profile.videoFrameRate / 6.0)
        mMediaRecorder.setOrientationHint(mTotalRotation)
        mMediaRecorder.prepare()
    }

    //step13 set timeLapseVideo size
    private fun setTimeLapseVideoSize(): Int {
        return when (mVideoSize.height) {
            in 0..720 -> {
                CamcorderProfile.QUALITY_TIME_LAPSE_480P
            }
            in 721..1280 -> {
                CamcorderProfile.QUALITY_TIME_LAPSE_720P
            }
            in 1281..1920 -> {
                CamcorderProfile.QUALITY_TIME_LAPSE_1080P
            }
            else -> {
                CamcorderProfile.QUALITY_TIME_LAPSE_2160P
            }
        }
    }


    //step11 lock focus method
    private fun lockFocus() {
        mCaptureState = STATE_WAIT_LOCK
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler)
    }
}
