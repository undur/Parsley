# Design: An ng-equivalent Ajax framework for wonder-slim

**Status:** Probable-TODO / design exploration. Not scheduled.
**Goal:** A new `Ajax` framework for wonder-slim whose partial-page-update behavior is
**equivalent, from a template writer's perspective**, to ng-objects' Ajax support — i.e. the
same handful of elements (`AjaxUpdateContainer`, `AjaxUpdateLink`, `AjaxSubmitButton`,
`AjaxObserveField`) behaving the same way — but running on the real WO runtime via
wonder-slim's own `ERXWOContext`.

This doc is the result of reading three code bases side by side:

- **ng-objects** Ajax: `ng-appserver/.../templating/elements/ajax/` + `ng-js.js` + `NGContext` + `NGPageCache` — **~4 element classes, one ~120-line page cache.**
- **Wonder Ajax.framework**: `wonder-slim/Ajax/` — **92 Java classes** + Prototype/Scriptaculous JS.
- **Wonder's caching core**: `wonder-slim/ERExtensions/.../appserver/ajax/` — `ERXAjaxSession`, `ERXAjaxApplication`, `ERXAjaxContext`.

The headline finding: **the difficulty is almost entirely in one place — the page cache — and ng
already avoided it by making a different design decision, not by writing cleverer code.** If we
adopt ng's decision, the snake stays in its cage.

---

## 1. The two layers, and which one is hard

Wonder's AJAX (and ng's) is two cooperating layers. Only one is hard.

### Layer 1 — Elements + the partial-render gate (the easy ~70%)

The trigger/container elements are pure presentation, and **Wonder and ng already implement the
gate the same way**, which is the single most reassuring finding in this whole investigation.

In ng, `NGContext.shouldAppendToResponse()` (`NGContext.java:303`) walks the whole page on an
Ajax request but only emits output for elements inside the targeted container — the container id
arrives in the `ng-container-id` header (`NGContext.java:256-266`), and `AjaxUpdateContainer`
pushes/pops its id on a stack while rendering children (`AjaxUpdateContainer.java:69-74`).

Wonder does **structurally the same thing**: `AjaxUpdateContainer` gates `takeValuesFromRequest`,
`invokeAction`, and `appendToResponse` on a thread-scoped "current update container id"
(`Ajax/.../AjaxUpdateContainer.java:81,101,189`), stored via
`ERXWOContext.contextDictionary()`. The container id travels as the `_u` form value
(`ERXAjaxApplication.KEY_UPDATE_CONTAINER_ID = "_u"`).

So this layer is a near-direct port. The work is mechanical:

- 4 WO `WODynamicElement`/`WODynamicGroup` subclasses.
- The gate lives in **`ERXWOContext`** — which we own in wonder-slim, so unlike the original
  Parsley analysis there is **no "proxy-element-only" leakage problem**. Every element on the page
  consults the same context, exactly like ng. This is the key enabler the user identified.
- Port `ng-js.js` (429 lines, framework-free, modern `fetch`) instead of dragging in
  Prototype/Scriptaculous. This is a big simplification over Wonder's JS in its own right.

### Layer 2 — The page cache (the hard ~30%, and the entire reason this doc exists)

This is the pit of snakes. See §2.

---

## 2. Why Wonder's caching is a pit of snakes (and why ng's isn't)

**The complexity is not inherent to AJAX. It is the cost of one decision: Wonder kept WO
*component actions* alive inside AJAX-updated regions.** Everything in `ERXAjaxSession` flows from
that. mschrag's own comment is the confession (`ERXAjaxSession.java:166-198`):

> "if you let them use the normal page cache, then after only 30 updates from Ajax, you will fill
> your backtrack cache… the foreground page has fallen out of the cache… Enter page replacement
> cache."

The consequences, all of which we'd inherit if we make the same choice:

1. **A parallel "page replacement cache"** layered on top of WO's native backtrack cache
   (`ERXAjaxSession.savePage`, `:200-253`), keyed per Ajax component, replace-on-refresh.
2. **Double-buffering** — it keeps the last **two** states per component to dodge a race where the
   replacement cache evicts context-2's link before the browser has rendered context-3
   (`:190-193`). This is pure consequence of (1).
