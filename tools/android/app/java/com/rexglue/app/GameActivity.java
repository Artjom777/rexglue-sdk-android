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
    protected String[] getLibraries() {
        return new String[] {
            "c++_shared",
            "rexruntime",
            "main"
        };
    }
}
