package parsley;

/**
 * PROTOTYPE — renders {@link ParsleyRenderProfiler.Result} as a self-contained HTML
 * overlay showing the <b>template tree</b>, so you can see which region/section of
 * the template is expensive, not merely which element types are.
 *
 * <p>Each node is indented to its depth and carries a heat bar sized to its
 * <em>inclusive</em> time (self + descendants) as a fraction of the whole page —
 * so a hot subtree is obvious at the top level, and you can read down into it to
 * find the specific child carrying the cost (its self-time, shown per row, is what
 * pinpoints the culprit). Hottest children are listed first within each parent.
 *
 * <p>Inline-styled + {@code <details>}-based: no external CSS/JS, can't be broken by
 * or break the host page. // 2026-06-01
 */
final class ParsleyRenderHeatmapOverlay {

	private ParsleyRenderHeatmapOverlay() {}

	/** Matches a single position marker (open or close). */
	private static final java.util.regex.Pattern MARKER = java.util.regex.Pattern.compile( "<!--/?p:\\d+-->" );

	/**
	 * Removes position markers that ended up somewhere an HTML comment is invalid or
	 * would corrupt content: inside a raw-text element ({@code <script>} /
	 * {@code <style>} / {@code <title>}, where a comment isn't parsed and becomes
	 * literal text) or inside an <em>authored</em> {@code <!-- … -->} comment (HTML
	 * comments don't nest, so our marker's {@code -->} would prematurely end the
	 * author's comment and spill its contents as visible text).
	 *
	 * <p>This runs on the fully-assembled response (see {@code ParsleyRequestObserver})
	 * — the only place these contexts are unambiguous. Some can't be guarded at render
	 * time at all: Wonder collects script via response rewriting and wraps it in
	 * {@code <script>} <em>after</em> the template renders, so no render-time check
	 * could see it.
	 *
	 * <p>Implementation: a single linear scan tracking whether we're inside a
	 * raw-text element or an authored comment, dropping markers while inside either.
	 * Markers in normal body flow are kept.
	 */
	static String stripMarkersInUnsafeContexts( final String content ) {
		if( content == null || content.indexOf( "<!--p:" ) == -1 ) {
			return content;
		}

		final java.util.regex.Matcher m = MARKER.matcher( content );
		final StringBuilder out = new StringBuilder( content.length() );
		int pos = 0;
		while( m.find() ) {
			final int markerStart = m.start();
			// Decide safety from the text already emitted before this marker, i.e. the
			// content from the end of the previous marker up to here, plus carry-over
			// state. Simpler and robust: re-evaluate context from the start of the
			// uncopied region is too costly, so we scan the *prefix* once per marker
			// over a bounded window — markers are sparse enough that this is fine.
			out.append( content, pos, markerStart );
			if( !inUnsafeContext( content, markerStart ) ) {
				out.append( m.group() ); // keep it — it's in normal flow
			}
			// else: drop the marker (append nothing)
			pos = m.end();
		}
		out.append( content, pos, content.length() );
		return out.toString();
	}

	/**
	 * @return true if position {@code at} in {@code content} is inside a raw-text
	 *         element or an authored HTML comment — i.e. a marker there is unsafe.
	 *
	 * <p>Scans a bounded window ending at {@code at}. The window comfortably covers
	 * realistic script/comment spans; a span larger than the window is an acceptable
	 * cosmetic edge for a dev-only tool.
	 */
	private static boolean inUnsafeContext( final String content, final int at ) {
		final int windowStart = Math.max( 0, at - 65_536 );
		final String s = content.substring( windowStart, at );
		final String lower = s.toLowerCase();

		// Inside an authored comment? Last authored "<!--" with no later "-->",
		// skipping our own self-closed markers so they don't mask an open comment.
		if( authoredCommentOpen( lower ) ) {
			return true;
		}

		// Inside <script>/<style>/<title>? Last such open tag with no matching close.
		return rawTextOpen( lower, "script" ) || rawTextOpen( lower, "style" ) || rawTextOpen( lower, "title" );
	}

	private static boolean authoredCommentOpen( final String s ) {
		int i = 0;
		boolean open = false;
		while( i < s.length() ) {
			final int no = s.indexOf( "<!--", i );
			final int nc = s.indexOf( "-->", i );
			if( no == -1 && nc == -1 ) {
				break;
			}
			if( nc != -1 && (no == -1 || nc < no) ) {
				open = false;
				i = nc + 3;
				continue;
			}
			if( s.startsWith( "<!--p:", no ) || s.startsWith( "<!--/p:", no ) ) {
				final int me = s.indexOf( "-->", no + 4 );
				i = me == -1 ? s.length() : me + 3;
				continue;
			}
			open = true;
			i = no + 4;
		}
		return open;
	}

	private static boolean rawTextOpen( final String lower, final String tag ) {
		final int lastOpen = lower.lastIndexOf( "<" + tag );
		if( lastOpen == -1 ) {
			return false;
		}
		return lower.indexOf( "</" + tag, lastOpen ) == -1;
	}

	static String render( final ParsleyRenderProfiler.Result result ) {
		return render( result, null );
	}