3. **A permanent-page-cache override** reaching into private WO ivars via `ERXPrivateKVC` and
   `_PermanentCacheSingleton` reflection (`_saveCurrentPage`/`savePageInPermanentCache`,
   `:348-429`).
4. **A header/sentinel thicket** to drive it all: `DONT_STORE_PAGE`, `FORCE_STORE_PAGE`,
   `PAGE_REPLACEMENT_CACHE_LOOKUP_KEY`, `ORIGINAL_CONTEXT_ID_KEY`, plus `shouldNotStorePage` /
   `forceStorePage` / `cleanUpHeaders` (`ERXAjaxApplication.java:131-193`).
5. **`ERXAjaxContext._wasFormSubmitted()` override** for partial form submits
   (`ERXAjaxContext.java:27-48`).

**ng's `NGPageCache` is ~120 lines with none of (1)–(3).** It keeps **one entry per update
container**, keyed by `updateContainerID`, replaced each refresh (`NGPageCache.java:107`), and
explicitly declares (`NGPageCache.java:40` FIXME) that a container's old content "will never get
used once it's been replaced." No replacement-vs-backtrack reconciliation, no double-buffering, no
private-ivar reflection. ng can do this **because it didn't try to preserve a 30-deep backtrack
history of background updates alongside the foreground page.**

---

## 3. The one fork that decides everything

Before any code is written, decide this:

### Option A — Follow ng: component actions do **not** live inside AJAX regions

- `AjaxUpdateLink` carries a fresh-context / direct-action-style round-trip; the container
  re-renders from a page kept alive by **WO's existing backtrack cache** for the foreground page,
  plus an ng-style per-container entry for the updated region.
- We write **ng's ~120-line cache, not Wonder's ~480**. The entire `ERXAjaxSession`
  replacement-cache apparatus — items (1), (2), (3) above — **evaporates.**
- Template-writer experience is identical to ng. **This is the stated goal**, so the
  simplification comes for free: the writer never sees a cache either way.

### Option B — Preserve full WO component-action semantics inside regions

- We are back to re-deriving `ERXAjaxSession`. The snakes return in full.

**Recommendation: Option A.** ng is template-writer-equivalent to Wonder's
AjaxUpdateContainer/Link/ObserveField *precisely because* it refused to solve B. Emulating ng means
inheriting that refusal. If a future use case truly needs B in one spot, that can be a localized
escape hatch rather than the default architecture.

