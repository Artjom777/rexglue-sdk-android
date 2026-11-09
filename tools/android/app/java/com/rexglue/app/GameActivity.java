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

        // 2. Check args.txt or rexglue.cfg in app storage
        File[] candidateConfigFiles = new File[] {
            new File(getExternalFilesDir(null), "args.txt"),
            new File(getFilesDir(), "args.txt"),
            new File(getExternalFilesDir(null), "rexglue.cfg"),
            new File(getFilesDir(), "rexglue.cfg")
        };

        for (File cfg : candidateConfigFiles) {
            if (cfg.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(cfg))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        if (line.startsWith("--")) {
                            args.add(line);
                        } else if (line.startsWith("iso_0=")) {
                            args.add("--game_data_root=" + line.substring("iso_0=".length()).trim());
                        } else if (line.startsWith("iso_path=")) {
                            args.add("--game_data_root=" + line.substring("iso_path=".length()).trim());
                        } else if (line.startsWith("iso_1=")) {
                            args.add("--disc2_data_root=" + line.substring("iso_1=".length()).trim());
                        } else if (line.startsWith("iso_2=")) {
                            args.add("--disc3_data_root=" + line.substring("iso_2=".length()).trim());
                        } else if (line.startsWith("update_path=")) {
                            args.add("--update_data_root=" + line.substring("update_path=".length()).trim());
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
