# 生图页面右侧嵌入 Excalidraw 画布 — 设计方案

## 一、背景与目标

在生图聊天页面（`ImageGenPage`）右侧嵌入 [Excalidraw](https://excalidraw.com/) 无限画布，用户可在画布上标注、涂鸦、写文字说明，辅助对生成的图片提出修改意见，提升生图协作体验。

## 二、当前页面布局（现状）

```
+-- ThemeSidebar --+-- Main Content (flex-1) --+
|  (w-56)          |  title bar                |
|  主题列表         |  消息列表 / 图片展示       |
|                   |  底部 Composer 输入框      |
+-------------------+---------------------------+
```

## 三、目标布局（改造后）

```
+-- ThemeSidebar --+-- Chat Content ----+-- Excalidraw Canvas --+
|  (w-56)          |  (flex-1)          |  (w-[520px] / 50%)   |
|  主题列表         |  title bar         |  Excalidraw iframe    |
|                   |  消息列表 / 图片    |  工具栏               |
|                   |  Composer 输入框    |                       |
+-------------------+--------------------+-----------------------+
```

## 四、核心设计方案

### 4.1 嵌入方式：iframe

通过 `<iframe>` 直接将 Excalidraw 官网嵌入右侧面板。这是最简单可靠的方式，无需额外引入 npm 包或处理复杂的 Excalidraw React 依赖。

```html
<iframe src="https://excalidraw.com/" ... />
```

### 4.2 右侧画布面板

- **位置**：主内容区域右侧
- **宽度**：默认 `w-[520px]`（约 520px），可拖拽调整宽度
- **高度**：`h-full`，充满父容器
- **可折叠**：右侧有一个切换按钮，点击可展开/收起画布面板
- **收起状态**：面板完全隐藏，仅保留一个展开按钮悬浮在右侧边缘

### 4.3 面板头部

- 标题：`🖌️ 画布`
- 右侧有收起按钮（`X` 或 `ChevronRight`）

### 4.4 Excalidraw iframe 容器

- 包裹在圆角容器内
- 设置 `overflow: hidden` 和适当的内边距
- iframe 宽高 = 100% × 100%
- 设置 `allow="clipboard-read; clipboard-write"` 允许剪贴板操作

### 4.5 拖拽调整宽度（可选增强）

在画布面板左侧边界添加拖拽手柄，用户可拖拽调整画布和聊天区域的宽度比例。拖拽范围限制在 `360px ~ 50vw` 之间。

## 五、交互设计

### 5.1 图片发送到画布（核心功能）

用户点击图片下方的 **"在画布中打开"** 按钮时，将图片以 Excalidraw 支持的方式（通过 URL 参数 `?addImage=<imageUrl>`）加载到画布中：

```
https://excalidraw.com/?addImage=<encoded-image-url>
```

**方案 A（推荐）**：点击图片下方的「在画布中标注」按钮 → 将图片 URL 编码后作为 iframe 的 `src` 参数传递 → Excalidraw 加载时自动导入该图片

Excalidraw 官方支持 URL 参数 `?addImage=<base64-or-url>` 在初始化时自动加载图片到画布。用户可在画布上自由标注、裁剪、书写意见。

**方案 B（备选）**：用户手动复制图片 → 在 Excalidraw 中按 Ctrl+V 粘贴 → 进行标注。实现成本低，但操作步骤较多。

### 5.2 画布内容持久化

画布内容由 Excalidraw 自身的 localStorage 机制管理（它默认自动保存）。我们无需额外处理持久化。

### 5.3 状态联动

- 右侧画布面板的打开/关闭状态使用 `canvasOpen: boolean` 状态管理
- 状态存储在组件本地，刷新页面后默认关闭
- 可以额外存储到 localStorage 以保持用户偏好

## 六、需要修改的文件

| 文件 | 改动内容 |
|------|---------|
| `frontend/src/components/ImageGenPage.tsx` | 添加右侧画布面板、折叠状态、图片「在画布中标注」按钮、Excalidraw iframe 嵌入 |

## 七、具体代码改动

### 7.1 添加新状态

```typescript
// 在 ImageGenPage 组件内添加
const [canvasOpen, setCanvasOpen] = useState(() => {
  try {
    return localStorage.getItem('artverse.genCanvasOpen') === 'true';
  } catch { return false; }
});

// 状态变化时持久化
useEffect(() => {
  localStorage.setItem('artverse.genCanvasOpen', String(canvasOpen));
}, [canvasOpen]);
```

### 7.2 布局结构调整

当前渲染代码（第 776-957 行）的结构是：

```tsx
<div className="flex-1 min-h-0 bg-ink text-cream flex">
  <ThemeSidebar ... />
  {/* Main Content */}
  <div className="flex min-h-0 flex-1 flex-col"> ... </div>
</div>
```

改为：

```tsx
<div className="flex-1 min-h-0 bg-ink text-cream flex">
  <ThemeSidebar ... />
  {/* Main Content */}
  <div className="flex min-h-0 flex-1 flex-col"> ... </div>
  {/* 画布面板：折叠状态时隐藏 */}
  {canvasOpen && (
    <div className="flex shrink-0 flex-col border-l border-ink-border bg-ink-light">
      {/* 面板头部 */}
      <div className="flex h-14 items-center justify-between border-b border-ink-border px-4">
        <span className="flex items-center gap-1.5 text-sm font-bold tracking-wide text-coral">
          🖌️ 画布
        </span>
        <button onClick={() => setCanvasOpen(false)} className="text-cream-dim hover:text-cream">
          <ChevronRight size={16} />
        </button>
      </div>
      {/* iframe 容器 */}
      <div className="flex-1 min-h-0">
        <iframe
          src="https://excalidraw.com/"
          className="h-full w-full border-0"
          title="Excalidraw 画布"
          allow="clipboard-read; clipboard-write"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups"
        />
      </div>
    </div>
  )}
</div>
```

### 7.3 主题标题栏添加「画布」展开按钮

在主题标题栏右侧（已有图片计数位置）增加一个画布切换按钮：

```tsx
<button
  onClick={() => setCanvasOpen(true)}
  className="flex items-center gap-1.5 rounded-lg border border-ink-border px-3 py-1.5 text-xs text-cream-dim hover:text-cream hover:bg-ink-lighter transition-colors"
  title="打开画布"
>
  🖌️ 画布
</button>
```

### 7.4 图片操作增加「在画布中标注」按钮

在每张生成图片的操作按钮区域（第 898-916 行）添加：

```tsx
<button
  onClick={() => openImageInCanvas(imageUrl)}
  className="rounded-lg p-2 hover:bg-ink-lighter transition-colors"
  title="在画布中标注"
>
  <Edit size={14} />
</button>
```

对应的处理函数：

```typescript
const openImageInCanvas = (imageUrl: string) => {
  setCanvasOpen(true);
  // 通过 Excalidraw 的 addImage URL 参数传递图片
  // 注意：需要延时以确保画布面板已渲染
  setTimeout(() => {
    const encodedUrl = encodeURIComponent(imageUrl);
    setExcalidrawSrc(`https://excalidraw.com/#addImage=${encodedUrl}`);
  }, 300);
};
```

但这里跨域通信存在限制。实际上更合适的做法是：

**推荐做法**：当用户点击「在画布中标注」时：
1. 打开画布面板
2. 将图片 URL 编码后通过 `postMessage` 发送到 iframe（但 Excalidraw 不支持外部 postMessage 控制）
3. **替代方案**：直接在新标签页打开 Excalidraw 并携带图片参数，或采用最简方案：让用户手动复制粘贴

## 八、简化方案（推荐优先实现）

考虑到 Excalidraw iframe 跨域通信限制，推荐分阶段实现：

### 第一阶段（最小可用）
1. 右侧嵌入 Excalidraw iframe
2. 可折叠/展开
3. 用户可手动复制图片到画布中粘贴编辑

### 第二阶段（增强）
探索通过 Excalidraw 支持的 URL hash 参数传递图片，或改用 `@excalidraw/excalidraw` React 组件方式集成（可完全控制画布行为）。

## 九、关键技术要点

1. **Sandbox 属性**：iframe 设置 `sandbox="allow-scripts allow-same-origin allow-forms allow-popups"`，保证安全性的同时允许必要功能。
2. **响应式**：画布面板在窄屏（<1024px）时自动隐藏，保留展开按钮。
3. **无外部依赖**：整个改动仅修改 `ImageGenPage.tsx` 一个文件，无需安装新 npm 包。
4. **样式一致性**：画布面板使用现有 `bg-ink-light`、`border-ink-border`、`text-coral` 等主题色，与整个应用风格统一。

## 十、UI 参考示意

```
┌─────────┬─────────────────────────────┬──────────────────────┐
│ 主题列表 │  📋 主题名称    [🖌️ 画布]    │  🖌️ 画布       ✕   │
│         │ ─────────────────────────── │ ─────────────────── │
│ 主题 A   │                             │                     │
│ 主题 B   │   [生成图片]                  │   [Excalidraw]      │
│ 主题 C   │                             │   [无限画布]         │
│         │   📝 在画布中标注             │                     │
│  + 新建  │                             │                     │
│         │ ─────────────────────────── │                     │
│         │  [Composer 输入框]            │                     │
└─────────┴─────────────────────────────┴──────────────────────┘
```

---
**方案状态**：待你确认后执行第一阶段实现。
