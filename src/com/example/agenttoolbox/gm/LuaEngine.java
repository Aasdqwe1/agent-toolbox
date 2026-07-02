package com.example.agenttoolbox.gm;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class LuaEngine {

    private static Context context = null;
    private static Handler mainHandler = new Handler(Looper.getMainLooper());
    private static List<java.util.Map<String, Object>> searchResults = new ArrayList<>();
    private static List<java.util.Map<String, Object>> frozenList = new ArrayList<>();
    private static StringBuilder outputLog = new StringBuilder();

    public static void setContext(Context ctx) {
        context = ctx;
    }

    public static void setActivity(android.app.Activity act) {
        context = act;
    }

    public static String executeScript(String scriptContent) {
        outputLog.setLength(0);
        searchResults.clear();
        frozenList.clear();

        try {
            org.luaj.vm2.Globals globals = JsePlatform.standardGlobals();
            LuaTable gg = new LuaTable();
            registerGgApi(gg);
            globals.set("gg", gg);
            LuaValue chunk = globals.load(scriptContent);
            chunk.call();
            return outputLog.toString();
        } catch (Exception e) {
            String errorMsg = "Lua 执行错误: " + e.getMessage();
            outputLog.append(errorMsg);
            return outputLog.toString();
        }
    }

    private static int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_PHONE;
        }
    }

    private static int showChoiceDialog(final String title, final List<String> items) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger selectedIndex = new AtomicInteger(-1);
        final Context ctx = context;
        if (ctx == null) return -1;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
                    builder.setTitle(title)
                        .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                selectedIndex.set(which + 1);
                                latch.countDown();
                            }
                        })
                        .setCancelable(false)
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                selectedIndex.set(-1);
                                latch.countDown();
                            }
                        });
                    android.app.AlertDialog dialog = builder.create();
                    if (!(ctx instanceof android.app.Activity)) {
                        dialog.getWindow().setType(getOverlayType());
                    }
                    dialog.show();
                } catch (Exception e) {
                    selectedIndex.set(-1);
                    latch.countDown();
                }
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {}
        return selectedIndex.get();
    }

    private static String showInputDialog(final String title, final String defaultValue) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> inputResult = new AtomicReference<>(defaultValue);
        final Context ctx = context;
        if (ctx == null) return defaultValue;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final android.widget.EditText editText = new android.widget.EditText(ctx);
                    editText.setText(defaultValue);
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
                    builder.setTitle(title)
                        .setView(editText)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                inputResult.set(editText.getText().toString());
                                latch.countDown();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                inputResult.set(defaultValue);
                                latch.countDown();
                            }
                        })
                        .setCancelable(false);
                    android.app.AlertDialog dialog = builder.create();
                    if (!(ctx instanceof android.app.Activity)) {
                        dialog.getWindow().setType(getOverlayType());
                    }
                    dialog.show();
                } catch (Exception e) {
                    inputResult.set(defaultValue);
                    latch.countDown();
                }
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {}
        return inputResult.get();
    }

    private static boolean showConfirmDialog(final String title, final String message) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger confirmed = new AtomicInteger(0);
        final Context ctx = context;
        if (ctx == null) return false;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
                    builder.setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                confirmed.set(1);
                                latch.countDown();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                confirmed.set(0);
                                latch.countDown();
                            }
                        })
                        .setCancelable(false);
                    android.app.AlertDialog dialog = builder.create();
                    if (!(ctx instanceof android.app.Activity)) {
                        dialog.getWindow().setType(getOverlayType());
                    }
                    dialog.show();
                } catch (Exception e) {
                    confirmed.set(0);
                    latch.countDown();
                }
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {}
        return confirmed.get() == 1;
    }

    private static String luaTypeToDataType(int type) {
        switch (type) {
            case 1: return "byte";
            case 2: return "word";
            case 4: return "dword";
            case 8: return "qword";
            case 16: return "float";
            case 32: return "double";
            default: return "dword";
        }
    }

    private static int dataTypeToLuaType(String type) {
        switch (type) {
            case "byte": return 1;
            case "word": return 2;
            case "dword": return 4;
            case "qword": return 8;
            case "float": return 16;
            case "double": return 32;
            default: return 4;
        }
    }

    private static void registerGgApi(LuaTable gg) {
        gg.set("toast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                final String msg = arg.tojstring();
                outputLog.append("📢 ").append(msg).append("\n");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); } catch (Exception e) {}
                    }
                });
                return LuaValue.NIL;
            }
        });

        gg.set("alert", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String msg = arg.tojstring();
                outputLog.append("⚠️ ").append(msg).append("\n");
                showConfirmDialog("提示", msg);
                return LuaValue.NIL;
            }
        });

        gg.set("prompt", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String msg = args.arg(1).tojstring();
                String defaultValue = "";
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    LuaTable table = args.arg(2).checktable();
                    if (table.length() > 0) defaultValue = table.get(1).tojstring();
                }
                String result = showInputDialog(msg, defaultValue);
                outputLog.append("📝 ").append(msg).append(" → ").append(result).append("\n");
                LuaTable resultTable = new LuaTable();
                resultTable.set(1, LuaValue.valueOf(result));
                return resultTable;
            }
        });

        gg.set("choice", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                List<String> items = new ArrayList<>();
                String title = "选择";
                if (args.arg(1).istable()) {
                    LuaTable table = args.arg(1).checktable();
                    for (int i = 1; i <= table.length(); i++) {
                        items.add(table.get(i).tojstring());
                    }
                }
                if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    title = args.arg(3).tojstring();
                } else if (args.narg() >= 2 && args.arg(2).isstring()) {
                    LuaValue second = args.arg(2);
                    if (second.isstring()) title = second.tojstring();
                }
                if (items.isEmpty()) return LuaValue.NIL;

                outputLog.append("📋 ").append(title).append("\n");
                for (int i = 0; i < items.size(); i++) {
                    outputLog.append("  ").append(i + 1).append(". ").append(items.get(i)).append("\n");
                }

                int selected = showChoiceDialog(title, items);
                if (selected > 0) {
                    outputLog.append("  → 选择了: ").append(items.get(selected - 1)).append("\n");
                } else {
                    outputLog.append("  → 已取消").append("\n");
                }
                return selected > 0 ? LuaValue.valueOf(selected) : LuaValue.NIL;
            }
        });

        gg.set("searchNumber", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue arg1, LuaValue arg2) {
                String value = arg1.tojstring();
                String type = luaTypeToDataType(arg2.toint());
                Object numValue = parseNumber(value, type);
                if (numValue == null) return LuaValue.valueOf(0);

                org.json.JSONArray results = MemoryEngine.searchExact(numValue, type);
                searchResults.clear();
                try {
                    for (int i = 0; i < results.length(); i++) {
                        org.json.JSONObject obj = results.getJSONObject(i);
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("address", obj.getString("address"));
                        map.put("addressInt", obj.getLong("addressInt"));
                        map.put("value", obj.get("value"));
                        map.put("type", obj.getString("type"));
                        searchResults.add(map);
                    }
                } catch (Exception e) {}

                outputLog.append("🔍 搜索 ").append(value).append(" (").append(type).append("): 找到 ").append(searchResults.size()).append(" 个结果\n");
                return LuaValue.valueOf(searchResults.size());
            }
        });

        gg.set("refineNumber", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                String value = arg.tojstring();
                List<Long> prevAddresses = new ArrayList<>();
                for (java.util.Map<String, Object> r : searchResults) {
                    prevAddresses.add((Long) r.get("addressInt"));
                }

                String type = "dword";
                if (!searchResults.isEmpty()) {
                    type = (String) searchResults.get(0).get("type");
                }

                Object numValue = parseNumber(value, type);
                if (numValue == null) return LuaValue.valueOf(0);

                org.json.JSONArray results = MemoryEngine.filterResults(prevAddresses, numValue, type);
                searchResults.clear();
                try {
                    for (int i = 0; i < results.length(); i++) {
                        org.json.JSONObject obj = results.getJSONObject(i);
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        map.put("address", obj.getString("address"));
                        map.put("addressInt", obj.getLong("addressInt"));
                        map.put("value", obj.get("value"));
                        map.put("type", obj.getString("type"));
                        searchResults.add(map);
                    }
                } catch (Exception e) {}

                outputLog.append("🔍 过滤后: ").append(searchResults.size()).append(" 个结果\n");
                return LuaValue.valueOf(searchResults.size());
            }
        });

        ZeroArgFunction getResultsCountFunc = new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(searchResults.size());
            }
        };
        gg.set("getResultsCount", getResultsCountFunc);
        gg.set("getResultCount", getResultsCountFunc);

        gg.set("getResults", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int count = arg.toint();
                LuaTable table = new LuaTable();
                int takeCount = Math.min(count, searchResults.size());
                for (int i = 0; i < takeCount; i++) {
                    java.util.Map<String, Object> result = searchResults.get(i);
                    LuaTable item = new LuaTable();
                    item.set("address", LuaValue.valueOf((String) result.get("address")));
                    Number numValue = (Number) result.get("value");
                    item.set("value", LuaValue.valueOf(numValue != null ? numValue.doubleValue() : 0.0));
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType((String) result.get("type"))));
                    table.set(i + 1, item);
                }
                return table;
            }
        });

        gg.set("setValues", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (!arg.istable()) return LuaValue.valueOf(0);
                LuaTable table = arg.checktable();
                int count = 0;
                for (int i = 1; i <= table.length(); i++) {
                    LuaValue item = table.get(i);
                    if (item.istable()) {
                        LuaTable itemTable = item.checktable();
                        LuaValue addr = itemTable.get("address");
                        LuaValue value = itemTable.get("value");
                        LuaValue flags = itemTable.get("flags");
                        if (!addr.isnil() && !value.isnil()) {
                            long address = parseAddress(addr.tojstring());
                            String type = luaTypeToDataType(flags.toint());
                            Object numValue = parseNumberValue(value, type);
                            if (address >= 0 && numValue != null) {
                                if (MemoryEngine.writeMemory(address, numValue, type)) count++;
                            }
                        }
                    }
                }
                outputLog.append("✏️ 已修改 ").append(count).append(" 个地址\n");
                return LuaValue.valueOf(count);
            }
        });

        gg.set("writeMemory", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                long address = parseAddress(args.arg(1).tojstring());
                if (address < 0) return LuaValue.valueOf(false);
                LuaValue value = args.arg(2);
                String type = luaTypeToDataType(args.arg(3).toint());
                Object numValue = parseNumberValue(value, type);
                if (numValue == null) return LuaValue.valueOf(false);

                boolean success = MemoryEngine.writeMemory(address, numValue, type);
                if (success) {
                    outputLog.append("✏️ 写入 0x").append(Long.toHexString(address).toUpperCase()).append(" = ").append(numValue).append("\n");
                }
                return LuaValue.valueOf(success);
            }
        });

        gg.set("freeze", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                long address = parseAddress(args.arg(1).tojstring());
                if (address < 0) return LuaValue.valueOf(false);
                LuaValue value = args.arg(2);
                String type = luaTypeToDataType(args.arg(3).toint());
                Object numValue = parseNumberValue(value, type);
                if (numValue == null) return LuaValue.valueOf(false);

                boolean success = MemoryFreezer.freeze(address, numValue, type);
                if (success) {
                    outputLog.append("🔒 冻结 0x").append(Long.toHexString(address).toUpperCase()).append(" = ").append(numValue).append("\n");
                }
                return LuaValue.valueOf(success);
            }
        });

        gg.set("addListItems", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                if (!arg.istable()) return LuaValue.valueOf(0);
                LuaTable table = arg.checktable();
                int count = 0;
                for (int i = 1; i <= table.length(); i++) {
                    LuaValue item = table.get(i);
                    if (item.istable()) {
                        LuaTable itemTable = item.checktable();
                        LuaValue freeze = itemTable.get("freeze");
                        if (freeze.toboolean()) {
                            long address = parseAddress(itemTable.get("address").tojstring());
                            if (address < 0) continue;
                            LuaValue value = itemTable.get("value");
                            String type = luaTypeToDataType(itemTable.get("flags").toint());
                            Object numValue = parseNumberValue(value, type);
                            if (numValue != null) {
                                if (MemoryFreezer.freeze(address, numValue, type)) count++;
                            }
                        }
                    }
                }
                outputLog.append("🔒 已冻结 ").append(count).append(" 个地址\n");
                return LuaValue.valueOf(count);
            }
        });

        gg.set("clearResults", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                searchResults.clear();
                return LuaValue.NIL;
            }
        });

        gg.set("clearList", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                frozenList.clear();
                MemoryFreezer.clearAll();
                return LuaValue.NIL;
            }
        });

        gg.set("sleep", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                try { Thread.sleep(arg.tolong()); } catch (InterruptedException e) {}
                return LuaValue.NIL;
            }
        });

        gg.set("TYPE_BYTE", LuaValue.valueOf(1));
        gg.set("TYPE_WORD", LuaValue.valueOf(2));
        gg.set("TYPE_DWORD", LuaValue.valueOf(4));
        gg.set("TYPE_QWORD", LuaValue.valueOf(8));
        gg.set("TYPE_FLOAT", LuaValue.valueOf(16));
        gg.set("TYPE_DOUBLE", LuaValue.valueOf(32));
    }

    private static Object parseNumber(String value, String type) {
        try {
            if ("float".equals(type) || "double".equals(type)) {
                return Double.parseDouble(value);
            } else {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return 0L;
            }
        }
    }

    private static Object parseNumberValue(LuaValue value, String type) {
        if ("float".equals(type)) return value.todouble();
        if ("double".equals(type)) return value.todouble();
        return value.tolong();
    }

    private static long parseAddress(String s) {
        String trimmed = s.trim();
        try {
            if (trimmed.startsWith("0x", 0)) {
                return Long.parseLong(trimmed.substring(2), 16);
            }
            return Long.parseLong(trimmed, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}