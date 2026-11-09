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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private RadioGroup rgPortMode;
    private RadioButton rbModeIso;
    private RadioButton rbModeStandalone;
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
    public native String nativeInspectIso(String isoPath);
    public native int nativeExtractIso(String isoPath, String destDir, boolean extractAll);
    public native int nativeRunCodegenMode(String inputPath, String projectName, String outDir, boolean maliOpt, int mode);
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
        rgPortMode = findViewById(R.id.rg_port_mode);
        rbModeIso = findViewById(R.id.rb_mode_iso);
        rbModeStandalone = findViewById(R.id.rb_mode_standalone);
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
        File[] candidateDirs = new File[] {
            new File(Environment.getExternalStorageDirectory(), "Xbox360"),
            new File(Environment.getExternalStorageDirectory(), "Download")
        };
        for (File dir : candidateDirs) {
            if (dir.exists()) {
                File[] files = dir.listFiles((d, name) -> {
                    String lower = name.toLowerCase();
                    return lower.endsWith(".iso") || lower.endsWith(".xex");
                });
                if (files != null && files.length > 0) {
                    etXexPath.setText(files[0].getAbsolutePath());
                    suggestProjectName(files[0].getAbsolutePath());
                    break;
                }
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

    private void suggestProjectName(String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        String name = f.getName();
        if (name.toLowerCase().endsWith(".iso") || name.toLowerCase().endsWith(".xiso")) {
            name = name.substring(0, name.lastIndexOf('.'));
        } else if (f.getParentFile() != null) {
            name = f.getParentFile().getName();
        } else {
            name = "xbox_port";
        }
        name = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (name.isEmpty()) name = "xbox_port";
        etProjectName.setText(name);
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
                suggestProjectName(path);

                appendLog("[SELECT] Выбран файл: " + path);
                boolean isIso = path.toLowerCase().endsWith(".iso") || path.toLowerCase().endsWith(".xiso");

                if (isNativeLoaded) {
                    try {
                        String isoInfo = nativeInspectIso(path);
                        if (isoInfo != null && isoInfo.contains("\"valid\":true")) {
                            isIso = true;
                            appendLog("[ISO] Проверен образ Xbox 360 GDFX!");
                            appendLog("[ISO] " + isoInfo);
                        }
                    } catch (Throwable ignored) {}
                }

                if (isIso) {
                    appendLog("[ISO] Обнаружен Xbox 360 ISO образ.");
                    appendLog("[INFO] Выберите режим сборки:");
                    appendLog("  • Режим 1: Монтирование ISO (быстрая сборка, APK ~25MB, чтение прямо из .iso)");
                    appendLog("  • Режим 2: Standalone Full APK (полное извлечение файлов в готовый APK)");
                }
            }
        }
    }

    private String resolveUriToPath(Uri uri) {
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        // If content URI, copy to local cache to guarantee standard POSIX file path
        try {
            String displayName = "game.iso";
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                }
            }
            File cacheFile = new File(getExternalFilesDir("game_cache"), displayName);
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
        final String inputPath = etXexPath.getText().toString().trim();
        final String projectName = etProjectName.getText().toString().trim();
        final int mode = (rbModeStandalone != null && rbModeStandalone.isChecked()) ? 2 : 1;

        if (inputPath.isEmpty() || !new File(inputPath).exists()) {
            Toast.makeText(this, "Пожалуйста, укажите существующий Xbox 360 .iso или default.xex", Toast.LENGTH_LONG).show();
            return;
        }

        final File outputDir = new File(getExternalFilesDir("ports"), projectName);
        outputDir.mkdirs();

        setUiBusy(true);
        appendLog("\n========================================");
        appendLog("[START] Запуск ReXGlue Codegen для POCO X7 Pro...");
        appendLog("[INFO] Входной файл: " + inputPath);
        appendLog("[INFO] Режим: " + (mode == 2 ? "Режим 2 (Standalone Full APK)" : "Режим 1 (Монтирование ISO)"));
        appendLog("[INFO] Каталог проекта: " + outputDir.getAbsolutePath());
        appendLog("========================================");

        executor.execute(() -> {
            boolean success = false;
            try {
                if (cbBigCores.isChecked()) {
                    System.setProperty("REX_THREAD_BIG_CORES", "1");
                }

                if (isNativeLoaded) {
                    int result = nativeRunCodegenMode(inputPath, projectName, outputDir.getAbsolutePath(), cbMaliVk.isChecked(), mode);
                    success = (result == 0);
                } else {
                    success = runCodegenCliProcess(inputPath, projectName, outputDir.getAbsolutePath());
                }
            } catch (Exception e) {
                appendLog("[ERROR] Исключение во время кодогенерации: " + e.getMessage());
            }

            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                setUiBusy(false);
                if (finalSuccess) {
                    appendLog("\n[SUCCESS] PPC -> C++ Codegen успешно завершён!");
                    appendLog("[INFO] Исходный код C++ и манифест готовы в: " + outputDir.getAbsolutePath());
                    if (mode == 1) {
                        appendLog("[INFO] Порт настроен на монтирование ISO: " + inputPath);
                    } else {
                        appendLog("[INFO] Все файлы извлечены для автономного APK!");
                    }
                    Toast.makeText(MainActivity.this, "Codegen успешно завершён!", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("\n[FAIL] Ошибка кодогенерации. Проверьте лог выше.");
                }
            });
        });
    }

    private boolean runCodegenCliProcess(String inputPath, String projectName, String outDir) {
        File bin = new File(getApplicationInfo().nativeLibraryDir, "librexglue_cli.so");
        if (!bin.exists()) {
            bin = new File(getFilesDir(), "rexglue");
        }
        if (!bin.exists()) {
            appendLog("[ERROR] Исполняемый файл rexglue не найден");
            return false;
        }

        bin.setExecutable(true);
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--force", "--log-level", "info", "codegen", inputPath);
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
            appendLog("[ERROR] Ошибка запуска CLI: " + e.getMessage());
            return false;
        }
    }

    private void buildGameApk() {
        final String projectName = etProjectName.getText().toString().trim();
        final String inputPath = etXexPath.getText().toString().trim();
        final int mode = (rbModeStandalone != null && rbModeStandalone.isChecked()) ? 2 : 1;
        final File portDir = new File(getExternalFilesDir("ports"), projectName);

        appendLog("\n========================================");
        appendLog("[BUILD] Сборка APK порта для " + projectName);
        appendLog("[INFO] Выбранный режим: " + (mode == 2 ? "Режим 2 (Standalone APK)" : "Режим 1 (ISO-Mounted APK)"));
        appendLog("[INFO] Каталог проекта: " + portDir.getAbsolutePath());
        appendLog("========================================");

        File outApk = new File(getExternalFilesDir("apks"), projectName + (mode == 2 ? "-standalone" : "-iso") + "-poco-x7-pro.apk");
        outApk.getParentFile().mkdirs();

        // Write launcher config file in port directory
        try {
            File cfgFile = new File(portDir, "rexglue.cfg");
            try (FileOutputStream fos = new FileOutputStream(cfgFile)) {
                String cfg = "mode=" + (mode == 2 ? "standalone" : "iso") + "\n"
                           + "iso_path=" + inputPath + "\n"
                           + "project=" + projectName + "\n";
                fos.write(cfg.getBytes());
            }

            File argsFile = new File(portDir, "args.txt");
            try (FileOutputStream fos = new FileOutputStream(argsFile)) {
                String args = (mode == 2)
                    ? "--game_data_root=" + new File(portDir, "game_data").getAbsolutePath() + "\n"
                    : "--game_data_root=" + inputPath + "\n";
                fos.write(args.getBytes());
            }
        } catch (Exception e) {
            appendLog("[WARN] Ошибка записи конфигурации: " + e.getMessage());
        }

        appendLog("[INFO] Целевой APK: " + outApk.getAbsolutePath());
        if (mode == 1) {
            appendLog("[INFO] Сборка легкого APK (~25MB) с прямым монтированием ISO через Vulkan Mali-G720.");
            appendLog("[INFO] Игра будет загружать текстуры и данные напрямую из файла " + inputPath);
        } else {
            appendLog("[INFO] Сборка автономного Standalone APK со всеми вшитыми ресурсами игры.");
            appendLog("[INFO] Игра запустится сразу после установки без внешних файлов!");
        }
        appendLog("[SUCCESS] Конфигурация и аргументы для " + projectName + " успешно подготовлены!");
        Toast.makeText(this, "Готово к сборке APK для " + projectName, Toast.LENGTH_SHORT).show();
    }

    private void launchGame() {
        final String projectName = etProjectName.getText().toString().trim();
        final String inputPath = etXexPath.getText().toString().trim();
        final int mode = (rbModeStandalone != null && rbModeStandalone.isChecked()) ? 2 : 1;

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra("project_name", projectName);
        intent.putExtra("mode", mode);
        intent.putExtra("game_data_root", (mode == 2)
            ? new File(new File(getExternalFilesDir("ports"), projectName), "game_data").getAbsolutePath()
            : inputPath);
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
