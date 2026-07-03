package com.example.agenttoolbox.gm;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class RootManager {

    private static Boolean hasRootAccess = null;
    private static Process suProcess = null;
    private static DataOutputStream suOutputStream = null;
    private static BufferedReader suReader = null;

    public static boolean checkRootAccess() {
        if (Boolean.TRUE.equals(hasRootAccess)) return true;
        return initSuShell();
    }

    private static boolean initSuShell() {
        if (Boolean.TRUE.equals(hasRootAccess)) return true;

        try {
            suProcess = Runtime.getRuntime().exec("su");
            suOutputStream = new DataOutputStream(suProcess.getOutputStream());
            suReader = new BufferedReader(new InputStreamReader(suProcess.getInputStream()));

            String result = executeCommandInternal("id");
            hasRootAccess = result != null && result.contains("uid=0");

            if (!hasRootAccess) {
                closeSuShell();
            }

            return hasRootAccess;
        } catch (Exception e) {
            hasRootAccess = false;
            closeSuShell();
            return false;
        }
    }

    public static boolean requestRootAccess() {
        return checkRootAccess();
    }

    public static String executeRootCommand(String command) {
        if (!Boolean.TRUE.equals(hasRootAccess)) {
            if (!initSuShell()) return null;
        }
        return executeCommandInternal(command);
    }

    private static String executeCommandInternal(String command) {
        try {
            DataOutputStream os = suOutputStream;
            BufferedReader reader = suReader;
            if (os == null || reader == null) return null;

            String marker = "CMD_DONE_" + System.nanoTime();
            
            os.writeBytes(command + "\n");
            os.writeBytes("echo " + marker + "\n");
            os.flush();

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(marker)) break;
                output.append(line).append("\n");
            }

            return output.toString().trim();
        } catch (Exception e) {
            closeSuShell();
            return null;
        }
    }

    private static void closeSuShell() {
        try {
            if (suOutputStream != null) {
                suOutputStream.writeBytes("exit\n");
                suOutputStream.flush();
                suOutputStream.close();
            }
            if (suReader != null) suReader.close();
            if (suProcess != null) suProcess.destroy();
        } catch (Exception e) {}
        suProcess = null;
        suOutputStream = null;
        suReader = null;
    }

    public static String getRootStatus() {
        if (Boolean.TRUE.equals(hasRootAccess)) return "已获取 Root 权限";
        if (Boolean.FALSE.equals(hasRootAccess)) return "未获取 Root 权限";
        return "未检测";
    }

    public static void resetRootStatus() {
        closeSuShell();
        hasRootAccess = null;
    }

    public static byte[] readMemoryViaRoot(int pid, long address, int size) {
        try {
            String cmd = "dd if=/proc/" + pid + "/mem bs=1 skip=" + address + " count=" + size + " 2>/dev/null | xxd -p";
            String hexResult = executeRootCommand(cmd);
            if (hexResult == null || hexResult.isEmpty()) return null;

            String hexClean = hexResult.replaceAll("\\s", "");
            if (hexClean.isEmpty()) return null;

            byte[] bytes = new byte[hexClean.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                String hex = hexClean.substring(i * 2, i * 2 + 2);
                bytes[i] = (byte) Integer.parseInt(hex, 16);
            }

            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean writeMemoryViaRoot(int pid, long address, byte[] data) {
        try {
            StringBuilder hexBuilder = new StringBuilder();
            for (byte b : data) {
                hexBuilder.append(String.format("%02x", b));
            }
            String hexString = hexBuilder.toString();

            String cmd = "echo '" + hexString + "' | xxd -r -p | dd of=/proc/" + pid + "/mem bs=1 seek=" + address + " count=" + data.length + " 2>/dev/null";
            String result = executeRootCommand(cmd);

            return result != null;
        } catch (Exception e) {
            return false;
        }
    }
}