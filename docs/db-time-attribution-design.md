# Design: Per-element database-time attribution in the render heat map

**Status:** Prototype, working and verified live against Strimillinn (Cayenne 5.0).
On branch `render-io-profiling`.

**Goal:** Show, in the render heat map, **how much database time each template
position cost and how many queries it ran** — so that "IO is a prime suspect" stops
being a guess. The actionable target is the **N+1**: a repetition body that quietly
runs one fetch per row, attributed to the exact line of template that caused it.

This extends the existing render profiler (which already attributes *render* time and
*binding* time per template position) with a third cost stream: **IO time + query
count**, plus drill-in to the actual SQL.

---

## 1. The idea, and why the heat map already had the hard part solved

A prior proof-of-concept (in `strimillinn`: `SMJDBCEventLogger` / `SMJDBCInfoProvider`
/ `SMComponent.valueForKey`) captured DB time per *method/keypath* by:

- subclassing Cayenne's `Slf4jJdbcEventLogger` to see query timing,
- walking a **stack trace** to guess the calling method,
- stashing `lastHandledKey` / `lastHandledKeyPath` in the context dictionary on every
  `valueForKey`.

It was useful but strained at the seams: the stack-walk guessed "first non-Cayenne
frame", `lastHandledKey` was a single overwritten slot (stale by the time the SQL
ran), and it flattened by **method name** — but the same keypath fires from a hundred
template positions, so "method X is slow" wasn't actionable.

**Parsley's render profiler already solved the attribution problem the PoC was
fighting.** It maintains a per-thread stack of frames keyed by **template position**
(`ParsleyRenderProfiler` — the `Request.stack` of `Frame`s). At the instant any SQL
runs during render, the top of that stack *is* the template node executing — `this
<wo:repetition> row → this component reference → this binding`. Precise, structural,
no stack-walking, no guessing.

So the DB feature is just: **feed query events into that existing stack.** The PoC's
hard problem disappears; what's left is plumbing.

---

## 2. Architecture: a neutral core sink + a per-persistence adapter

Parsley core must not depend on Cayenne (or EOF, or JDBC). The seam is **one static
method** on the profiler — the same shape as the existing `recordBindingPull` /
`recordBindingPush` hooks. There is deliberately **no SPI/interface**: core's other
hooks are plain static calls, and this matches.

```
parsley (core)
 └─ ParsleyRenderProfiler.recordQuery(nanos, sql)   ← neutral sink; core knows nothing about DBs
        ▲ called by
parsley-cayenne (optional module)                   ← knows Cayenne; core does NOT
 └─ ParsleyCayenneEventLogger extends Slf4jJdbcEventLogger
        translates Cayenne's logger callbacks into recordQuery(...)
```

- **Core** exposes `recordQuery(long nanos, String sql)`. It credits the query to the
  frame on top of the render stack and accumulates request totals. Nothing else.
- **`parsley-cayenne`** is a new optional Maven module, mirroring `parsley-ognl`
  exactly: it depends on `parsley` core + Cayenne, ships the adapter, and opts in via a
  single call. An **EOF adapter** (`parsley-eof`, future) would be a sibling calling the
  *same* `recordQuery` — Parsley core never changes.

The dependency arrow points only inward. This is the whole point of the split.

### Opt-in (Cayenne)

Cayenne resolves its `JdbcEventLogger` through DI, so the app contributes a module when
building its `ServerRuntime`:

```java
ServerRuntime runtime = ServerRuntime.builder()
    .addConfig("cayenne-project.xml")
    .addModule(ParsleyCayenne.profilingModule())   // binds ParsleyCayenneEventLogger
    .build();
```

Every hook is gated on `ParsleyRenderProfiler.isEnabled()`, so with profiling off
(production) the logger behaves exactly like its superclass — safe to leave installed.

---

## 3. Attribution rules

`recordQuery` credits the query to **the frame on top of the render stack** — the
element whose code is running when the fetch fires. `queryCount++` once per query,
`ioNanos += nanos`, and the SQL is sampled (see §6).

- **Happy path (the important one):** a fault/fetch fires synchronously inside a
  binding pull (`valueForKey` on a component) *during render*. That pull is already
  being timed by `ParsleyKeyValueAssociation`; the owning element's frame is on top; so
  the IO time lands on the right row, and it is a **subset** of that frame's measured
  time. This is exactly the N+1 case, and it attributes perfectly.

- **Off the render thread** (async, prefetched, background pool thread): no frame is on
  the stack. Rather than drop the query, it's recorded as **unattributed** request-level
  IO (`Request.unattributedIoNanos` / `unattributedQueryCount`) — counted in totals,
  just not pinned to a row, because the thread-local stack that does the pinning isn't
  valid off-thread. The profiler lazily creates its per-request state in `recordQuery`
  too, so a query firing before any element render still counts.

