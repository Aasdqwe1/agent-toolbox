package com.example.agenttoolbox.tools;

import org.json.JSONObject;

/**
 * 工具接口 - 所有工具必须实现此接口
 */
public interface Tool {
    
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 获取工具参数Schema（JSON格式）
     */
    JSONObject getInputSchema();
    
    /**
     * 执行工具
     * @param arguments 参数
     * @return 执行结果文本
     */
    String execute(JSONObject arguments) throws Exception;
    
}
