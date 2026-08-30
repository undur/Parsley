# Design: A source map for the rendered page (marker-free element location)

**Status:** Investigation. Not implemented. Captured from a late-night brainstorm so the
reasoning — including the dead end — survives to daylight.

**Goal:** Let the render heat map locate every element's output in the live page
**without injecting anything into the page at all**. The served HTML is byte-identical
to what a non-profiled render would produce; the location information travels alongside
the page as data — *element id → character range(s) in the response* — a **source map
for the rendered page**, interpreted client-side by the overlay.

This would retire the `<!--p:N-->` comment-marker system and, with it, the entire
"is it safe to emit a comment here" problem space.

---

## 1. What the markers cost us today

The current design brackets each profiled element's output with HTML comment pairs so
the overlay can find comment nodes and compute highlight rectangles. It works, and its
performance problems have been fixed (incremental safety scanner, linear strip pass) —
but the *architecture* keeps a standing tax:

- **A safety problem space.** An HTML comment is only valid at element-content level
  inside `<body>` — not mid-tag, not in `<script>`/`<style>`/`<title>`, not inside an
  authored comment (comments don't nest; our `-->` ends the author's comment and spills
  its content visibly — a bug class we have actually shipped). Every one of these
  contexts needs detection machinery (`ParsleyMarkerScanState`), and a post-render
  strip pass exists solely to catch markers that render-time checks *cannot* see
  (Wonder wraps collected script in `<script>` tags *after* the template renders).
- **Page weight.** ~35 bytes per element-occurrence. On a real 7.3 MB page with ~143k
  marker *pairs*, the markers are plausibly **around half the served bytes**. The
  instrumented page is materially not the real page.
- **The instrumented page is not the production page.** Marker emission subtly changes
  what the browser parses. Every past incident in this area (the comment spill, the
  served-JS corruption) came from *writing into the response*.

The root observation: **the page is the wrong place for this information.**

## 2. The dead end, recorded: wrapping elements in elements

The tempting inversion — render each dynamic element as a real DOM element
(`<p-47>output</p-47>`, a custom element that knows its identity) — *almost* works:
custom elements with a dash are legal unknown elements, and `display: contents` makes a
wrapper generate no layout box at all. Two things kill it, unfixably:

1. **HTML content models.** `<table><p-47><tr>…` — the parser foster-parents the
   unknown element out of the table, restructuring the document. Tables,
   `<select>`/`<option>` and friends cannot be wrapped. (Comments are the one node type
   allowed between table rows — which is exactly why they were the original choice.)
2. **CSS selectors see the wrapper even when layout doesn't.** `display: contents`
   fixes the box tree, not the DOM tree: `ul > li` stops matching through a wrapper,
   `:nth-child` counts it. Comment hazards are enumerable; selector breakage depends on
   the app's stylesheet — unbounded.

Do not re-derive this on the next late night.

## 3. The design

### 3.1 Server side: ranges are nearly free

`ParsleyProxyElement.appendToResponse` already reads the response's content length
before the wrapped element renders (captured for exception rollback, O(1) on the live
content buffer). Reading it again in the same `finally` yields the element's exact
**character range** in the response: `(lengthBefore, lengthAfter)`.

- Store the range(s) on the profiler's `TreeNode` — the tree the overlay already
  consumes (ids, component/line, bindings, timings, SQL, binding origins). The map is
  one more field per node.
- **Nesting falls out naturally**: rendering is a stack, so parent ranges contain child
  ranges — the map *is* the tree projected onto the text.
- **Repeats fall out naturally**: a repetition body that rendered 240 times carries 240
  ranges on its node — precisely what 240 marker pairs used to encode, at the cost of
  two ints each instead of ~30 bytes of comments in the page.

Cost: two O(1) length reads per element (one of which already happens) plus two ints of
storage per occurrence. No response writes. Note the ranges are **character offsets**
(the content buffer is a `StringBuilder`), not byte offsets — see §3.3.

### 3.2 Delivery: a lazy sidecar, not an embedded blob

On a ~143k-element page even a compact map is real bytes. Embedding it would re-inflate
the document we just cleaned. Instead:

