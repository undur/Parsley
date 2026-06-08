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
answered by the **SQL drill-in** (§6): "240 queries — showing 1 distinct" → N+1 (add a
prefetch); N distinct → genuinely different queries. Count and distinctness are kept
separate on purpose, because they lead to different fixes.

---

## 6. SQL drill-in

Each `TreeNode` keeps up to `MAX_SQL_SAMPLES` (5) **distinct** SQL strings it ran
(capped so a 10k-iteration N+1 doesn't retain 10k copies). Clicking the `db` cell
toggles an inline panel showing those statements, headed by "N queries — showing M
distinct" when the row ran more than it kept.

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
- **SQL sampling is capped at 5 distinct** per position; the drill-in header states when
  it's showing fewer than were run, so nothing silently misleads.

---

## 8. Status / what's built

Working and verified live (branch `render-io-profiling`):

- Core: `ParsleyRenderProfiler.recordQuery` + `TreeNode.ioNanos/queryCount/sqlSamples`
  + request-level totals incl. unattributed bucket.
- Overlay: `db` column (`time Nq`) + SQL drill-in with collapsed-`<details>` handling.
- `parsley-cayenne` module: `ParsleyCayenneEventLogger` (nanosecond timing) +
  `ParsleyCayenne.profilingModule()`, built against Cayenne 5.0-SNAPSHOT.
- Tests: attribution, N+1 collapse, `io ⊆ bind ⊆ self`, unattributed bucket, overlay
  column + drill-in render.

**Possible next steps:** bound-parameter capture in the drill-in; a `parsley-eof`
sibling adapter to prove the generalization; surfacing the unattributed-IO total
somewhere in the overlay; an explicit "(M distinct)" hint in the column for N+1s.