### IO is a *breakdown* of wall-clock, not an additive axis

A binding that triggers a fetch already has the SQL time *inside* its measured
`bindingNanos` (the fetch happens synchronously during the timed `valueForKey`). So IO
is modelled as a **breakdown annotation** of existing wall-clock, not a fourth additive
column:

```
io ⊆ bind ⊆ self            (per row)
```

This mirrors the existing `bind ⊆ self` discipline and means the page total never
double-counts: the `db` column says "*of this row's time, N was DB*", it does not add
time on top. (Tested: `queryTimeStaysWithinSelf_whenFiredInsideABindingPull`.)

---

## 4. Timing: measure it ourselves, don't trust the framework's milliseconds

**Lesson learned live.** Cayenne reports `logSelectCount` elapsed time in **whole
milliseconds**. Trusting it quantizes every value to a 1ms grid: a 0.6ms query reads as
`0`, every value is a round multiple of 1000µs. In the running app this showed up
immediately as suspiciously round numbers (`2×5,000`, `1×17,000`).

Fix: the adapter times the query **itself** at nanosecond resolution. Cayenne calls
`logQuery(sql, bindings)` when a statement is about to run and `logSelectCount(...)`
when it's done — both synchronously on the same thread. So the adapter captures
`System.nanoTime()` in `logQuery` and measures the span to `logSelectCount`, falling
back to Cayenne's millisecond value only if no start was captured. Result: true
sub-millisecond timings (`832 1q`, `1,091 1q`) instead of rounding to 0 or 1000.

General principle for any future adapter: **prefer your own nanoTime span over the
persistence layer's reported duration** unless it's already sub-ms precise.

---

## 5. Reading the `db` column

The cell renders **`<time> Nq`** — combined DB wall-clock for the row first (consistent
with the other metric columns), then the query count as a dimmed `q` suffix.

> It used to render `N×time` (e.g. `2×700`). That **prefix read as a multiplier** —
> `2×700` looked like 1400µs, when 700 was already the *combined total*. The suffix
> form `700 2q` reads as "700µs total, 2 queries" with no implied arithmetic. (This was
> a real point of confusion, fixed.)

**What `Nq` means, precisely:** the number of queries attributed to that template
position **over the whole request**. Combine it with the row's own occurrence count
(the `×N` by the label) to read it correctly:

| Row's `×count` (occurrences) | `db` shows | Meaning |
|---|---|---|
| 1 (rendered once) | `… 2q` | This single element ran **2 queries** — possibly two *different* fetches in one render. |
| 240 (repetition body) | `… 240q` | ~1 query per render — the **classic N+1**. |

Whether the `Nq` queries are *the same* statement repeated or *different* statements is
answered by the per-statement drill-in (§6) — and, for the same-statement case, by the
**red N+1 flag** below.

### The red N+1 indicator

When **any single distinct statement ran more than once** at a position, the `db` cell
renders **red** (bold, with an explanatory hover title). This is the most actionable
thing the column surfaces — the classic N+1 — made visible *without* opening the
drill-in.

The detection is deliberately **per distinct statement** (`TreeNode.
hasRepeatedStatement()`), **not** `queryCount > 1`:

| Row | `db` | Flagged? |
|---|---|---|
| ran the same query 9× | `… 9q` | 🔴 **red** — N+1 |
| ran **two different** queries | `… 2q` | normal — two fetches, not an N+1 |
| ran one query | `… 1q` | normal |

That distinction is the whole point: a naive `queryCount > 1` would falsely flag the
two-different-queries row. Catching the same statement repeating — whether within one
render (a fetch in a loop) or collapsed across a repetition's iterations — is what an
N+1 actually *is*.

---

## 6. SQL drill-in with per-statement timing

Each `TreeNode` aggregates its queries **per distinct statement** into a `SqlStat`
(`count`, `totalNanos`, slowest-run `maxNanos`), keyed by SQL text, capped at
`MAX_DISTINCT_SQL` (20) distinct statements — an N+1's repeats fold into one entry, so
the cap is effectively never hit by repetition. Clicking the `db` cell toggles an inline
panel listing each statement **slowest-total first**, each with its own timing line.

**Why per-statement, not just a per-row total.** A row total hides *which* of several
queries is the offender. The per-statement breakdown answers it directly, and
distinguishes the two failure modes a row total conflates (both seen live):

- **One slow query** — a row showed `167,948µs` and `4,044µs` as two separate
  statements: the offender is unmistakable, and it's a single execution.
