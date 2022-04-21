package com.watt.testcameraxmodule

import android.Manifest
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.watt.camerax.l
import com.watt.testcameraxmodule.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private val binding:ActivityMainBinding by lazy{ ActivityMainBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        fullScreenMode()
        checkPermissions()

    }


    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_MEDIA_LOCATION
                )
                .check()
        }else{
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA
                )
                .check()
        }
    }


    private fun fullScreenMode(){
        supportActionBar?.hide()
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }


    private var permissionListener: PermissionListener = object : PermissionListener {
        override fun onPermissionGranted() {
            Log.d("MainActivity", "onPermissionGranted:::::::: ")
//            binding.videoView.setSaveDir("${Environment.getExternalStorageDirectory().absolutePath}/CameraXVideo/")
//            binding.videoView.showModeSelectButton()
//            binding.videoView.start()

            //binding.photoView.setSaveDir("${Environment.getExternalStorageDirectory().absolutePath}/CameraXPhoto/")
            binding.photoView.showModeSelectButton()

            binding.photoView.callbackCompleteSavePhoto = {
                l.d("complete save photo uri:$it")
            }

            binding.photoView.callbackOnClickBack = {
                l.d("onclick back")
            }

            binding.photoView.callbackCanceledSavePhoto = {
                l.d("canceled save photo")
            }

            binding.photoView.callbackOnClickVideo = {
                l.d("onclick video")
            }
            binding.photoView.start()
        }

        override fun onPermissionDenied(deniedPermissions: ArrayList<String?>?) {
            Log.d("MainActivity", "onPermissionDenied:::::::: ")
            Toast.makeText(this@MainActivity, "권한이 허용되지 않으면 앱을 실행 할 수 없습니다.", Toast.LENGTH_SHORT)
                .show()
            finish()
        }
    }
}