- A **dev-only endpoint** (piggybacking the existing controls direct-action class)
  serves, keyed by a request id: the source map (tree node id → ranges) and the
  **original response text** as captured at end-of-render.
- The overlay fetches both **on demand** — first hover/click of the render tree — not
  at page load. The page the browser parses stays pristine; tooling weight loads only
  when the tool is reached for.
- The server retains the response text for recent profiled requests only (small LRU,
  dev-only; the profiler result already lives server-side per request — the text rides
  along).

### 3.3 Client side: interpreting the map

The one genuinely new component. The offsets refer to the **original response text** —
not `document.outerHTML`, which is the browser's re-serialization and will not line up.
Hence the sidecar serves the true text (as *text*, keeping offsets character-based —
byte offsets would drag encoding into it).

- **Exact approach:** a small tolerant HTML tokenizer in the overlay JS walks the
  original text once, tracking offsets and emitting DOM *paths*
  (`body/div[2]/table[0]/tr[4]`) as it opens/closes elements. A range then maps to a
  node path (or a start/end pair within one parent); the path is resolved against the
  **live** DOM; a `Range` + `getBoundingClientRect()` yields the highlight box — same
  UX as today, from data instead of scars. Worst case is a missed highlight; page
  corruption is *unrepresentable*.
- **Cheap fallback (also a possible v1):** the server knows each element's rendered
  snippet (it has text + range). Text-search the live DOM for the snippet and highlight
  matches. Ambiguous for identical outputs — but that's an N+1's 240 identical rows,
  and the marker system also highlighted all occurrences. Nearly zero infrastructure.

## 4. Open questions / risks

1. **Post-render response rewriting — the big one.** Anything that mutates the response
   *after* elements record their offsets shifts every range downstream of the edit.
   Our own injections are safe (they append at `</body>`, after all recorded ranges).
   Wonder's script-collection rewriting is the known offender — the same edge that
   forced the strip pass. Likely acceptable: divergence is localized around rewritten
   `<script>` blocks, whose output isn't visible/highlightable anyway. **A prototype
   must confirm this first** — capture ranges, capture final text, measure how far
   reality drifts on a real Strimillinn page.
2. **Parser error-recovery divergence.** The tokenizer's idea of the tree vs. the
   browser's, on imperfect markup (unclosed tags, foster-parenting). The tokenizer must
   mimic enough of the browser's recovery to keep paths aligned — or the design accepts
   missed highlights where markup is broken. Dev tool: acceptable.
3. **AJAX partial updates.** An `AjaxUpdateContainer` replace swaps live DOM chunks
   that no longer correspond to the original full-page response. The marker system
   partially survived this (markers ride along inside the replaced fragment); the map
   would need per-request maps for partial responses, or accept that highlights go
   stale after partial updates until the next full render. Scope decision, not a
   blocker.
4. **Memory.** Retained response texts (potentially MBs each) × LRU depth, dev-only.
   Bound it and move on.

## 5. What gets deleted when this lands

- Marker emission in `ParsleyProxyElement` (and the open/close balancing).
- `ParsleyMarkerScanState` and the entire marker-safety decision.
- The post-render strip pass in `ParsleyRenderHeatmapOverlay`.
- The comment-spill hazard **as a category** (the parked `PHTMLCommentNode` parser work
  remains worthwhile on its own merits, but stops being load-bearing for the heat map).
- Up to ~half the served bytes of marker-dense pages.

The overlay's comment-node index/`buildIndex` JS is replaced by the map interpreter.

## 6. Prototype plan (smallest path to confidence)

1. **Range capture** — the two-line `finally` addition + ranges on `TreeNode`. Data
   flows even while markers still exist; nothing consumed yet.
2. **Drift measurement** — dev endpoint dumping map + captured text; a throwaway check
   comparing recorded ranges against the final response on real Strimillinn pages
   (especially ones exercising Wonder's script rewriting). This answers risk #1 before
   any interpreter is written.
3. **Interpreter spike** — the tolerant tokenizer against a captured page; highlight
   parity check vs. the marker system on the same render.
4. Only then: switch the overlay, delete the marker machinery (§5).

Steps 1–2 are small and reversible; the go/no-go lives entirely in step 2's numbers.
