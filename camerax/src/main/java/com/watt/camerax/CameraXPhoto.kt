package com.watt.camerax

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.watt.camerax.databinding.CameraxPhotoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import splitties.systemservices.layoutInflater
import splitties.toast.toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

/**
 * Created by khm on 2021-10-26.
 */

class CameraXPhoto : ConstraintLayout, LifecycleObserver {
    private val binding:CameraxPhotoBinding by lazy { CameraxPhotoBinding.inflate(layoutInflater)}
    private val defaultScope = CoroutineScope(Dispatchers.Default)

    private val CameraSettingDir = "CameraSettingSharedPreference"
    private val CameraBitrate = "CameraSettingBitrate"
    private val CameraMenuHide = "CameraSettingMenuHide"

    private val defaultFileSaveDir = "${Environment.getExternalStorageDirectory().absolutePath}/Camera1/"
    private val defaultFileSaveDirOn11 = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.absolutePath
    private var fileName = ""
    private var fileDir:String = ""

    // An instance for display manager to get display change callbacks
    //private val displayManager by lazy { context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    private var camera: Camera?=null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    private var imageFileSaveDialog: ImageFileSaveDialog?=null

    private var displayId = -1

    private val RATIO_4_3_VALUE = 4.0 / 3.0 // aspect ratio 4x3
    private val RATIO_16_9_VALUE = 16.0 / 9.0 // aspect ratio 16x9

    // Selector showing which camera is selected (front or back)
    private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA

    private var torchMode: Boolean by Delegates.observable(false) { _, _, newValue ->
        camera?.cameraControl?.enableTorch(newValue)
        binding.tvFlashStatus.text = when(newValue){
            true-> resources.getString(R.string.flash_on)
            false-> resources.getString(R.string.flash_off)
        }
    }

    private var hideMenu:Boolean by Delegates.observable(false){ _, _, newValue ->
        if(newValue)
            hideMenu()
        else
            showMenu()

        context.getSharedPreferences(CameraSettingDir, 0).edit().putBoolean(
            CameraMenuHide, newValue).apply()
    }


