package com.example.agenttoolbox.gm;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MemoryFreezer {

    private static final String TAG = "MemoryFreezer";
    private static Map<Long, FreezeItem> frozenItems = new ConcurrentHashMap<>();
    private static ScheduledExecutorService freezeExecutor = null;

    private static class FreezeItem {
        long address;
        Object value;
        String type;

        FreezeItem(long address, Object value, String type) {
            this.address = address;
            this.value = value;
            this.type = type;
        }
    }

    public static boolean freeze(long address, Object value, String type) {
        Integer pid = MemoryEngine.getAttachedPid();
        if (pid == null) return false;

        try {
            boolean success = MemoryEngine.writeMemory(address, value, type);
            if (success) {
                frozenItems.put(address, new FreezeItem(address, value, type));
                startFreezeLoop();
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "freeze failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean unfreeze(long address) {
        frozenItems.remove(address);
        if (frozenItems.isEmpty()) {
            stopFreezeLoop();
        }
        return true;
    }

    public static void clearAll() {
        frozenItems.clear();
        stopFreezeLoop();
    }

    public static int getFrozenCount() {
        return frozenItems.size();
    }

    public static List<Map<String, Object>> getFrozenList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Long, FreezeItem> entry : frozenItems.entrySet()) {
            FreezeItem item = entry.getValue();
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("address", "0x" + Long.toHexString(item.address).toUpperCase());
            map.put("type", item.type);
            map.put("value", item.value != null ? item.value.toString() : "");
            list.add(map);
        }
        return list;
    }

    private static synchronized void startFreezeLoop() {
        if (freezeExecutor != null) return;

        freezeExecutor = Executors.newSingleThreadScheduledExecutor();
        freezeExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    Integer pid = MemoryEngine.getAttachedPid();
                    if (pid == null) {
                        stopFreezeLoop();
                        return;
                    }

                    for (Map.Entry<Long, FreezeItem> entry : frozenItems.entrySet()) {
                        FreezeItem item = entry.getValue();
                        MemoryEngine.writeMemory(item.address, item.value, item.type);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Freeze loop error: " + e.getMessage());
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    private static synchronized void stopFreezeLoop() {
        if (freezeExecutor != null) {
            freezeExecutor.shutdown();
            freezeExecutor = null;
        }
    }
}