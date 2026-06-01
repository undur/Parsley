package parsley;

import java.util.List;

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

		b.append( "<aside style=\"" )
				.append( "position:fixed;bottom:12px;right:12px;z-index:2147483647;" )
				.append( "width:min(960px,60vw);max-height:80vh;overflow:auto;" )
				.append( "font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;" )
				.append( "background:rgba(20,22,28,0.96);color:#e6e6e6;" )
				.append( "border:1px solid #3a3f4b;border-radius:10px;" )
				.append( "box-shadow:0 8px 30px rgba(0,0,0,0.5);\">" );

		// --- header ---
		b.append( "<div style=\"" )
				.append( "padding:10px 14px;font:600 13px/1.2 system-ui,sans-serif;" )
				.append( "border-bottom:1px solid #3a3f4b;display:flex;justify-content:space-between;align-items:center;" )
				.append( "position:sticky;top:0;background:rgba(20,22,28,0.98)\">" )
				.append( "<span>" ).append( ParsleyConstants.HERB ).append( " Parsley render tree</span>" )
				.append( "<span style=\"color:#9aa0aa;font-weight:400\">" ).append( formatNanos( total ) ).append( "</span>" )
				.append( "</div>" );

		// --- binding summary ---
		b.append( "<div style=\"padding:8px 14px;color:#9aa0aa;border-bottom:1px solid #2a2e38\">" )
				.append( "bindings: " )
				.append( "<span style=\"color:#8fd3ff\">" ).append( result.bindingPullCount() ).append( " pulls</span> " )
				.append( formatNanos( result.bindingPullNanos() ) )
				.append( " &middot; " )
				.append( "<span style=\"color:#ffd28f\">" ).append( result.bindingPushCount() ).append( " pushes</span> " )
				.append( formatNanos( result.bindingPushNanos() ) )
				.append( "</div>" );

		// --- tree ---
		b.append( "<div style=\"padding:6px 6px 10px\">" );
		for( final ParsleyRenderProfiler.TreeNode child : result.root().childrenByHeat() ) {
			appendNode( b, child, total, 0, appName );
		}
		b.append( "</div>" );

		b.append( "</aside>" );
		return b.toString();
	}

	/**
	 * Renders one tree node and its children recursively. Nodes with children are
	 * collapsible {@code <details>} (open by default); leaves are plain rows.
	 */
	private static void appendNode( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final int depth, final String appName ) {

		final boolean hasChildren = !node.children().isEmpty();
		final double fractionOfTotal = total == 0 ? 0 : (double)node.inclusiveNanos() / total;
		final int barPct = (int)Math.round( fractionOfTotal * 100 );
		final int indentPx = 10 + depth * 14;

		if( hasChildren ) {
			b.append( "<details open style=\"margin:0\">" );
			b.append( "<summary style=\"cursor:pointer;list-style:none\">" );
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, true, appName );
			b.append( "</summary>" );
			for( final ParsleyRenderProfiler.TreeNode child : node.childrenByHeat() ) {
				appendNode( b, child, total, depth + 1, appName );
			}
			b.append( "</details>" );
		}
		else {
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, false, appName );
		}
	}

	private static void appendRowInner( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final double fractionOfTotal, final int barPct, final int indentPx, final boolean hasChildren, final String appName ) {

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

		b.append( "<div style=\"position:relative;display:flex;justify-content:space-between;gap:8px\">" );

		// left: disclosure caret + label (click-to-open link) + line + count + phase
		b.append( "<span style=\"white-space:nowrap;overflow:hidden;text-overflow:ellipsis\">" );
		b.append( "<span style=\"color:#565b66\">" ).append( hasChildren ? "&#9662; " : "&nbsp;&nbsp;&nbsp;" ).append( "</span>" );

		// The label opens the component at this element's line in the IDE, if we can
		// build a dev-server URL for it. Otherwise it's plain text.
		final String openURL = ParsleyDevServerLinks.openComponentURL( appName, node.componentName(), node.line() );
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

		// right: inclusive time (+ self/bindings detail)
		b.append( "<span style=\"white-space:nowrap;text-align:right\">" );
		b.append( "<span style=\"color:#e6e6e6\">" ).append( formatNanos( node.inclusiveNanos() ) ).append( "</span>" );
		b.append( "<span style=\"color:#6b7280\"> " ).append( String.format( "%.0f%%", fractionOfTotal * 100 ) ).append( "</span>" );
		// self-time hint when it differs meaningfully from inclusive (i.e. node has children doing work)
		if( node.inclusiveNanos() - node.selfNanos() > 0 ) {
			b.append( "<span style=\"color:#7e8694;font-size:11px\"> &middot; self " ).append( formatNanos( node.selfNanos() ) ).append( "</span>" );
		}
		if( node.bindingNanos() > 0 ) {
			b.append( "<span style=\"color:#8fd3ff;font-size:11px\"> &middot; bind " ).append( formatNanos( node.bindingNanos() ) ).append( "</span>" );
		}
		b.append( "</span>" );

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