    private var zoomLevel:Int by Delegates.observable(1){ _,oldValue,newValue->
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


    private val zoomNumberTexts = ArrayList<TextView>()

    private var currentCaptureFile: File?=null


    /**
     * A display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
//    private val displayListener = object : DisplayManager.DisplayListener {
//        override fun onDisplayAdded(displayId: Int) = Unit
//        override fun onDisplayRemoved(displayId: Int) = Unit
//
//        @SuppressLint("UnsafeExperimentalUsageError")
//        override fun onDisplayChanged(displayId: Int){
//            l.d("onDisplayChanged displayId:$displayId, this@CameraXPhoto.displayId:${this@CameraXPhoto.displayId}")
//            if (displayId == this@CameraXPhoto.displayId) {
//                preview?.targetRotation = displayManager.getDisplay(displayId).rotation
//                imageCapture?.targetRotation = binding.viewFinder.display.rotation
//            }
//        }
//    }






    // Callback Listeners
    var callbackOnClickBack:(()->Unit)? = null
    var callbackCompleteSavePhoto:((uri: String)->Unit)? = null
    var callbackCanceledSavePhoto:(()->Unit)? = null
    var callbackOnClickVideo:(()->Unit)? =null


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
    }

    // 사진, 비디오 전환 버튼 표시
    fun showModeSelectButton(){
        binding.llModeSelect.visibility = View.VISIBLE
    }



    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume(){
        l.d("onResume")

        if(context is AppCompatActivity)
            (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        zoomLevel = 1


    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause(){
        l.d("onPause")
        defaultScope.cancel()
        if(context is AppCompatActivity)
            (context as AppCompatActivity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy(){
        Log.e("CameraXPhoto","onDestroy")
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

        //displayManager.registerDisplayListener(displayListener, null)



        binding.run{
//            viewFinder.addOnAttachStateChangeListener(object :
//                View.OnAttachStateChangeListener {
//                override fun onViewDetachedFromWindow(v: View) =
//                    displayManager.registerDisplayListener(displayListener, null)
//
//                override fun onViewAttachedToWindow(v: View) =
//                    displayManager.unregisterDisplayListener(displayListener)
//            })


            tvFlash.setOnClickListener {
                torchMode = !torchMode
            }

            hideMenu = context.getSharedPreferences(CameraSettingDir, 0).getBoolean(CameraMenuHide, false)

            tvMenu.setOnClickListener {
                hideMenu = !hideMenu
            }

            tvBack.setOnClickListener {
                callbackOnClickBack?.let{ callback->
                    callback()
                }
            }

            tvVideo.setOnClickListener {
                callbackOnClickVideo?.let{ callback->
                    callback()
                }
            }



            val meteringMode = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AWB

            tvFocus.setOnClickListener {
                val autoFocusPointCenter = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.5f, .5f)
                try {
                    val autoFocusAction = FocusMeteringAction.Builder(
                        autoFocusPointCenter,
                        meteringMode
                    ).apply {
                            //start auto-focusing after 2 seconds
                            disableAutoCancel()
                        }.build()
                    val focusListenableFuture = camera?.cameraControl?.startFocusAndMetering(autoFocusAction)
                    focusListenableFuture?.addListener( {
                        val result = focusListenableFuture.get()
                        val isSuccessful = result.isFocusSuccessful
                        l.d("focus listener isSuccessful : $isSuccessful")
                        if(isSuccessful){
                            l.d("success focus")
                        }else{
                            toast("Auto focus is failed")
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: CameraInfoUnavailableException) {
                    Log.d("ERROR", "cannot access camera", e)
                }
            }


            tvCaptureBtn.setOnClickListener {
                imageCapture?.targetRotation = binding.viewFinder.display.rotation
                val autoFocusPointCenter = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.5f, .5f)
                val autoFocusPointCenterRight = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.55f, .5f)
                val autoFocusPointCenterLeft = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.45f, .5f)
                val autoFocusPointTopRight = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.55f, .45f)
                val autoFocusPointTopLeft = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.45f, .45f)
                val autoFocusPointBottomRight = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.55f, .55f)
                val autoFocusPointBottomLeft = SurfaceOrientedMeteringPointFactory(1f, 1f)
                    .createPoint(.45f, .55f)

                try {
                    val autoFocusAction = FocusMeteringAction.Builder(
                        autoFocusPointCenter,
                        meteringMode
                    )
                        .addPoint(autoFocusPointCenterLeft, meteringMode)
                        .addPoint(autoFocusPointCenterRight, meteringMode)
                        .addPoint(autoFocusPointTopRight, meteringMode)
                        .addPoint(autoFocusPointTopLeft, meteringMode)
                        .addPoint(autoFocusPointBottomRight, meteringMode)
                        .addPoint(autoFocusPointBottomLeft, meteringMode)
                        .apply {
                            //start auto-focusing after 2 seconds
                            setAutoCancelDuration(3, TimeUnit.SECONDS)
                        }.build()
                    val focusListenableFuture = camera?.cameraControl?.startFocusAndMetering(autoFocusAction)
                    focusListenableFuture?.addListener( {
                        val result = focusListenableFuture.get()
                        val isSuccessful = result.isFocusSuccessful
                        l.d("focus listener isSuccessful : $isSuccessful")
                        if(isSuccessful){
                            captureImage()
                        }else{
                            toast("Auto focus is failed")
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: CameraInfoUnavailableException) {
                    Log.d("ERROR", "cannot access camera", e)
                }
            }


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
        startCamera()
    }


    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    @SuppressLint("UnsafeExperimentalUsageError")
    private fun startCamera() {
        // This is the CameraX PreviewView where the camera will be rendered
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
                .setTargetResolution(Size(3840, 2160))
                //.setTargetAspectRatio(aspectRatio) // set the camera aspect ratio
                .setTargetRotation(rotation) // set the camera rotation
                .build()

            l.d("view finder rotation : $rotation")

            // The Configuration of image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // setting to have pictures with highest quality possible (may be slow)
                .setTargetAspectRatio(aspectRatio) // set the capture aspect ratio
                .setTargetRotation(rotation) // set the capture rotation
                .build()




            localCameraProvider.unbindAll() // unbind the use-cases before rebinding them

            try {
                // Bind all use cases to the camera with lifecycle
                camera = localCameraProvider.bindToLifecycle(
                    context as AppCompatActivity, // current lifecycle owner
                    lensFacing, // either front or back facing
                    preview, // camera preview use case
                    imageCapture, // image capture use case
                )




                // Attach the viewfinder's surface provider to preview use case
                preview?.setSurfaceProvider(viewFinder.surfaceProvider)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(context))
    }





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

        val dir = File(fileDir)
        if(!dir.exists())
            dir.mkdirs()
        fileDir
    }


    private fun showSaveImageDialog(bitmap: Bitmap, isSelectSave:(Boolean)->Unit){
        if(imageFileSaveDialog ==null){
            imageFileSaveDialog = ImageFileSaveDialog(context, bitmap){
                when(it){
                    0 -> { // save
                        isSelectSave(true)
                    }
                    1 -> { // cancel
                        isSelectSave(false)

                        callbackCanceledSavePhoto?.let { callback ->
                            callback()
                        }
                    }
                }
            }
        }

        imageFileSaveDialog?.setOnDismissListener {
            imageFileSaveDialog = null
        }

        imageFileSaveDialog?.let{
            if(!it.isShowing)
                it.show()
        }
    }


    @SuppressLint("SimpleDateFormat")
    fun getNowDate(): String? {
        val now = System.currentTimeMillis()
        val date = Date(now)
        val sdf = SimpleDateFormat("yyyyMMddHHmmss")
        return sdf.format(date)
    }



    private fun captureImage() {
        l.d("captureImage into")
        val localImageCapture = imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        // Setup image capture metadata
        val metadata = ImageCapture.Metadata().apply {
            // Mirror image when using the front camera
            isReversedHorizontal = lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA
        }


        File(outputDirectory).mkdirs()
        currentCaptureFile = File(outputDirectory, "${getNowDate()}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(currentCaptureFile!!).setMetadata(metadata).build()





        //File(outputDirectory).mkdirs()
//        currentCaptureFile = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
//        val outputOptions = ImageCapture.OutputFileOptions.Builder(currentCaptureFile!!).setMetadata(metadata).build()


        localImageCapture.takePicture(
            outputOptions, // the options needed for the final image
            ContextCompat.getMainExecutor(context), // the executor, on which the task will run
            object : ImageCapture.OnImageSavedCallback { // the callback, about the result of capture process
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // This function is called if capture is successfully completed
                    var bitmap: Bitmap?=null
                    currentCaptureFile?.let{
                        try{
                            val rotation = (context as AppCompatActivity).windowManager.defaultDisplay.rotation
                            l.d("rotation : $rotation")
                            var degrees = 0
                            when (rotation) {
                                Surface.ROTATION_0 -> degrees = 0
                                Surface.ROTATION_90 -> degrees = 90
                                Surface.ROTATION_180 -> degrees = 180
                                Surface.ROTATION_270 -> degrees = 270
                            }


                            bitmap = BitmapFactory.decodeFile(it.path)
                            l.d("bitmap width:${bitmap?.width}, height:${bitmap?.height}")
                            bitmap?.let{ nonNullBitmap ->
                                showSaveImageDialog(nonNullBitmap){ isSelectedSave->
                                    if(isSelectedSave){
                                        callbackCompleteSavePhoto?.let{ callback->
                                            callback(it.path)
                                        }
                                    }else{
                                        if(it.exists()){
                                            if(it.delete())
                                                Log.d("onImageSaved :","delete file : $it")
                                            else
                                                Log.d("onImageSaved :","delete fail")
                                        }
                                        callbackCanceledSavePhoto?.let{ callback->
                                            callback()
                                        }
                                    }
                                    refreshGallery(it)
                                }
                            }
                        }catch (e: IOException){
                            e.printStackTrace()
                        }catch (e:NullPointerException){
                            e.printStackTrace()
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    // This function is called if there is an errors during capture process
                    val msg = "Photo capture failed: ${exception.message}"
                    Log.e("onError",msg)
                    toast(msg)
                    exception.printStackTrace()
                }
            }
        )
    }

    private fun refreshGallery(file: File) {
        val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(file)
        context.sendBroadcast(mediaScanIntent)
    }



    private fun showMenu() {
        binding.run {
            llGoBack.visibility = View.VISIBLE
            llZoom.visibility = View.VISIBLE
            tvFocus.visibility = View.VISIBLE
            llFlash.visibility = View.VISIBLE
            tvMenu.text = resources.getString(R.string.hide_menu)
        }
    }

    private fun hideMenu() {
        binding.run {
            llGoBack.visibility = View.GONE
            llZoom.visibility = View.GONE
                tvFocus.visibility = View.GONE
            llFlash.visibility = View.GONE
            tvMenu.text = resources.getString(R.string.show_menu)
        }
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