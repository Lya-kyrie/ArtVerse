# Markdown Rendering Design

**Date:** 2026-06-28  
**Scope:** Frontend — unified Markdown rendering for AI dialogue content  
**Decision:** `marked` + custom Tailwind Renderer, content-document visual style

---

## Problem

Backend AI agents return Markdown-formatted content (tables, multi-level headings, ordered/unordered lists, horizontal rules, bold, inline code, code blocks). Two frontend components render this content, and both are deficient:

| Component | Current State |
|-----------|--------------|
| `ChatPanel.tsx:275` | Zero Markdown processing — raw text with `whitespace-pre-wrap` |
| `MangaAgentPage.tsx:138-153` | Only `**bold**` and `` `code` `` via regex splitting; no tables, headings, lists, dividers |

Example of content that fails to render:
```
##当前第1话分镜概况|项目 |状态 |
|------|------|
| 总场景数 |10个分镜场景 |
...
---

###分镜内容一览1. 🎬 皇城城门 —初秋细雨...
```

---

## Solution

### Architecture

```
frontend/src/
├── components/
│   ├── MarkdownRenderer.tsx    ← NEW: unified Markdown rendering component
│   ├── ChatPanel.tsx            ← MODIFY: replace raw text with MarkdownRenderer
│   └── MangaAgentPage.tsx       ← MODIFY: replace MarkdownMessage with MarkdownRenderer
└── package.json                 ← MODIFY: add "marked" as explicit dependency
```

Single `MarkdownRenderer` component using `marked` with a custom `Renderer` that injects Tailwind classes onto every HTML element. Both consuming components swap their rendering call — no business logic changes.

### Dependency

`marked` (^16.4.2) already exists in `package-lock.json` as a transitive dependency. We make it explicit in `package.json` to pin the version and declare intent.

### Component: `MarkdownRenderer`

**Props:**
```typescript
interface MarkdownRendererProps {
  content: string;
}
```

Pure presentational component. Calls `marked.parse(content)` with a custom renderer, returns `<div className="markdown-body" dangerouslySetInnerHTML={...} />`.

**Security:** Custom renderer outputs only controlled HTML structures. No raw HTML passthrough from input. `marked` disables HTML parsing by default. No `DOMPurify` needed.

### Element → Style Mapping (Content-Document Style)

Every element receives Tailwind classes matching the project's existing color palette (`sumi`/`vermilion`/`kinpaku`/`paper-*`/`aizuri`).

#### Block Elements

| Element | Tailwind Classes |
|---------|-----------------|
| **h2** | `text-lg font-semibold text-sumi mt-6 mb-3 pb-2 border-b border-paper-border` |
| **h3** | `text-base font-semibold text-sumi mt-5 mb-2` |
| **h4** | `text-sm font-semibold text-sumi-dim mt-4 mb-1` |
| **p** | `text-sm text-sumi leading-relaxed my-2` |
| **hr** | `border-paper-border my-6` |
| **blockquote** | `border-l-4 border-kinpaku/40 bg-kinpaku-light/20 px-4 py-2 my-3 rounded-r-lg text-sm text-sumi-dim italic` |
| **ol** | `list-decimal pl-5 space-y-1 my-3 text-sm text-sumi leading-relaxed` |
| **ul** | `list-disc pl-5 space-y-1 my-3 text-sm text-sumi leading-relaxed` |
| **li** | Inherits from parent list |

#### Table

Table gets a scroll wrapper for narrow screens:

```html
<div class="overflow-x-auto rounded-lg border border-paper-border my-4">
  <table class="w-full border-collapse">
    ...
  </table>
</div>
```

| Element | Tailwind Classes |
|---------|-----------------|
| **table wrapper** | `overflow-x-auto rounded-lg border border-paper-border my-4` |
| **table** | `w-full border-collapse` |
| **thead** | `bg-sumi text-white` |
| **th** | `px-4 py-2 text-left text-xs font-medium uppercase tracking-wider` |
| **tbody tr (even)** | `bg-paper-surface` (zebra striping) |
| **tbody tr (odd)** | `bg-paper-base` |
| **td** | `px-4 py-2 text-sm border-t border-paper-border` |

Zebra striping is applied via a counter on `<tbody>` rows in the custom renderer.

#### Inline Elements

| Element | Tailwind Classes |
|---------|-----------------|
| **strong** | `font-semibold text-sumi` |
| **em** | `italic` |
| **inline code** | `rounded bg-paper-surface px-1.5 py-0.5 text-xs text-vermilion font-mono` |
| **a** | `text-aizuri underline hover:brightness-110 transition` |
| **img** | `max-w-full rounded-lg my-2` |

#### Code Block

```html
<pre class="block bg-paper-surface rounded-lg p-4 overflow-x-auto my-3 border border-paper-border">
  <code class="text-xs font-mono text-sumi leading-relaxed">...</code>
</pre>
```

#### Root Container

`div.markdown-body` with `space-y-1` for vertical rhythm.

### Consumers

**ChatPanel.tsx** — 2 changes:
- Line 275: `{msg.content}` → `<MarkdownRenderer content={msg.content} />`
- Line 293: `{streamContent}` → `<MarkdownRenderer content={streamContent} />`

**MangaAgentPage.tsx** — 2 changes + removal:
- Remove functions: `renderInlineMarkdown` (line 138) and `MarkdownMessage` (line 151)
- Line 1032: `<MarkdownMessage content={msg.content} />` → `<MarkdownRenderer content={msg.content} />`
- Line 1082: `<MarkdownMessage content={draftReply} />` → `<MarkdownRenderer content={draftReply} />`
- Add import for `MarkdownRenderer`

### marked Configuration

```typescript
marked.setOptions({
  breaks: true,    // single newline → <br>
  gfm: true,       // GitHub Flavored Markdown (tables, task lists, strikethrough)
});
```

Custom renderer overrides via `marked.use({ renderer })`:
- `heading(text, level)` → wrapped with level-appropriate classes
- `table(header, body)` → wrapped in scroll div
- `tablecell(content, flags)` → header vs data cell styling
- `list(body, ordered)` → ol vs ul styling
- `hr()` → themed divider
- `blockquote(text)` → themed quote
- `codespan(text)` → inline code badge
- `code(code, language)` → fenced code block
- `link(href, title, text)` → styled anchor
- `image(href, title, text)` → responsive image
- `paragraph(text)` → styled paragraph
- `strong(text)` / `em(text)` → styled inline

---

## Non-Goals

- No server-side Markdown processing changes
- No editing/preview toggle (read-only rendering only)
- No syntax highlighting in code blocks (out of scope; would add highlight.js later if needed)
- No copy button on code blocks (future iteration)

---

## Files Changed

| File | Change |
|------|--------|
| `frontend/package.json` | Add `"marked": "^16.4.2"` to dependencies |
| `frontend/src/components/MarkdownRenderer.tsx` | New file |
| `frontend/src/components/ChatPanel.tsx` | Replace raw text rendering with `<MarkdownRenderer>` (2 sites) + add import |
| `frontend/src/components/MangaAgentPage.tsx` | Remove `renderInlineMarkdown`/`MarkdownMessage`, replace with `<MarkdownRenderer>` (2 sites) + add import |

---

## Verification

1. `npm run build` — TypeScript compilation and Vite build pass
2. Manual test: send AI message that returns Markdown with tables, headings, lists, code blocks — verify all render correctly in both ChatPanel and MangaAgentPage
3. Visual check: table zebra striping, heading hierarchy, horizontal rules, code blocks all match the design spec
4. Narrow screen test: tables scroll horizontally instead of breaking layout
