# JSON Streaming Timeout Fix

## Problem Statement

When DeepSeek LLM generates long/complex tool call JSON outputs, the system was prematurely timing out with error:
```
超时：工具调用JSON不完整 (Timeout: Tool call JSON incomplete)
```

This occurred because:
1. The poll timeout was set to `pollCount > 240` (240 polls × 500ms = 120 seconds = **2 minutes**)
2. When LLM needed more than 2 minutes to generate complete JSON, the system would truncate it
3. This caused tool call failures since incomplete JSON cannot be parsed

### Root Cause Analysis

**File**: `src/com/example/agenttoolbox/DeepSeekChatBridge.java`

The JavaScript polling loop in the WebView monitoring script had a hard-coded timeout:
```javascript
// Poll every 500ms via setInterval(pollOnce, 500)
// Timeout check: if (pollCount > 240) → 240 * 500ms = 120 seconds
```

The timeout was applied in **5 locations**:
1. **Line 468-469**: No AI messages detected timeout
2. **Line 495-496**: No new messages detected timeout  
3. **Line 620-621**: JSON incomplete timeout (主要问题)
4. **Line 635-636**: Empty content timeout
5. **Line 692-693**: Normal reply fallback timeout

## Solution

Increased all poll count thresholds from **240 to 600**:
- **New timeout**: 600 polls × 500ms = 300 seconds = **5 minutes**
- **Impact**: System now waits up to 5 minutes for JSON completion before timeout
- **Backwards compatible**: Only increases wait time, no breaking changes

### Changes Made

Updated all 5 timeout checks in `DeepSeekChatBridge.java`:

```javascript
// Before (2 minutes)
if (pollCount > 240) {
  // ... timeout handling ...
}

// After (5 minutes)
if (pollCount > 600) {
  // ... timeout handling ...
}
// Added comment: "超时时间从 240 (2分钟) 增加到 600 (5分钟)，支持更长的生成时间"
```

## Timeline Reference

| Poll Count | Time | Scenario |
|-----------|------|----------|
| 1-100 | 0-50s | Normal AI generation |
| 100-200 | 50-100s | Longer response |
| 200-240 | 100-120s | **OLD TIMEOUT** (2 minutes) |
| 200-600 | 100-300s | Extended wait period (NEW) |
| 600+ | 300s+ | **NEW TIMEOUT** (5 minutes) |

## Files Modified

- **Modified**: `src/com/example/agenttoolbox/DeepSeekChatBridge.java`
- **Lines changed**: 
  - 468-469 (no messages timeout)
  - 495-496 (no new messages timeout)
  - 620-621 (JSON incomplete - main issue)
  - 635-636 (empty content timeout)
  - 692-693 (normal reply fallback)

## Testing Recommendations

1. **Long JSON Responses**: Test tool calls that generate 2+ minute responses
2. **Streaming Verification**: Confirm chunks are received during generation
3. **Completion Detection**: Verify JSON completion is properly detected
4. **Error Scenarios**: Test timeout error messages still appear after 5 minutes

## Related Issues

- Previous v1.2.0 fix addressed heartbeat interruption of JSON streams
- This fix extends the time window to accommodate very long generations
- Together they create a robust streaming solution for large JSON outputs

## Performance Impact

**Minimal**: 
- Only impacts timeout logic, not polling frequency
- No additional network calls
- Memory usage unchanged
- Slight increase in max response wait time (3 minutes additional)

## Future Considerations

If timeouts continue to occur:
1. Consider configurable timeout via Android settings
2. Implement adaptive timeout based on response size
3. Add separate timeout thresholds for tool calls vs normal replies
4. Implement progress indicators for long-running operations
