package com.zfdang.chess.controllers;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** 引擎/控制器链路文件日志（该 ROM 的 logcat 对本应用不可靠）。写入走后台线程。 */
public class GcLog {
    private static final ExecutorService IO =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "GcLog"));

    public static void d(String msg) {
        IO.execute(() -> {
            try {
                Context ctx = com.zfdang.chess.ChessApp.getContext();
                File dir = new File(ctx.getDir("debug", Context.MODE_PRIVATE), "");
                dir.mkdirs();
                File f = new File(dir, "gc.log");
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((ts + " " + msg + "\n").getBytes());
                fos.close();
            } catch (Exception ignored) {}
        });
    }
}
