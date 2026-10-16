package com.school.smsgateway;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ============================================================================
 * CLASS: Logger
 * PACKAGE: com.school.smsgateway
 * DESCRIPTION:
 *     Centralized Persistent File and Logcat Diagnostics Logger.
 *     Stores exhaustive chronological logs to a fixed, ADB-accessible file path
 *     so developers and autonomous agents can inspect real-time cellular and
 *     HTTP operations without requiring UI access.
 *
 * KNOWN ACCESSIBLE FILE LOCATIONS:
 *     Primary:   /sdcard/Android/data/com.school.smsgateway/files/gateway.log
 *     Logcat Tag: SchoolSMSGateway
 *
 * THREADING & PERFORMANCE:
 *     - Uses a dedicated single-thread executor to write asynchronously to disk
 *       without blocking the main Android UI or cellular dispatch threads.
 *     - Rotates log files when exceeding 2MB to prevent storage bloat.
 *
 * AUTHOR: School System Development Team
 * LAST MODIFIED: 2026-10-16
 * ============================================================================
 */
public class Logger {

    public static final String TAG = "SchoolSMSGateway";
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2 Megabytes
    private static File logFile = null;
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final ExecutorService diskWriterExecutor = Executors.newSingleThreadExecutor();

    /**
     * Initializes the file logging subsystem with application context.
     * Computes the permanent, standard storage path.
     *
     * @param context Application or Activity context.
     */
    public static synchronized void init(Context context) {
        if (logFile != null) return;

        try {
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                if (!externalDir.exists()) {
                    externalDir.mkdirs();
                }
                logFile = new File(externalDir, "gateway.log");
            } else {
                // Fallback to internal storage files dir
                logFile = new File(context.getFilesDir(), "gateway.log");
            }

            i(TAG, "=== LOGGER INITIALIZED ===");
            i(TAG, "Log file location: " + logFile.getAbsolutePath());
            i(TAG, "Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize disk logger", e);
        }
    }

    public static void d(String tag, String message) {
        log("DEBUG", tag, message, null);
    }

    public static void i(String tag, String message) {
        log("INFO", tag, message, null);
    }

    public static void w(String tag, String message) {
        log("WARN", tag, message, null);
    }

    public static void e(String tag, String message) {
        log("ERROR", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        log("ERROR", tag, message, throwable);
    }

    /**
     * Internal logger coordinating logcat emission and asynchronous disk persistence.
     */
    private static void log(String level, String tag, String message, Throwable throwable) {
        // 1. Output to standard Android Logcat
        switch (level) {
            case "DEBUG": Log.d(tag, message, throwable); break;
            case "INFO":  Log.i(tag, message, throwable); break;
            case "WARN":  Log.w(tag, message, throwable); break;
            case "ERROR": Log.e(tag, message, throwable); break;
            default:      Log.v(tag, message, throwable); break;
        }

        // 2. Format log line with timestamp and thread ID
        String timestamp = dateFormat.format(new Date());
        String threadName = Thread.currentThread().getName();
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp)
          .append(" [")
          .append(level)
          .append("] [")
          .append(threadName)
          .append("] ")
          .append(tag)
          .append(": ")
          .append(message)
          .append("\n");

        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw.toString()).append("\n");
        }

        final String formattedEntry = sb.toString();

        // 3. Dispatch append operation to background disk queue
        diskWriterExecutor.execute(() -> {
            if (logFile == null) return;
            try {
                // Rotate log if size exceeds 2MB
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
                    File backupFile = new File(logFile.getParent(), "gateway.log.old");
                    if (backupFile.exists()) backupFile.delete();
                    logFile.renameTo(backupFile);
                }

                FileWriter writer = new FileWriter(logFile, true);
                writer.write(formattedEntry);
                writer.flush();
                writer.close();
            } catch (Exception e) {
                Log.e(TAG, "Error writing entry to log file: " + e.getMessage());
            }
        });
    }

    /**
     * Returns the absolute path of the active log file on disk.
     */
    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "Not initialized";
    }
}
