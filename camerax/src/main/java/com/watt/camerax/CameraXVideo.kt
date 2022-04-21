package com.watt.camerax

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.watt.camerax.databinding.CameraxVideoBinding
import kotlinx.coroutines.*
import splitties.systemservices.layoutInflater
import splitties.toast.toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

/**
 * Created by khm on 2021-11-02.
 */

class CameraXVideo : ConstraintLayout, LifecycleObserver {
    val binding:CameraxVideoBinding by lazy { CameraxVideoBinding.inflate(layoutInflater) }
    private val defaultScope = CoroutineScope(Dispatchers.Default)

    private var videoFileSaveDialog: VideoFileSaveDialog?=null

    private val CameraSettingDir = "CameraSettingSharedPreference"
    private val CameraBitrate = "CameraSettingBitrate"
    private val CameraMenuHide = "CameraSettingMenuHide"

    private val defaultFileSaveDir = "${Environment.getExternalStorageDirectory().absolutePath}/Camera1/"
    private val defaultFileSaveDirOn11 = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath
    private var fileName = ""
    private var fileDir:String = ""

    private val RATIO_4_3_VALUE = 4.0 / 3.0 // aspect ratio 4x3
    private val RATIO_16_9_VALUE = 16.0 / 9.0 // aspect ratio 16x9

    private val fhdBitrate = 4*1024*1024  //4mbps
    private val hdBitrate = 2621440   //2.5mbps
    private val fhdResolution: Size = Size(1920, 1080)
    private val hdResolution: Size = Size(1280, 720)
    private var selectedResolution: Size = fhdResolution


    private var baseTime:Long = 0L
    private var jobRecordTime: Job? =null
    private var availableRecordSeconds:Long =0L

    private var camera: Camera?=null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture? = null

    private var triggerStart = false

    // Selector showing which camera is selected (front or back)
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    private var currentRecordedFile: File?=null


    // Selector showing is recording currently active
    private var isRecording = false

    private var isEnableModeSelectButton = false


    private var selectedBitRate:Int by Delegates.observable(fhdBitrate){ _, _, newValue ->
        // save sharedpreferences camera bitrate
        context.getSharedPreferences(CameraSettingDir, 0).edit().putInt(
                CameraBitrate, newValue).apply()
    }

    private val zoomNumberTexts = ArrayList<TextView>()

    private var zoomLevel:Int by Delegates.observable(1){ _, oldValue, newValue->
        if(oldValue>0)
            zoomNumberTexts[oldValue-1].setTextColor(Color.WHITE)
        if(newValue>0)
            zoomNumberTexts[newValue-1].setTextColor(context.getColor(R.color.text_yellow))

        if(camera == null){
            Log.d("CameraFragment", "camera is null")
        }

        when(newValue){
            2->{
                camera?.cameraControl?.setZoomRatio(1.7f)
            }
            3->{
                camera?.cameraControl?.setZoomRatio(2.3f)
            }
            4->{
                camera?.cameraControl?.setZoomRatio(2.9f)
            }
            5->{
                camera?.cameraControl?.setZoomRatio(3.2f)
            }
            else->{ // == 1
                camera?.cameraControl?.setZoomRatio(1f)
                //camera?.cameraControl?.setLinearZoom(100f/100f)

            }
        }
    }


    // Callback Listeners
    var callbackOnClickBack:(()->Unit)? = null
    var callbackCompleteSaveVideo:((uri: String)->Unit)? = null
    var callbackCanceledSaveVideo:(()->Unit)? = null
    var callbackOnClickPhoto:(()->Unit)? =null


    // Setting Values
    fun setSaveDir(filePath: String?){
        if(filePath.isNullOrEmpty()){
            l.e("setSaveDir is null or empty --> default save dir : $defaultFileSaveDir")
            return
        }
        fileDir = filePath
    }


    // 사진, 비디오 전환 버튼 숨김
    fun hideModeSelectButton(){
        binding.llModeSelect.visibility = View.GONE
        isEnableModeSelectButton = false
    }