	static String render( final ParsleyRenderProfiler.Result result, final String appName ) {

		final long total = result.totalInclusiveNanos();

		final StringBuilder b = new StringBuilder( 8192 );

		b.append( overlayScript() );

		// Emit a JS map of marker id -> IDE-open URL, so inspect-mode clicks on the
		// page can resolve the element under the cursor straight to its template.
		appendOpenUrlMap( b, result.root(), appName );

		b.append( "<aside id=\"parsleyPanel\" style=\"" )
				.append( "position:fixed;bottom:12px;right:12px;z-index:2147483647;" )
				.append( "width:min(960px,60vw);max-height:80vh;overflow:auto;" )
				.append( "font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;" )
				.append( "background:rgba(20,22,28,0.96);color:#e6e6e6;" )
				.append( "border:1px solid #3a3f4b;border-radius:10px;" )
				.append( "box-shadow:0 8px 30px rgba(0,0,0,0.5);\">" );

		// Resize handles — thin fixed-position grab strips kept aligned to the panel's
		// left and top edges by the resize JS (fixed, not absolute, so they don't
		// scroll away with the panel's content). Drag the left edge to change width,
		// the top edge to change height — the two edges that make sense for a panel
		// docked bottom-right.
		b.append( "<div id=\"parsleyResizeL\" style=\"position:fixed;width:7px;cursor:ew-resize;z-index:2147483647\"></div>" );
		b.append( "<div id=\"parsleyResizeT\" style=\"position:fixed;height:7px;cursor:ns-resize;z-index:2147483647\"></div>" );

		// The whole panel is a collapsed <details> so it doesn't overlay page content
		// until you ask for it — it sits as just the header bar bottom-right, click to
		// expand the tree.
		b.append( "<details id=\"parsleyDetails\" style=\"margin:0\">" );

		// --- header (the <summary> — always visible; click toggles, drag moves) ---
		b.append( "<summary id=\"parsleyHeader\" style=\"" )
				.append( "cursor:grab;list-style:none;user-select:none;" )
				.append( "padding:10px 14px;font:600 13px/1.2 system-ui,sans-serif;" )
				.append( "display:flex;justify-content:space-between;align-items:center;" )
				.append( "position:sticky;top:0;background:rgba(20,22,28,0.98)\">" )
				.append( "<span>" ).append( ParsleyConstants.HERB ).append( " Parsley render tree <span style=\"color:#565b66;font-weight:400\">⠿ drag</span></span>" )
				.append( "<span style=\"display:flex;align-items:center;gap:12px\">" )
				// Inspect-mode toggle: flips the page into a devtools-style picker — hover
				// highlights the element, click opens its template in the IDE. onmousedown
				// stops the header's drag/toggle from also firing.
				.append( "<button id=\"parsleyInspectBtn\" onmousedown=\"event.stopPropagation()\" onclick=\"event.preventDefault();event.stopPropagation();window.parsleyToggleInspect()\" " )
				.append( "style=\"font:inherit;cursor:pointer;border:1px solid #3a3f4b;background:#272b34;color:#9ecbff;border-radius:5px;padding:2px 8px\">⊹ inspect</button>" )
				.append( "<span style=\"color:#9aa0aa;font-weight:400\">" ).append( formatNanos( total ) ).append( "</span>" )
				.append( "</span>" )
				.append( "</summary>" );

		b.append( "<div style=\"border-top:1px solid #3a3f4b\">" );

		// --- binding summary ---
		b.append( "<div style=\"padding:8px 14px;color:#9aa0aa;border-bottom:1px solid #2a2e38\">" )
				.append( "bindings: " )
				.append( "<span style=\"color:#8fd3ff\">" ).append( result.bindingPullCount() ).append( " pulls</span> " )
				.append( formatNanos( result.bindingPullNanos() ) )
				.append( " &middot; " )
				.append( "<span style=\"color:#ffd28f\">" ).append( result.bindingPushCount() ).append( " pushes</span> " )
				.append( formatNanos( result.bindingPushNanos() ) )
				.append( "</div>" );

		// --- column headers (so the aligned metric columns are legible) ---
		b.append( "<div style=\"display:flex;gap:8px;padding:4px 8px;color:#565b66;font-size:11px;border-bottom:1px solid #2a2e38\">" )
				.append( "<span style=\"flex:1 1 auto\">element <span style=\"color:#454a55\">(times in µs)</span></span>" )
				.append( metricHeader( "time", COL_TIME_PX ) )
				.append( metricHeader( "%", COL_PCT_PX ) )
				.append( metricHeader( "self", COL_SELF_PX ) )
				.append( metricHeader( "bind", COL_BIND_PX ) )
				.append( metricHeader( "db", COL_DB_PX ) )
				.append( "</div>" );

		// Build the self-time distribution so the "self" column can be colored by
		// percentile rank (median = green↔red boundary) — see SelfTimeScale.
		final SelfTimeScale selfScale = SelfTimeScale.of( result.root() );

		// --- tree ---
		b.append( "<div style=\"padding:6px 6px 10px\">" );
		for( final ParsleyRenderProfiler.TreeNode child : result.root().childrenByHeat() ) {
			appendNode( b, child, total, 0, appName, selfScale );
		}
		b.append( "</div>" );

		b.append( "</div>" ); // body
		b.append( "</details>" );
		b.append( "</aside>" );
		return b.toString();
	}

	/**
	 * Emits {@code window.parsleyOpenUrls = { id: "url", … }} mapping each timed
	 * position's marker id to its IDE-open URL, for inspect-mode click resolution.
	 * Walks the whole tree (not just the hot path) so any clickable element resolves.
	 */
	private static void appendOpenUrlMap( final StringBuilder b, final ParsleyRenderProfiler.TreeNode root, final String appName ) {
		final StringBuilder map = new StringBuilder();
		collectOpenUrls( map, root, appName );
		b.append( "<script>window.parsleyOpenUrls={" ).append( map ).append( "};</script>" );
	}

	private static void collectOpenUrls( final StringBuilder map, final ParsleyRenderProfiler.TreeNode node, final String appName ) {
		if( node.id() >= 0 ) {
			final String url = ParsleyDevServerLinks.openComponentURL( appName, node.componentName(), node.line(), node.offset(), node.length() );
			if( url != null ) {
				if( map.length() > 0 ) {
					map.append( ',' );
				}
				map.append( node.id() ).append( ":'" ).append( escapeAttr( url ) ).append( '\'' );
			}
		}
		for( final ParsleyRenderProfiler.TreeNode child : node.children() ) {
			collectOpenUrls( map, child, appName );
		}
	}

