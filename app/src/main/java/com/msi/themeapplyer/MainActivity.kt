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
import androidx.core.content.FileProvider
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
    private var savedMtzFile: File? = null

    private val pickMtzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedMtzUri = uri
            val fileName = getFileNameFromUri(uri)
            if (fileName.endsWith(".mtz", ignoreCase = true)) {
                selectedFileName = fileName
                tvSelectedFile.text = "Selected: $selectedFileName"
                btnApplyTheme.isEnabled = true
                tvStatus.text = "Status: Ready to process theme!"
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
            tvStatus.text = "Status: Saving .mtz file to MIUI/theme..."

            // 1. Save .mtz file to MIUI/theme directory
            val miuiThemeDir = File(Environment.getExternalStorageDirectory(), "MIUI/theme")
            if (!miuiThemeDir.exists()) {
                miuiThemeDir.mkdirs()
            }
            savedMtzFile = File(miuiThemeDir, fileName)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(savedMtzFile!!).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // 2. Extract contents in cache to look for Wallpapers as backup
            val extractDir = File(cacheDir, "extracted_theme")
            if (extractDir.exists()) extractDir.deleteRecursively()
            extractDir.mkdirs()
            unzipMtz(savedMtzFile!!, extractDir)

            // 3. Try to apply wallpaper as a secondary feature
            applyExtractedWallpaper(extractDir)

            // 4. Try launching MIUI Theme Manager / Send file to Theme app
            openInThemeManager(savedMtzFile!!)

            tvStatus.text = "Status: Theme saved! Opening Theme Manager..."
            Toast.makeText(this, "Theme imported successfully!", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            tvStatus.text = "Error: ${e.message}"
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInThemeManager(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file),
                    "application/zip" // MIUI themes are zip/mtz formats
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // Try launching chooser so user can pick Xiaomi Theme App or File Manager
            val chooser = Intent.createChooser(intent, "Open Theme with")
            startActivity(chooser)
        } catch (e: Exception) {
            // Fallback if fileprovider fails
            Toast.makeText(this, "Saved in MIUI/theme folder. Open Xiaomi Themes app to apply.", Toast.LENGTH_LONG).show()
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

    private fun applyExtractedWallpaper(extractDir: File) {
        try {
            var wallpaperFile: File? = null
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
