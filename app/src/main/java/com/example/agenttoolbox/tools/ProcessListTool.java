package com.example.agenttoolbox.tools;

import android.content.Context;
import com.example.agenttoolbox.gm.ProcessManager;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProcessListTool implements Tool {

    private Context context;

    public ProcessListTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "gm_process_list";
    }

    @Override
    public String getDescription() {
        return "获取设备上运行的应用进程列表（需要Root权限）";
    }

    @Override
    public JSONObject getInputSchema() {
        try {
            JSONObject schema = new JSONObject();
            return schema;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        if (!com.example.agenttoolbox.gm.RootManager.checkRootAccess()) {
            return "{\"error\": \"需要Root权限才能获取进程列表\"}";
        }
        
        JSONArray processes = ProcessManager.getProcessList(context);
        return processes.toString(2);
    }
}