	// Per-column widths, sized to each column's actual content rather than one shared
	// width — keeps the metrics block as narrow as possible so the label keeps room
	// even deep in the tree. time can be the biggest (page total, grouped µs); % is
	// never wider than "100%"; self/bind are usually smaller than time.
	private static final int COL_TIME_PX = 76;
	private static final int COL_PCT_PX = 40;
	private static final int COL_SELF_PX = 72;
	private static final int COL_BIND_PX = 68;
	private static final int COL_DB_PX = 84;

	/** Fixed-width right-aligned column-header cell, matching the metric cells. */
	private static String metricHeader( final String label, final int widthPx ) {
		return "<span style=\"flex:0 0 " + widthPx + "px;text-align:right\">" + label + "</span>";
	}

	/** Fixed-width, right-aligned metric cell (empty string renders an empty column). */
	private static String metricCell( final String value, final String color, final int widthPx ) {
		return "<span style=\"flex:0 0 " + widthPx + "px;text-align:right;white-space:nowrap;color:" + color + "\">" + value + "</span>";
	}

	/** Subtrees whose inclusive time is below this fraction of the page start collapsed. */
	private static final double COLLAPSE_BELOW_FRACTION = 0.01;

	/**
	 * Colors a self-time value by its <em>absolute magnitude</em>, log-scaled, so the
	 * color answers "is this costing real time?" rather than "is this in the slow
	 * half?". Two anchors define the ramp:
	 *
	 * <ul>
	 *   <li><b>Floor (~1ms)</b> — anything at or below stays cold (green). Sub-millisecond
	 *       render work is never the thing worth flagging, so nanosecond/microsecond
	 *       elements don't get false-alarm colors (the flaw of a percentile scale,
	 *       which forces half the rows warm by construction).</li>
	 *   <li><b>Ceiling</b> — the page's largest self-time, clamped to at least
	 *       {@link #MIN_CEILING_NANOS} so a page where nothing is slow doesn't paint
	 *       its modest max bright red.</li>
	 * </ul>
	 *
	 * <p>Between floor and ceiling the ramp is logarithmic (each ~10× step advances
	 * the color evenly), green → yellow → orange → red.
	 */
	private static final class SelfTimeScale {

		/** At/below this self-time, always cold — sub-ms work isn't worth flagging. */
		private static final long FLOOR_NANOS = 1_000_000L; // 1ms

		/** The warm end is never anchored below this, so fast pages stay calm. */
		private static final long MIN_CEILING_NANOS = 50_000_000L; // 50ms

		private final double logFloor;
		private final double logCeiling;

		private SelfTimeScale( final long ceilingNanos ) {
			this.logFloor = Math.log( FLOOR_NANOS );
			this.logCeiling = Math.log( Math.max( ceilingNanos, MIN_CEILING_NANOS ) );
		}

		static SelfTimeScale of( final ParsleyRenderProfiler.TreeNode root ) {
			return new SelfTimeScale( maxSelf( root ) );
		}

		private static long maxSelf( final ParsleyRenderProfiler.TreeNode node ) {
			long max = node.id() >= 0 ? node.selfNanos() : 0;
			for( final ParsleyRenderProfiler.TreeNode child : node.children() ) {
				max = Math.max( max, maxSelf( child ) );
			}
			return max;
		}

		/**
		 * @return a CSS color for the given self-time on a log magnitude scale: cold
		 *         (green) at/below the ~1ms floor, ramping through yellow/orange to red
		 *         as it approaches the page's max self-time.
		 */
		String colorFor( final long selfNanos ) {
			if( selfNanos <= FLOOR_NANOS ) {
				// Cold: muted green. Genuinely cheap — not worth the eye's attention.
				return "hsl(140,35%,62%)";
			}

			// Position on the log ramp between floor and ceiling, clamped to [0,1].
			double t = (Math.log( selfNanos ) - logFloor) / (logCeiling - logFloor);
			t = Math.max( 0, Math.min( 1, t ) );

			// Hue 120°(green) → 0°(red) through yellow/orange; saturation/lightness
			// rise a touch with cost so hot rows read as more vivid.
			final int hue = (int)Math.round( 120 - 120 * t );
			final int sat = (int)Math.round( 55 + 30 * t ); // 55%→85%
			final int light = (int)Math.round( 62 - 6 * t ); // 62%→56%
			return "hsl(" + hue + "," + sat + "%," + light + "%)";
		}
	}