    // 사진, 비디오 전환 버튼 표시
    fun showModeSelectButton(){
        binding.llModeSelect.visibility = View.VISIBLE
        isEnableModeSelectButton = true
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume(){
        l.d("onResume")
        if(context is AppCompatActivity)
            (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.ibRecordeStart.requestFocus()

        if(triggerStart){
            startCamera()
        }

        zoomLevel = 1
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause(){
        l.d("onPause")

        if(context is AppCompatActivity)
            (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stopRecordTime()

        cameraProvider?.unbindAll()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy(){
        Log.e("CameraXPhoto","onDestroy")
        defaultScope.cancel()
        //displayManager.unregisterDisplayListener(displayListener)
        (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    constructor(context: Context):super(context){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet):super(context, attrs){
        initView()
    }

    constructor(context: Context, attrs: AttributeSet, defSty: Int):super(context, attrs, defSty){
        initView()
    }

    @SuppressLint("UseCompatLoadingForDrawables", "RestrictedApi")
    private fun initView(){
        //카메라가 사용되는 액티비티의 라이프사이클 수명주기 일치시킴
        if(context is AppCompatActivity){
            (context as AppCompatActivity).lifecycle.addObserver(this)
        }else{
            l.e("This camera1 library only works in activities.")
            return
        }

        addView(binding.root)

        (context as AppCompatActivity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoFileSaveDialog = VideoFileSaveDialog(context)

        binding.run {
            tvBack.setOnClickListener {
                callbackOnClickBack?.let{ callback->
                    callback()
                }
            }

            tvPhoto.setOnClickListener {
                callbackOnClickPhoto?.let{ callback->
                    callback()
                }
            }

            ibRecordeStart.setOnClickListener {
                l.d("onclick record start")
                hideButtonOnRecording()
                ibRecordeStart.visibility = View.GONE
                ibRecordeStop.visibility = View.VISIBLE
                startRecordTime()
                recordVideo()
            }

            ibRecordeStop.setOnClickListener {
                showButtonOnReady()
                isRecording = false
                //animateRecord.cancel()
                ibRecordeStop.visibility = View.GONE
                ibRecordeStart.visibility = View.VISIBLE
                stopRecordTime()
                videoCapture?.stopRecording()
            }


            tvFHD.setOnClickListener {
                if(isRecording)
                    return@setOnClickListener
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                selectedBitRate = fhdBitrate
                selectedResolution = fhdResolution
                startCamera()
            }

            tvHD.setOnClickListener {
                if(isRecording)
                    return@setOnClickListener
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                selectedBitRate = hdBitrate
                selectedResolution = hdResolution
                startCamera()
            }

            selectedBitRate = context.getSharedPreferences(CameraSettingDir, 0).getInt(
                    CameraBitrate, fhdBitrate)


            if(selectedBitRate == fhdBitrate){
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                selectedResolution = fhdResolution
            }else{
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                selectedResolution = hdResolution
            }

            llRecordTime.visibility = View.GONE

            zoomNumberTexts.addAll(
                    llZoomLevelNumbers.children.toList().filterIsInstance<TextView>()
            )

            addCommand(resources.getString(R.string.zoom) + " 1"){
                zoomLevel = 1
            }
            addCommand(resources.getString(R.string.zoom) + " 2"){
                zoomLevel = 2
            }
            addCommand(resources.getString(R.string.zoom) + " 3"){
                zoomLevel = 3
            }
            addCommand(resources.getString(R.string.zoom) + " 4"){
                zoomLevel = 4
            }
            addCommand(resources.getString(R.string.zoom) + " 5"){
                zoomLevel = 5
            }


            // for test
            if(BuildConfig.DEBUG){
                for(i in zoomNumberTexts.indices){
                    zoomNumberTexts[i].setOnClickListener {
                        zoomLevel = i+1
                    }
                }
            }


        }

    }


    fun start(){
        l.d("start camera")
        triggerStart = true
        startCamera()
    }


    @SuppressLint("UseCompatLoadingForDrawables")
    private fun hideButtonOnRecording(){
        binding.run {
            //llAvailableTime.visibility = View.GONE
            if(selectedBitRate == fhdBitrate){
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                tvHD.visibility = View.GONE
            }else{
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round, null)
                tvFHD.visibility = View.GONE
            }
            llBack.visibility = View.GONE
            llModeSelect.visibility = View.GONE
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun showButtonOnReady(){
        binding.run {
            //llAvailableTime.visibility = View.VISIBLE
            if(selectedBitRate == fhdBitrate){
                tvFHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvHD.visibility = View.VISIBLE
            }else{
                tvHD.background = resources.getDrawable(R.drawable.btn_bg_round_line, null)
                tvFHD.visibility = View.VISIBLE
            }
            llBack.visibility = View.VISIBLE
            if(isEnableModeSelectButton){
                llModeSelect.visibility = View.VISIBLE
            }

        }
    }


    @SuppressLint("RestrictedApi")
    private fun startRecordTime(){
        getCurrentRemainRecordTime()
        if(availableRecordSeconds <= 0){
            toast(resources.getString(R.string.not_enough_storage))
            return
        }

        l.d("before jobRecordtime")

        baseTime = System.currentTimeMillis()
        binding.llRecordTime.visibility = View.VISIBLE
        jobRecordTime?.cancel()

        jobRecordTime = defaultScope.launch {
            l.d("start start recording time")
            repeat(availableRecordSeconds.toInt()){
                val pastTime = (System.currentTimeMillis() - baseTime)/1000
                withContext(Dispatchers.Main){
                    binding.tvAvailableTimeBottom.text = "/ ${convertPastTimeMillsToHHMMSSColon(availableRecordSeconds)}"
                    binding.tvRecordTime.text = convertPastTimeMillsToHHMMSSColon(pastTime)
                }
                if(pastTime >= availableRecordSeconds){
                    l.d("pastTime >= availableRecordSeconds")
                    withContext(Dispatchers.Main){
                        l.d("stop recording process")
                        showButtonOnReady()
                        isRecording = false
                        //animateRecord.cancel()
                        binding.ibRecordeStop.visibility = View.GONE
                        binding.ibRecordeStart.visibility = View.VISIBLE
                        videoCapture?.stopRecording()
                        stopRecordTime()
                    }
                }

                delay(1000)
            }
            withContext(Dispatchers.Main){
                l.d("exit repeat and stop recording")
                showButtonOnReady()
                isRecording = false
                //animateRecord.cancel()
                binding.ibRecordeStop.visibility = View.GONE
                binding.ibRecordeStart.visibility = View.VISIBLE
                videoCapture?.stopRecording()
                stopRecordTime()
            }
        }
    }


    private fun stopRecordTime(){
        jobRecordTime?.cancel()
        binding.llRecordTime.visibility = View.GONE
        binding.ibRecordeStop.visibility = View.GONE
        binding.ibRecordeStart.visibility = View.VISIBLE
    }


    private fun convertPastTimeMillsToHHMMSSColon(pastTime:Long):String{
        var minutes = 0
        var seconds = 0

        if(pastTime == 3600L){
            return "60:00"
        }

        minutes = (pastTime / 60).toInt()
        seconds = (pastTime % 60).toInt()
        minutes %= 60




        var availableRecordTime = ""

        availableRecordTime += if(minutes < 10){
            "0${minutes}:"
        }else{
            "${minutes}:"
        }

        availableRecordTime += if(seconds<10){
            "0${seconds}"
        }else{
            "${seconds}"
        }

        return availableRecordTime
    }



    private fun convertPastTimeMillsToHHMMSS(pastTime:Long):String{
        var hours = 0
        var minutes = 0
        var seconds = 0

        minutes = (pastTime / 60).toInt()
        hours = minutes / 60
        seconds = (pastTime % 60).toInt()
        minutes %= 60

        var availableRecordTime = ""

        if(hours > 0){
            availableRecordTime += if(hours < 10)
                "0${hours}:"
            else
                "${hours}:"

        }


        availableRecordTime += if(minutes < 10){
            "0${minutes}:"
        }else{
            "${minutes}:"
        }

        availableRecordTime += if(seconds<10){
            "0${seconds}"
        }else{
            "${seconds}"
        }

        return availableRecordTime
    }


    private fun getCurrentRemainRecordTime():String {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.path)
        val blockSize = stat.blockSizeLong;
        val availableBlocks = stat.availableBlocksLong;

        val availableMemory = availableBlocks * blockSize - 1073741824  //- 1073741824 // -1gb ( 1024 * 1024 * 1024 ) 안드로이드 여유 내부 용량 확보 - 다른앱 고려

        l.d("available Memory : $availableMemory")

        //fhd일때 초당 620000 byte, hd일때 초당 380000
        availableRecordSeconds = if(selectedBitRate == fhdBitrate){
            availableMemory / 620000
        }else{
            availableMemory / 380000
        }

        l.d("available record seconds at getCurrentRemainRecordTime : $availableRecordSeconds")

        if(availableRecordSeconds < 0){
            availableRecordSeconds = 0
        }

        if(availableRecordSeconds > 3600){
            availableRecordSeconds = 3600
        }

//        if(availableRecordSeconds > 120){
//            availableRecordSeconds = 120
//        }



        //return resources.getString(R.string.available_record_time) + " : " + convertPastTimeMillsToHHMMSS(availableRecordSeconds)
        return convertPastTimeMillsToHHMMSS(availableRecordSeconds)
        //return formatMemorySize(availableBlocks * blockSize);
    }


    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    @SuppressLint("RestrictedApi")
    private fun startCamera() {
        zoomLevel = 1
        // This is the Texture View where the camera will be rendered
        val viewFinder = binding.viewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // The display information
            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
            // The ratio for the output image and preview
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            // The display rotation
            val rotation = viewFinder.display.rotation

            val localCameraProvider = cameraProvider
                    ?: throw IllegalStateException("Camera initialization failed.")

            // The Configuration of camera preview
            preview = Preview.Builder()
                    //.setTargetAspectRatio(aspectRatio) // set the camera aspect ratio
                    .setTargetRotation(rotation) // set the camera rotation
                    .setTargetResolution(selectedResolution)
                    //.setTargetResolution(Size(1280, 720))
                    .build()

            val videoCaptureConfig =
                    VideoCapture.DEFAULT_CONFIG.config // default config for video capture
            // The Configuration of video capture
            videoCapture = VideoCapture.Builder
                    .fromConfig(videoCaptureConfig)
                    .setBitRate(selectedBitRate)
                    .setDefaultResolution(selectedResolution)
                    //.setTargetResolution(Size(1280, 720))
                    .setMaxResolution(selectedResolution)
                    .build()

            localCameraProvider.unbindAll() // unbind the use-cases before rebinding them

            try {
                // Bind all use cases to the camera with lifecycle
                camera = localCameraProvider.bindToLifecycle(
                        context as AppCompatActivity, // current lifecycle owner
                        lensFacing, // either front or back facing
                        preview, // camera preview use case
                        videoCapture, // video capture use case
                )

                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(viewFinder.surfaceProvider)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     *  Detecting the most suitable aspect ratio for current dimensions
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    private val outputDirectory:String by lazy {
        if(fileDir.isEmpty()){
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
                fileDir = defaultFileSaveDirOn11
            }else{
                fileDir = defaultFileSaveDir
            }
        }
        fileDir
    }


    @SuppressLint("SimpleDateFormat")
    fun getNowDate(): String {
        val now = System.currentTimeMillis()
        val date = Date(now)
        val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS")
        return sdf.format(date)
    }


    @SuppressLint("RestrictedApi")
    private fun recordVideo() {
        val localVideoCapture = videoCapture ?: throw IllegalStateException("Camera initialization failed.")


        fileName = getNowDate()

        File(outputDirectory).mkdirs()
        currentRecordedFile = File("$outputDirectory$fileName.mp4")
        val outputOptions = VideoCapture.OutputFileOptions.Builder(currentRecordedFile!!).build()





        // Options fot the output video file
//        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            val contentValues = ContentValues().apply {
//                put(MediaStore.MediaColumns.DISPLAY_NAME, getNowDate())
//                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
//                put(MediaStore.MediaColumns.RELATIVE_PATH, outputDirectory)
//            }
//
//            contentResolver.run {
//                val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
//
//                VideoCapture.OutputFileOptions.Builder(this, contentUri, contentValues)
//            }
//        } else {
//            File(outputDirectory).mkdirs()
//            currentRecordedFile = File("$outputDirectory${getNowDate()}.mp4")
//
//            VideoCapture.OutputFileOptions.Builder(currentRecordedFile!!)
//        }.build()

        if (!isRecording) {
            //animateRecord.start()
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                checkPermissionForRecordAudio()
                return
            }
            localVideoCapture.startRecording(
                    outputOptions, // the options needed for the final video
                    ContextCompat.getMainExecutor(context), // the executor, on which the task will run
                    object : VideoCapture.OnVideoSavedCallback { // the callback after recording a video
                        override fun onVideoSaved(outputFileResults: VideoCapture.OutputFileResults) {
                            // Create small preview
//                        outputFileResults.savedUri?.path?.let {
//                            refreshGallery(File(it))
//                        }
                            //animateRecord.cancel()
                            showButtonOnReady()
                            stopRecordTime()
                            currentRecordedFile?.let{
                                videoFileSaveDialog?.showDialog(it.path){ isSelectSave ->
                                    if(!isSelectSave){
                                        if(it.exists()){
                                            if(it.delete())
                                                Log.d("onVideoSaved :","delete file : $it")
                                            else
                                                Log.d("onVideoSaved :","delete fail")
                                            startCamera()
                                            refreshGallery(it)
                                            callbackCanceledSaveVideo?.let{ callback->
                                                callback()
                                            }
                                        }
                                    }else{
                                        startCamera()
                                        refreshGallery(it)
                                        callbackCompleteSaveVideo?.let { callback->
                                            callback(it.path)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onError(
                                videoCaptureError: Int,
                                message: String,
                                cause: Throwable?
                        ) {
                            stopRecordTime()
                            // This function is called if there is an error during recording process
                            //animateRecord.cancel()
                            val msg = "Video capture failed: $message"
                            toast(msg)
                            cause?.printStackTrace()
                        }
                    })
        } else {
            //animateRecord.cancel()
            localVideoCapture.stopRecording()
        }
        isRecording = !isRecording
    }


    private fun checkPermissionForRecordAudio(){
        TedPermission.with(context).setPermissionListener(permissionListener)
                .setPermissions(Manifest.permission.RECORD_AUDIO).check()
    }

    private var permissionListener: PermissionListener = object : PermissionListener {
        override fun onPermissionGranted() {
            l.d("permission granted")
        }

        override fun onPermissionDenied(deniedPermissions: java.util.ArrayList<String?>?) {
            l.d("permission denied")
            toast("오디오 녹음 권한이 허용되지 않으면 비디오를 실행할 수 없습니다.")
        }
    }




    private fun refreshGallery(file: File) {
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(file)
        context.sendBroadcast(mediaScanIntent)
    }

    private fun addCommand(commandText: String, onClickCallback: () -> Unit){
        val textView = TextView(context)
        textView.text = commandText
        textView.contentDescription = "hf_no_number"
        val lp = LinearLayout.LayoutParams(1, 1)
        textView.layoutParams = lp
        textView.setOnClickListener {
            onClickCallback()
        }

        binding.llCommand.addView(textView)
    }



}