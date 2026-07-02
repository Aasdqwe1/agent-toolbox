package com.example.agenttoolbox.gm;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MemoryEngine {

    private static final String TAG = "MemoryEngine";
    private static final int MAX_RESULTS = 500;

    private static Integer attachedPid = null;
    private static List<MemRegion> activeRegions = new ArrayList<>();
    private static Map<Long, byte[]> lastSnapshot = new HashMap<>();
    private static Context appContext = null;

    public static void setContext(Context context) {
        appContext = context;
    }

    public static boolean attachProcess(int pid) {
        try {
            if (!RootManager.checkRootAccess()) {
                Log.e(TAG, "No root access");
                return false;
            }

            activeRegions = getRegions(pid);
            if (activeRegions.isEmpty()) {
                Log.e(TAG, "Process " + pid + " has no accessible regions");
                return false;
            }

            attachedPid = pid;
            lastSnapshot.clear();

            long totalMB = 0;
            for (MemRegion r : activeRegions) {
                totalMB += r.endAddr - r.startAddr;
            }
            totalMB /= 1024 * 1024;
            Log.i(TAG, "Attached to process " + pid + " (" + activeRegions.size() + " regions, " + totalMB + "MB)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to attach: " + e.getMessage(), e);
            return false;
        }
    }

    public static void detachProcess() {
        attachedPid = null;
        activeRegions.clear();
        lastSnapshot.clear();
    }

    public static Integer getAttachedPid() {
        return attachedPid;
    }

    private static class MemRegion {
        long startAddr;
        long endAddr;
        int priority;

        MemRegion(long startAddr, long endAddr, int priority) {
            this.startAddr = startAddr;
            this.endAddr = endAddr;
            this.priority = priority;
        }
    }

    private static List<MemRegion> getRegions(int pid) {
        List<MemRegion> regions = new ArrayList<>();
        String mapsResult = RootManager.executeRootCommand("cat /proc/" + pid + "/maps 2>/dev/null");
        if (mapsResult == null) return regions;

        String[] lines = mapsResult.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;

            String[] addrRange = parts[0].split("-");
            if (addrRange.length != 2) continue;

            Long startAddr = parseLong(addrRange[0], 16);
            Long endAddr = parseLong(addrRange[1], 16);
            if (startAddr == null || endAddr == null) continue;

            String permissions = parts[1];
            String name = "";
            if (parts.length > 5) {
                StringBuilder sb = new StringBuilder();
                for (int i = 5; i < parts.length; i++) {
                    if (i > 5) sb.append(" ");
                    sb.append(parts[i]);
                }
                name = sb.toString();
            }

            long regionSize = endAddr - startAddr;
            if (regionSize <= 0) continue;
            if (!permissions.contains("r") || !permissions.contains("w")) continue;
            if (name.contains("/dev/ashmem") || name.contains("[anon:vulkan]")) continue;
            if (regionSize > 100 * 1024 * 1024) continue;

            int priority = 0;
            if (permissions.contains("r")) priority += 10;
            if (permissions.contains("w")) priority += 20;
            if (name.contains("[heap]")) priority += 70;
            else if (name.contains("[anon:")) priority += 50;
            else if (name.isEmpty()) priority += 40;

            regions.add(new MemRegion(startAddr, endAddr, priority));
        }

        regions.sort(new java.util.Comparator<MemRegion>() {
            @Override
            public int compare(MemRegion a, MemRegion b) {
                return Integer.compare(b.priority, a.priority);
            }
        });
        return regions;
    }

    public static JSONArray searchExact(Object value, String type) {
        JSONArray results = new JSONArray();
        Integer pid = attachedPid;
        if (pid == null || activeRegions.isEmpty()) return results;

        try {
            byte[] targetBytes = valueToBytes(value, type);
            if (targetBytes == null) return results;
            int typeSize = getTypeSize(type);

            List<Long> allAddresses = new ArrayList<>();
            for (MemRegion region : activeRegions) {
                long regionSize = region.endAddr - region.startAddr;
                if (regionSize < typeSize) continue;

                long chunkSize = 2 * 1024 * 1024;
                for (long offset = 0; offset < regionSize; offset += chunkSize) {
                    long addr = region.startAddr + offset;
                    long readSize = Math.min(chunkSize, regionSize - offset);
                    byte[] regionData = RootManager.readMemoryViaRoot(pid, addr, (int) readSize);
                    if (regionData == null) continue;

                    for (int i = 0; i <= regionData.length - typeSize; i++) {
                        boolean match = true;
                        for (int j = 0; j < typeSize; j++) {
                            if (regionData[i + j] != targetBytes[j]) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            allAddresses.add(addr + i);
                            if (allAddresses.size() >= MAX_RESULTS) break;
                        }
                    }
                    if (allAddresses.size() >= MAX_RESULTS) break;
                }
                if (allAddresses.size() >= MAX_RESULTS) break;
            }

            for (Long addr : allAddresses) {
                JSONObject result = createResultMap(addr, value, type);
                results.put(result);
            }
            saveSnapshot(results, type);

        } catch (Exception e) {
            Log.e(TAG, "searchExact failed: " + e.getMessage(), e);
        }

        return results;
    }

    public static JSONArray filterResults(List<Long> previousAddresses, Object value, String type) {
        JSONArray results = new JSONArray();
        Integer pid = attachedPid;
        if (pid == null) return results;

        try {
            int typeSize = getTypeSize(type);
            byte[] targetBytes = valueToBytes(value, type);
            if (targetBytes == null) return results;

            for (Long addr : previousAddresses) {
                byte[] bytes = RootManager.readMemoryViaRoot(pid, addr, typeSize);
                if (bytes != null && bytes.length == typeSize) {
                    boolean match = true;
                    for (int i = 0; i < typeSize; i++) {
                        if (bytes[i] != targetBytes[i]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        JSONObject result = createResultMap(addr, value, type);
                        results.put(result);
                    }
                }
            }

            if (results.length() > 0) {
                saveSnapshot(results, type);
            }

        } catch (Exception e) {
            Log.e(TAG, "filterResults failed: " + e.getMessage(), e);
        }

        return results;
    }

    public static Object readMemory(long address, String type) {
        Integer pid = attachedPid;
        if (pid == null) return null;

        try {
            int typeSize = getTypeSize(type);
            byte[] bytes = RootManager.readMemoryViaRoot(pid, address, typeSize);
            if (bytes == null) return null;
            return bytesToValue(bytes, type);
        } catch (Exception e) {
            Log.e(TAG, "readMemory failed: " + e.getMessage(), e);
            return null;
        }
    }

    public static boolean writeMemory(long address, Object value, String type) {
        Integer pid = attachedPid;
        if (pid == null) return false;

        try {
            byte[] bytes = valueToBytes(value, type);
            if (bytes == null) return false;
            return RootManager.writeMemoryViaRoot(pid, address, bytes);
        } catch (Exception e) {
            Log.e(TAG, "writeMemory failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static JSONArray searchAob(String pattern) {
        JSONArray results = new JSONArray();
        Integer pid = attachedPid;
        if (pid == null || activeRegions.isEmpty()) return results;

        Long addrLong = parseAddress(pattern);
        if (addrLong != null) {
            return readAddressValues(pid, addrLong);
        }

        try {
            Pair<byte[], byte[]> parsed = parseAobPattern(pattern);
            byte[] patternBytes = parsed.first;
            byte[] maskBytes = parsed.second;
            if (patternBytes.length == 0) return results;

            List<Long> allAddresses = new ArrayList<>();
            for (MemRegion region : activeRegions) {
                long regionSize = region.endAddr - region.startAddr;
                if (regionSize < patternBytes.length) continue;

                long chunkSize = 2 * 1024 * 1024;
                for (long offset = 0; offset < regionSize; offset += chunkSize) {
                    long addr = region.startAddr + offset;
                    long readSize = Math.min(chunkSize, regionSize - offset);
                    byte[] regionData = RootManager.readMemoryViaRoot(pid, addr, (int) readSize);
                    if (regionData == null) continue;

                    for (int i = 0; i <= regionData.length - patternBytes.length; i++) {
                        boolean match = true;
                        for (int j = 0; j < patternBytes.length; j++) {
                            if (maskBytes[j] != 0 && regionData[i + j] != patternBytes[j]) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            allAddresses.add(addr + i);
                            if (allAddresses.size() >= MAX_RESULTS) break;
                        }
                    }
                    if (allAddresses.size() >= MAX_RESULTS) break;
                }
                if (allAddresses.size() >= MAX_RESULTS) break;
            }

            for (Long addr : allAddresses) {
                byte[] mc = RootManager.readMemoryViaRoot(pid, addr, 8);
                String mcStr = mc != null ? bytesToHex(mc) : "";
                byte[] valBytes = RootManager.readMemoryViaRoot(pid, addr, 4);
                Object actualValue = valBytes != null ? bytesToValue(valBytes, "dword") : 0;

                JSONObject result = new JSONObject();
                result.put("address", "0x" + Long.toHexString(addr).toUpperCase());
                result.put("addressInt", addr);
                result.put("value", actualValue);
                result.put("type", "aob");
                result.put("isFavorite", false);
                result.put("isFrozen", false);
                result.put("machineCode", mcStr);
                results.put(result);
            }

        } catch (Exception e) {
            Log.e(TAG, "searchAob failed: " + e.getMessage(), e);
        }

        return results;
    }

    private static Long parseAddress(String input) {
        String s = input.trim();
        try {
            if (s.startsWith("0x", 0)) {
                return Long.parseLong(s.substring(2), 16);
            }
            if (s.length() >= 6 && s.matches("[0-9a-fA-F]+")) {
                return Long.parseLong(s, 16);
            }
        } catch (NumberFormatException e) {}
        return null;
    }

    private static JSONArray readAddressValues(int pid, long address) {
        JSONArray results = new JSONArray();
        try {
            byte[] mc = RootManager.readMemoryViaRoot(pid, address, 8);
            String mcStr = mc != null ? bytesToHex(mc) : "";

            String[][] types = {{"dword", "4"}, {"float", "4"}, {"double", "8"}, {"word", "2"}, {"byte", "1"}};
            for (String[] typeInfo : types) {
                String type = typeInfo[0];
                int size = Integer.parseInt(typeInfo[1]);
                try {
                    byte[] bytes = RootManager.readMemoryViaRoot(pid, address, size);
                    if (bytes != null && bytes.length == size) {
                        Object value = bytesToValue(bytes, type);
                        if (value != null) {
                            JSONObject result = new JSONObject();
                            result.put("address", "0x" + Long.toHexString(address).toUpperCase());
                            result.put("addressInt", address);
                            result.put("value", value);
                            result.put("type", type);
                            result.put("isFavorite", false);
                            result.put("isFrozen", false);
                            result.put("machineCode", mcStr);
                            results.put(result);
                        }
                    }
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        return results;
    }

    private static void saveSnapshot(JSONArray results, String type) {
        Integer pid = attachedPid;
        if (pid == null) return;

        int typeSize = getTypeSize(type);
        lastSnapshot.clear();

        try {
            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.getJSONObject(i);
                Long addr = result.getLong("addressInt");
                byte[] bytes = RootManager.readMemoryViaRoot(pid, addr, typeSize);
                if (bytes != null) {
                    lastSnapshot.put(addr, bytes);
                }
            }
        } catch (Exception e) {}
    }

    private static int getTypeSize(String type) {
        if ("byte".equals(type)) return 1;
        if ("word".equals(type)) return 2;
        if ("dword".equals(type)) return 4;
        if ("qword".equals(type)) return 8;
        if ("float".equals(type)) return 4;
        if ("double".equals(type)) return 8;
        return 4;
    }

    private static byte[] valueToBytes(Object value, String type) {
        try {
            Number num = (Number) value;
            switch (type) {
                case "byte":
                    return new byte[]{num.intValue() < 128 ? (byte) num.intValue() : (byte) (num.intValue() - 256)};
                case "word":
                    return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(num.shortValue()).array();
                case "dword":
                    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num.intValue()).array();
                case "qword":
                    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(num.longValue()).array();
                case "float":
                    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(num.floatValue()).array();
                case "double":
                    return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(num.doubleValue()).array();
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Object bytesToValue(byte[] bytes, String type) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            switch (type) {
                case "byte":
                    return bytes[0] & 0xFF;
                case "word":
                    return buf.getShort() & 0xFFFF;
                case "dword":
                    return buf.getInt();
                case "qword":
                    return buf.getLong();
                case "float":
                    return buf.getFloat();
                case "double":
                    return buf.getDouble();
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Pair<byte[], byte[]> parseAobPattern(String input) {
        String raw = input.trim();
        if (raw.startsWith("0x", 0)) raw = raw.substring(2);

        List<String> tokens = new ArrayList<>();
        if (raw.contains(" ")) {
            String[] parts = raw.split("\\s+");
            for (String part : parts) {
                if (!part.isEmpty()) tokens.add(part);
            }
        } else {
            int j = 0;
            while (j < raw.length()) {
                if (j + 1 < raw.length() && raw.charAt(j) == '?' && raw.charAt(j + 1) == '?') {
                    tokens.add("??");
                    j += 2;
                } else if (j + 1 < raw.length()) {
                    tokens.add(raw.substring(j, j + 2));
                    j += 2;
                } else {
                    j++;
                }
            }
        }

        byte[] patternBytes = new byte[tokens.size()];
        byte[] maskBytes = new byte[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("??".equals(token)) {
                patternBytes[i] = 0;
                maskBytes[i] = 0;
            } else {
                patternBytes[i] = (byte) Integer.parseInt(token, 16);
                maskBytes[i] = 1;
            }
        }

        return new Pair<>(patternBytes, maskBytes);
    }

    private static JSONObject createResultMap(long address, Object value, String type) {
        try {
            JSONObject result = new JSONObject();
            result.put("address", "0x" + Long.toHexString(address).toUpperCase());
            result.put("addressInt", address);
            result.put("value", value);
            result.put("type", type);
            result.put("isFavorite", false);
            result.put("isFrozen", false);

            Integer pid = attachedPid;
            if (pid != null) {
                byte[] mc = RootManager.readMemoryViaRoot(pid, address, 8);
                if (mc != null) {
                    result.put("machineCode", bytesToHex(mc));
                }
            }

            return result;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    private static Long parseLong(String s, int radix) {
        try {
            return Long.parseLong(s, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class Pair<A, B> {
        A first;
        B second;

        Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }
}