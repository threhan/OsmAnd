package com.peaknav.views;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import java.util.List;

/** The terrain engine must never initialise or terminate the host navigation process. */
public final class MountainProcess {
    private MountainProcess() { }
    public static boolean isCurrent(Context context) {
        String expected = context.getPackageName() + ":mountain_view";
        if (Build.VERSION.SDK_INT >= 28) return expected.equals(Application.getProcessName());
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == Process.myPid()) return expected.equals(process.processName);
        }
        return false;
    }
    public static void terminate(Context context) {
        if (isCurrent(context)) Process.killProcess(Process.myPid());
    }
}