	/**
	 * Renders one tree node and its children recursively. Nodes with children are
	 * collapsible {@code <details>}; "hot" subtrees (≥1% of total) start open so the
	 * expensive path is visible at a glance, while the cold long tail starts collapsed
	 * (expandable on demand) to keep the panel scannable.
	 */
	private static void appendNode( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final int depth, final String appName, final SelfTimeScale selfScale ) {

		final boolean hasChildren = !node.children().isEmpty();
		final double fractionOfTotal = total == 0 ? 0 : (double)node.inclusiveNanos() / total;
		final int barPct = (int)Math.round( fractionOfTotal * 100 );
		final int indentPx = 10 + depth * 14;

		if( hasChildren ) {
			final boolean startOpen = fractionOfTotal >= COLLAPSE_BELOW_FRACTION;
			b.append( "<details" ).append( startOpen ? " open" : "" ).append( " style=\"margin:0\">" );
			b.append( "<summary style=\"cursor:pointer;list-style:none\">" );
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, true, appName, selfScale );
			b.append( "</summary>" );
			// SQL drill-in sits outside the row's overflow:hidden container, before children.
			appendSqlPanel( b, node, indentPx );
			for( final ParsleyRenderProfiler.TreeNode child : node.childrenByHeat() ) {
				appendNode( b, child, total, depth + 1, appName, selfScale );
			}
			b.append( "</details>" );
		}
		else {
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, false, appName, selfScale );
			appendSqlPanel( b, node, indentPx );
		}
	}

	/**
	 * Emits the hidden SQL drill-in panel for a row, if it captured any SQL. Placed
	 * as a sibling <em>after</em> the row (not inside it, whose {@code overflow:hidden}
	 * would clip it), toggled by clicking the row's db cell.
	 *
	 * <p>Each distinct statement is shown with its own timing — total, execution count,
	 * and slowest single run — <b>slowest-total first</b>, so when a row ran several
	 * queries you can see <em>which one</em> ate the time rather than only the row's
	 * sum. That's the difference between "ran the same query 240× for 4ms" (N+1) and
	 * "ran one query that took 168ms".
	 */
	private static void appendSqlPanel( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final int indentPx ) {
		final java.util.List<ParsleyRenderProfiler.SqlStat> stats = node.sqlStats();
		if( stats.isEmpty() ) {
			return;
		}
		b.append( "<div id=\"parsleySql" ).append( node.id() ).append( "\" " )
				.append( "style=\"display:none;margin:2px 0 4px " ).append( indentPx + 16 ).append( "px;" )
				.append( "padding:6px 8px;background:#0c0e12;border-left:2px solid #d98fc0;border-radius:3px;" )
				.append( "font-family:ui-monospace,Menlo,monospace;font-size:11px;line-height:1.5;color:#c8ccd4;" )
				.append( "white-space:pre-wrap;word-break:break-word;max-height:280px;overflow:auto\">" );

		// Header: distinct-statement count vs total queries, so an N+1 (many queries,
		// one distinct) is obvious before reading the statements.
		if( node.queryCount() > stats.size() ) {
			b.append( "<div style=\"color:#6b7280;margin-bottom:6px\">" )
					.append( node.queryCount() ).append( " queries · " ).append( stats.size() ).append( " distinct statement" )
					.append( stats.size() == 1 ? "" : "s" ).append( "</div>" );
		}

		for( final ParsleyRenderProfiler.SqlStat stat : stats ) {
			// Per-statement timing line: total, ×count (only when repeated), and the
			// slowest single run (only when it differs from total, i.e. count > 1).
			b.append( "<div style=\"margin:6px 0 2px\">" );
			b.append( "<span style=\"color:#ff8ad8\">" ).append( formatMicros( stat.totalNanos() ) ).append( "</span>" );
			if( stat.count() > 1 ) {
				b.append( "<span style=\"color:#6b7280\"> · " ).append( stat.count() ).append( "q · max " )
						.append( formatMicros( stat.maxNanos() ) ).append( "</span>" );
			}
			b.append( "</div>" );
			b.append( "<div style=\"margin:0 0 2px;color:#c8ccd4\">" ).append( escape( stat.sql() ) ).append( "</div>" );
		}
		b.append( "</div>" );
	}

	private static void appendRowInner( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final double fractionOfTotal, final int barPct, final int indentPx, final boolean hasChildren, final String appName, final SelfTimeScale selfScale ) {

		// Hovering the row highlights every occurrence of this element in the page.
		// Leaf rows also reveal (scroll-to) on click; rows with children reserve
		// click for the <summary> expand/collapse, so we don't fight the disclosure.
		b.append( "<div onmouseenter=\"parsleyHighlight(" ).append( node.id() ).append( ")\" " )
				.append( "onmouseleave=\"parsleyClear()\" " );
		if( !hasChildren ) {
			b.append( "onclick=\"return parsleyReveal(" ).append( node.id() ).append( ")\" " );
		}
		b.append( "style=\"position:relative;padding:4px 8px 4px " ).append( indentPx ).append( "px;" )
				.append( "border-radius:5px;margin:1px 0;overflow:hidden;cursor:" ).append( hasChildren ? "pointer" : "crosshair" ).append( "\">" );

		// heat bar (inclusive time), behind the text
		b.append( "<div style=\"position:absolute;inset:0;width:" ).append( barPct ).append( "%;" )
				.append( "background:" ).append( heatColor( fractionOfTotal ) ).append( ";opacity:0.28\"></div>" );

		b.append( "<div style=\"position:relative;display:flex;align-items:baseline;gap:8px\">" );

		// left: disclosure caret + label (click-to-open link) + line + count + phase.
		// flex:1 takes the slack so the metric columns on the right always align.
		// A title= holds the full label + bindings so truncated rows are still readable on hover.
		final String fullTitle = node.label()
				+ (node.line() > 0 ? " :" + node.line() : "")
				+ (node.bindingsSummary() != null && !node.bindingsSummary().isEmpty() ? "  " + node.bindingsSummary() : "");
		b.append( "<span title=\"" ).append( escapeAttr( fullTitle ) ).append( "\" " )
				.append( "style=\"flex:1 1 auto;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis\">" );
		b.append( "<span style=\"color:#565b66\">" ).append( hasChildren ? "&#9662; " : "&nbsp;&nbsp;&nbsp;" ).append( "</span>" );

		// The label opens the component at this element's line in the IDE, if we can
		// build a dev-server URL for it. Otherwise it's plain text.
		final String openURL = ParsleyDevServerLinks.openComponentURL( appName, node.componentName(), node.line(), node.offset(), node.length() );
		if( openURL != null ) {
			b.append( "<a href=\"#\" onclick=\"return parsleyOpen('" ).append( escapeAttr( openURL ) ).append( "')\" " )
					.append( "title=\"Open " ).append( escapeAttr( node.componentName() ) ).append( " at line " ).append( node.line() ).append( " in IDE\" " )
					.append( "style=\"color:#9ecbff;text-decoration:none\">" )
					.append( escape( node.label() ) ).append( "</a>" );
		}
		else {
			b.append( "<span style=\"color:#c8ccd4\">" ).append( escape( node.label() ) ).append( "</span>" );
		}

		if( node.line() > 0 ) {
			b.append( "<span style=\"color:#6b7280\"> :" ).append( node.line() ).append( "</span>" );
		}
		// Orientation hint: the element's bindings (e.g. value="$resultsString"),
		// dimmed and truncated so a row reads as more than a bare element name.
		final String bindings = node.bindingsSummary();
		if( bindings != null && !bindings.isEmpty() ) {
			b.append( "<span style=\"color:#7fae7f\"> " ).append( escape( truncate( bindings, 60 ) ) ).append( "</span>" );
		}
		if( node.count() > 1 ) {
			b.append( "<span style=\"color:#6b7280\"> &times;" ).append( node.count() ).append( "</span>" );
		}
		if( node.phase() != ParsleyRenderProfiler.Phase.APPEND ) {
			b.append( "<span style=\"color:#565b66\"> " ).append( node.phase().label() ).append( "</span>" );
		}
		b.append( "</span>" );

		// right: five fixed-width, right-aligned metric columns that line up down the
		// tree regardless of label width/indent — time | % | self | bind | db.
		// Metric values are whole microseconds, no unit (see formatMicros) — a single
		// fixed unit keeps the column's digits monotonic with cost.
		b.append( metricCell( formatMicros( node.inclusiveNanos() ), "#e6e6e6", COL_TIME_PX ) );
		b.append( metricCell( String.format( "%.0f%%", fractionOfTotal * 100 ), "#6b7280", COL_PCT_PX ) );
		// Always show self when there's any (it's ownWork + bind, so it contextualizes
		// the bind column on every row — hiding it for leaves made bind look like it
		// exceeded a blank self). Colored by log magnitude: cold below ~1ms, ramping
		// to red toward the page max.
		final boolean showSelf = node.selfNanos() > 0;
		b.append( metricCell( showSelf ? formatMicros( node.selfNanos() ) : "", selfScale.colorFor( node.selfNanos() ), COL_SELF_PX ) );
		b.append( metricCell( node.bindingNanos() > 0 ? formatMicros( node.bindingNanos() ) : "", "#8fd3ff", COL_BIND_PX ) );

		// db: "<time> <count>q" — time FIRST (the combined DB wall-clock for this row,
		// consistent with the other metric columns), then the query count as a suffixed
		// "q". The count is the N+1 signal (a repetition row that ran 240 selects); it's
		// a suffix, not an "N&times;" prefix, so the value never reads as a multiplier
		// (2&times;700 looked like "1400"; "700 2q" reads as "700us total, 2 queries").
		// Warm magenta so DB cost stands out from the cool bind column; brighter when
		// the query count is high, since count is what usually indicates the problem.
		// When we captured the SQL, the cell is a button that toggles a drill-in panel
		// beneath the row (stopPropagation so it doesn't also trigger row reveal).
		final boolean hasSql = !node.sqlStats().isEmpty();
		if( node.queryCount() > 0 ) {
			final String dbValue = formatMicros( node.ioNanos() ) + " <span style=\"opacity:0.65\">" + node.queryCount() + "q</span>";
			// N+1 hint: when one distinct statement ran more than once at this position,
			// the db cell goes red — the single most actionable thing the column flags,
			// visible without opening the drill-in. Otherwise the usual magenta, brighter
			// for higher query counts.
			final boolean nPlusOne = node.hasRepeatedStatement();
			final String dbColor = nPlusOne ? "#ff5b5b" : (node.queryCount() >= 10 ? "#ff8ad8" : "#d98fc0");
			final String dbTitle = nPlusOne ? "Possible N+1: the same query ran more than once here — click to see it" : "Show SQL";
			if( hasSql ) {
				b.append( "<span onclick=\"return parsleyToggleSql(event," ).append( node.id() ).append( ")\" " )
						.append( "title=\"" ).append( dbTitle ).append( "\" " )
						.append( "style=\"flex:0 0 " ).append( COL_DB_PX ).append( "px;text-align:right;white-space:nowrap;" )
						.append( "cursor:pointer;text-decoration:underline;text-decoration-style:dotted;" )
						.append( nPlusOne ? "font-weight:600;" : "" ).append( "color:" ).append( dbColor ).append( "\">" )
						.append( dbValue ).append( "</span>" );
			}
			else {
				b.append( metricCell( dbValue, dbColor, COL_DB_PX ) );
			}
		}
		else {
			b.append( metricCell( "", "#d98fc0", COL_DB_PX ) );
		}

		b.append( "</div>" );
		b.append( "</div>" );
	}

	/**
	 * The overlay's inline script: a fire-and-forget IDE opener plus the
	 * "highlight this element in the page" machinery. The latter scans the document
	 * for {@code <!--p:N-->…<!--/p:N-->} comment-marker pairs (emitted
	 * around each profiled element's rendered output), and on row hover draws a
	 * highlight box over <em>every</em> occurrence of that id; on click it scrolls
	 * the first occurrence into view. Self-contained, no external deps.
	 */
	private static String overlayScript() {
		return """
				<script>
				(function(){
				  // Index comment markers: id -> array of {start, end} comment node pairs.
				  var idx = null;
				  function buildIndex(){
				    idx = {};
				    var stack = {};
				    var it = document.createNodeIterator(document.body, NodeFilter.SHOW_COMMENT, null, false);
				    var n;
				    while((n = it.nextNode())){
				      var v = n.nodeValue;
				      var m = /^p:(\\d+)$/.exec(v);
				      if(m){ (stack[m[1]] = stack[m[1]] || []).push(n); continue; }
				      var c = /^\\/p:(\\d+)$/.exec(v);
				      if(c){
				        var open = (stack[c[1]] || []).pop();
				        if(open){ (idx[c[1]] = idx[c[1]] || []).push({s:open, e:n}); }
				      }
				    }
				  }
				  // Bounding rect of all nodes between two comment markers.
				  function rangeRect(pair){
				    try{
				      var r = document.createRange();
				      r.setStartAfter(pair.s); r.setEndBefore(pair.e);
				      var rect = r.getBoundingClientRect();
				      if(rect.width===0 && rect.height===0) return null;
				      return rect;
				    }catch(e){ return null; }
				  }
				  var layer = null;
				  function clearHighlight(){ if(layer){ layer.innerHTML=''; } }
				  function ensureLayer(){
				    if(!layer){
				      layer = document.createElement('div');
				      layer.style.cssText='position:fixed;inset:0;pointer-events:none;z-index:2147483646';
				      document.body.appendChild(layer);
				    }
				    return layer;
				  }
				  window.parsleyHighlight = function(id){
				    if(idx===null) buildIndex();
				    clearHighlight(); ensureLayer();
				    var pairs = idx[id] || [], first=null;
				    for(var i=0;i<pairs.length;i++){
				      var rect = rangeRect(pairs[i]); if(!rect) continue;
				      if(!first) first=rect;
				      var box = document.createElement('div');
				      box.style.cssText='position:fixed;pointer-events:none;border:2px solid #ff5c8a;'
				        +'background:rgba(255,92,138,0.18);border-radius:3px;'
				        +'left:'+rect.left+'px;top:'+rect.top+'px;width:'+rect.width+'px;height:'+rect.height+'px;'
				        +'transition:opacity .1s';
				      layer.appendChild(box);
				    }
				    return first;
				  };
				  window.parsleyClear = clearHighlight;
				  window.parsleyReveal = function(id){
				    var first = window.parsleyHighlight(id);
				    if(first){
				      var y = first.top + window.pageYOffset - 80;
				      window.scrollTo({top:y, behavior:'smooth'});
				    }
				    return false;
				  };
				  // Toggle a row's SQL drill-in panel. The panel may live inside a <details>
				  // (parent rows render as <details>/<summary>): its visibility is then
				  // governed by BOTH our display flag AND the details' open state, so just
				  // flipping display does nothing when the details is collapsed. We handle
				  // both: open every ancestor <details> when showing, and suppress the
				  // summary's own toggle so clicking the db cell doesn't collapse the row
				  // out from under the panel (preventDefault) or trigger row reveal
				  // (stopPropagation).
				  window.parsleyToggleSql = function(e, id){
				    if(e){ e.preventDefault(); e.stopPropagation(); }
				    var p = document.getElementById('parsleySql'+id);
				    if(!p){ return false; }
				    var show = (p.style.display === 'none' || p.style.display === '');
				    p.style.display = show ? 'block' : 'none';
				    if(show){
				      // Make sure no collapsed ancestor <details> is hiding the panel.
				      var d = p.closest && p.closest('details');
				      while(d){ d.open = true; d = d.parentElement && d.parentElement.closest('details'); }
				    }
				    return false;
				  };
				  // Markers reflect a single rendered layout; rebuild the index if the page
				  // resizes/reflows so boxes stay aligned.
				  window.addEventListener('resize', function(){ idx=null; clearHighlight(); });
				  function parsleyOpen(u){ try{ new Image().src=u; }catch(e){} return false; }
				  window.parsleyOpen = parsleyOpen;

				  // Drag-to-move: the header is the handle. We distinguish a click (toggle
				  // the panel's <details>) from a drag (reposition) by movement distance —
				  // if the pointer moved more than a few px, it's a drag and we suppress the
				  // toggle. On first drag we switch the panel from its bottom/right anchor to
				  // left/top so it follows the cursor.
				  function initDrag(){
				    var header = document.getElementById('parsleyHeader');
				    var panel = document.getElementById('parsleyPanel');
				    if(!header || !panel) return;
				    var dragging=false, moved=false, sx=0, sy=0, ox=0, oy=0;
				    header.addEventListener('mousedown', function(e){
				      dragging=true; moved=false; sx=e.clientX; sy=e.clientY;
				      var r=panel.getBoundingClientRect(); ox=r.left; oy=r.top;
				      header.style.cursor='grabbing';
				    });
				    document.addEventListener('mousemove', function(e){
				      if(!dragging) return;
				      var dx=e.clientX-sx, dy=e.clientY-sy;
				      if(!moved && Math.abs(dx)+Math.abs(dy) > 4){
				        moved=true;
				        // pin to left/top, drop the bottom/right anchor, so it tracks the cursor
				        panel.style.right='auto'; panel.style.bottom='auto';
				      }
				      if(moved){
				        panel.style.left=(ox+dx)+'px'; panel.style.top=(oy+dy)+'px';
				        if(window.parsleySyncHandles) window.parsleySyncHandles();
				        e.preventDefault();
				      }
				    });
				    document.addEventListener('mouseup', function(e){
				      if(!dragging) return;
				      dragging=false; header.style.cursor='grab';
				      // If this was a drag, swallow the click so <details> doesn't toggle.
				      if(moved){ e.preventDefault(); e.stopPropagation(); savePos(panel); }
				    }, true);
				    // Belt-and-suspenders: cancel the toggle on the click that follows a drag.
				    header.addEventListener('click', function(e){ if(moved){ e.preventDefault(); moved=false; } }, true);
				  }

				  // ---- Persistence: remember the panel's open/closed state, position (if
				  // dragged) and size (if resized) across page navigation, so it behaves like
				  // a tool you left where you put it rather than resetting every load. Uses
				  // localStorage (per-origin, not sent to the server); best-effort.
				  var POS_KEY='parsley.panel.pos', OPEN_KEY='parsley.panel.open', SIZE_KEY='parsley.panel.size';
				  function savePos(panel){
				    try{ var r=panel.getBoundingClientRect(); localStorage.setItem(POS_KEY, JSON.stringify({left:r.left, top:r.top})); }catch(e){}
				  }
				  function saveSize(panel){
				    try{ localStorage.setItem(SIZE_KEY, JSON.stringify({w:panel.offsetWidth, h:panel.offsetHeight})); }catch(e){}
				  }

				  // Keep the fixed-position resize handles aligned to the panel's edges.
				  function syncHandles(){
				    var panel=document.getElementById('parsleyPanel');
				    var hl=document.getElementById('parsleyResizeL');
				    var ht=document.getElementById('parsleyResizeT');
				    if(!panel||!hl||!ht) return;
				    var r=panel.getBoundingClientRect();
				    hl.style.left=(r.left-3)+'px'; hl.style.top=r.top+'px'; hl.style.height=r.height+'px';
				    ht.style.left=r.left+'px'; ht.style.top=(r.top-3)+'px'; ht.style.width=r.width+'px';
				  }
				  window.parsleySyncHandles=syncHandles;

				  function initResize(){
				    var panel=document.getElementById('parsleyPanel');
				    var hl=document.getElementById('parsleyResizeL');
				    var ht=document.getElementById('parsleyResizeT');
				    if(!panel||!hl||!ht) return;

				    // Switch the panel to explicit left/top/width/height anchoring so resizing
				    // from the left/top edges grows the panel the intuitive direction.
				    function pin(){
				      var r=panel.getBoundingClientRect();
				      panel.style.right='auto'; panel.style.bottom='auto'; panel.style.maxHeight='none';
				      panel.style.left=r.left+'px'; panel.style.top=r.top+'px';
				      panel.style.width=r.width+'px'; panel.style.height=r.height+'px';
				    }
				    function startResize(axis){
				      return function(e){
				        e.preventDefault(); e.stopPropagation();
				        pin();
				        var r=panel.getBoundingClientRect();
				        var sx=e.clientX, sy=e.clientY, sw=r.width, sh=r.height, sl=r.left, st=r.top;
				        function move(ev){
				          if(axis==='x'){ // left edge: width grows as the edge moves left
				            var w=Math.max(280, sw+(sx-ev.clientX));
				            panel.style.width=w+'px'; panel.style.left=(sl+(sw-w))+'px';
				          } else { // top edge: height grows as the edge moves up
				            var h=Math.max(120, sh+(sy-ev.clientY));
				            panel.style.height=h+'px'; panel.style.top=(st+(sh-h))+'px';
				          }
				          syncHandles();
				        }
				        function up(){
				          document.removeEventListener('mousemove', move, true);
				          document.removeEventListener('mouseup', up, true);
				          savePos(panel); saveSize(panel);
				        }
				        document.addEventListener('mousemove', move, true);
				        document.addEventListener('mouseup', up, true);
				      };
				    }
				    hl.addEventListener('mousedown', startResize('x'));
				    ht.addEventListener('mousedown', startResize('y'));
				  }

				  function restoreState(){
				    var panel=document.getElementById('parsleyPanel');
				    var details=document.getElementById('parsleyDetails');
				    if(!panel||!details) return;
				    try{
				      var willBeOpen=localStorage.getItem(OPEN_KEY)==='1';
				      var size=JSON.parse(localStorage.getItem(SIZE_KEY)||'null');
				      if(size && typeof size.w==='number'){
				        panel.style.maxHeight='none';
				        panel.style.width=Math.min(size.w, window.innerWidth-20)+'px';
				        var h=Math.min(size.h, window.innerHeight-20)+'px';
				        // Only pin the saved height when restoring open; a collapsed panel must
				        // shrink to its title bar, so stash the height for the first expand instead
				        // of leaving an empty sized box.
				        if(willBeOpen){ panel.style.height=h; } else { panel.dataset.collapsedHeight=h; }
				      }
				      var pos=JSON.parse(localStorage.getItem(POS_KEY)||'null');
				      if(pos && typeof pos.left==='number'){
				        // Clamp into the viewport so a panel saved off-screen (smaller window
				        // now) is still reachable.
				        var left=Math.max(0, Math.min(pos.left, window.innerWidth-60));
				        var top=Math.max(0, Math.min(pos.top, window.innerHeight-40));
				        panel.style.right='auto'; panel.style.bottom='auto';
				        panel.style.left=left+'px'; panel.style.top=top+'px';
				      }
				      if(willBeOpen) details.open=true;
				    }catch(e){}
				    // Save open/closed whenever it changes, and realign the handles (the panel
				    // grows/shrinks when expanded/collapsed).
				    //
				    // Resizing pins an explicit pixel height on the panel. A collapsed <details>
				    // hides its body, but that pinned height would keep the panel box full-size,
				    // leaving an empty sized box instead of collapsing to the title bar. So on
				    // collapse we release the height (stashing it) and let the panel shrink to fit
				    // the summary; on expand we restore the stashed height.
				    details.addEventListener('toggle', function(){
				      try{ localStorage.setItem(OPEN_KEY, details.open?'1':'0'); }catch(e){}
				      if(details.open){
				        if(panel.dataset.collapsedHeight){ panel.style.height=panel.dataset.collapsedHeight; delete panel.dataset.collapsedHeight; }
				      } else {
				        if(panel.style.height){ panel.dataset.collapsedHeight=panel.style.height; panel.style.height='auto'; }
				      }
				      syncHandles();
				    });
				    syncHandles();
				    window.addEventListener('scroll', syncHandles, true);
				    window.addEventListener('resize', syncHandles);
				  }

				  function initPanel(){ initDrag(); initResize(); restoreState(); }
				  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', initPanel); else initPanel();

				  // ---- Inspect mode: devtools-style picker that opens the element under
				  // the cursor in the IDE. Hover highlights the innermost marked element;
				  // click opens its template. Only active while toggled on, so it never
				  // interferes with normal page use.
				  var inspecting=false, hoverBox=null, hoverId=-1;
				  function ensureHoverBox(){
				    if(!hoverBox){
				      hoverBox=document.createElement('div');
				      hoverBox.style.cssText='position:fixed;pointer-events:none;z-index:2147483646;'
				        +'border:2px solid #9ecbff;background:rgba(158,203,255,0.15);border-radius:3px;display:none';
				      document.body.appendChild(hoverBox);
				    }
				    return hoverBox;
				  }
				  // Find the innermost marked element under a screen point (cursor x/y). For each
				  // marker we walk its occurrence ranges and test the *rect that actually contains
				  // the cursor* (a marked region can render as several line boxes, only one of which
				  // is under the cursor). Among all markers whose region is under the cursor, the one
				  // with the smallest containing rect wins — the most specific element, so a big
				  // container never steals the hit from a small child nested inside it.
				  //
				  // Returns {id, rect} for the winner (rect = the exact box under the cursor, so the
				  // highlight matches what was hit), or null when nothing marked is under the point.
				  function innermostHitAt(x, y){
				    if(idx===null) buildIndex();
				    var best=null, bestArea=Infinity, bestId=-1;
				    for(var id in idx){
				      if(!window.parsleyOpenUrls || !(id in window.parsleyOpenUrls)) continue;
				      var nid=+id;
				      var pairs=idx[id];
				      for(var i=0;i<pairs.length;i++){
				        var r=document.createRange();
				        r.setStartAfter(pairs[i].s); r.setEndBefore(pairs[i].e);
				        // A range can span multiple line boxes; getClientRects() gives each one.
				        // Use whichever box the cursor is actually inside, not the union bounds.
				        var rects=r.getClientRects();
				        for(var j=0;j<rects.length;j++){
				          var rc=rects[j];
				          if(x>=rc.left && x<=rc.right && y>=rc.top && y<=rc.bottom){
				            var area=rc.width*rc.height;
				            if(area<=0) continue;
				            // Smallest box wins. On an exact-area tie (a child that exactly fills its
				            // parent), prefer the higher marker id — markers open in source order, so
				            // a nested child always has a larger id than its container. Without this,
				            // for..in's ascending-numeric order would let the container win the tie.
				            if(area<bestArea || (area===bestArea && nid>bestId)){ bestArea=area; bestId=nid; best={id:id, rect:rc}; }
				          }
				        }
				      }
				    }
				    return best;
				  }
				  function onInspectMove(e){
				    if(!inspecting) return;
				    var hit=innermostHitAt(e.clientX, e.clientY);
				    hoverId = hit ? hit.id : -1;
				    var box=ensureHoverBox();
				    if(!hit){ box.style.display='none'; return; }
				    var rc=hit.rect;
				    box.style.display='block'; box.style.left=rc.left+'px'; box.style.top=rc.top+'px'; box.style.width=rc.width+'px'; box.style.height=rc.height+'px';
				  }
				  function onInspectClick(e){
				    if(!inspecting) return;
				    // never inside our own panel
				    var panel=document.getElementById('parsleyPanel'); if(panel && panel.contains(e.target)) return;
				    // Open exactly what the highlight showed. hoverId is set on the last move and is
				    // what the user sees outlined; resolving the click independently risks a tie or
				    // sub-pixel difference picking a different (containing) element than was shown.
				    // Fall back to a fresh point resolve only if there's no current hover (e.g. a
				    // click with no preceding move, like a touch tap).
				    var id = hoverId;
				    if(id===-1){ var hit=innermostHitAt(e.clientX, e.clientY); id = hit ? hit.id : -1; }
				    if(id!==-1 && window.parsleyOpenUrls[id]){
				      // Stop the click from reaching the page: stopImmediatePropagation also blocks
				      // other capture-phase listeners, and preventDefault kills the default action,
				      // so the click can't leak through to navigate the page's own elements.
				      e.preventDefault(); e.stopImmediatePropagation();
				      parsleyOpen(window.parsleyOpenUrls[id]);
				    }
				  }
				  window.parsleyToggleInspect=function(){
				    inspecting=!inspecting;
				    var btn=document.getElementById('parsleyInspectBtn');
				    if(inspecting){
				      idx=null; // rebuild marker index against current layout
				      document.addEventListener('mousemove', onInspectMove, true);
				      document.addEventListener('click', onInspectClick, true);
				      document.body.style.cursor='crosshair';
				      if(btn){ btn.style.background='#9ecbff'; btn.style.color='#11141a'; }
				    } else {
				      document.removeEventListener('mousemove', onInspectMove, true);
				      document.removeEventListener('click', onInspectClick, true);
				      document.body.style.cursor='';
				      if(hoverBox) hoverBox.style.display='none';
				      if(btn){ btn.style.background='#272b34'; btn.style.color='#9ecbff'; }
				    }
				  };
				  // Esc exits inspect mode.
				  document.addEventListener('keydown', function(e){ if(e.key==='Escape' && inspecting) window.parsleyToggleInspect(); });
				})();
				</script>
				""";
	}

	/** @return green→yellow→red for 0..1 heat fraction. */
	private static String heatColor( final double fraction ) {
		final double f = Math.max( 0, Math.min( 1, fraction ) );
		final int hue = (int)Math.round( 120 * (1 - f) );
		return "hsl(" + hue + ",85%,55%)";
	}

	private static String formatNanos( final long nanos ) {
		if( nanos < 1_000 ) {
			return nanos + "ns";
		}
		if( nanos < 1_000_000 ) {
			return String.format( "%.1fµs", nanos / 1_000.0 );
		}
		return String.format( "%.2fms", nanos / 1_000_000.0 );
	}

	/**
	 * Formats a duration as a whole number of microseconds, no unit, no decimals —
	 * the metric-column format. A single fixed unit across the whole column keeps the
	 * digits monotonic with cost (mixed µs/ms made a slower row's number look smaller),
	 * and the rising number is the strongest at-a-glance signal. Thin-space digit
	 * grouping keeps big (slow-row) values readable without re-introducing a unit.
	 */
	private static String formatMicros( final long nanos ) {
		final long micros = Math.round( nanos / 1_000.0 );
		final String digits = Long.toString( micros );

		// Group thousands with a comma so the digit groups read as one cohesive number.
		final StringBuilder out = new StringBuilder();
		final int len = digits.length();
		for( int i = 0; i < len; i++ ) {
			if( i > 0 && (len - i) % 3 == 0 ) {
				out.append( ',' );
			}
			out.append( digits.charAt( i ) );
		}
		return out.toString();
	}

	private static String escape( final String s ) {
		return s == null ? "" : s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
	}

	/** Truncates with an ellipsis so a long bindings list doesn't overflow the row. */
	private static String truncate( final String s, final int max ) {
		return s.length() <= max ? s : s.substring( 0, max - 1 ) + "…";
	}

	/** Escapes for use inside a single-quoted JS string / HTML attribute. */
	private static String escapeAttr( final String s ) {
		return s == null ? "" : s.replace( "&", "&amp;" ).replace( "'", "\\'" ).replace( "\"", "&quot;" );
	}
}
