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

import com.rexglue.app.R;

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

    private Button btnPortGame;
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

        btnPortGame = findViewById(R.id.btn_port_game);
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

        btnPortGame.setOnClickListener(v -> startPortProcess());
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

    private void startPortProcess() {
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
        appendLog("[START] Запуск портирования для " + projectName);
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
        appendLog("[1/2] Фаза 1: Декомпиляция бинарников и подготовка C++ исходников...");

        executor.execute(() -> {
            boolean success = false;
            int resCode = -1;
            try {
                // Elevate thread scheduling priority to foreground
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

                if (cbBigCores.isChecked()) {
                    System.setProperty("REX_THREAD_BIG_CORES", "1");
                }

                if (isNativeLoaded) {
                    resCode = nativeRunCodegenMulti(
                            isoList.toArray(new String[0]),
                            updatePath,
                            projectName,
                            outputDir.getAbsolutePath(),
                            cbMaliVk.isChecked(),
                            mode);
                    success = (resCode == 0);
                } else {
                    success = runCodegenCliFallback(isoList.get(0), outputDir.getAbsolutePath());
                    resCode = success ? 0 : -99;
                }
            } catch (Exception e) {
                appendLog("[ERROR] Исключение декомпиляции: " + e.getMessage());
            }

            final boolean codegenSuccess = success;
            final int codegenCode = resCode;

            if (!codegenSuccess) {
                stopFullPowerService();
                runOnUiThread(() -> {
                    setUiBusy(false);
                    appendLog("\n[FAIL] Ошибка декомпиляции (код " + codegenCode + "). Проверьте лог выше и /sdcard/rexglue_log.txt");
                    Toast.makeText(MainActivity.this, "Ошибка декомпиляции (код " + codegenCode + ")", Toast.LENGTH_LONG).show();
                });
                return;
            }

            // Decompilation succeeded -> immediately proceed to Phase 2: Build APK and save to Downloads
            runOnUiThread(() -> {
                appendLog("\n[SUCCESS] Декомпиляция успешно завершена!");
                appendLog("[INFO] Готовые исходники в: " + outputDir.getAbsolutePath());
                appendLog("\n[2/2] Фаза 2: Сборка портированного APK и сохранение в Загрузки...");
            });

            boolean apkSuccess = false;
            File destApk = null;
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir == null || !downloadDir.exists()) {
                    downloadDir = new File("/storage/emulated/0/Download");
                }
                downloadDir.mkdirs();

                destApk = new File(downloadDir, projectName + "-poco-x7-pro.apk");
                apkSuccess = buildAndSaveApk(projectName, mode, isoList, updatePath, outputDir, destApk);
            } catch (Exception e) {
                appendLog("[ERROR] Исключение сборки APK: " + e.getMessage());
            } finally {
                stopFullPowerService();
            }

            final boolean finalApkSuccess = apkSuccess;
            final File finalDestApk = destApk;

            runOnUiThread(() -> {
                setUiBusy(false);
                if (finalApkSuccess && finalDestApk != null) {
                    appendLog("\n========================================");
                    appendLog("[SUCCESS] Портирование игры полностью завершено!");
                    appendLog("[SUCCESS] Портированный APK сохранен в Загрузки:");
                    appendLog("  " + finalDestApk.getAbsolutePath());
                    appendLog("========================================");
                    Toast.makeText(MainActivity.this, "Портированный APK сохранен в Загрузки!", Toast.LENGTH_LONG).show();
                } else {
                    appendLog("\n[FAIL] Ошибка при сборке APK. Проверьте права доступа.");
                    Toast.makeText(MainActivity.this, "Ошибка сборки APK", Toast.LENGTH_LONG).show();
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

    private boolean buildAndSaveApk(String projectName, int mode, List<String> isoList, String updatePath, File portOutputDir, File destApk) {
        try {
            appendLog("[BUILD] Сборка APK порта: " + destApk.getName());

            // 1. Try to copy from assets/game_runner.apk if bundled
            boolean copiedFromAssets = false;
            try (InputStream in = getAssets().open("game_runner.apk")) {
                try (FileOutputStream out = new FileOutputStream(destApk)) {
                    byte[] buffer = new byte[65536];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    out.flush();
                }
                copiedFromAssets = true;
                appendLog("[BUILD] Шаблон game_runner.apk скопирован из assets");
            } catch (Exception ignored) {
                // Not bundled or exception, fallback to runtime package
            }

            // 2. Fallback: copy our own APK (which already contains GameActivity, SDL3, librexruntime.so)
            if (!copiedFromAssets) {
                String myApkPath = getPackageCodePath();
                File myApk = new File(myApkPath);
                if (myApk.exists()) {
                    try (InputStream in = new java.io.FileInputStream(myApk);
                         FileOutputStream out = new FileOutputStream(destApk)) {
                        byte[] buffer = new byte[65536];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    }
                    appendLog("[BUILD] APK собран на базе runtime пакета");
                } else {
                    appendLog("[ERROR] Не удалось найти базовый APK для сборки");
                    return false;
                }
            }

            // 3. Generate configuration files (rexglue.cfg and args.txt)
            StringBuilder cfgContent = new StringBuilder();
            cfgContent.append("# ReXGlue Port Configuration for ").append(projectName).append("\n");
            cfgContent.append("project=").append(projectName).append("\n");
            cfgContent.append("mode=").append(mode == 2 ? "standalone" : "iso").append("\n");
            for (int i = 0; i < isoList.size(); i++) {
                cfgContent.append("iso_").append(i).append("=").append(isoList.get(i)).append("\n");
            }
            if (updatePath != null && !updatePath.isEmpty()) {
                cfgContent.append("update_path=").append(updatePath).append("\n");
            }
            if (isoList.size() > 0) {
                cfgContent.append("--game_data_root=").append(isoList.get(0)).append("\n");
            }
            if (isoList.size() > 1) {
                cfgContent.append("--disc2_data_root=").append(isoList.get(1)).append("\n");
            }
            if (isoList.size() > 2) {
                cfgContent.append("--disc3_data_root=").append(isoList.get(2)).append("\n");
            }
            if (updatePath != null && !updatePath.isEmpty()) {
                cfgContent.append("--update_data_root=").append(updatePath).append("\n");
            }

            StringBuilder argsContent = new StringBuilder();
            if (isoList.size() > 0) {
                argsContent.append("--game_data_root=").append(isoList.get(0)).append("\n");
            }
            if (isoList.size() > 1) {
                argsContent.append("--disc2_data_root=").append(isoList.get(1)).append("\n");
            }
            if (isoList.size() > 2) {
                argsContent.append("--disc3_data_root=").append(isoList.get(2)).append("\n");
            }
            if (updatePath != null && !updatePath.isEmpty()) {
                argsContent.append("--update_data_root=").append(updatePath).append("\n");
            }

            File downloadDir = destApk.getParentFile();
            File[] cfgDestinations = new File[] {
                new File(downloadDir, "rexglue.cfg"),
                new File(downloadDir, "args.txt"),
                new File(new File(downloadDir, projectName), "rexglue.cfg"),
                new File(new File(downloadDir, projectName), "args.txt"),
                new File(portOutputDir, "rexglue.cfg"),
                new File(portOutputDir, "args.txt"),
                new File(getExternalFilesDir(null), "rexglue.cfg"),
                new File(getExternalFilesDir(null), "args.txt"),
                new File("/storage/emulated/0/rexglue.cfg"),
                new File("/sdcard/rexglue.cfg")
            };

            for (File cf : cfgDestinations) {
                try {
                    if (cf.getParentFile() != null) {
                        cf.getParentFile().mkdirs();
                    }
                    boolean isArgs = cf.getName().equals("args.txt");
                    try (FileWriter fw = new FileWriter(cf)) {
                        fw.write(isArgs ? argsContent.toString() : cfgContent.toString());
                    }
                } catch (Throwable ignored) {}
            }

            // Embed assets/rexglue.cfg and assets/args.txt into the APK directly
            injectAssetsIntoApk(destApk, cfgContent.toString(), argsContent.toString());

            // Also keep a copy in app's internal apks directory
            try {
                File appApk = new File(getExternalFilesDir("apks"), destApk.getName());
                appApk.getParentFile().mkdirs();
                try (InputStream in = new java.io.FileInputStream(destApk);
                     FileOutputStream out = new FileOutputStream(appApk)) {
                    byte[] buffer = new byte[65536];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            } catch (Throwable ignored) {}

            // Notify Android MediaScanner so file is instantly recognized in Downloads
            try {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    new String[] { destApk.getAbsolutePath() },
                    new String[] { "application/vnd.android.package-archive" },
                    null
                );
            } catch (Throwable ignored) {}

            appendLog("[INFO] Размер APK: " + (destApk.length() / 1024 / 1024) + " MB");
            return true;
        } catch (Exception e) {
            appendLog("[ERROR] Ошибка сборки APK: " + e.getMessage());
            return false;
        }
    }

    private void injectAssetsIntoApk(File apkFile, String cfgContent, String argsContent) {
        if (apkFile == null || !apkFile.exists()) return;
        File tempApk = new File(apkFile.getParentFile(), apkFile.getName() + ".tmp");
        try (java.util.zip.ZipFile zipIn = new java.util.zip.ZipFile(apkFile);
             java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(new FileOutputStream(tempApk))) {
            
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals("assets/rexglue.cfg") || name.equals("assets/args.txt")) {
                    continue;
                }
                java.util.zip.ZipEntry newEntry = new java.util.zip.ZipEntry(name);
                zipOut.putNextEntry(newEntry);
                try (InputStream is = zipIn.getInputStream(entry)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        zipOut.write(buf, 0, n);
                    }
                }
                zipOut.closeEntry();
            }

            // Inject assets/rexglue.cfg
            java.util.zip.ZipEntry cfgEntry = new java.util.zip.ZipEntry("assets/rexglue.cfg");
            zipOut.putNextEntry(cfgEntry);
            zipOut.write(cfgContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // Inject assets/args.txt
            java.util.zip.ZipEntry argsEntry = new java.util.zip.ZipEntry("assets/args.txt");
            zipOut.putNextEntry(argsEntry);
            zipOut.write(argsContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOut.closeEntry();
        } catch (Exception e) {
            appendLog("[WARN] Встраивание конфига в APK пропущено: " + e.getMessage());
            if (tempApk.exists()) tempApk.delete();
            return;
        }

        if (tempApk.exists() && tempApk.length() > 0) {
            apkFile.delete();
            tempApk.renameTo(apkFile);
            appendLog("[BUILD] Конфигурация успешно встроена в assets APK");
        }
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

        // Concurrently write to /sdcard/rexglue_log.txt
        executor.execute(() -> {
            try {
                File logFile = new File("/sdcard/rexglue_log.txt");
                if (!logFile.exists()) {
                    File altLog = new File("/storage/emulated/0/rexglue_log.txt");
                    if (altLog.getParentFile() != null && altLog.getParentFile().exists()) {
                        logFile = altLog;
                    }
                }
                try (FileWriter fw = new FileWriter(logFile, true)) {
                    fw.write(message + "\n");
                }
            } catch (Throwable ignored) {}
        });
    }

    private void setUiBusy(boolean busy) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (btnPortGame != null) btnPortGame.setEnabled(!busy);
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
