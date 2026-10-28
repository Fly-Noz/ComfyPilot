/**
 * 把模型回复收敛成适合聊天气泡的纯文本。
 *
 * 为什么需要：模型走的是 Markdown（标题、加粗、代码围栏、表格），
 * 而气泡只是一个普通 Text，不处理就会把 `**`、`###`、`|` 这些符号原样显示出来。
 *
 * 取舍：不追求完整渲染（富文本要 Span 拼装，改造成本大于收益），
 * 只保证"读起来像正常句子"——去掉标记、保留结构与缩进。
 * 规则要和 Android 的 AiText.kt 保持一致，别只改一边。
 */
export function aiPlainText(raw: string): string {
  const lines: string[] = raw.replace(/\r\n?/g, '\n').split('\n');
  const out: string[] = [];
  let inCode: boolean = false;
  for (const original of lines) {
    const line: string = original.replace(/\s+$/, '');
    const trimmed: string = line.trim();
    // 代码围栏本身不显示，里面的内容保持原样（缩进有意义，不参与下面的替换）。
    if (trimmed.startsWith('```')) {
      inCode = !inCode;
      continue;
    }
    if (inCode) {
      out.push(line);
      continue;
    }
    // 分割线
    if (/^[-*_]{3,}$/.test(trimmed)) {
      continue;
    }
    // 表格：保留单元格内容，去掉竖线与分隔行
    if (trimmed.startsWith('|')) {
      if (/^[\s|:-]+$/.test(trimmed)) {
        continue;
      }
      const cells: string[] = trimmed.split('|').map((cell: string) => cell.trim())
        .filter((cell: string) => cell.length > 0);
      out.push(cells.join('  '));
      continue;
    }
    let body: string = line
      .replace(/^\s{0,3}>\s?/, '')
      .replace(/^\s{0,3}#{1,6}\s+/, '');
    // 无序列表统一成中点，比 - 更好认；有序列表原样保留
    body = body.replace(/^\s{0,3}[-*+]\s+/, '· ');
    out.push(body);
  }
  let text: string = out.join('\n');
  text = text
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/\n{3,}/g, '\n\n');
  return text.trim();
}

/** 工具结果是内部协议，不该出现在聊天气泡里。 */
export function isToolResult(content: string): boolean {
  return content.startsWith('（工具结果）');
}
