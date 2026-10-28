import { http } from '@kit.NetworkKit';

/**
 * 让模型自己去查提示词规范。
 *
 * 为什么不在代码里枚举模型：ComfyUI 生态的新模型太多，写死规则永远追不上。
 * 做法是"先搜、再读"两步，由模型决定用不用：
 * - lookupPromptGuide：关键词搜索，返回标题/链接/摘要，模型自己挑一个；
 * - fetchDoc：把挑中的页面抓成纯文本回填。
 *
 * 搜索用 cn.bing.com：HuggingFace、DuckDuckGo 在部分网络下不可达，
 * 而搜索页在手机上通常能打开；结果里指向 bing 自身的跳转链接会被跳过。
 * 约束：只走 https、单次超时 15s、返回截断，避免撑爆上下文。
 *
 * 规则要和 Android 的 AiLookup.kt 保持一致。
 */
const MAX_CHARS: number = 2600;
const UA: string = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36';

interface SearchHit {
  title: string;
  url: string;
  snippet: string;
}

async function fetchText(url: string): Promise<string | null> {
  const client = http.createHttp();
  try {
    const response = await client.request(url, {
      method: http.RequestMethod.GET,
      header: {
        'User-Agent': UA,
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      },
      connectTimeout: 15000,
      readTimeout: 15000,
      expectDataType: http.HttpDataType.STRING,
    });
    if (response.responseCode < 200 || response.responseCode >= 300) {
      return null;
    }
    return response.result as string;
  } catch (err) {
    return null;
  } finally {
    client.destroy();
  }
}

function decode(text: string): string {
  return text
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&nbsp;/g, ' ')
    .replace(/&ensp;/g, ' ')
    .replace(/&emsp;/g, ' ')
    .replace(/&#\d+;/g, ' ');
}

function plain(raw: string): string {
  return decode(raw.replace(/<[^>]+>/g, ' '))
    .replace(/[\s\u00a0]+/g, ' ')
    .trim();
}

function parseBing(html: string): SearchHit[] {
  const hits: SearchHit[] = [];
  const blocks = html.match(/<li class="b_algo"[\s\S]*?<\/li>/gi);
  if (blocks === null) {
    return hits;
  }
  for (const block of blocks) {
    const link = block.match(/<h2[^>]*>\s*<a[^>]*href="([^"]+)"/i);
    const title = block.match(/<h2[^>]*>\s*<a[^>]*>([\s\S]*?)<\/a>/i);
    const snippet = block.match(/<p[^>]*>([\s\S]*?)<\/p>/i);
    if (link === null || title === null) {
      continue;
    }
    const url: string = decode(link[1]);
    // bing 的 ck/a 是跳转壳，取不到真实地址，直接跳过。
    if (!url.startsWith('https://') || url.indexOf('bing.com/ck/') >= 0) {
      continue;
    }
    const name: string = plain(title[1]);
    if (name.length === 0 || hits.some((item: SearchHit) => item.url === url)) {
      continue;
    }
    hits.push({
      title: name,
      url: url,
      snippet: snippet === null ? '' : plain(snippet[1]).substring(0, 200),
    });
  }
  return hits;
}

/** 抓页面时保留段落：先干掉 script/style，再把块级标签换成换行。 */
function stripHtml(raw: string): string {
  const text: string = raw
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|h[1-6])>/gi, '\n')
    .replace(/<[^>]+>/g, ' ');
  return decode(text)
    .replace(/[ \t]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n\n')
    .trim();
}

export async function lookupPromptGuide(model: string): Promise<string> {
  const name: string = model.trim();
  if (name.length === 0) {
    return '{"ok":false,"error":"model 为空"}';
  }
  const query: string = `${name} prompt guide 提示词`;
  const html = await fetchText(`https://cn.bing.com/search?q=${encodeURIComponent(query)}`);
  if (html === null) {
    return '{"ok":false,"error":"搜索失败：网络不可达，可以先按通用规范写，或让用户给文档链接"}';
  }
  const hits: SearchHit[] = parseBing(html);
  if (hits.length === 0) {
    return '{"ok":false,"error":"没搜到结果；可以换更短的关键词重试，或直接用你的知识写"}';
  }
  const results: string[] = hits.slice(0, 5).map((item: SearchHit) =>
    `{"title":${JSON.stringify(item.title)},"url":${JSON.stringify(item.url)},"snippet":${JSON.stringify(item.snippet)}}`);
  return `{"ok":true,"query":${JSON.stringify(query)},"results":[${results.join(',')}],"hint":"挑一个最像官方文档的 url 再用 fetch_doc 读正文"}`;
}

export async function fetchDoc(url: string): Promise<string> {
  const target: string = url.trim();
  if (!target.startsWith('https://')) {
    return '{"ok":false,"error":"只支持 https 地址"}';
  }
  const body = await fetchText(target);
  if (body === null) {
    return '{"ok":false,"error":"抓取失败，可能是 404 或站点拒绝"}';
  }
  const text: string = stripHtml(body).substring(0, MAX_CHARS);
  return `{"ok":true,"source":${JSON.stringify(target)},"text":${JSON.stringify(text)}}`;
}
