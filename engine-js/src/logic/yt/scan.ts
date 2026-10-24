// Access helpers for scraped InnerTube JSON — everything here operates on parsed DATA
// (Record<string, unknown>), never on declared structures, so key iteration and bracket access are
// the correct discipline (Closure never renames quoted/computed keys; there is no structure to
// reflect over).

import type { WireThumb } from "./wire";

export function isObj(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * exec `re` only at the positions where `literal` occurs, instead of letting the regex scan the
 * whole string: `indexOf` runs at C speed while QuickJS's regex engine tries every position in
 * interpreted bytecode (quickjs/JS_FEATURES.md), so over MB documents this is ~an order of
 * magnitude faster. `re` MUST be sticky (`/y`) — sticky means "match exactly here", zero scanning.
 * `offset` shifts the match start relative to the literal hit, for patterns with a FIXED-width
 * prefix before the literal: `/(\w{3})INNERTUBE_API_KEY/y` anchors with offset -3. A
 * variable-width prefix can't share one offset — slice a bounded window and scan that instead.
 */
export function anchoredExec(
  s: string,
  literal: string,
  re: RegExp,
  offset = 0,
  from = 0,
): RegExpExecArray | null {
  let at = s.indexOf(literal, from);
  while (at !== -1) {
    re.lastIndex = Math.max(0, at + offset);
    const m = re.exec(s);
    if (m !== null) return m;
    at = s.indexOf(literal, at + 1);
  }
  return null;
}

export function asString(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined;
}

/** Walk `path` (string = object key, number = array index); undefined on any miss. */
export function get(v: unknown, ...path: Array<string | number>): unknown {
  let cur: unknown = v;
  for (const p of path) {
    if (typeof p === "number") {
      if (!Array.isArray(cur)) return undefined;
      cur = cur[p];
    } else {
      if (!isObj(cur)) return undefined;
      cur = cur[p];
    }
  }
  return cur;
}

/** InnerTube text: `{simpleText}` | `{runs:[{text}…]}` (concatenated) | viewModel `{content}`. */
export function textOf(node: unknown): string | undefined {
  const simple = asString(get(node, "simpleText"));
  if (simple !== undefined) return simple;
  const content = asString(get(node, "content"));
  if (content !== undefined) return content;
  const runs = get(node, "runs");
  if (Array.isArray(runs)) {
    let s = "";
    for (const r of runs) {
      const t = asString(get(r, "text"));
      if (t !== undefined) s += t;
    }
    if (s !== "") return s;
  }
  return undefined;
}

/** YouTube serves some image urls scheme-relative (`//yt3.ggpht.com/…`); the app's loader needs a scheme. */
export function absImageUrl(url: string): string {
  return url.indexOf("//") === 0 ? "https:" + url : url;
}

/**
 * Map a list of `{url,width,height}` nodes (a renderer `thumbnails` array or a viewModel `sources`
 * array — both ascend in size) to wire thumbs; undefined when nothing usable.
 */
export function thumbVariants(list: unknown): WireThumb[] | undefined {
  if (!Array.isArray(list)) return undefined;
  const out: WireThumb[] = [];
  for (const v of list) {
    const url = asString(get(v, "url"));
    if (url === undefined) continue;
    const t: WireThumb = { "url": absImageUrl(url) };
    const w = get(v, "width");
    const h = get(v, "height");
    if (typeof w === "number") t["w"] = w;
    if (typeof h === "number") t["h"] = h;
    out.push(t);
  }
  return out.length > 0 ? out : undefined;
}

/** Variants of a `{thumbnails:[…]}` node. */
export function thumbsOf(thumbnail: unknown): WireThumb[] | undefined {
  return thumbVariants(get(thumbnail, "thumbnails"));
}

/**
 * Depth-first, in-order walk over parsed JSON. `visit` sees every object entry; returning true
 * claims the value (no descent into it). Feed shapes nest renderers arbitrarily (grids in shelves
 * in sections), so a walk keyed on renderer names is far more change-resilient than exact paths.
 */
export function walk(root: unknown, visit: (key: string, value: unknown) => boolean | void): void {
  if (Array.isArray(root)) {
    for (const v of root) walk(v, visit);
  } else if (isObj(root)) {
    for (const k in root) {
      const v = root[k];
      if (visit(k, v) !== true) walk(v, visit);
    }
  }
}
