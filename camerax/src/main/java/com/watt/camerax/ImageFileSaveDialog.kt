package com.watt.camerax

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import com.watt.camerax.databinding.DialogImageFileSaveBinding

class ImageFileSaveDialog(context: Context, private val bitmap: Bitmap, private val callback:(Int)->Unit) : Dialog(context,
    R.style.DialogCustomTheme
) {

    private val binding = DialogImageFileSaveBinding.inflate(layoutInflater)

    init {
        setContentView(binding.root)
        binding.ivSaveImg.setImageBitmap(bitmap)
        binding.infoSaveBtn.setOnClickListener {
            callback(0)
            dismiss()
        }
        binding.infoCancelBtn.setOnClickListener {
            callback(1)
            dismiss()
        }
        show()
    }

}