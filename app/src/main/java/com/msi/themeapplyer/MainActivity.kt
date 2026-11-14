package com.msi.themeapplyer

import android.Manifest
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnGrantPermission: Button
    private lateinit var btnSelectMtz: Button
    private lateinit var btnApplyTheme: Button
    private lateinit var tvSelectedFile: TextView
    private lateinit var tvStatus: TextView

    private var selectedMtzUri: Uri? = null
    private var selectedFileName: String = "custom_theme.mtz"

    private val pickMtzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedMtzUri = uri
            val fileName = getFileNameFromUri(uri)
            if (fileName.endsWith(".mtz", ignoreCase = true)) {
                selectedFileName = fileName
                tvSelectedFile.text = "Selected: $selectedFileName"
                btnApplyTheme.isEnabled = true
                tvStatus.text = "Status: Ready to apply theme contents!"
            } else {
                Toast.makeText(this, "Please select a valid .mtz file!", Toast.LENGTH_SHORT).show()
                btnApplyTheme.isEnabled = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnGrantPermission = findViewById(R.id.btnGrantPermission)
        btnSelectMtz = findViewById(R.id.btnSelectMtz)
        btnApplyTheme = findViewById(R.id.btnApplyTheme)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        tvStatus = findViewById(R.id.tvStatus)

        btnGrantPermission.setOnClickListener {
            requestStoragePermission()
        }

        btnSelectMtz.setOnClickListener {
            if (hasStoragePermission()) {
                pickMtzLauncher.launch(arrayOf("*/*"))
            } else {
                Toast.makeText(this, "Grant Storage Permission First!", Toast.LENGTH_SHORT).show()
                requestStoragePermission()
            }
        }

        btnApplyTheme.setOnClickListener {
            selectedMtzUri?.let { uri ->
                processAndApplyTheme(uri, selectedFileName)
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                101
            )
        }
    }

    private fun processAndApplyTheme(uri: Uri, fileName: String) {
        try {
            tvStatus.text = "Status: Processing .mtz file..."

            // 1. Save .mtz file to MIUI/theme directory so it's stored properly
            val miuiThemeDir = File(Environment.getExternalStorageDirectory(), "MIUI/theme")
            if (!miuiThemeDir.exists()) {
                miuiThemeDir.mkdirs()
            }
            val destFile = File(miuiThemeDir, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // 2. Extract contents in cache to look for Wallpapers or Icons
            val extractDir = File(cacheDir, "extracted_theme")
            if (extractDir.exists()) extractDir.deleteRecursively()
            extractDir.mkdirs()
            unzipMtz(destFile, extractDir)

            // 3. Try to apply wallpaper directly from the extracted theme if available
            val wallpaperApplied = applyExtractedWallpaper(extractDir)

            if (wallpaperApplied) {
                tvStatus.text = "Status: Theme imported & Wallpaper applied successfully!\n(Note: Icon/System changes require MIUI Theme Manager restrictions)"
                Toast.makeText(this, "Wallpaper applied successfully!", Toast.LENGTH_LONG).show()
            } else {
                tvStatus.text = "Status: Theme saved to MIUI/theme/ successfully!"
                Toast.makeText(this, "Theme saved successfully!", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            tvStatus.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun unzipMtz(mtzFile: File, targetDirectory: File) {
        ZipInputStream(mtzFile.inputStream()).use { zis ->
            var zipEntry = zis.nextEntry
            while (zipEntry != null) {
                val newFile = File(targetDirectory, zipEntry.name)
                if (zipEntry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zipEntry = zis.nextEntry
            }
        }
    }

    private fun applyExtractedWallpaper(extractDir: File): Boolean {
        try {
            // Common paths inside .mtz files where wallpapers are stored
            val possibleWallpaperPaths = listOf(
                "wallpaper/default_wallpaper",
                "wallpaper/wallpaper.jpg",
                "wallpaper/lock_wallpaper",
                "default_lock_wallpaper"
            )

            var wallpaperFile: File? = null
            
            // Search recursively for any image file inside the extracted theme folder
            extractDir.walkTopDown().forEach { file ->
                if (file.isFile && (file.name.endsWith(".jpg") || file.name.endsWith(".png"))) {
                    if (wallpaperFile == null) {
                        wallpaperFile = file
                    }
                }
            }

            if (wallpaperFile != null && wallpaperFile!!.exists()) {
                val bitmap = BitmapFactory.decodeFile(wallpaperFile!!.absolutePath)
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                wallpaperManager.setBitmap(bitmap)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "msi_theme_${System.currentTimeMillis()}.mtz"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
