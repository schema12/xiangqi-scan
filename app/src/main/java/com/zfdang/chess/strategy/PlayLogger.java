package com.zfdang.chess.strategy;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Append-only JSONL log of every strategy decision (design: PlayLogger).
 * Written on the decision thread; capped by size (rotate = delete) so it can never
 * grow unbounded or disturb a game. Entries follow the schema documented in
 * docs/aggressive-ai.md and are the data source for A/B match analysis.
 */
public final class PlayLogger {

    private final File file;
    private final boolean enabled;

    public PlayLogger(Context context) {
        File dir = (context != null) ? new File(context.getFilesDir(), "strategy_logs") : null;
        if (dir == null) {
            this.file = null;
            this.enabled = false;
            return;
        }
        this.file = new File(dir, "decisions.jsonl");
        this.enabled = true;
        if (!dir.exists()) dir.mkdirs();
    }

    /** Test hook: write to an explicit file. */
    PlayLogger(File file) {
        this.file = file;
        this.enabled = file != null;
    }

    public void log(String jsonLine) {
        if (!enabled || jsonLine == null) return;
        try {
            if (file.exists() && file.length() > StrategyParams.LOG_MAX_BYTES) {
                // rotate: keep it simple, old history is deleted (logs are diagnostics)
                File old = new File(file.getParentFile(), "decisions.1.jsonl");
                old.delete();
                file.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((jsonLine + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            Log.w("PlayLogger", "log failed: " + e);
        }
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
