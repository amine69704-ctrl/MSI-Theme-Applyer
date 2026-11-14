package com.msi.themeapplyer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.InputStream
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var btnGrantPermission: Button
    private lateinit var btnSelectMtz: Button
    private lateinit var btnApplyTheme: Button
    private lateinit var tvSelectedFile: TextView
    private lateinit var tvStatus: TextView

    private var selectedMtzUri: Uri? = null
    private var selectedFileName: String = "custom_theme.mtz"

    // File Picker Launcher
    private val pickMtzLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            selectedMtzUri = uri
            val fileName = getFileNameFromUri(uri)
            if (fileName.endsWith(".mtz", ignoreCase = true)) {
                selectedFileName = fileName
                tvSelectedFile.text = "Selected: $selectedFileName"
                btnApplyTheme.isEnabled = true
                tvStatus.text = "Status: Ready to apply!"
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
                // Open file manager to pick .mtz
                pickMtzLauncher.launch(arrayOf("*/*"))
            } else {
                Toast.makeText(this, "Grant Storage Permission First!", Toast.LENGTH_SHORT).show()
                requestStoragePermission()
            }
        }

        btnApplyTheme.setOnClickListener {
            selectedMtzUri?.let { uri ->
                importAndApplyMtz(uri, selectedFileName)
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

    private fun importAndApplyMtz(uri: Uri, fileName: String) {
        try {
            tvStatus.text = "Status: Extracting & Importing Theme..."

            // 1. Copy .mtz to MIUI/theme directory
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

            // 2. Also extract internal components (icons, wallpaper, lockscreen) to cache for preview/direct apply
            val extractDir = File(cacheDir, "extracted_theme")
            if (extractDir.exists()) extractDir.deleteRecursively()
            extractDir.mkdirs()
            unzipMtz(destFile, extractDir)

            tvStatus.text = "Status: Imported to ${destFile.absolutePath}\nOpening Theme Manager..."
            Toast.makeText(this, "Theme Imported! Select it in Theme Manager to Apply.", Toast.LENGTH_LONG).show()

            // 3. Launch MIUI Theme Manager directly to Local Themes section
            openMiuiThemeManager(destFile)

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

    private fun openMiuiThemeManager(mtzFile: File) {
        try {
            // Try opening MIUI Theme Manager directly with the .mtz file
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(mtzFile), "application/mtz")
                component = ComponentName(
                    "com.android.thememanager",
                    "com.android.thememanager.ApplyThemeForScreenshot"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: Open main MIUI Theme Manager app
            val fallbackIntent = packageManager.getLaunchIntentForPackage("com.android.thememanager")
            if (fallbackIntent != null) {
                startActivity(fallbackIntent)
            } else {
                tvStatus.text = "Status: Theme saved to MIUI/theme/. MIUI Theme Manager not found on this device."
            }
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
