package com.example.agenttoolbox.gm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProcessManager {

    private static Map<String, ApplicationInfo> appInfoCache = new HashMap<>();

    public static JSONArray getProcessList(Context context) {
        JSONArray processes = new JSONArray();

        try {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            appInfoCache.clear();
            for (ApplicationInfo appInfo : installedApps) {
                appInfoCache.put(appInfo.packageName, appInfo);
            }

            String psResult = RootManager.executeRootCommand("ps -A -o PID,NAME");
            if (psResult != null) {
                String[] lines = psResult.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty() || line.startsWith("PID")) continue;
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length < 2) continue;

                    Integer pid = parseInt(parts[0]);
                    if (pid == null) continue;
                    String packageName = parts[1].trim();

                    if (packageName.isEmpty() || !packageName.contains(".")) continue;

                    String appName = getAppName(pm, packageName);
                    boolean isSystem = isSystemApp(packageName);

                    JSONObject proc = new JSONObject();
                    proc.put("pid", pid);
                    proc.put("packageName", packageName);
                    proc.put("processName", appName);
                    proc.put("uid", 0);
                    proc.put("isSystem", isSystem);
                    processes.put(proc);
                }
            }

            if (processes.length() == 0) {
                return getProcessListFallback(context);
            }
        } catch (Exception e) {
            return getProcessListFallback(context);
        }

        return sortProcesses(processes);
    }

    private static JSONArray getProcessListFallback(Context context) {
        JSONArray processes = new JSONArray();

        try {
            PackageManager pm = context.getPackageManager();

            String procResult = RootManager.executeRootCommand(
                "for pid in /proc/[0-9]*; do " +
                "p=${pid##*/}; " +
                "c=$(cat /proc/$p/cmdline 2>/dev/null | tr '\\0' ' ' | sed 's/ *$//'); " +
                "[ -n \"$c\" ] && echo \"$p|$c\"; " +
                "done"
            );

            if (procResult != null) {
                String[] lines = procResult.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty() || !line.contains("|")) continue;
                    String[] parts = line.split("\\|", 2);
                    if (parts.length < 2) continue;

                    Integer pid = parseInt(parts[0].trim());
                    if (pid == null) continue;
                    String packageName = parts[1].trim();

                    if (packageName.isEmpty()) continue;

                    String appName = getAppName(pm, packageName);
                    boolean isSystem = isSystemApp(packageName);

                    JSONObject proc = new JSONObject();
                    proc.put("pid", pid);
                    proc.put("packageName", packageName);
                    proc.put("processName", appName);
                    proc.put("uid", 0);
                    proc.put("isSystem", isSystem);
                    processes.put(proc);
                }
            }
        } catch (Exception e) {}

        return sortProcesses(processes);
    }

    private static String getAppName(PackageManager pm, String packageName) {
        try {
            ApplicationInfo appInfo = appInfoCache.get(packageName);
            if (appInfo != null) {
                return pm.getApplicationLabel(appInfo).toString();
            }
            return packageName;
        } catch (Exception e) {
            return packageName;
        }
    }

    private static boolean isSystemApp(String packageName) {
        return packageName.startsWith("com.android.") ||
               packageName.startsWith("android.") ||
               packageName.equals("system") ||
               packageName.equals("zygote") ||
               packageName.equals("zygote64") ||
               packageName.startsWith("com.google.android.") ||
               packageName.equals("root") ||
               packageName.equals("shell");
    }

    private static JSONArray sortProcesses(JSONArray processes) {
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < processes.length(); i++) {
            try {
                list.add(processes.getJSONObject(i));
            } catch (Exception e) {}
        }

        list.sort(new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                try {
                    String nameA = a.getString("processName");
                    String nameB = b.getString("processName");
                    boolean isChineseA = !nameA.isEmpty() && nameA.charAt(0) > 127;
                    boolean isChineseB = !nameB.isEmpty() && nameB.charAt(0) > 127;
                    if (isChineseA && !isChineseB) return -1;
                    if (!isChineseA && isChineseB) return 1;
                    return nameA.compareTo(nameB);
                } catch (Exception e) {
                    return 0;
                }
            }
        });

        JSONArray sorted = new JSONArray();
        for (JSONObject obj : list) {
            sorted.put(obj);
        }
        return sorted;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}