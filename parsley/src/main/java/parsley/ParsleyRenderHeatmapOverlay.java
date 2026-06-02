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
	private static final java.util.regex.Pattern MARKER = java.util.regex.Pattern.compile( "<!--/?parsley:\\d+-->" );

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
		if( content == null || content.indexOf( "parsley:" ) == -1 ) {
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
			if( s.startsWith( "<!--parsley:", no ) || s.startsWith( "<!--/parsley:", no ) ) {
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

		// The whole panel is a collapsed <details> so it doesn't overlay page content
		// until you ask for it — it sits as just the header bar bottom-right, click to
		// expand the tree.
		b.append( "<details style=\"margin:0\">" );

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
				.append( "<span style=\"flex:1 1 auto\">element</span>" )
				.append( metricHeader( "time" ) )
				.append( metricHeader( "%" ) )
				.append( metricHeader( "self" ) )
				.append( metricHeader( "bind" ) )
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

	/** Fixed-width right-aligned column-header cell, matching the metric cells. */
	private static String metricHeader( final String label ) {
		return "<span style=\"flex:0 0 " + METRIC_COL_PX + "px;text-align:right\">" + label + "</span>";
	}

	/** Width of each right-hand metric column, so they line up down the tree. */
	private static final int METRIC_COL_PX = 64;

	/** Fixed-width, right-aligned metric cell (empty string renders an empty column). */
	private static String metricCell( final String value, final String color ) {
		return "<span style=\"flex:0 0 " + METRIC_COL_PX + "px;text-align:right;white-space:nowrap;color:" + color + "\">" + value + "</span>";
	}

	/** Subtrees whose inclusive time is below this fraction of the page start collapsed. */
	private static final double COLLAPSE_BELOW_FRACTION = 0.01;

	/**
	 * Colors a self-time value by its <em>percentile rank</em> among all self-times on
	 * the page, so the "self" column reads as its own heat scale: the median sits at
	 * the green↔red boundary, the fast half ramps green, the slow half ramps red, and
	 * intensity rises with rank so the worst offenders are the most vivid.
	 *
	 * <p>Percentile (not raw magnitude) on purpose: with hundreds of values and a few
	 * large ones, a magnitude scale washes everything to one shade. Ranking spreads
	 * the color evenly across the column regardless of outliers.
	 */
	private static final class SelfTimeScale {

		private final long[] sorted;

		private SelfTimeScale( final long[] sorted ) {
			this.sorted = sorted;
		}

		static SelfTimeScale of( final ParsleyRenderProfiler.TreeNode root ) {
			final java.util.List<Long> values = new java.util.ArrayList<>();
			collect( root, values );
			final long[] arr = new long[values.size()];
			for( int i = 0; i < arr.length; i++ ) {
				arr[i] = values.get( i );
			}
			java.util.Arrays.sort( arr );
			return new SelfTimeScale( arr );
		}

		private static void collect( final ParsleyRenderProfiler.TreeNode node, final java.util.List<Long> out ) {
			if( node.id() >= 0 && node.selfNanos() > 0 ) {
				out.add( node.selfNanos() );
			}
			for( final ParsleyRenderProfiler.TreeNode child : node.children() ) {
				collect( child, out );
			}
		}

		/**
		 * @return a CSS color for the given self-time, by its percentile rank. Returns
		 *         a neutral grey when there's no distribution to rank against.
		 */
		String colorFor( final long selfNanos ) {
			if( sorted.length == 0 || selfNanos <= 0 ) {
				return "#7e8694"; // neutral — nothing to rank, or zero self-time
			}

			// Percentile rank in [0,1]: fraction of values <= this one.
			int lo = 0, hi = sorted.length;
			while( lo < hi ) {
				final int mid = (lo + hi) >>> 1;
				if( sorted[mid] <= selfNanos ) {
					lo = mid + 1;
				}
				else {
					hi = mid;
				}
			}
			final double pct = (double)lo / sorted.length; // 0 = fastest, 1 = slowest

			// Below the median → green, lightening toward the fast end.
			// Above the median → red, deepening toward the slow end.
			if( pct < 0.5 ) {
				final double t = pct / 0.5; // 0 at fastest, 1 at median
				// pale green-grey (fast) → solid green (approaching median)
				final int light = (int)Math.round( 72 - 14 * t ); // 72%→58% lightness
				return "hsl(140," + (int)Math.round( 30 + 30 * t ) + "%," + light + "%)";
			}
			final double t = (pct - 0.5) / 0.5; // 0 just above median, 1 at slowest
			// orange (just-above-median) → vivid deep red (slowest)
			final int hue = (int)Math.round( 40 - 40 * t ); // 40°(orange)→0°(red)
			final int light = (int)Math.round( 62 - 8 * t ); // 62%→54%
			return "hsl(" + hue + ",85%," + light + "%)";
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
			for( final ParsleyRenderProfiler.TreeNode child : node.childrenByHeat() ) {
				appendNode( b, child, total, depth + 1, appName, selfScale );
			}
			b.append( "</details>" );
		}
		else {
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, false, appName, selfScale );
		}
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

		// right: four fixed-width, right-aligned metric columns that line up down the
		// tree regardless of label width/indent — time | % | self | bind.
		b.append( metricCell( formatNanos( node.inclusiveNanos() ), "#e6e6e6" ) );
		b.append( metricCell( String.format( "%.0f%%", fractionOfTotal * 100 ), "#6b7280" ) );
		// self only when it differs from inclusive (node has children doing work),
		// colored by percentile rank: fast half ramps green, slow half ramps red, so
		// the eye can rank the column at a glance instead of reading every number.
		final boolean showSelf = node.inclusiveNanos() - node.selfNanos() > 0;
		b.append( metricCell( showSelf ? formatNanos( node.selfNanos() ) : "", selfScale.colorFor( node.selfNanos() ) ) );
		b.append( metricCell( node.bindingNanos() > 0 ? formatNanos( node.bindingNanos() ) : "", "#8fd3ff" ) );

		b.append( "</div>" );
		b.append( "</div>" );
	}

	/**
	 * The overlay's inline script: a fire-and-forget IDE opener plus the
	 * "highlight this element in the page" machinery. The latter scans the document
	 * for {@code <!--parsley:N-->…<!--/parsley:N-->} comment-marker pairs (emitted
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
				      var m = /^parsley:(\\d+)$/.exec(v);
				      if(m){ (stack[m[1]] = stack[m[1]] || []).push(n); continue; }
				      var c = /^\\/parsley:(\\d+)$/.exec(v);
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
				        e.preventDefault();
				      }
				    });
				    document.addEventListener('mouseup', function(e){
				      if(!dragging) return;
				      dragging=false; header.style.cursor='grab';
				      // If this was a drag, swallow the click so <details> doesn't toggle.
				      if(moved){ e.preventDefault(); e.stopPropagation(); }
				    }, true);
				    // Belt-and-suspenders: cancel the toggle on the click that follows a drag.
				    header.addEventListener('click', function(e){ if(moved){ e.preventDefault(); moved=false; } }, true);
				  }
				  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded', initDrag); else initDrag();

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
				  // Find the innermost marker id whose <!--parsley:N-->…<!--/parsley:N--> range
				  // contains the given node — the most specific element under the cursor.
				  function innermostIdAt(node){
				    if(idx===null) buildIndex();
				    var best=-1, bestLen=Infinity;
				    for(var id in idx){
				      if(!window.parsleyOpenUrls || !(id in window.parsleyOpenUrls)) continue;
				      var pairs=idx[id];
				      for(var i=0;i<pairs.length;i++){
				        var r=document.createRange();
				        r.setStartAfter(pairs[i].s); r.setEndBefore(pairs[i].e);
				        if(r.comparePoint(node,0)===0){ // node within this range
				          var len=(r.endOffset||0)+ (r.getBoundingClientRect().width*r.getBoundingClientRect().height||0);
				          // prefer the geometrically smallest enclosing region
				          var rect=r.getBoundingClientRect(); var area=rect.width*rect.height;
				          if(area>0 && area<bestLen){ bestLen=area; best=id; }
				        }
				      }
				    }
				    return best;
				  }
				  function onInspectMove(e){
				    if(!inspecting) return;
				    var id=innermostIdAt(e.target);
				    hoverId=id;
				    var box=ensureHoverBox();
				    if(id===-1){ box.style.display='none'; return; }
				    // box the hovered element's first occurrence rect
				    var pairs=idx[id]; var best=null;
				    for(var i=0;i<pairs.length;i++){ var r=document.createRange(); r.setStartAfter(pairs[i].s); r.setEndBefore(pairs[i].e); var rc=r.getBoundingClientRect(); if(rc.width||rc.height){ best=rc; break; } }
				    if(best){ box.style.display='block'; box.style.left=best.left+'px'; box.style.top=best.top+'px'; box.style.width=best.width+'px'; box.style.height=best.height+'px'; }
				    else box.style.display='none';
				  }
				  function onInspectClick(e){
				    if(!inspecting) return;
				    // never inside our own panel
				    var panel=document.getElementById('parsleyPanel'); if(panel && panel.contains(e.target)) return;
				    var id=innermostIdAt(e.target);
				    if(id!==-1 && window.parsleyOpenUrls[id]){
				      e.preventDefault(); e.stopPropagation();
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