- **An N+1** — a row showed `9 queries · 1 distinct`, `4,354µs total · max 923µs`: many
  cheap runs of one statement. The `max` (no single run over ~1ms) says *don't optimise
  the query, kill the repetition* (add a prefetch).

So `max` vs `total` vs `count` together tell you the *kind* of problem, which determines
the fix. The header reads "N queries · M distinct statement(s)" when more queries ran
than there are distinct statements.

**One sharp edge, fixed.** Parent rows render as `<details>/<summary>`; the SQL panel
for such a row lives *inside* its `<details>`, so the panel's visibility is governed by
BOTH our display flag AND the details' open state. On a collapsed subtree, flipping
`display:block` did nothing — the panel stayed hidden (the "db cell that wouldn't
reveal"). `parsleyToggleSql` now force-opens every ancestor `<details>` when revealing,
and suppresses the summary's own toggle so the row can't collapse out from under the
panel. (Future: capture **bound parameter values** from `logQuery`'s
`ParameterBinding[]` so the drill-in shows real values, not `?` placeholders.)

---

## 7. Caveats (honest)

- **Thread affinity is load-bearing.** Attribution works because EOF/Cayenne faults
  fire synchronously on the render thread. Async/prefetched fetches can't be pinned and
  land in the unattributed bucket — counted, not located. Document, don't pretend.
- **`logQuery`/`logSelectCount` pairing** assumes they bracket one query on one thread.
  True for Cayenne's synchronous fetch path; an adapter for a batching/async layer would
  need its own correlation.
- **Per-statement capture is capped at 20 distinct statements** per position (repeats of
  one statement fold into a single `SqlStat`, so an N+1 never approaches the cap). The
  row's total `ioNanos`/`queryCount` still count statements past the cap; only the
  breakdown list is bounded.

---

## 8. Status / what's built

Working and verified live (branch `render-io-profiling`):

- Core: `ParsleyRenderProfiler.recordQuery` + `TreeNode` IO fields
  (`ioNanos`/`queryCount`), per-statement `SqlStat` aggregation
  (`count`/`totalNanos`/`maxNanos`), `hasRepeatedStatement()`, and request-level totals
  incl. the unattributed bucket.
- Overlay: `db` column (`time Nq`), **red N+1 flag**, SQL drill-in with **per-statement
  timing** (slowest-first) and collapsed-`<details>` handling.
- `parsley-cayenne` module: `ParsleyCayenneEventLogger` (nanosecond timing) +
  `ParsleyCayenne.profilingModule()`, built against Cayenne 5.0-SNAPSHOT.
- Tests: attribution, N+1 collapse, per-statement offender/`max`, N+1 detection
  (per-distinct, not count), `io ⊆ bind ⊆ self`, unattributed bucket, overlay column +
  drill-in + red-flag render.

**Possible next steps:**
- Bound-parameter capture from `logQuery`'s `ParameterBinding[]`, so the drill-in shows
  real values, not `?` placeholders.
- A `parsley-eof` sibling adapter, to prove the persistence-agnostic split generalises
  beyond Cayenne.
- Surface the unattributed-IO total somewhere in the overlay (currently tracked, not
  shown).

### Known wart: the overlay is hand-built HTML (intentional placeholder)

`ParsleyRenderHeatmapOverlay` constructs its HTML by hand — a ~900-line `StringBuilder`
of concatenated `<div>`s with manual `escape(...)` at every interpolation. Shipping
hand-rolled string-concatenated HTML *inside a templating library* is conspicuously
ironic, and the manual escaping is exactly the class of bug Parsley exists to prevent.

**The intended fix is to render the overlay through the template engine itself** — full
dogfood. The thing that makes this currently impractical is that Parsley is a *WO*
component-template parser: rendering through it today would mean standing up a
`WOComponent` + `WOContext` and the WO render cycle for a diagnostic that runs *after*
the real page rendered (inside `ParsleyRequestObserver.didHandleRequest`), including the
reentrancy puzzle of profiling the overlay's own render.

That blocker is **going away on its own**: the ng-objects template engine is being made
**framework-agnostic** and usable standalone. Once that lands, the overlay renders
through that engine — no WO scaffolding, no re-entering the WO cycle it just profiled —
and the irony resolves properly (the heat map renders via the very engine it profiles).

So the decision is explicit:

- **Target:** render the overlay via the ng framework-agnostic engine, once it's ready.
- **Blocked on:** that engine reaching standalone/usable — already in construction in
  ng-objects, not this repo's work.
- **Interim:** leave the `StringBuilder` as-is. It works; it's just inelegant. **Do not**
  build an intermediate typed HTML-builder — it would be torn out wholesale when the
  engine arrives, i.e. throwaway polish on code that's slated for deletion.

Flagged here so it reads as a **deliberate placeholder pending a known dependency**, not
neglected code.
