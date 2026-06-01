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

	static String render( final ParsleyRenderProfiler.Result result ) {

		final long total = result.totalInclusiveNanos();

		final StringBuilder b = new StringBuilder( 8192 );

		b.append( "<aside style=\"" )
				.append( "position:fixed;bottom:12px;right:12px;z-index:2147483647;" )
				.append( "width:460px;max-height:75vh;overflow:auto;" )
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
			appendNode( b, child, total, 0 );
		}
		b.append( "</div>" );

		b.append( "</aside>" );
		return b.toString();
	}

	/**
	 * Renders one tree node and its children recursively. Nodes with children are
	 * collapsible {@code <details>} (open by default); leaves are plain rows.
	 */
	private static void appendNode( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final int depth ) {

		final boolean hasChildren = !node.children().isEmpty();
		final double fractionOfTotal = total == 0 ? 0 : (double)node.inclusiveNanos() / total;
		final int barPct = (int)Math.round( fractionOfTotal * 100 );
		final int indentPx = 10 + depth * 14;

		if( hasChildren ) {
			b.append( "<details open style=\"margin:0\">" );
			b.append( "<summary style=\"cursor:pointer;list-style:none\">" );
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, true );
			b.append( "</summary>" );
			for( final ParsleyRenderProfiler.TreeNode child : node.childrenByHeat() ) {
				appendNode( b, child, total, depth + 1 );
			}
			b.append( "</details>" );
		}
		else {
			appendRowInner( b, node, total, fractionOfTotal, barPct, indentPx, false );
		}
	}

	private static void appendRowInner( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final long total, final double fractionOfTotal, final int barPct, final int indentPx, final boolean hasChildren ) {

		b.append( "<div style=\"position:relative;padding:4px 8px 4px " ).append( indentPx ).append( "px;" )
				.append( "border-radius:5px;margin:1px 0;overflow:hidden\">" );

		// heat bar (inclusive time), behind the text
		b.append( "<div style=\"position:absolute;inset:0;width:" ).append( barPct ).append( "%;" )
				.append( "background:" ).append( heatColor( fractionOfTotal ) ).append( ";opacity:0.28\"></div>" );

		b.append( "<div style=\"position:relative;display:flex;justify-content:space-between;gap:8px\">" );

		// left: disclosure caret + label + offset + count + phase
		b.append( "<span style=\"white-space:nowrap;overflow:hidden;text-overflow:ellipsis\">" );
		b.append( "<span style=\"color:#565b66\">" ).append( hasChildren ? "&#9662; " : "&nbsp;&nbsp;&nbsp;" ).append( "</span>" );
		b.append( "<span style=\"color:#c8ccd4\">" ).append( escape( node.label() ) ).append( "</span>" );
		b.append( "<span style=\"color:#6b7280\"> @" ).append( node.offset() ).append( "</span>" );
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
}
