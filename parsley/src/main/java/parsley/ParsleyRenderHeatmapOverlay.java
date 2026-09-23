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

		// Single left-to-right pass. We track whether we're currently inside a raw-text
		// element (<script>/<style>/<title>) or an authored HTML comment, updating that
		// state as we walk — never re-scanning a prefix. When we reach one of our own
		// position markers, we drop it if we're currently in an unsafe context, else keep
		// it. O(n) over the response, no per-marker windows or allocations.
		final int n = content.length();
		final StringBuilder out = new StringBuilder( n );
		boolean inScript = false, inStyle = false, inTitle = false, inComment = false;
		int i = 0;
		while( i < n ) {
			// One of our markers? Keep or drop based on current context, then skip it whole.
			if( content.charAt( i ) == '<' && (starts( content, i, "<!--p:" ) || starts( content, i, "<!--/p:" )) ) {
				final int end = content.indexOf( "-->", i + 4 );
				final int markerEnd = end == -1 ? n : end + 3;
				if( !(inScript || inStyle || inTitle || inComment) ) {
					out.append( content, i, markerEnd ); // safe context — keep it
				}
				i = markerEnd;
				continue;
			}

			if( inComment ) {
				if( starts( content, i, "-->" ) ) {
					inComment = false;
					out.append( content, i, i + 3 );
					i += 3;
					continue;
				}
			}
			else if( inScript ) {
				if( startsIgnoreCase( content, i, "</script" ) ) {
					inScript = false;
				}
			}
			else if( inStyle ) {
				if( startsIgnoreCase( content, i, "</style" ) ) {
					inStyle = false;
				}
			}
			else if( inTitle ) {
				if( startsIgnoreCase( content, i, "</title" ) ) {
					inTitle = false;
				}
			}
			else if( content.charAt( i ) == '<' ) {
				if( starts( content, i, "<!--" ) ) {
					inComment = true;
				}
				else if( startsIgnoreCase( content, i, "<script" ) ) {
					inScript = true;
				}
				else if( startsIgnoreCase( content, i, "<style" ) ) {
					inStyle = true;
				}
				else if( startsIgnoreCase( content, i, "<title" ) ) {
					inTitle = true;
				}
			}

			out.append( content.charAt( i ) );
			i++;
		}
		return out.toString();
	}

	private static boolean starts( final String s, final int at, final String sub ) {
		return s.startsWith( sub, at );
	}

	private static boolean startsIgnoreCase( final String s, final int at, final String sub ) {
		return s.regionMatches( true, at, sub, 0, sub.length() );
	}

	static String render( final ParsleyRenderProfiler.Result result ) {
		return render( result, null, null );
	}

	static String render( final ParsleyRenderProfiler.Result result, final String appName ) {
		return render( result, appName, null );
	}

	/**
	 * @param sourceMapURL where the overlay fetches the page's source map to locate elements,
	 *        or null when the page carries position markers instead
	 */
	static String render( final ParsleyRenderProfiler.Result result, final String appName, final String sourceMapURL ) {

		final long total = result.totalInclusiveNanos();

		final StringBuilder b = new StringBuilder( 8192 );

		if( sourceMapURL != null ) {
			b.append( "<script>window.parsleySourceMapUrl=\"" ).append( escapeAttr( sourceMapURL ) ).append( "\";</script>" );
		}
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
				.append( "position:sticky;top:0;z-index:2;background:rgba(20,22,28,0.98)\">" )
				.append( "<span>" ).append( ParsleyConstants.HERB ).append( " Parsley render tree <span style=\"color:#565b66;font-weight:400\">⠿ drag</span></span>" )
				.append( "<span style=\"display:flex;align-items:center;gap:12px\">" )
				// Inspect-mode toggle: flips the page into a devtools-style picker — hover
				// highlights the element, click opens its template in the IDE. onmousedown
				// stops the header's drag/toggle from also firing.
				// Sibling ordering toggle: by weight (hottest first) or by appearance order.
				.append( "<button id=\"parsleyOrderBtn\" title=\"Order siblings by weight or by appearance in the page\" onmousedown=\"event.stopPropagation()\" onclick=\"event.preventDefault();event.stopPropagation();window.parsleyToggleOrder()\" " )
				.append( "style=\"font:inherit;cursor:pointer;border:1px solid #3a3f4b;background:#272b34;color:#9aa0aa;border-radius:5px;padding:2px 8px\">⇅ weight</button>" )
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
		appendChildren( b, result.root().childrenByHeat(), total, 0, appName, selfScale );
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
	/**
	 * @return the IDE link for a row: the element's own position — except for a
	 *         {@code <wo:content>}, whose rendered markup lives in the enclosing component's
	 *         template, so it opens that component reference's body instead.
	 */
	private static String openURL( final String appName, final ParsleyRenderProfiler.TreeNode node ) {
		final ParsleyRenderProfiler.TreeNode source = node.contentSource();
		if( source != null ) {
			final int[] span = source.contentSpan();
			return ParsleyDevServerLinks.openComponentURL( appName, source.componentName(), source.line(), span[0], span[1] );
		}
		return ParsleyDevServerLinks.openComponentURL( appName, node.componentName(), node.line(), node.offset(), node.length() );
	}

	private static void appendOpenUrlMap( final StringBuilder b, final ParsleyRenderProfiler.TreeNode root, final String appName ) {
		final StringBuilder map = new StringBuilder();
		collectOpenUrls( map, root, appName );
		b.append( "<script>window.parsleyOpenUrls={" ).append( map ).append( "};</script>" );
	}

	private static void collectOpenUrls( final StringBuilder map, final ParsleyRenderProfiler.TreeNode node, final String appName ) {
		if( node.id() >= 0 ) {
			final String url = openURL( appName, node );
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
	private static void appendNode( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final int heatRank, final long total, final int depth, final String appName, final SelfTimeScale selfScale ) {

		// Wrapper carrying the node's render-order id (monotonic at first render = appearance
		// order, comparable across component boundaries) and its heat rank among siblings, so
		// the tree can be re-sorted client-side either way.
		b.append( "<div data-pn=\"" ).append( node.id() ).append( "\" data-ph=\"" ).append( heatRank ).append( "\">" );

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
			appendChildren( b, node.childrenByHeat(), total, depth + 1, appName, selfScale );
			b.append( "</details>" );
		}
		else {
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, false, appName, selfScale );
			appendSqlPanel( b, node, indentPx );
		}
		b.append( "</div>" );
	}

	/** Emits sibling nodes (heat order) inside a container the client can re-sort. */
	private static void appendChildren( final StringBuilder b, final java.util.List<ParsleyRenderProfiler.TreeNode> children, final long total, final int depth, final String appName, final SelfTimeScale selfScale ) {
		b.append( "<div data-pkids>" );
		for( int i = 0; i < children.size(); i++ ) {
			appendNode( b, children.get( i ), i, total, depth, appName, selfScale );
		}
		b.append( "</div>" );
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

		// PROTOTYPE — which binding(s) on this element triggered the queries. Jump-off point
		// for finding the code: navigate the keypath's getters in your IDE.
		appendBindingOrigins( b, node );

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

	/**
	 * PROTOTYPE — lists the binding(s) whose value-pull triggered this row's queries: each
	 * binding's name, its key path, and how many queries (and how much time) it accounted for.
	 * This names the culprit binding for an N+1; you then navigate the key path's getters in
	 * the IDE to reach the code that queries the DB.
	 */
	private static void appendBindingOrigins( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node ) {
		final java.util.Collection<ParsleyRenderProfiler.BindingStat> origins = node.bindingStats();
		if( origins.isEmpty() ) {
			return;
		}
		b.append( "<div style=\"margin:0 0 8px;padding-bottom:6px;border-bottom:1px solid #1c1f26\">" );
		b.append( "<div style=\"color:#6b7280;margin-bottom:3px\">triggered by</div>" );
		for( final ParsleyRenderProfiler.BindingStat o : origins ) {
			b.append( "<div style=\"margin:1px 0\">" );
			b.append( "<span style=\"color:#8fd9a0\">" ).append( escape( o.bindingName() ) )
					.append( "=\"$" ).append( escape( o.keyPath() ) ).append( "\"</span>" );
			b.append( "<span style=\"color:#6b7280\"> · " ).append( o.count() ).append( "q · " )
					.append( formatMicros( o.totalNanos() ) ).append( "</span>" );
			b.append( "</div>" );
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
		final String openURL = openURL( appName, node );
		final ParsleyRenderProfiler.TreeNode contentSource = node.contentSource();
		if( openURL != null ) {
			final String openTitle = contentSource != null
					? "Open the content this renders — the body of " + contentSource.label() + " in " + contentSource.componentName() + " — in IDE"
					: "Open " + node.componentName() + " at line " + node.line() + " in IDE";
			b.append( "<a href=\"#\" onclick=\"return parsleyOpen('" ).append( escapeAttr( openURL ) ).append( "')\" " )
					.append( "title=\"" ).append( escapeAttr( openTitle ) ).append( "\" " )
					.append( "style=\"color:#9ecbff;text-decoration:none\">" )
					.append( escape( node.label() ) ).append( "</a>" );
		}
		else {
			b.append( "<span style=\"color:#c8ccd4\">" ).append( escape( node.label() ) ).append( "</span>" );
		}

		if( node.line() > 0 ) {
			b.append( "<span style=\"color:#6b7280\"> :" ).append( node.line() ).append( "</span>" );
		}
		// A <wo:content> renders the enclosing component's body, which lives in that
		// component's template — say where, since that's where the rendered markup is.
		if( contentSource != null ) {
			b.append( "<span style=\"color:#6b7280\"> &larr; body of " ).append( escape( contentSource.label() ) )
					.append( " in " ).append( escape( contentSource.componentName() ) );
			if( contentSource.line() > 0 ) {
				b.append( " :" ).append( contentSource.line() );
			}
			b.append( "</span>" );
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
				  // ---- Source map mode (no markers in the page) ------------------------------------
				  // The server records, per element, the character ranges of its output in the
				  // served page (relative to "<body"), and serves them with the page text itself
				  // from window.parsleySourceMapUrl. We walk that text with a small tokenizer IN
				  // STEP with the live DOM — each opening tag steps into the matching element —
				  // and wherever the walk reaches a recorded offset, note the DOM boundary there.
				  // A range then becomes a DOM Range between two boundaries. Where the walk can't
				  // follow the DOM (script-mutated structure) it tracks a "phantom" level and yields
				  // no boundary: such ranges simply don't highlight, rather than highlight wrongly.
				  var SM=null, smLoading=false, smWaiters=[];
				  function ensureMap(cb){
				    if(!window.parsleySourceMapUrl || SM){ if(cb) cb(); return; }
				    if(cb) smWaiters.push(cb);
				    if(smLoading) return;
				    smLoading=true;
				    fetch(window.parsleySourceMapUrl, {credentials:'same-origin'})
				      .then(function(r){ if(!r.ok) throw new Error('HTTP '+r.status); return r.json(); })
				      .then(function(d){
				        var t0=performance.now();
				        SM=buildSourceMap(d);
				        console.log('[parsley] source map: '+SM.mapped+'/'+SM.total+' offsets mapped in '+Math.round(performance.now()-t0)+'ms');
				        var w=smWaiters; smWaiters=[]; for(var i=0;i<w.length;i++) w[i]();
				      })
				      .catch(function(e){ console.warn('[parsley] source map unavailable', e); smLoading=false; smWaiters=[]; });
				  }
				  var VOID={area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1};
				  var RAW={script:1,style:1,textarea:1,title:1,xmp:1,noscript:1,iframe:1,noembed:1,noframes:1};
				  // Opening one of these closes an open <p> (browser implied end tag).
				  var CLOSES_P={address:1,article:1,aside:1,blockquote:1,details:1,div:1,dl:1,fieldset:1,figcaption:1,figure:1,footer:1,form:1,h1:1,h2:1,h3:1,h4:1,h5:1,h6:1,header:1,hr:1,main:1,menu:1,nav:1,ol:1,p:1,pre:1,section:1,table:1,ul:1};
				  function decodedLength(raw){ return raw.replace(/\\r\\n?/g,'\\n').replace(/&(#\\d+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);/g,'x').length; }
				  function buildSourceMap(d){
				    var text=d.text, ranges=d.ranges, all=[];
				    for(var id in ranges){ var rs=ranges[id]; for(var i=0;i<rs.length;i++) all.push(rs[i]); }
				    all.sort(function(a,b){ return a-b; });
				    var offs=[]; for(var i=0;i<all.length;i++) if(i===0 || all[i]!==all[i-1]) offs.push(all[i]);
				    var b=walkText(text, offs), mapped=0;
				    b.forEach(function(v){ if(v) mapped++; });
				    return {ranges:ranges, b:b, mapped:mapped, total:offs.length, lost:b.lost};
				  }
				  function walkText(text, offs){
				    var out=new Map(), k=0, n=text.length, i=0;
				    // Stack of open elements: el = matched live DOM element (null = phantom, lost
				    // sync), last = last DOM child consumed inside it, tag = lowercase tag name.
				    var stack=[{el:document.body, last:null, tag:'body'}];
				    function top(){ return stack[stack.length-1]; }
				    function here(){ var t=top(); return t.el ? {p:t.el, a:t.last} : null; }
				    // Record boundaries for all offsets in [start, end): at start → before this token.
				    function mark(start, end, inside){
				      while(k<offs.length && offs[k]<end){
				        var o=offs[k++];
				        out.set(o, o<=start ? here() : inside ? inside(o) : null);
				      }
				    }
				    function nextChild(t){ return t.last ? t.last.nextSibling : (t.el ? t.el.firstChild : null); }
				    function matchElement(t, name){
				      if(!t.el) return null;
				      var c=nextChild(t), seen=0;
				      for(; c && seen<8; c=c.nextSibling){
				        if(c.nodeType!==1) continue;
				        if(c.tagName.toLowerCase()===name) return c;
				        seen++;
				      }
				      return null;
				    }
				    function popTo(name){
				      for(var j=stack.length-1;j>0;j--){ if(stack[j].tag===name){ stack.length=j; return true; } }
				      return false;
				    }
				    function consumeNode(type){ var t=top(); if(!t.el) return null; var c=nextChild(t); if(c && c.nodeType===type){ t.last=c; return c; } return null; }
				    // A text run [start, end) is one DOM text node. The boundary at its start is
				    // "before the text" — taken before the node is consumed; offsets inside it
				    // are character positions within the node (entities decoded).
				    function textRun(start, end){
				      var before=here(), tn=consumeNode(3);
				      while(k<offs.length && offs[k]<end){
				        var o=offs[k++];
				        out.set(o, o<=start ? before : (tn ? {t:tn, o:decodedLength(text.slice(start,o))} : null));
				      }
				    }
				    var lost=[];
				    // The opening <body ...> tag itself maps onto document.body.
				    if(text.lastIndexOf('<body',0)===0){ var e0=text.indexOf('>'); mark(0, e0+1, null); i=e0+1; }
				    while(i<n){
				      var lt=text.indexOf('<', i);
				      if(lt!==i){
				        // Text run [i, end): one DOM text node.
				        var end = lt<0 ? n : lt;
				        textRun(i, end);
				        i=end; continue;
				      }
				      if(text.startsWith('<!--', i)){
				        var ce=text.indexOf('-->', i+4); ce = ce<0 ? n : ce+3;
				        mark(i, ce, null); consumeNode(8); i=ce; continue;
				      }
				      if(text[i+1]==='/'){
				        var ge=text.indexOf('>', i); ge = ge<0 ? n : ge+1;
				        var cname=text.slice(i+2, ge-1).trim().toLowerCase().split(/\\s/)[0];
				        mark(i, ge, null);
				        if(cname==='table') popTo('table'); else popTo(cname);
				        i=ge; continue;
				      }
				      if(text[i+1]==='!' || text[i+1]==='?'){
				        var de=text.indexOf('>', i); de = de<0 ? n : de+1; mark(i, de, null); i=de; continue;
				      }
				      if(!/[a-zA-Z]/.test(text[i+1]||'')){
				        // A literal '<' in text (browser treats it as text). Extend the text run.
				        var nx=text.indexOf('<', i+1); var endT = nx<0 ? n : nx;
				        textRun(i, endT);
				        i=endT; continue;
				      }
				      // Opening tag: find its end, honouring quoted attribute values.
				      var j=i+1, q=null;
				      while(j<n){ var ch=text[j]; if(q){ if(ch===q) q=null; } else if(ch==='"'||ch==="'") q=ch; else if(ch==='>') break; j++; }
				      var te=j+1, raw=text.slice(i+1, j), name=raw.split(/[\\s\\/>]/)[0].toLowerCase();
				      var selfClosing = raw.charAt(raw.length-1)==='/';
				      // Implied end tags the browser applies before opening this element.
				      var tt=top().tag;
				      if(name==='li' && tt==='li') stack.pop();
				      else if((name==='dt'||name==='dd') && (tt==='dt'||tt==='dd')) stack.pop();
				      else if(name==='option' && tt==='option') stack.pop();
				      else if((name==='td'||name==='th') && (tt==='td'||tt==='th')) stack.pop();
				      else if(name==='tr'){ if(tt==='td'||tt==='th') stack.pop(); if(top().tag==='tr') stack.pop(); }
				      else if(CLOSES_P[name] && tt==='p') stack.pop();
				      // Browser inserts <tbody> when a <tr> appears directly in a <table>.
				      if(name==='tr' && top().tag==='table'){
				        var tb=matchElement(top(), 'tbody');
				        if(tb){ top().last=tb; stack.push({el:tb, last:null, tag:'tbody'}); }
				      }
				      mark(i, te, null);
				      var parent=top(), el=matchElement(parent, name);
				      if(el) parent.last=el;
				      else if(parent.el && lost.length<20){ var nc=nextChild(parent); while(nc && nc.nodeType!==1) nc=nc.nextSibling; lost.push({at:i, tag:name, parent:parent.tag, next:nc?nc.tagName.toLowerCase():null}); }
				      if(RAW[name]){
				        // Raw-text element: content isn't markup; skip to its end tag.
				        var re=text.toLowerCase().indexOf('</'+name, te);
				        var reEnd = re<0 ? n : (text.indexOf('>', re)+1 || n);
				        mark(te, reEnd, null);
				        i=reEnd; continue;
				      }
				      if(!VOID[name] && !selfClosing) stack.push({el:el, last:null, tag:name});
				      i=te;
				    }
				    while(k<offs.length){ out.set(offs[k++], here()); }
				    out.lost=lost;
				    return out;
				  }
				  function setBoundary(range, b, isStart){
				    if(b.t){ var o=Math.min(b.o, b.t.length); if(isStart) range.setStart(b.t, o); else range.setEnd(b.t, o); }
				    else if(b.a){ if(isStart) range.setStartAfter(b.a); else range.setEndAfter(b.a); }
				    else { if(isStart) range.setStart(b.p, 0); else range.setEnd(b.p, 0); }
				  }
				  // DOM Ranges for one element's occurrences, from the source map.
				  function mapRangesFor(id){
				    var rs=SM.ranges[id]||[], list=[];
				    for(var i=0;i+1<rs.length;i+=2){
				      var s=SM.b.get(rs[i]), e=SM.b.get(rs[i+1]);
				      if(!s || !e) continue;
				      var r=document.createRange();
				      try{ setBoundary(r, s, true); setBoundary(r, e, false); }catch(x){ continue; }
				      list.push(r);
				    }
				    return list;
				  }
				  function mapIds(){ return SM ? Object.keys(SM.ranges) : []; }
				  // Measured line boxes per marker id, in document coordinates (so scrolling doesn't
				  // invalidate them). Measuring is a layout query per occurrence, so each id is
				  // measured once and shared by tree-row hover and inspect-mode hit testing. Dropped
				  // on resize / inspect toggle.
				  var boxCache={};
				  function occurrenceRanges(id){
				    if(window.parsleySourceMapUrl) return SM ? mapRangesFor(id) : null;
				    if(idx===null) buildIndex();
				    var pairs=idx[id]||[], list=[];
				    for(var i=0;i<pairs.length;i++){
				      var r=document.createRange();
				      try{ r.setStartAfter(pairs[i].s); r.setEndBefore(pairs[i].e); }catch(e){ continue; }
				      list.push(r);
				    }
				    return list;
				  }
				  // Occurrences inside a position:fixed/sticky ancestor (headers, sticky navs) don't
				  // scroll with the document, so their document coordinates change with scroll —
				  // they're "pinned": kept as ranges and measured live, never cached. Few in number.
				  var pinCache=new WeakMap();
				  function isPinned(node){
				    var el = node && (node.nodeType===1 ? node : node.parentElement), chain=[], v=false;
				    while(el && el!==document.body && el!==document.documentElement){
				      if(pinCache.has(el)){ v=pinCache.get(el); break; }
				      chain.push(el);
				      var pos=getComputedStyle(el).position;
				      if(pos==='fixed' || pos==='sticky'){ v=true; break; }
				      el=el.parentElement;
				    }
				    for(var i=0;i<chain.length;i++) pinCache.set(chain[i], v);
				    return v;
				  }
				  function measure(ranges, id, sx, sy, out){
				    for(var i=0;i<ranges.length;i++){
				      var rects=ranges[i].getClientRects();
				      for(var j=0;j<rects.length;j++){
				        var rc=rects[j], area=rc.width*rc.height;
				        if(area>0) out.push({id:id, l:rc.left+sx, t:rc.top+sy, r:rc.right+sx, b:rc.bottom+sy, area:area});
				      }
				    }
				    return out;
				  }
				  // Per id: cached document-coordinate boxes for scrolling content, plus the ranges
				  // of pinned occurrences to measure live.
				  function boxesFor(id){
				    var c=boxCache[id];
				    if(c) return c;
				    var ranges=occurrenceRanges(id);
				    if(ranges===null) return {boxes:[], pins:[]};   // source map not loaded yet — don't cache
				    var flowing=[], pins=[];
				    for(var i=0;i<ranges.length;i++) (isPinned(ranges[i].commonAncestorContainer) ? pins : flowing).push(ranges[i]);
				    c={boxes:measure(flowing, +id, window.pageXOffset, window.pageYOffset, []), pins:pins};
				    boxCache[id]=c;
				    return c;
				  }
				  // All of an id's boxes in current document coordinates (pinned ones measured now).
				  function currentBoxes(id){
				    var c=boxesFor(id);
				    return c.pins.length ? measure(c.pins, +id, window.pageXOffset, window.pageYOffset, c.boxes.slice()) : c.boxes;
				  }
				  // Highlights an element's occurrences. Only boxes in or near the viewport are drawn
				  // (capped), so a row whose element rendered thousands of times costs a few dozen
				  // divs, not thousands. Returns the first occurrence's box (viewport coordinates),
				  // for scroll-to-reveal.
				  var MAX_DRAWN=400, pendingHighlight=null;
				  window.parsleyHighlight = function(id){
				    if(window.parsleySourceMapUrl && !SM){
				      pendingHighlight=id;
				      ensureMap(function(){ if(pendingHighlight===id){ pendingHighlight=null; window.parsleyHighlight(id); } });
				      return null;
				    }
				    clearHighlight(); ensureLayer();
				    var boxes=currentBoxes(id);
				    if(!boxes.length) return null;
				    var sx=window.pageXOffset, sy=window.pageYOffset, vh=window.innerHeight, vw=window.innerWidth, margin=vh;
				    var frag=document.createDocumentFragment(), drawn=0;
				    for(var i=0;i<boxes.length && drawn<MAX_DRAWN;i++){
				      var b=boxes[i];
				      if(b.b<sy-margin || b.t>sy+vh+margin || b.r<sx || b.l>sx+vw) continue;
				      var box=document.createElement('div');
				      box.style.cssText='position:fixed;pointer-events:none;border:2px solid #ff5c8a;'
				        +'background:rgba(255,92,138,0.18);border-radius:3px;'
				        +'left:'+(b.l-sx)+'px;top:'+(b.t-sy)+'px;width:'+(b.r-b.l)+'px;height:'+(b.b-b.t)+'px';
				      frag.appendChild(box); drawn++;
				    }
				    layer.appendChild(frag);
				    var f=boxes[0];
				    return {left:f.l-sx, top:f.t-sy, width:f.r-f.l, height:f.b-f.t};
				  };
				  window.parsleyClear = function(){ pendingHighlight=null; clearHighlight(); };
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
				  window.addEventListener('resize', function(){ idx=null; grid=null; boxCache={}; clearHighlight(); });
				  // Content growing or shrinking (late images, charts, AJAX updates) moves boxes.
				  if(window.ResizeObserver) new ResizeObserver(function(){ grid=null; boxCache={}; }).observe(document.documentElement);
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
				  // Inspect-mode hit testing. Measuring marker ranges is a layout query, so it must
				  // never happen per mousemove: we measure every occurrence's line boxes ONCE (in
				  // document coordinates, so scrolling doesn't invalidate them) and bucket them into a
				  // coarse grid. A hover then tests only the boxes in the cursor's cell — no layout.
				  // The cache is dropped on resize/inspect-toggle and rebuilt on the next hover.
				  var CELL=128, grid=null, pinnedHits=[];
				  function buildGrid(){
				    var ids;
				    if(window.parsleySourceMapUrl){ ids=mapIds(); }
				    else { if(idx===null) buildIndex(); ids=Object.keys(idx); }
				    grid={}; pinnedHits=[];
				    for(var gi=0;gi<ids.length;gi++){
				      var id=ids[gi];
				      if(!window.parsleyOpenUrls || !(id in window.parsleyOpenUrls)) continue;
				      var c=boxesFor(id), boxes=c.boxes;
				      for(var p=0;p<c.pins.length;p++) pinnedHits.push({id:+id, range:c.pins[p]});
				      for(var i=0;i<boxes.length;i++){
				        var b=boxes[i];
				        var x0=Math.floor(b.l/CELL), x1=Math.floor(b.r/CELL), y0=Math.floor(b.t/CELL), y1=Math.floor(b.b/CELL);
				        for(var gx=x0;gx<=x1;gx++) for(var gy=y0;gy<=y1;gy++){ var k=gx+','+gy; (grid[k]=grid[k]||[]).push(b); }
				      }
				    }
				  }
				  // Innermost marked element under a document point: among boxes containing it, the
				  // smallest wins (a big container never steals the hit from a nested child); on an
				  // exact-area tie the higher id wins, since a nested child opens after its container.
				  function innermostHitAt(dx, dy){
				    if(grid===null) buildGrid();
				    var cell=grid[Math.floor(dx/CELL)+','+Math.floor(dy/CELL)] || [];
				    // Pinned occurrences (fixed/sticky) are measured live at the current scroll.
				    if(pinnedHits.length){
				      cell=cell.slice();
				      for(var p=0;p<pinnedHits.length;p++) measure([pinnedHits[p].range], pinnedHits[p].id, window.pageXOffset, window.pageYOffset, cell);
				    }
				    var best=null;
				    for(var i=0;i<cell.length;i++){
				      var b=cell[i];
				      if(dx<b.l || dx>b.r || dy<b.t || dy>b.b) continue;
				      if(!best || b.area<best.area || (b.area===best.area && b.id>best.id)) best=b;
				    }
				    return best;
				  }
				  // Mousemoves arrive far faster than frames; resolve at most once per frame, with
				  // the latest position, so the highlight tracks the cursor instead of lagging it.
				  var lastX=0, lastY=0, framePending=false;
				  // True when the pointer is over our own UI (panel, resize handles) — there the tree
				  // rows drive highlighting, so the page pick must stand down.
				  function overOwnUI(target){
				    if(!target || !target.closest) return false;
				    return !!target.closest('#parsleyPanel, #parsleyResizeL, #parsleyResizeT');
				  }
				  var overUI=false;
				  function onInspectMove(e){
				    if(!inspecting) return;
				    overUI=overOwnUI(e.target);
				    lastX=e.clientX; lastY=e.clientY;
				    if(framePending) return;
				    framePending=true;
				    requestAnimationFrame(updateHover);
				  }
				  function updateHover(){
				    framePending=false;
				    if(!inspecting) return;
				    if(window.parsleySourceMapUrl && !SM){ ensureMap(updateHover); return; }
				    if(overUI){ hoverId=-1; if(hoverBox) hoverBox.style.display='none'; return; }
				    var sx=window.pageXOffset, sy=window.pageYOffset;
				    var hit=innermostHitAt(lastX+sx, lastY+sy);
				    hoverId = hit ? String(hit.id) : -1;
				    var box=ensureHoverBox();
				    if(!hit){ box.style.display='none'; return; }
				    box.style.display='block'; box.style.left=(hit.l-sx)+'px'; box.style.top=(hit.t-sy)+'px'; box.style.width=(hit.r-hit.l)+'px'; box.style.height=(hit.b-hit.t)+'px';
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
				    if(id===-1){ var hit=innermostHitAt(e.clientX+window.pageXOffset, e.clientY+window.pageYOffset); id = hit ? String(hit.id) : -1; }
				    if(id!==-1 && window.parsleyOpenUrls[id]){
				      // Stop the click from reaching the page: stopImmediatePropagation also blocks
				      // other capture-phase listeners, and preventDefault kills the default action,
				      // so the click can't leak through to navigate the page's own elements.
				      e.preventDefault(); e.stopImmediatePropagation();
				      parsleyOpen(window.parsleyOpenUrls[id]);
				    }
				  }
				  // Tree ordering: 'weight' (server order, hottest first) or 'source' (appearance
				  // order). Re-sorts each level's siblings in place; remembered per viewer.
				  var ORDER_KEY='parsleyTreeOrder';
				  function applyOrder(order){
				    var attr = order==='source' ? 'data-pn' : 'data-ph';
				    var lists=document.querySelectorAll('#parsleyPanel [data-pkids]');
				    for(var i=0;i<lists.length;i++){
				      var list=lists[i], kids=[];
				      for(var c=list.firstElementChild;c;c=c.nextElementSibling) kids.push(c);
				      kids.sort(function(a,b){ return (+a.getAttribute(attr)) - (+b.getAttribute(attr)); });
				      for(var k=0;k<kids.length;k++) list.appendChild(kids[k]);
				    }
				    var btn=document.getElementById('parsleyOrderBtn');
				    if(btn) btn.textContent = order==='source' ? '⇅ source' : '⇅ weight';
				  }
				  var treeOrder='weight';
				  try{ if(localStorage.getItem(ORDER_KEY)==='source') treeOrder='source'; }catch(e){}
				  window.parsleyToggleOrder=function(){
				    treeOrder = treeOrder==='source' ? 'weight' : 'source';
				    try{ localStorage.setItem(ORDER_KEY, treeOrder); }catch(e){}
				    applyOrder(treeOrder);
				  };
				  function initOrder(){
				    if(treeOrder==='source') applyOrder('source');
				    // Fetch the source map as soon as the panel is approached, so it's ready by hover.
				    var panel=document.getElementById('parsleyPanel');
				    if(panel) panel.addEventListener('mouseenter', function(){ ensureMap(); });
				  }
				  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', initOrder); else initOrder();
				  window.parsleyToggleInspect=function(){
				    inspecting=!inspecting;
				    var btn=document.getElementById('parsleyInspectBtn');
				    if(inspecting){
				      idx=null; grid=null; boxCache={}; // rebuild marker index and hit boxes against current layout
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
