package com.instadownloader

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class SupportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)

        val toolbar = findViewById<MaterialToolbar>(R.id.supportToolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val saveBtn = findViewById<MaterialButton>(R.id.saveQrBtn)
        val qrImage = findViewById<ImageView>(R.id.qrCodeImage)

        saveBtn.setOnClickListener {
            val bitmap = (qrImage.drawable as? BitmapDrawable)?.bitmap
            if (bitmap == null) {
                Toast.makeText(this, getString(R.string.toast_qr_read_error), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveQrToGallery(bitmap)
        }
    }

    private fun saveQrToGallery(bitmap: Bitmap) {
        val filename = "donation_qr_${System.currentTimeMillis()}.jpg"

        try {
            val out: OutputStream
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Downloader")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("无法创建图片文件")
                out = contentResolver.openOutputStream(uri)!!
                out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null)
            } else {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES
                ).also { it.mkdirs() }
                out = FileOutputStream(File(dir, filename))
                out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            Toast.makeText(this, getString(R.string.toast_qr_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_qr_save_failed, e.message), Toast.LENGTH_SHORT).show()
        }
    }
}
