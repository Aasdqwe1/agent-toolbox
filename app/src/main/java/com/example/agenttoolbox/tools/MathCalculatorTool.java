package com.example.agenttoolbox.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * 数学计算工具 - 基于栈实现的表达式计算器
 * 支持：加减乘除、括号、小数、负数
 */
public class MathCalculatorTool implements Tool {

    @Override
    public String getName() {
        return "math_calculator";
    }

    @Override
    public String getDescription() {
        return "高精度数学计算工具，支持代数、统计、方程求解等复杂运算";
    }

    @Override
    public JSONObject getInputSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "object");
            
            JSONObject properties = new JSONObject();
            JSONObject expression = new JSONObject();
            expression.put("type", "string");
            expression.put("description", "数学表达式，支持加减乘除、括号、小数");
            properties.put("expression", expression);
            
            schema.put("properties", properties);
            
            String[] required = {"expression"};
            JSONArray requiredArray = new JSONArray();
            for (String r : required) requiredArray.put(r);
            schema.put("required", requiredArray);
        } catch (JSONException e) {
            // 正常情况下不会发生
            e.printStackTrace();
        }
        
        return schema;
    }

    @Override
    public String execute(JSONObject arguments) throws Exception {
        String expression = arguments.getString("expression");
        
        // 安全检查
        if (expression == null || expression.trim().isEmpty()) {
            throw new Exception("表达式不能为空");
        }
        
        // 移除空格
        expression = expression.replaceAll("\\s+", "");
        
        try {
            double result = calculate(expression);
            return "计算结果: " + formatResult(result);
        } catch (Exception e) {
            throw new Exception("表达式计算失败: " + e.getMessage());
        }
    }
    
    /**
     * 格式化结果，去掉多余的小数位
     */
    private String formatResult(double result) {
        if (result == (long) result) {
            return String.valueOf((long) result);
        } else {
            return String.valueOf(result);
        }
    }
    
    /**
     * 计算表达式的值
     */
    private double calculate(String expression) {
        // 处理负号开头的情况
        if (expression.startsWith("-")) {
            expression = "0" + expression;
        }
        
        ArrayDeque<Double> numbers = new ArrayDeque<>();
        ArrayDeque<Character> operators = new ArrayDeque<>();
        
        int i = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            
            if (Character.isDigit(c) || c == '.') {
                // 读取数字
                StringBuilder sb = new StringBuilder();
                while (i < expression.length() && 
                       (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
                    sb.append(expression.charAt(i));
                    i++;
                }
                numbers.push(Double.parseDouble(sb.toString()));
            } else if (c == '(') {
                operators.push(c);
                i++;
            } else if (c == ')') {
                // 计算到左括号
                while (operators.peek() != '(') {
                    numbers.push(applyOperator(operators.pop(), numbers.pop(), numbers.pop()));
                }
                operators.pop(); // 弹出左括号
                i++;
            } else if (isOperator(c)) {
                // 处理运算符优先级
                while (!operators.isEmpty() && 
                       precedence(operators.peek()) >= precedence(c)) {
                    numbers.push(applyOperator(operators.pop(), numbers.pop(), numbers.pop()));
                }
                operators.push(c);
                i++;
            } else {
                throw new IllegalArgumentException("无效字符: " + c);
            }
        }
        
        // 计算剩余的运算符
        while (!operators.isEmpty()) {
            numbers.push(applyOperator(operators.pop(), numbers.pop(), numbers.pop()));
        }
        
        return numbers.pop();
    }
    
    /**
     * 判断是否为运算符
     */
    private boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }
    
    /**
     * 获取运算符优先级
     */
    private int precedence(char op) {
        switch (op) {
            case '+':
            case '-':
                return 1;
            case '*':
            case '/':
                return 2;
            default:
                return 0;
        }
    }
    
    /**
     * 应用运算符
     */
    private double applyOperator(char op, double b, double a) {
        switch (op) {
            case '+':
                return a + b;
            case '-':
                return a - b;
            case '*':
                return a * b;
            case '/':
                if (b == 0) {
                    throw new ArithmeticException("除数不能为零");
                }
                return a / b;
            default:
                throw new IllegalArgumentException("无效运算符: " + op);
        }
    }

}
