import { Marked, Renderer } from 'marked';
import { useMemo } from 'react';

// ---------------------------------------------------------------------------
// Custom Renderer — injects Tailwind classes matching the project palette.
//
// Design note: we override EVERY method needed so the renderer object passed
// to the Marked constructor is a complete, self-contained renderer.  We do
// NOT call sibling renderer methods manually (e.g. list → listitem) because
// that can bypass marked's internal token-walk and trigger assertion errors.
// Instead we let the Marked engine walk the token tree and call the right
// method for every token.
// ---------------------------------------------------------------------------

class TailwindRenderer extends Renderer {
  heading({ tokens, depth }: { tokens: any[]; depth: number }): string {
    const text = this.parser.parseInline(tokens);
    if (depth === 2) return `<h2 class="text-lg font-semibold text-sumi mt-6 mb-3 pb-2 border-b border-paper-border">${text}</h2>`;
    if (depth === 3) return `<h3 class="text-base font-semibold text-sumi mt-5 mb-2">${text}</h3>`;
    if (depth === 4) return `<h4 class="text-sm font-semibold text-sumi-dim mt-4 mb-1">${text}</h4>`;
    return `<h${depth} class="text-sm font-semibold text-sumi my-2">${text}</h${depth}>`;
  }

  paragraph({ tokens }: { tokens: any[] }): string {
    const text = this.parser.parseInline(tokens);
    return `<p class="text-sm text-sumi leading-relaxed my-2">${text}</p>`;
  }

  list({ items, ordered, start }: { items: any[]; ordered: boolean; start: number | '' }): string {
    const tag = ordered ? 'ol' : 'ul';
    const cls = ordered
      ? 'list-decimal pl-5 space-y-1 my-3 text-sm text-sumi leading-relaxed'
      : 'list-disc pl-5 space-y-1 my-3 text-sm text-sumi leading-relaxed';
    const startAttr = ordered && start && start !== 1 ? ` start="${start}"` : '';
    let body = '';
    for (const item of items) {
      body += this.listitem(item);
    }
    return `<${tag} class="${cls}"${startAttr}>${body}</${tag}>`;
  }

  listitem(item: { tokens: any[]; task?: boolean; checked?: boolean; loose?: boolean }): string {
    const content = this.parser.parseInline(item.tokens);
    let checkbox = '';
    if (item.task) {
      checkbox = item.checked
        ? '<input type="checkbox" class="mr-2 accent-vermilion" checked disabled />'
        : '<input type="checkbox" class="mr-2 accent-vermilion" disabled />';
    }
    return `<li>${checkbox}${content}</li>`;
  }

  hr(): string {
    return '<hr class="border-paper-border my-6" />';
  }

  blockquote({ tokens }: { tokens: any[] }): string {
    const content = this.parser.parseInline(tokens);
    return `<blockquote class="border-l-4 border-kinpaku/40 bg-kinpaku-light/20 px-4 py-2 my-3 rounded-r-lg text-sm text-sumi-dim italic">${content}</blockquote>`;
  }

  codespan({ text }: { text: string }): string {
    return `<code class="rounded bg-paper-surface px-1.5 py-0.5 text-xs text-vermilion font-mono">${text}</code>`;
  }

  code({ text }: { text: string }): string {
    const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return `<pre class="block bg-paper-surface rounded-lg p-4 overflow-x-auto my-3 border border-paper-border"><code class="text-xs font-mono text-sumi leading-relaxed">${escaped}</code></pre>`;
  }

  link({ href, title, tokens }: { href: string; title?: string | null; tokens: any[] }): string {
    const text = this.parser.parseInline(tokens);
    const safeHref = href ?? '';
    const titleAttr = title ? ` title="${title}"` : '';
    return `<a href="${safeHref}"${titleAttr} class="text-aizuri underline hover:brightness-110 transition-colors" target="_blank" rel="noopener noreferrer">${text}</a>`;
  }

  image({ href, title, text }: { href: string; title?: string | null; text: string }): string {
    const safeHref = href ?? '';
    const titleAttr = title ? ` title="${title}"` : '';
    return `<img src="${safeHref}" alt="${text}"${titleAttr} class="max-w-full rounded-lg my-2" />`;
  }

  strong({ tokens }: { tokens: any[] }): string {
    const text = this.parser.parseInline(tokens);
    return `<strong class="font-semibold text-sumi">${text}</strong>`;
  }

  em({ tokens }: { tokens: any[] }): string {
    const text = this.parser.parseInline(tokens);
    return `<em class="italic">${text}</em>`;
  }

  del({ tokens }: { tokens: any[] }): string {
    const text = this.parser.parseInline(tokens);
    return `<del class="line-through text-sumi-dim">${text}</del>`;
  }

  // Table — we override this to add wrapper div and header/body styling.
  // The engine supplies us with parsed Table token; we render header and
  // body by calling this.tablecell / this.tablerow the same way the
  // default renderer does.
  table({ header, rows, align }: { header: any[]; rows: any[][]; align: Array<'center' | 'left' | 'right' | null> }): string {
    // header row
    let headHtml = '<tr>';
    for (let i = 0; i < header.length; i++) {
      headHtml += this.tablecell({ ...header[i], header: true, align: align[i] });
    }
    headHtml += '</tr>';

    // body rows
    let bodyHtml = '';
    for (const row of rows) {
      let rowHtml = '';
      for (let i = 0; i < row.length; i++) {
        rowHtml += this.tablecell({ ...row[i], header: false, align: align[i] });
      }
      bodyHtml += `<tr>${rowHtml}</tr>`;
    }

    return `<div class="overflow-x-auto rounded-lg border border-paper-border my-4"><table class="w-full border-collapse"><thead class="bg-sumi text-white">${headHtml}</thead><tbody>${bodyHtml}</tbody></table></div>`;
  }

