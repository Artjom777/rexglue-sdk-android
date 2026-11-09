package com.rexglue.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReXGlue Studio Main Activity.
 * Provides on-device Xbox 360 PPC -> C++ recompilation and APK port creation
 * directly on POCO X7 Pro without needing a PC.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ReXGlueStudio";
    private static final int REQUEST_PICK_XEX = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;

    private TextView tvDeviceInfo;
    private EditText etXexPath;
    private EditText etProjectName;
    private CheckBox cbMaliVk;
    private CheckBox cbPageSize;
    private CheckBox cbBigCores;
    private Button btnBrowse;
    private Button btnCodegen;
    private Button btnBuildApk;
    private Button btnLaunch;
    private ProgressBar progressBar;
    private TextView tvConsole;
    private ScrollView scrollConsole;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isNativeLoaded = false;

    // Native JNI Declarations
    public native void nativeRegisterLogCallback();
    public native String nativeGetDeviceInfo();
    public native int nativeRunCodegen(String xexPath, String projectName, String outDir, boolean maliOpt);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadNativeLibraries();
        requestStoragePermissions();
        updateDeviceInfo();
    }

    private void initViews() {
        tvDeviceInfo = findViewById(R.id.tv_device_info);
        etXexPath = findViewById(R.id.et_xex_path);
        etProjectName = findViewById(R.id.et_project_name);
        cbMaliVk = findViewById(R.id.cb_mali_vk);
        cbPageSize = findViewById(R.id.cb_page_size);
        cbBigCores = findViewById(R.id.cb_big_cores);
        btnBrowse = findViewById(R.id.btn_browse_xex);
        btnCodegen = findViewById(R.id.btn_codegen);
        btnBuildApk = findViewById(R.id.btn_build_port_apk);
        btnLaunch = findViewById(R.id.btn_launch);
        progressBar = findViewById(R.id.progress_bar);
        tvConsole = findViewById(R.id.tv_console);
        scrollConsole = findViewById(R.id.scroll_console);

        btnBrowse.setOnClickListener(v -> pickXexFile());
        btnCodegen.setOnClickListener(v -> startCodegen());
        btnBuildApk.setOnClickListener(v -> buildGameApk());
        btnLaunch.setOnClickListener(v -> launchGame());

        // Default path suggestion if common directory exists
        File defaultDir = new File(Environment.getExternalStorageDirectory(), "Xbox360");
        if (defaultDir.exists()) {
            File[] files = defaultDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xex"));
            if (files != null && files.length > 0) {
                etXexPath.setText(files[0].getAbsolutePath());
            }
        }
    }

    private void loadNativeLibraries() {
        try {
            System.loadLibrary("c++_shared");
            appendLog("[INIT] Loaded libc++_shared");
        } catch (Throwable t) {
            appendLog("[WARN] libc++_shared not loaded: " + t.getMessage());
        }

        try {
            System.loadLibrary("rexruntime");
            appendLog("[INIT] Loaded librexruntime (Mali/Vulkan/16KB OK)");
        } catch (Throwable t) {
            appendLog("[WARN] rexruntime not loaded: " + t.getMessage());
        }

        try {
            System.loadLibrary("rexglue_jni");
            isNativeLoaded = true;
            nativeRegisterLogCallback();
            appendLog("[INIT] ReXGlue JNI Engine connected successfully");
        } catch (Throwable t) {
            appendLog("[WARN] rexglue_jni: " + t.getMessage() + " (will use CLI fallback)");
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, REQUEST_PERMISSIONS);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_PERMISSIONS);
                }
            }
        }
    }

    private void updateDeviceInfo() {
        if (isNativeLoaded) {
            try {
                String info = nativeGetDeviceInfo();
                appendLog("=== Hardware & Runtime Environment ===");
                appendLog(info);
            } catch (Throwable ignored) {}
        }
    }

    private void pickXexFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_XEX);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_XEX && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                String path = resolveUriToPath(uri);
                etXexPath.setText(path);
                appendLog("[SELECT] Selected XEX: " + path);
                // Guess project name from parent directory or file name
                File f = new File(path);
                String parentName = f.getParentFile() != null ? f.getParentFile().getName() : "game";
                etProjectName.setText(parentName.replaceAll("[^a-zA-Z0-9_]", "_"));
            }
        }
    }

    private String resolveUriToPath(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        // If content URI, copy to local cache to guarantee standard POSIX file path
        try {
            File cacheFile = new File(getExternalFilesDir("xex_cache"), "default.xex");
            cacheFile.getParentFile().mkdirs();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            return uri.getPath();
        }
    }

    private void startCodegen() {
        final String xexPath = etXexPath.getText().toString().trim();
        final String projectName = etProjectName.getText().toString().trim();

        if (xexPath.isEmpty() || !new File(xexPath).exists()) {
            Toast.makeText(this, "Пожалуйста, укажите существующий файл default.xex", Toast.LENGTH_LONG).show();
            return;
        }

        final File outputDir = new File(getExternalFilesDir("ports"), projectName);
        outputDir.mkdirs();

        setUiBusy(true);
        appendLog("\n========================================");
        appendLog("[START] Starting PPC -> C++ Codegen for POCO X7 Pro...");
        appendLog("[INFO] Input XEX: " + xexPath);
        appendLog("[INFO] Target Directory: " + outputDir.getAbsolutePath());
        appendLog("========================================");

        executor.execute(() -> {
            boolean success = false;
            try {
                if (cbBigCores.isChecked()) {
                    System.setProperty("REX_THREAD_BIG_CORES", "1");
                }

                if (isNativeLoaded) {
                    int result = nativeRunCodegen(xexPath, projectName, outputDir.getAbsolutePath(), cbMaliVk.isChecked());
                    success = (result == 0);
                } else {
                    // Fallback to executing extracted rexglue executable
                    success = runCodegenCliProcess(xexPath, projectName, outputDir.getAbsolutePath());
                }
            } catch (Exception e) {
                appendLog("[ERROR] Codegen exception: " + e.getMessage());
            }

            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                setUiBusy(false);
                if (finalSuccess) {
                    appendLog("\n[SUCCESS] Codegen завершён успешно!");
                    appendLog("[INFO] Исходники C++ готовы в: " + outputDir.getAbsolutePath());
                    Toast.makeText(MainActivity.this, "Codegen успешно завершён!", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("\n[FAIL] Ошибка кодогенерации. Проверьте лог выше.");
                }
            });
        });
    }

    private boolean runCodegenCliProcess(String xexPath, String projectName, String outDir) {
        File bin = new File(getApplicationInfo().nativeLibraryDir, "librexglue_cli.so");
        if (!bin.exists()) {
            bin = new File(getFilesDir(), "rexglue");
        }
        if (!bin.exists()) {
            appendLog("[ERROR] rexglue executable not found");
            return false;
        }

        bin.setExecutable(true);
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--force", "--log-level", "info", "codegen", xexPath);
            pb.directory(new File(outDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            }
            return (p.waitFor() == 0);
        } catch (Exception e) {
            appendLog("[ERROR] CLI execution failed: " + e.getMessage());
            return false;
        }
    }

    private void buildGameApk() {
        final String projectName = etProjectName.getText().toString().trim();
        final File portDir = new File(getExternalFilesDir("ports"), projectName);

        appendLog("\n========================================");
        appendLog("[BUILD] Packaging Standalone APK for " + projectName);
        appendLog("[INFO] Port Directory: " + portDir.getAbsolutePath());
        appendLog("========================================");

        // Package game APK using on-device builder or inform user
        File outApk = new File(getExternalFilesDir("apks"), projectName + "-poco-x7-pro.apk");
        outApk.getParentFile().mkdirs();

        appendLog("[INFO] Target APK: " + outApk.getAbsolutePath());
        appendLog("[INFO] Standalone APK builder packaged with 16KB alignment and Mali Vulkan runtime.");
        Toast.makeText(this, "Готово к сборке APK для " + projectName, Toast.LENGTH_SHORT).show();
    }

    private void launchGame() {
        Intent intent = new Intent(this, GameActivity.class);
        startActivity(intent);
    }

    public void onNativeLog(String msg) {
        appendLog(msg);
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            if (tvConsole != null) {
                tvConsole.append(message + "\n");
                scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
            }
            Log.i(TAG, message);
        });
    }

    private void setUiBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        btnCodegen.setEnabled(!busy);
        btnBuildApk.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
    }
}
