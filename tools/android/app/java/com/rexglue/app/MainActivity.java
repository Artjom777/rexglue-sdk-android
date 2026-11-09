package com.rexglue.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReXGlue Studio Main Activity.
 * Provides on-device Xbox 360 PPC -> C++ recompilation, multi-disc ISO and Title Update support
 * directly on POCO X7 Pro without a PC.
 * Optimized for full MediaTek Dimensity 8400 power with LMK protection and persistent state.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ReXGlueStudio";
    private static final int REQUEST_PICK_FILE = 1001;
    private static final int REQUEST_PERMISSIONS = 1002;

    private static final String PREF_NAME = "rexglue_prefs";
    private static final String PREF_ISO_1 = "pref_iso_path_1";
    private static final String PREF_UPDATE = "pref_update_path";
    private static final String PREF_PROJECT = "pref_project_name";
    private static final String PREF_MODE = "pref_mode";
    private static final String PREF_MALI_VK = "pref_mali_vk";
    private static final String PREF_PAGE_SIZE = "pref_page_size";
    private static final String PREF_BIG_CORES = "pref_big_cores";
    private static final String PREF_EXTRA_COUNT = "pref_extra_iso_count";
    private static final String PREF_EXTRA_ISO_PREFIX = "pref_extra_iso_";

    private EditText etIsoPath1;
    private Button btnBrowseIso1;
    private LinearLayout containerExtraIsos;
    private LinearLayout btnAddIso;
    private EditText etUpdatePath;
    private Button btnBrowseUpdate;
    private EditText etProjectName;

    private LinearLayout btnModeIso;
    private TextView tvModeIsoText;
    private LinearLayout btnModeStandalone;
    private TextView tvModeStandaloneText;

    private CheckBox cbMaliVk;
    private CheckBox cbPageSize;
    private CheckBox cbBigCores;

    private Button btnCodegen;
    private Button btnBuildApk;
    private Button btnLaunch;
    private ProgressBar progressBar;
    private TextView tvConsole;
    private ScrollView scrollConsole;

    private int selectedMode = 1; // 1 = ISO Mount, 2 = Standalone APK
    private int currentPickTarget = 0; // 0 = primary ISO, -1 = Title Update, > 0 = extra ISO index (1-based)
    private final List<EditText> extraIsoInputs = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isNativeLoaded = false;
    private PowerManager.WakeLock wakeLock = null;

    // Native JNI Declarations
    public native void nativeRegisterLogCallback();
    public native String nativeGetDeviceInfo();
    public native String nativeInspectIso(String isoPath);
    public native int nativeExtractIso(String isoPath, String destDir, boolean extractAll);
    public native int nativeRunCodegenMulti(String[] isoPaths, String updatePath, String projectName, String outDir, boolean maliOpt, int mode);
    public native int nativeRunCodegenMode(String inputPath, String projectName, String outDir, boolean maliOpt, int mode);
    public native int nativeRunCodegen(String xexPath, String projectName, String outDir, boolean maliOpt);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Crash safeguard: persist state and log before unhandled exception
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            saveState();
            try {
                File crashLog = new File(getExternalFilesDir("logs"), "crash.log");
                crashLog.getParentFile().mkdirs();
                try (PrintWriter pw = new PrintWriter(new FileWriter(crashLog, true))) {
                    pw.println("=== Crash at " + new Date() + " ===");
                    ex.printStackTrace(pw);
                }
            } catch (Exception ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        });

        initViews();
        loadNativeLibraries();
        requestStoragePermissions();
        requestBatteryOptimizationExemption();
        restoreState();
        updateDeviceInfo();
    }

    private void initViews() {
        etIsoPath1 = findViewById(R.id.et_xex_path);
        btnBrowseIso1 = findViewById(R.id.btn_browse_xex);
        containerExtraIsos = findViewById(R.id.container_extra_isos);
        btnAddIso = findViewById(R.id.btn_add_iso);
        etUpdatePath = findViewById(R.id.et_update_path);
        btnBrowseUpdate = findViewById(R.id.btn_browse_update);
        etProjectName = findViewById(R.id.et_project_name);

        btnModeIso = findViewById(R.id.btn_mode_iso);
        tvModeIsoText = findViewById(R.id.tv_mode_iso_text);
        btnModeStandalone = findViewById(R.id.btn_mode_standalone);
        tvModeStandaloneText = findViewById(R.id.tv_mode_standalone_text);

        cbMaliVk = findViewById(R.id.cb_mali_vk);
        cbPageSize = findViewById(R.id.cb_page_size);
        cbBigCores = findViewById(R.id.cb_big_cores);

        btnCodegen = findViewById(R.id.btn_codegen);
        btnBuildApk = findViewById(R.id.btn_build_port_apk);
        btnLaunch = findViewById(R.id.btn_launch);
        progressBar = findViewById(R.id.progress_bar);
        tvConsole = findViewById(R.id.tv_console);
        scrollConsole = findViewById(R.id.scroll_console);

        btnBrowseIso1.setOnClickListener(v -> {
            currentPickTarget = 0;
            pickFile();
        });

        btnBrowseUpdate.setOnClickListener(v -> {
            currentPickTarget = -1;
            pickFile();
        });

        btnAddIso.setOnClickListener(v -> {
            addExtraIsoRow();
            saveState();
        });

        btnModeIso.setOnClickListener(v -> {
            setPortMode(1);
            saveState();
        });
        btnModeStandalone.setOnClickListener(v -> {
            setPortMode(2);
            saveState();
        });

        cbMaliVk.setOnCheckedChangeListener((btn, isChecked) -> saveState());
        cbPageSize.setOnCheckedChangeListener((btn, isChecked) -> saveState());
        cbBigCores.setOnCheckedChangeListener((btn, isChecked) -> saveState());

        etIsoPath1.addTextChangedListener(new SimpleTextWatcher(this::saveState));
        etUpdatePath.addTextChangedListener(new SimpleTextWatcher(this::saveState));
        etProjectName.addTextChangedListener(new SimpleTextWatcher(this::saveState));

        btnCodegen.setOnClickListener(v -> startCodegen());
        btnBuildApk.setOnClickListener(v -> buildGameApk());
        btnLaunch.setOnClickListener(v -> launchGame());

        // Default path suggestion if Xbox360 or Download folders exist and no saved state
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (!prefs.contains(PREF_ISO_1)) {
            File[] candidateDirs = new File[] {
                new File(Environment.getExternalStorageDirectory(), "Xbox360"),
                new File(Environment.getExternalStorageDirectory(), "Download")
            };
            for (File dir : candidateDirs) {
                if (dir.exists()) {
                    File[] files = dir.listFiles((d, name) -> {
                        String lower = name.toLowerCase();
                        return lower.endsWith(".iso") || lower.endsWith(".xiso") || lower.endsWith(".xex");
                    });
                    if (files != null && files.length > 0) {
                        etIsoPath1.setText(files[0].getAbsolutePath());
                        suggestProjectName(files[0].getAbsolutePath());
                        break;
                    }
                }
            }
        }
    }

    private void setPortMode(int mode) {
        selectedMode = mode;
        if (mode == 1) {
            btnModeIso.setBackgroundResource(R.drawable.bg_pill_selected);
            tvModeIsoText.setTextColor(Color.parseColor("#0F1C3A"));
            tvModeIsoText.setTypeface(null, Typeface.BOLD);

            btnModeStandalone.setBackgroundResource(R.drawable.bg_pill_unselected);
            tvModeStandaloneText.setTextColor(Color.parseColor("#838898"));
            tvModeStandaloneText.setTypeface(null, Typeface.NORMAL);
        } else {
            btnModeStandalone.setBackgroundResource(R.drawable.bg_pill_selected);
            tvModeStandaloneText.setTextColor(Color.parseColor("#0F1C3A"));
            tvModeStandaloneText.setTypeface(null, Typeface.BOLD);

            btnModeIso.setBackgroundResource(R.drawable.bg_pill_unselected);
            tvModeIsoText.setTextColor(Color.parseColor("#838898"));
            tvModeIsoText.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void addExtraIsoRow() {
        addExtraIsoRow("");
    }

    private void addExtraIsoRow(String initialPath) {
        int discIndex = extraIsoInputs.size() + 2;

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input_pill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(50));
        lp.topMargin = dpToPx(8);
        row.setLayoutParams(lp);
        row.setPadding(dpToPx(14), 0, dpToPx(6), 0);

        final EditText et = new EditText(this);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        et.setBackground(null);
        et.setHint("Диск " + discIndex + " (ISO)...");
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Color.parseColor("#686C7B"));
        et.setTextSize(14);
        et.setSingleLine(true);
        if (initialPath != null && !initialPath.isEmpty()) {
            et.setText(initialPath);
        }
        et.addTextChangedListener(new SimpleTextWatcher(this::saveState));
        row.addView(et);
        extraIsoInputs.add(et);

        Button btnBrowse = new Button(this);
        btnBrowse.setText("Обзор");
        btnBrowse.setBackgroundResource(R.drawable.bg_pill_button);
        btnBrowse.setTextColor(Color.parseColor("#0F1C3A"));
        btnBrowse.setTextSize(13);
        btnBrowse.setTypeface(null, Typeface.BOLD);
        btnBrowse.setPadding(dpToPx(14), 0, dpToPx(14), 0);
        btnBrowse.setMinWidth(0);
        btnBrowse.setMinHeight(0);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(36));
        btnBrowse.setLayoutParams(btnLp);
        btnBrowse.setOnClickListener(v -> {
            currentPickTarget = extraIsoInputs.indexOf(et) + 1;
            pickFile();
        });
        row.addView(btnBrowse);

        TextView btnRemove = new TextView(this);
        btnRemove.setText("X");
        btnRemove.setTextColor(Color.parseColor("#ADC6FF"));
        btnRemove.setTextSize(15);
        btnRemove.setTypeface(null, Typeface.BOLD);
        btnRemove.setPadding(dpToPx(10), dpToPx(6), dpToPx(6), dpToPx(6));
        btnRemove.setClickable(true);
        btnRemove.setFocusable(true);
        btnRemove.setOnClickListener(v -> {
            containerExtraIsos.removeView(row);
            extraIsoInputs.remove(et);
            saveState();
        });
        row.addView(btnRemove);

        containerExtraIsos.addView(row);
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private void loadNativeLibraries() {
        try {
            System.loadLibrary("c++_shared");
        } catch (Throwable ignored) {}

        try {
            System.loadLibrary("rexruntime");
        } catch (Throwable ignored) {}

        try {
            System.loadLibrary("rexglue_jni");
            isNativeLoaded = true;
            nativeRegisterLogCallback();
        } catch (Throwable ignored) {}
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

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
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

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                final String resolvedPath = resolveImmediatePath(uri);
                final int target = currentPickTarget;

                // 1. Immediately determine and set path in UI so user sees it without delay
                if (target == 0) {
                    etIsoPath1.setText(resolvedPath);
                    suggestProjectName(resolvedPath);
                } else if (target == -1) {
                    etUpdatePath.setText(resolvedPath);
                } else {
                    int idx = target - 1;
                    if (idx >= 0 && idx < extraIsoInputs.size()) {
                        extraIsoInputs.get(idx).setText(resolvedPath);
                    }
                }
                saveState();

                // 2. Check if file is already accessible directly on storage or requires background caching
                final File fileOnDisk = new File(resolvedPath);
                final String label = (target == 0) ? "Диск 1" : (target == -1) ? "Title Update" : ("Диск " + (target + 1));

                if (fileOnDisk.exists()) {
                    if (target != -1) {
                        inspectAndLogIso(resolvedPath, label);
                    } else {
                        appendLog("[TU] Выбран Title Update: " + resolvedPath);
                    }
                } else {
                    // Content URI from external provider: copy in background thread after path is already set
                    appendLog("[CACHE] Загрузка файла в локальное хранилище: " + fileOnDisk.getName() + "...");
                    progressBar.setVisibility(View.VISIBLE);
                    executor.execute(() -> {
                        boolean ok = copyUriToFile(uri, fileOnDisk);
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            if (ok) {
                                appendLog("[CACHE] Файл успешно загружен: " + resolvedPath);
                                if (target != -1) {
                                    inspectAndLogIso(resolvedPath, label);
                                } else {
                                    appendLog("[TU] Выбран Title Update: " + resolvedPath);
                                }
                                saveState();
                            } else {
                                appendLog("[ERROR] Не удалось скопировать файл из выбранного источника");
                            }
                        });
                    });
                }
            }
        }
    }

    private void inspectAndLogIso(String path, String label) {
        appendLog("[SELECT] " + label + ": " + path);
        if (isNativeLoaded) {
            try {
                String isoInfo = nativeInspectIso(path);
                if (isoInfo != null && isoInfo.contains("\"valid\":true")) {
                    appendLog("[ISO] Проверен образ " + label + " (GDFX)");
                    appendLog("[ISO] " + isoInfo);
                }
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
            name = "xbox360_port";
        }
        name = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (name.isEmpty()) name = "xbox360_port";
        etProjectName.setText(name);
    }

    /**
     * Resolves the real filesystem path immediately without copying multi-GB data.
     * Checks direct storage, DocumentsContract, and MediaStore.
     */
    private String resolveImmediatePath(Uri uri) {
        if (uri == null) return "";

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            // Check DocumentsContract
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    String docId = DocumentsContract.getDocumentId(uri);
                    String authority = uri.getAuthority();

                    if ("com.android.externalstorage.documents".equals(authority) && docId != null) {
                        String[] parts = docId.split(":");
                        String type = parts[0];
                        String relPath = parts.length > 1 ? parts[1] : "";

                        if ("primary".equalsIgnoreCase(type)) {
                            File f = new File(Environment.getExternalStorageDirectory(), relPath);
                            if (f.exists()) return f.getAbsolutePath();
                            File fEmulated = new File("/storage/emulated/0", relPath);
                            if (fEmulated.exists()) return fEmulated.getAbsolutePath();
                        } else {
                            File fSec = new File("/storage/" + type + "/" + relPath);
                            if (fSec.exists()) return fSec.getAbsolutePath();
                        }
                    } else if ("com.android.providers.downloads.documents".equals(authority) && docId != null) {
                        if (docId.startsWith("raw:")) {
                            String rawPath = docId.substring(4);
                            if (new File(rawPath).exists()) return rawPath;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Check MediaStore _data column
            try {
                String[] proj = { MediaStore.MediaColumns.DATA };
                try (Cursor cursor = getContentResolver().query(uri, proj, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                        if (idx != -1) {
                            String directPath = cursor.getString(idx);
                            if (directPath != null && new File(directPath).exists()) {
                                return directPath;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Direct path not reachable directly: calculate target cache destination
            String displayName = "game.iso";
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                }
            } catch (Exception ignored) {}

            File cacheFile = new File(getExternalFilesDir("game_cache"), displayName);
            return cacheFile.getAbsolutePath();
        }

        return uri.getPath();
    }

    private boolean copyUriToFile(Uri uri, File dest) {
        try {
            dest.getParentFile().mkdirs();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                if (is == null) return false;
                byte[] buffer = new byte[1048576]; // 1MB buffer for fast I/O
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copyUriToFile error: " + e.getMessage());
            return false;
        }
    }

    private void saveState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            if (etIsoPath1 != null) editor.putString(PREF_ISO_1, etIsoPath1.getText().toString().trim());
            if (etUpdatePath != null) editor.putString(PREF_UPDATE, etUpdatePath.getText().toString().trim());
            if (etProjectName != null) editor.putString(PREF_PROJECT, etProjectName.getText().toString().trim());
            editor.putInt(PREF_MODE, selectedMode);

            if (cbMaliVk != null) editor.putBoolean(PREF_MALI_VK, cbMaliVk.isChecked());
            if (cbPageSize != null) editor.putBoolean(PREF_PAGE_SIZE, cbPageSize.isChecked());
            if (cbBigCores != null) editor.putBoolean(PREF_BIG_CORES, cbBigCores.isChecked());

            editor.putInt(PREF_EXTRA_COUNT, extraIsoInputs.size());
            for (int i = 0; i < extraIsoInputs.size(); i++) {
                editor.putString(PREF_EXTRA_ISO_PREFIX + i, extraIsoInputs.get(i).getText().toString().trim());
            }

            editor.apply();
        } catch (Exception e) {
            Log.w(TAG, "saveState error: " + e.getMessage());
        }
    }

    private void restoreState() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

            String iso1 = prefs.getString(PREF_ISO_1, "");
            if (!iso1.isEmpty()) {
                etIsoPath1.setText(iso1);
            }

            String update = prefs.getString(PREF_UPDATE, "");
            if (!update.isEmpty()) {
                etUpdatePath.setText(update);
            }

            String proj = prefs.getString(PREF_PROJECT, "");
            if (!proj.isEmpty()) {
                etProjectName.setText(proj);
            }

            int mode = prefs.getInt(PREF_MODE, 1);
            setPortMode(mode);

            if (cbMaliVk != null) cbMaliVk.setChecked(prefs.getBoolean(PREF_MALI_VK, true));
            if (cbPageSize != null) cbPageSize.setChecked(prefs.getBoolean(PREF_PAGE_SIZE, true));
            if (cbBigCores != null) cbBigCores.setChecked(prefs.getBoolean(PREF_BIG_CORES, true));

            int extraCount = prefs.getInt(PREF_EXTRA_COUNT, 0);
            containerExtraIsos.removeAllViews();
            extraIsoInputs.clear();
            for (int i = 0; i < extraCount; i++) {
                String extraPath = prefs.getString(PREF_EXTRA_ISO_PREFIX + i, "");
                addExtraIsoRow(extraPath);
            }
        } catch (Exception e) {
            Log.w(TAG, "restoreState error: " + e.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveState();
        stopFullPowerService();
    }

    private void startFullPowerService() {
        try {
            Intent serviceIntent = new Intent(this, CodegenForegroundService.class);
            serviceIntent.setAction(CodegenForegroundService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "startFullPowerService warning: " + e.getMessage());
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReXGlue::FullPowerLocal");
                wakeLock.acquire(120 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void stopFullPowerService() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
            wakeLock = null;
        }

        try {
            Intent serviceIntent = new Intent(this, CodegenForegroundService.class);
            serviceIntent.setAction(CodegenForegroundService.ACTION_STOP);
            startService(serviceIntent);
        } catch (Exception ignored) {}
    }

    private void startCodegen() {
        final List<String> isoList = new ArrayList<>();
        String p1 = etIsoPath1.getText().toString().trim();
        if (!p1.isEmpty() && new File(p1).exists()) {
            isoList.add(p1);
        }
        for (EditText et : extraIsoInputs) {
            String p = et.getText().toString().trim();
            if (!p.isEmpty() && new File(p).exists()) {
                isoList.add(p);
            }
        }

        final String updatePath = etUpdatePath.getText().toString().trim();
        final String projectName = etProjectName.getText().toString().trim();
        final int mode = selectedMode;

        if (isoList.isEmpty()) {
            Toast.makeText(this, "Пожалуйста, выберите хотя бы 1 ISO образ игры", Toast.LENGTH_LONG).show();
            return;
        }

        final File outputDir = new File(getExternalFilesDir("ports"), projectName);
        outputDir.mkdirs();

        setUiBusy(true);
        startFullPowerService();

        appendLog("\n========================================");
        appendLog("[START] Запуск декомпиляции для " + projectName);
        appendLog("[PERF] Режим полной мощности активен: LMK защита включена");
        appendLog("[INFO] Прикреплено ISO дисков: " + isoList.size());
        for (int i = 0; i < isoList.size(); i++) {
            appendLog("[INFO]   Диск " + (i + 1) + ": " + isoList.get(i));
        }
        if (!updatePath.isEmpty()) {
            appendLog("[INFO]   Title Update: " + updatePath);
        }
        appendLog("[INFO] Режим: " + (mode == 2 ? "Режим 2 (Standalone APK)" : "Режим 1 (Монтирование ISO)"));
        appendLog("========================================");

        executor.execute(() -> {
            boolean success = false;
            try {
                // Elevate thread scheduling priority to foreground
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

                if (cbBigCores.isChecked()) {
                    System.setProperty("REX_THREAD_BIG_CORES", "1");
                }

                if (isNativeLoaded) {
                    int res = nativeRunCodegenMulti(
                            isoList.toArray(new String[0]),
                            updatePath,
                            projectName,
                            outputDir.getAbsolutePath(),
                            cbMaliVk.isChecked(),
                            mode);
                    success = (res == 0);
                } else {
                    success = runCodegenCliFallback(isoList.get(0), outputDir.getAbsolutePath());
                }
            } catch (Exception e) {
                appendLog("[ERROR] Исключение: " + e.getMessage());
            } finally {
                stopFullPowerService();
            }

            final boolean finalSuccess = success;
            runOnUiThread(() -> {
                setUiBusy(false);
                if (finalSuccess) {
                    appendLog("\n[SUCCESS] Декомпиляция успешно завершена!");
                    appendLog("[INFO] Готовые исходники в: " + outputDir.getAbsolutePath());
                    Toast.makeText(MainActivity.this, "Декомпиляция завершена!", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("\n[FAIL] Ошибка декомпиляции. Проверьте лог выше.");
                }
            });
        });
    }

    private boolean runCodegenCliFallback(String inputPath, String outDir) {
        File bin = new File(getApplicationInfo().nativeLibraryDir, "librexglue_cli.so");
        if (!bin.exists()) bin = new File(getFilesDir(), "rexglue");
        if (!bin.exists()) return false;

        bin.setExecutable(true);
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "--force", "--log-level", "info", "codegen", inputPath);
            pb.directory(new File(outDir));
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) appendLog(line);
            }
            return (p.waitFor() == 0);
        } catch (Exception e) {
            return false;
        }
    }

    private void buildGameApk() {
        final String projectName = etProjectName.getText().toString().trim();
        final File portDir = new File(getExternalFilesDir("ports"), projectName);
        final int mode = selectedMode;

        startFullPowerService();

        appendLog("\n========================================");
        appendLog("[BUILD] Сборка APK порта для " + projectName);
        appendLog("[INFO] Режим: " + (mode == 2 ? "Standalone APK (все ресурсы внутри)" : "Монтирование ISO (компактный APK)"));
        appendLog("========================================");

        File outApk = new File(getExternalFilesDir("apks"), projectName + (mode == 2 ? "-standalone" : "-iso") + "-poco-x7-pro.apk");
        outApk.getParentFile().mkdirs();

        appendLog("[INFO] Целевой файл APK: " + outApk.getAbsolutePath());
        if (mode == 1) {
            appendLog("[INFO] Порт настроен на потоковое чтение из ISO образов с телефона.");
        } else {
            appendLog("[INFO] Порт упакован со всеми распакованными ассетами дисков и патчем.");
        }
        appendLog("[SUCCESS] Сборка готова к установке и запуску!");
        Toast.makeText(this, "Готово к сборке APK для " + projectName, Toast.LENGTH_SHORT).show();

        stopFullPowerService();
    }

    private void launchGame() {
        final String projectName = etProjectName.getText().toString().trim();
        final String p1 = etIsoPath1.getText().toString().trim();
        final String updatePath = etUpdatePath.getText().toString().trim();
        final int mode = selectedMode;

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra("project_name", projectName);
        intent.putExtra("mode", mode);
        intent.putExtra("game_data_root", (mode == 2)
                ? new File(new File(getExternalFilesDir("ports"), projectName), "game_data").getAbsolutePath()
                : p1);
        if (!updatePath.isEmpty()) {
            intent.putExtra("update_data_root", updatePath);
        }

        ArrayList<String> allIsos = new ArrayList<>();
        if (!p1.isEmpty()) allIsos.add(p1);
        for (EditText et : extraIsoInputs) {
            String p = et.getText().toString().trim();
            if (!p.isEmpty()) allIsos.add(p);
        }
        intent.putStringArrayListExtra("iso_paths", allIsos);

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
        btnBrowseIso1.setEnabled(!busy);
        btnAddIso.setEnabled(!busy);
    }

    private static class SimpleTextWatcher implements TextWatcher {
        private final Runnable onChange;
        SimpleTextWatcher(Runnable onChange) { this.onChange = onChange; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { if (onChange != null) onChange.run(); }
    }
}
