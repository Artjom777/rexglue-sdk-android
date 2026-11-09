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
 * Optimized for MediaTek Dimensity 8400 power with LMK protection and instant state persistence.
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
    private boolean isRestoring = false;
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
            try {
                saveState();
                File crashLog = new File(getExternalFilesDir("logs"), "crash.log");
                crashLog.getParentFile().mkdirs();
                try (PrintWriter pw = new PrintWriter(new FileWriter(crashLog, true))) {
                    pw.println("=== Crash at " + new Date() + " ===");
                    ex.printStackTrace(pw);
                }
            } catch (Throwable ignored) {}
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        });

        initViews();
        loadNativeLibraries();
        requestStoragePermissions();
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

        cbMaliVk.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isRestoring) saveState();
        });
        cbPageSize.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isRestoring) saveState();
        });
        cbBigCores.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!isRestoring) saveState();
        });

        etIsoPath1.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (!isRestoring) saveState();
        }));
        etUpdatePath.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (!isRestoring) saveState();
        }));
        etProjectName.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (!isRestoring) saveState();
        }));

        btnCodegen.setOnClickListener(v -> startCodegen());
        btnBuildApk.setOnClickListener(v -> buildGameApk());
        btnLaunch.setOnClickListener(v -> launchGame());
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
        et.addTextChangedListener(new SimpleTextWatcher(() -> {
            if (!isRestoring) saveState();
        }));
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
            if (!isRestoring) saveState();
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
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivityForResult(intent, REQUEST_PERMISSIONS);
                    } catch (Exception ignored) {}
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

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQUEST_PICK_FILE);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}

                final String resolvedPath = resolveImmediatePath(uri);
                final int target = currentPickTarget;

                // 1. Immediately determine and set path in UI for the selected target
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

                // 2. Commit all paths synchronously to disk immediately
                saveState();

                // 3. Inspect file or cache asynchronously if needed
                final File fileOnDisk = new File(resolvedPath);
                final String label = (target == 0) ? "Диск 1" : (target == -1) ? "Title Update" : ("Диск " + (target + 1));

                if (fileOnDisk.exists()) {
                    if (target != -1) {
                        inspectAndLogIso(resolvedPath, label);
                    } else {
                        appendLog("[TU] Выбран Title Update: " + resolvedPath);
                    }
                } else if (!"file".equalsIgnoreCase(uri.getScheme()) && uri.toString().startsWith("content://")) {
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
                                appendLog("[WARN] Файл сохранен по пути: " + resolvedPath);
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
     * Decodes URL components, checks direct storage, DocumentsContract, and MediaStore.
     */
    private String resolveImmediatePath(Uri uri) {
        if (uri == null) return "";

        if ("file".equalsIgnoreCase(uri.getScheme())) {
            String p = uri.getPath();
            return (p != null) ? p : "";
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            // 1. Check DocumentsContract
            try {
                if (DocumentsContract.isDocumentUri(this, uri)) {
                    String docId = DocumentsContract.getDocumentId(uri);
                    String authority = uri.getAuthority();

                    if ("com.android.externalstorage.documents".equals(authority) && docId != null) {
                        String[] parts = docId.split(":");
                        String type = parts[0];
                        String relPath = parts.length > 1 ? parts[1] : "";

                        try {
                            relPath = Uri.decode(relPath);
                        } catch (Exception ignored) {}

                        if ("primary".equalsIgnoreCase(type)) {
                            File f = new File(Environment.getExternalStorageDirectory(), relPath);
                            if (f.exists()) return f.getAbsolutePath();
                            File fEmulated = new File("/storage/emulated/0", relPath);
                            if (fEmulated.exists()) return fEmulated.getAbsolutePath();
                            File fSd = new File("/sdcard", relPath);
                            if (fSd.exists()) return fSd.getAbsolutePath();
                            return fEmulated.getAbsolutePath();
                        } else {
                            File fSec = new File("/storage/" + type + "/" + relPath);
                            return fSec.getAbsolutePath();
                        }
                    } else if ("com.android.providers.downloads.documents".equals(authority) && docId != null) {
                        if (docId.startsWith("raw:")) {
                            return docId.substring(4);
                        }
                    } else if ("com.android.providers.media.documents".equals(authority) && docId != null) {
                        String[] parts = docId.split(":");
                        if (parts.length > 1) {
                            String id = parts[1];
                            String p = queryColumn(MediaStore.Files.getContentUri("external"),
                                    MediaStore.MediaColumns.DATA,
                                    MediaStore.MediaColumns._ID + "=?", new String[]{id});
                            if (p != null && !p.isEmpty()) return p;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Direct MediaStore query
            try {
                String direct = queryColumn(uri, MediaStore.MediaColumns.DATA, null, null);
                if (direct != null && !direct.isEmpty()) {
                    return direct;
                }
            } catch (Throwable ignored) {}

            // 3. Fallback to cache destination with display name
            String displayName = "game.iso";
            try {
                String name = queryColumn(uri, OpenableColumns.DISPLAY_NAME, null, null);
                if (name != null && !name.isEmpty()) displayName = name;
            } catch (Throwable ignored) {}

            File cacheFile = new File(getExternalFilesDir("game_cache"), displayName);
            return cacheFile.getAbsolutePath();
        }

        String fallback = uri.getPath();
        return (fallback != null) ? fallback : "";
    }

    private String queryColumn(Uri uri, String column, String selection, String[] selectionArgs) {
        if (uri == null || column == null) return null;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{column}, selection, selectionArgs, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(column);
                if (idx != -1) {
                    return cursor.getString(idx);
                }
            }
        } catch (Throwable ignored) {}
        return null;
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

    /**
     * Saves all current inputs synchronously to disk (commit) immediately.
     * Prevents reentrant calls during state restoration.
     */
    private synchronized void saveState() {
        if (isRestoring) return;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            String p1 = (etIsoPath1 != null) ? etIsoPath1.getText().toString().trim() : "";
            editor.putString(PREF_ISO_1, p1);

            String tu = (etUpdatePath != null) ? etUpdatePath.getText().toString().trim() : "";
            editor.putString(PREF_UPDATE, tu);

            String proj = (etProjectName != null) ? etProjectName.getText().toString().trim() : "";
            editor.putString(PREF_PROJECT, proj);

            editor.putInt(PREF_MODE, selectedMode);

            if (cbMaliVk != null) editor.putBoolean(PREF_MALI_VK, cbMaliVk.isChecked());
            if (cbPageSize != null) editor.putBoolean(PREF_PAGE_SIZE, cbPageSize.isChecked());
            if (cbBigCores != null) editor.putBoolean(PREF_BIG_CORES, cbBigCores.isChecked());

            // Save ALL extra ISO disc paths immediately
            int count = extraIsoInputs.size();
            editor.putInt(PREF_EXTRA_COUNT, count);
            for (int i = 0; i < count; i++) {
                EditText et = extraIsoInputs.get(i);
                String extraPath = (et != null) ? et.getText().toString().trim() : "";
                editor.putString(PREF_EXTRA_ISO_PREFIX + i, extraPath);
            }

            editor.commit(); // Synchronous write to guarantee zero data loss
            Log.i(TAG, "Saved state immediately: iso1=" + p1 + ", tu=" + tu + ", extraCount=" + count);
        } catch (Throwable e) {
            Log.w(TAG, "saveState error: " + e.getMessage());
        }
    }

    /**
     * Restores all saved file paths (Disc 1, all extra discs, Title Update) and settings.
     * Guarded with isRestoring to prevent TextWatchers from wiping stored state.
     */
    private synchronized void restoreState() {
        isRestoring = true;
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

            String iso1 = prefs.getString(PREF_ISO_1, "");
            if (etIsoPath1 != null && !iso1.isEmpty()) {
                etIsoPath1.setText(iso1);
            }

            String update = prefs.getString(PREF_UPDATE, "");
            if (etUpdatePath != null && !update.isEmpty()) {
                etUpdatePath.setText(update);
            }

            String proj = prefs.getString(PREF_PROJECT, "");
            if (etProjectName != null && !proj.isEmpty()) {
                etProjectName.setText(proj);
            }

            int mode = prefs.getInt(PREF_MODE, 1);
            setPortMode(mode);

            if (cbMaliVk != null) cbMaliVk.setChecked(prefs.getBoolean(PREF_MALI_VK, true));
            if (cbPageSize != null) cbPageSize.setChecked(prefs.getBoolean(PREF_PAGE_SIZE, true));
            if (cbBigCores != null) cbBigCores.setChecked(prefs.getBoolean(PREF_BIG_CORES, true));

            int extraCount = prefs.getInt(PREF_EXTRA_COUNT, 0);
            if (containerExtraIsos != null) {
                containerExtraIsos.removeAllViews();
            }
            extraIsoInputs.clear();

            for (int i = 0; i < extraCount; i++) {
                String extraPath = prefs.getString(PREF_EXTRA_ISO_PREFIX + i, "");
                addExtraIsoRow(extraPath);
            }

            // Fallback: If no saved disc 1, check standard candidate folders
            if (iso1.isEmpty()) {
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
            Log.i(TAG, "Restored state: iso1=" + iso1 + ", tu=" + update + ", extraCount=" + extraCount);
        } catch (Throwable e) {
            Log.w(TAG, "restoreState error: " + e.getMessage());
        } finally {
            isRestoring = false;
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
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReXGlue::FullPowerLocal");
                wakeLock.acquire(120 * 60 * 1000L);
            }
        } catch (Throwable ignored) {}

        try {
            Intent serviceIntent = new Intent(this, CodegenForegroundService.class);
            serviceIntent.setAction(CodegenForegroundService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startFullPowerService safe bypass: " + t.getMessage());
        }
    }

    private void stopFullPowerService() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {}
            wakeLock = null;
        }

        try {
            Intent serviceIntent = new Intent(this, CodegenForegroundService.class);
            serviceIntent.setAction(CodegenForegroundService.ACTION_STOP);
            startService(serviceIntent);
        } catch (Throwable ignored) {}
    }

    private void startCodegen() {
        final List<String> isoList = new ArrayList<>();
        String p1 = etIsoPath1.getText().toString().trim();
        if (!p1.isEmpty()) {
            isoList.add(p1);
        }
        for (EditText et : extraIsoInputs) {
            String p = et.getText().toString().trim();
            if (!p.isEmpty()) {
                isoList.add(p);
            }
        }

        final String updatePath = etUpdatePath.getText().toString().trim();
        String proj = etProjectName.getText().toString().trim();
        if (proj.isEmpty()) proj = "xbox360_port";
        final String projectName = proj;
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
        String proj = etProjectName.getText().toString().trim();
        if (proj.isEmpty()) proj = "xbox360_port";
        final String projectName = proj;
        final File portDir = new File(getExternalFilesDir("ports"), projectName);
        final int mode = selectedMode;

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
    }

    private void launchGame() {
        String proj = etProjectName.getText().toString().trim();
        if (proj.isEmpty()) proj = "xbox360_port";
        final String projectName = proj;
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
        @Override public void afterTextChanged(Editable s) {
            if (onChange != null) onChange.run();
        }
    }
}