  tablecell({ tokens, header: isHeader, align }: { tokens: any[]; header: boolean; align: 'center' | 'left' | 'right' | null }): string {
    const content = this.parser.parseInline(tokens);
    const alignCls = align ? ` text-${align}` : '';
    if (isHeader) {
      return `<th class="px-4 py-2 text-xs font-medium uppercase tracking-wider${alignCls}">${content}</th>`;
    }
    return `<td class="px-4 py-2 text-sm border-t border-paper-border${alignCls}">${content}</td>`;
  }
}

// ---------------------------------------------------------------------------
// Convert space-aligned tabular text to GFM pipe tables
//
// AI responses often contain pseudo-tables where columns are separated by
// 2+ spaces or tabs instead of GFM pipe delimiters.  This preprocessor
// detects consecutive lines with consistent column counts and rewrites
// them as GFM tables so the marked parser renders them as proper <table>.
//
// Heuristic:
//  - 2+ consecutive lines each containing >= 2 column separators
//    (2+ spaces or a tab) that produce the same column count.
//  - Lines that already start with |, empty lines, markdown headings,
//    list items, and blockquotes act as block separators.
// ---------------------------------------------------------------------------

function convertSpaceAlignedTables(src: string): string {
  const lines = src.split('\n');
  const result: string[] = [];
  let blockLines: string[] = [];
  let blockCols = 0;

  function flush() {
    if (blockLines.length >= 2 && blockCols >= 2) {
      const gfmRows: string[] = [];
      for (const line of blockLines) {
        const cells = line.split(/\s{2,}|\t|　+/).map(c => c.trim()).filter(c => c !== '');
        while (cells.length < blockCols) cells.push('');
        gfmRows.push('| ' + cells.slice(0, blockCols).join(' | ') + ' |');
      }
      // Insert GFM alignment row after the header (first row)
      const sep = '|' + Array(blockCols).fill(' --- ').join('|') + '|';
      gfmRows.splice(1, 0, sep);
      result.push(...gfmRows);
    } else {
      result.push(...blockLines);
    }
    blockLines = [];
    blockCols = 0;
  }

  for (const line of lines) {
    const trimmed = line.trim();

    // Empty line → flush pending table block
    if (trimmed === '') {
      flush();
      result.push(line);
      continue;
    }

    // Already a GFM table row → don't touch
    if (trimmed.startsWith('|')) {
      flush();
      result.push(line);
      continue;
    }

    // Markdown structural tokens → flush before them
    if (/^\s*(#{1,6}\s|[-*+]\s|\d+\.\s|[>])/.test(line)) {
      flush();
      result.push(line);
      continue;
    }

    // Count columns separated by 2+ spaces, a tab, or full-width space(s)
    const cells = line.split(/\s{2,}|\t|　+/).map(c => c.trim()).filter(c => c !== '');
    if (cells.length >= 2) {
      if (blockCols === 0) {
        blockLines.push(line);
        blockCols = cells.length;
      } else if (cells.length === blockCols) {
        blockLines.push(line);
      } else {
        flush();
        blockLines.push(line);
        blockCols = cells.length;
      }
    } else {
      flush();
      result.push(line);
    }
  }

  flush();
  return result.join('\n');
}

// ---------------------------------------------------------------------------
// Normalize non-standard Markdown from AI output before parsing
// ---------------------------------------------------------------------------

function normalizeMarkdown(src: string): string {
  // 0. Convert space-aligned pseudo-tables to GFM first
  let result = convertSpaceAlignedTables(src);

  // 1. Ensure space after heading markers: ##text → ## text
  result = result.replace(/^(#{1,6})([^\s#])/gm, '$1 $2');

  // 2. Split heading + table on same line: ## heading|col| → ## heading\n|col|
  result = result.replace(/^(#{1,6}\s+.+?)(\|[^|\n]+\|[^|\n]+\|)/gm, '$1\n$2');

  // 3. Insert newline before numbered list items following Chinese text
  result = result.replace(/([\p{Script=Han}。！？）])(\d+)\. /gu, '$1\n$2. ');

  // 4. Insert newline before unordered list items following Chinese text
  result = result.replace(/([\p{Script=Han}。！？）])(- [^-])/gu, '$1\n$2');

  // 5. Ensure horizontal rules on their own line
  result = result.replace(/([^\n])---$/gm, '$1\n---');
  result = result.replace(/^---([^\n])/gm, '---\n$1');

  return result;
}

// ---------------------------------------------------------------------------
// Marked instance — per-module singleton, NOT global marked
// ---------------------------------------------------------------------------

const renderer = new TailwindRenderer();

const md = new Marked({
  breaks: true,
  gfm: true,
  renderer,
});

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

interface MarkdownRendererProps {
  content: string;
}

export default function MarkdownRenderer({ content }: MarkdownRendererProps) {
  const html = useMemo(() => {
    try {
      const normalized = normalizeMarkdown(content);
      return md.parse(normalized) as string;
    } catch {
      // Fallback: if parsing fails, show raw text safely escaped
      return content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }
  }, [content]);

  return (
    <div
      className="markdown-body space-y-1"
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