> ⚠️ **The one place Option A can quietly break on WO**, and the thing to nail down first:
> when an `AjaxUpdateLink`'s action fires *inside* a container, WO's `restorePageForContextID`
> must hand back the live page instance whose action method we then invoke. ng routes this through
> its own `NGPageCache` keyed by container id; WO routes it through the session's context cache
> keyed by context id. The port has to ensure the foreground page's context **survives** the
> background Ajax round-trips (the exact failure Wonder's replacement cache was built to prevent).
> On WO with Option A the answer is "lean on WO's native backtrack cache for the foreground page,
> and don't let Ajax updates evict it" — which, if Ajax updates are stored ng-style under their own
> per-container key rather than consuming backtrack slots, they won't. **Prototype this single
> round-trip before committing.** It is the whole ballgame.

---

## 4. Element → WO mapping

| ng element | wonder-slim element | Notes / hooks needed |
|---|---|---|
| `AjaxUpdateContainer` (`div` + id stack push/pop) | `WODynamicGroup` subclass | Push/pop container id on **`ERXWOContext`** stack while appending children. Gate output via context (`shouldAppendToResponse` analog). |
| `AjaxUpdateLink` (`<a onclick=ajaxUpdateLinkClick>`) | `WODynamicElement` | `action` (required) + optional `updateContainerID`. `invokeAction` returns the action result only when `currentElementIsSender()`. |
| `AjaxSubmitButton` | `WODynamicElement` | Submits containing form via `fetch`, appends button name/value, sets `ng-container-id`. |
| `AjaxObserveField` | `WODynamicElement` + JS | Two modes (single field / observe-descendants), debounce, `fullSubmit`. See §5. |

The gate, the id stack, and `currentElementIsSender` are the only context-level hooks. We own
`ERXWOContext`, so all three are ours to add cleanly — no private-API reflection required (the
sharp contrast with `ERXAjaxSession`'s `ERXPrivateKVC` hacks).

---

## 5. The second-trickiest bit: partial form submit (`AjaxObserveField`)

Wonder solves "take values from only this field/region, not the whole form" **server-side** by
overriding `ERXAjaxContext._wasFormSubmitted()` (`:27-48`) so non-targeted inputs are skipped
during `takeValuesFromRequest`.

ng solves it **client-side**: the observe field only *sends* its own value (`ng-js.js:294-296`),
or the full form when `fullSubmit` is set. The server then just takes values normally — there's
nothing special to skip because nothing extra was sent.

ng's approach is dramatically simpler and removes the need for the `_wasFormSubmitted` override
entirely. **Confirm it holds on WO**: WO's `takeValuesFromRequest` walks the whole component tree
and pulls each input's value from the form data by element id/name. If the request body only
contains the observed field's value, the other inputs simply have no incoming value — which is the
desired outcome — **provided** WO doesn't clobber absent fields to null/empty. That single
behavior (absent-vs-empty in WO `takeValuesFromRequest`) is the thing to verify in the prototype.
If WO does clobber, we either (a) send the full form always (lose the optimization, still correct),
or (b) fall back to an ng-`ERXAjaxContext`-style gate for the take-values phase only.

---

## 6. JS port: what's verbatim vs. what needs WO adaptation

ng's `ng-js.js` is framework-free and almost entirely portable.

**Verbatim (or nearly):**
- `ajaxRequest` core (`fetch`, loading classes, multipart-vs-text branch) — `:95-174`
- `setUpdateContainerContent` + `ng-content-updated` event — `:71-84`
- Focus capture/restore — `:24-64`
- Debounce — `:7-20`
- Error overlay — `:182-201`
- Observe-field binding + `MutationObserver` re-bind — `:314-429`

**Needs WO adaptation:**
- **URLs.** ng builds `ajaxUpdateLinkClick(url, …)` from `context.componentActionURL()`
  (ng's senderID/contextID scheme, `AjaxUpdateLink.java:40`). On WO this becomes a WO
  component-action URL (`context.componentActionURL()` exists on WO too, but the URL *shape* and
  the senderID encoding differ). The JS contract (pass a URL + a container id header) is unchanged;
  only the server-emitted URL string differs.
- **Multipart container responses.** ng keys multipart parts by container id (`ng-js.js:118-133`,
  `NGResponseMultipart`). Port the multipart writer to a WO `WOResponse`.
- **Header name.** ng uses `ng-container-id`; pick our canonical header (could keep `ng-container-id`
  for symmetry, or namespace it). Wonder used the `_u` form value — we'll standardize on a header,
  per ng.

---

## 7. Proposed build order (when this becomes real)

1. **Spike the one risky round-trip first** (§3 warning + §5 confirm): a single `AjaxUpdateContainer`
   with an `AjaxUpdateLink` whose action mutates component state, proving (a) the foreground page's
   context survives Ajax updates without a replacement cache, and (b) partial submit doesn't clobber
   absent fields. **If this spike is clean, the rest is mechanical.**
2. `ERXWOContext` hooks: container-id stack + `shouldAppendToResponse` gate + `currentElementIsSender`.
3. The 4 elements, full-page-update first (they're useful even before partial render — they just
   re-render the whole page into the container).
4. Partial render via the gate.
5. ng-style per-container page cache (~120 lines).
6. Port `ng-js.js`; adapt URLs + multipart.
7. `AjaxObserveField` + multi-container multipart.

## 8. Bottom line

- **Layer 1 (elements + gate): very doable, near-direct port, no private APIs.** Owning
  `ERXWOContext` removes the only architectural blocker.
- **Layer 2 (cache): doable *if* we take Option A.** ng's simplicity is a *design choice* we can
  re-make on WO, not an artifact of ng's runtime. Taking it shrinks the cache from Wonder's
  ~480 lines of replacement/permanent/double-buffer machinery to ng's ~120.
- **One spike de-risks the whole thing** (§7.1). Everything downstream of a clean spike is
  mechanical porting.
- Net: a handful of element classes + ~120-line cache + a ported `ng-js.js`, versus Wonder's 92
  classes. The 4-vs-92 gap is real and it's mostly *avoided complexity*, not *missing features* —
  because the missing feature (component actions inside Ajax regions) is the one we're deliberately
  declining.
