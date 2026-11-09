package com.rexglue.app;

import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintStream;
import java.util.ArrayList;

import org.libsdl.app.SDLActivity;

/**
 * ReXGlue SDL Activity for Android / POCO X7 Pro.
 * Runs Xbox 360 recompiled games using Vulkan and SDL3.
 */
public class GameActivity extends SDLActivity {
    private static final String TAG = "ReXGlue";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in " + thread.getName(), throwable);
        });

        // Request storage permissions if not granted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            }
        }

        // Keep screen on during gameplay
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getArguments() {
        ArrayList<String> args = new ArrayList<>();

        // 1. Check Intent extras
        String gameData = getIntent().getStringExtra("game_data_root");
        if (gameData != null && !gameData.isEmpty()) {
            args.add("--game_data_root=" + gameData);
        }
        String updateData = getIntent().getStringExtra("update_data_root");
        if (updateData != null && !updateData.isEmpty()) {
            args.add("--update_data_root=" + updateData);
        }
        ArrayList<String> allIsos = getIntent().getStringArrayListExtra("iso_paths");
        if (allIsos != null) {
            if (args.isEmpty() && allIsos.size() > 0) {
                args.add("--game_data_root=" + allIsos.get(0));
            }
            if (allIsos.size() > 1) {
                args.add("--disc2_data_root=" + allIsos.get(1));
            }
            if (allIsos.size() > 2) {
                args.add("--disc3_data_root=" + allIsos.get(2));
            }
        }
        if (!args.isEmpty()) {
            return args.toArray(new String[0]);
        }

        // 2. Check APK bundled assets (rexglue.cfg / args.txt)
        for (String assetName : new String[] { "rexglue.cfg", "args.txt" }) {
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(getAssets().open(assetName)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("--")) {
                        if (!args.contains(line)) args.add(line);
                    } else if (line.startsWith("iso_0=")) {
                        String v = "--game_data_root=" + line.substring("iso_0=".length()).trim();
                        if (!args.contains(v)) args.add(v);
                    } else if (line.startsWith("iso_path=")) {
                        String v = "--game_data_root=" + line.substring("iso_path=".length()).trim();
                        if (!args.contains(v)) args.add(v);
                    } else if (line.startsWith("iso_1=")) {
                        String v = "--disc2_data_root=" + line.substring("iso_1=".length()).trim();
                        if (!args.contains(v)) args.add(v);
                    } else if (line.startsWith("iso_2=")) {
                        String v = "--disc3_data_root=" + line.substring("iso_2=".length()).trim();
                        if (!args.contains(v)) args.add(v);
                    } else if (line.startsWith("update_path=")) {
                        String v = "--update_data_root=" + line.substring("update_path=".length()).trim();
                        if (!args.contains(v)) args.add(v);
                    }
                }
            } catch (Exception ignored) {}
            if (!args.isEmpty()) {
                return args.toArray(new String[0]);
            }
        }

        // 3. Check candidate config files in Download, app data, and sdcard
        File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        ArrayList<File> candidateConfigFiles = new ArrayList<>();
        if (downloadDir != null && downloadDir.exists()) {
            candidateConfigFiles.add(new File(downloadDir, "rexglue.cfg"));
            candidateConfigFiles.add(new File(downloadDir, "args.txt"));
            File[] subs = downloadDir.listFiles(File::isDirectory);
            if (subs != null) {
                for (File sub : subs) {
                    candidateConfigFiles.add(new File(sub, "rexglue.cfg"));
                    candidateConfigFiles.add(new File(sub, "args.txt"));
                }
            }
        }

        candidateConfigFiles.add(new File(getExternalFilesDir(null), "rexglue.cfg"));
        candidateConfigFiles.add(new File(getExternalFilesDir(null), "args.txt"));
        candidateConfigFiles.add(new File(getFilesDir(), "rexglue.cfg"));
        candidateConfigFiles.add(new File(getFilesDir(), "args.txt"));
        candidateConfigFiles.add(new File("/storage/emulated/0/Download/rexglue.cfg"));
        candidateConfigFiles.add(new File("/storage/emulated/0/Download/args.txt"));
        candidateConfigFiles.add(new File("/storage/emulated/0/rexglue.cfg"));
        candidateConfigFiles.add(new File("/sdcard/rexglue.cfg"));
        candidateConfigFiles.add(new File("/storage/emulated/0/Android/data/com.rexglue.app/files/rexglue.cfg"));
        candidateConfigFiles.add(new File("/storage/emulated/0/Android/data/com.rexglue.app/files/args.txt"));

        for (File cfg : candidateConfigFiles) {
            if (cfg != null && cfg.exists() && cfg.isFile()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (line.startsWith("--")) {
                            if (!args.contains(line)) args.add(line);
                        } else if (line.startsWith("iso_0=")) {
                            String v = "--game_data_root=" + line.substring("iso_0=".length()).trim();
                            if (!args.contains(v)) args.add(v);
                        } else if (line.startsWith("iso_path=")) {
                            String v = "--game_data_root=" + line.substring("iso_path=".length()).trim();
                            if (!args.contains(v)) args.add(v);
                        } else if (line.startsWith("iso_1=")) {
                            String v = "--disc2_data_root=" + line.substring("iso_1=".length()).trim();
                            if (!args.contains(v)) args.add(v);
                        } else if (line.startsWith("iso_2=")) {
                            String v = "--disc3_data_root=" + line.substring("iso_2=".length()).trim();
                            if (!args.contains(v)) args.add(v);
                        } else if (line.startsWith("update_path=")) {
                            String v = "--update_data_root=" + line.substring("update_path=".length()).trim();
                            if (!args.contains(v)) args.add(v);
                        }
                    }
                } catch (Exception ignored) {}
                if (!args.isEmpty()) {
                    return args.toArray(new String[0]);
                }
            }
        }

        // 3. Check internal standalone game assets folder
        File internalGameDir = new File(getFilesDir(), "game");
        if (internalGameDir.exists() && internalGameDir.isDirectory()) {
            args.add("--game_data_root=" + internalGameDir.getAbsolutePath());
            return args.toArray(new String[0]);
        }

        // 4. Check common Xbox360 storage directory
        File defaultDir = new File(android.os.Environment.getExternalStorageDirectory(), "Xbox360");
        if (defaultDir.exists()) {
            File[] isos = defaultDir.listFiles((d, name) -> name.toLowerCase().endsWith(".iso"));
            if (isos != null && isos.length > 0) {
                args.add("--game_data_root=" + isos[0].getAbsolutePath());
                if (isos.length > 1) {
                    args.add("--disc2_data_root=" + isos[1].getAbsolutePath());
                }
                return args.toArray(new String[0]);
            }
        }

        return super.getArguments();
    }

    @Override
    protected String[] getLibraries() {
        File mainLib = new File(getApplicationInfo().nativeLibraryDir, "libmain.so");
        if (mainLib.exists()) {
            return new String[] {
                "c++_shared",
                "rexruntime",
                "main"
            };
        }
        return new String[] {
            "c++_shared",
            "rexruntime"
        };
    }
}
