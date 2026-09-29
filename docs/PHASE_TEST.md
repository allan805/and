# Hermes Mobile UI Runtime v3 — 测试清单

## 0. 前置
- 安装 APK 后开启“显示在其他应用上层”。
- Hermes/Relay 与 Android Bridge 已配对并连接。
- 首先测试 `/overlay/show`，再按顺序测试 patch、action state、lifecycle、reconnect、restore。

## 1. Phase 1
### 1.1 Component patch
```json
{"page_id":"p1","component_id":"message","patch":{"text":"更新后的消息","revision":2}}
```

### 1.2 Field/action patch
```json
{"page_id":"p1","component_id":"field:name","patch":{"label":"姓名（已更新）","value":"Alice","revision":3}}
```
```json
{"page_id":"p1","component_id":"action:submit","patch":{"state":"busy","disabled":true,"revision":4}}
```

### 1.3 Dynamic list
在 `/overlay/show` 中加入：
```json
{"id":"tasks","title":"任务","items":["Task A","Task B","Task C"]}
```

### 1.4 State machine
依次调用 `/overlay/patch`：
`loading -> ready -> busy -> success -> error -> disabled`，确认状态文本和 action 状态同步。

### 1.5 Navigation / lifecycle
测试 back_page_id / next_page_id，并观察 Hermes 收到：
`page_created`, `page_shown`, `page_selected`, `page_navigated`, `page_closed`, `overlay_minimized`, `overlay_restored`。

### 1.6 Persistence
显示页面 → 最小化 → 强制杀掉 App → 重新启动 App。确认页面恢复。

## 2. Phase 2
- Tabs：`tabs:["状态","控制","日志"]`
- Dynamic list：超过一项并点击条目
- Switch / Slider / Select / Radio / Multiline
- Toast：`POST /overlay/toast`，body `{"text":"Hello Hermes"}`
- 磨砂球：拖动、重启后确认位置保持
- 长页面：确认滚动位置在 patch 后保持

## 3. Phase 3
### 3.1 on_change + debounce
Field:
```json
{"id":"query","label":"搜索","type":"text","on_change_action_id":"search"}
```
Action:
```json
{"id":"search","label":"搜索","event":"on_change","debounce_ms":500}
```
连续输入时应合并为一次延迟事件。

### 3.2 Action state
```json
{"page_id":"p1","action_id":"submit","state":"busy","progress":0.4,"message":"处理中"}
```
完成后：
```json
{"page_id":"p1","action_id":"submit","state":"success","progress":1}
```

### 3.3 Debug state
`GET /overlay/state` 应返回 page_count、page_id、revision、status、field_count、action_count、list_count。

### 3.4 Resource limits
- fields > 30 → 400
- actions > 20 → 400
- components > 80 → 400
- list items > 100 → 400
- message > 8000 字符 → 400

## 4. WebSocket 可靠性
断开 Relay → 等待自动重连 → 恢复连接后再次 `/overlay/show`。
确认 UI 不因一次断线丢失，且 reconnect 不无限循环。
