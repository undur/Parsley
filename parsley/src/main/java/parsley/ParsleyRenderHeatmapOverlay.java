package parsley;

import java.util.List;

/**
 * PROTOTYPE — renders {@link ParsleyRenderProfiler.Result} as a self-contained
 * HTML overlay (a fixed "aside" panel) injected into the page before {@code </body>}.
 *
 * <p>The panel lists template elements sorted by total self-time (hottest first),
 * each with a heat bar whose width and color reflect its share of the measured
 * render time, plus a summary of time spent pulling/pushing bindings.
 *
 * <p>Everything is inline-styled and {@code <details>}-based so it needs no external
 * CSS/JS and can't be broken by (or break) the host page's styles. // 2026-06-01
 */
final class ParsleyRenderHeatmapOverlay {

	/** Don't list more than this many rows — the long tail isn't actionable. */
	private static final int MAX_ROWS = 25;

	private ParsleyRenderHeatmapOverlay() {}

	static String render( final ParsleyRenderProfiler.Result result ) {

		final long totalSelf = result.totalSelfNanos();
		final List<ParsleyRenderProfiler.Row> rows = result.rows();
		final long hottest = rows.isEmpty() ? 0 : rows.get( 0 ).totalNanos();

		final StringBuilder b = new StringBuilder( 4096 );

		b.append( "<aside style=\"" )
				.append( "position:fixed;bottom:12px;right:12px;z-index:2147483647;" )
				.append( "width:420px;max-height:70vh;overflow:auto;" )
				.append( "font:12px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace;" )
				.append( "background:rgba(20,22,28,0.96);color:#e6e6e6;" )
				.append( "border:1px solid #3a3f4b;border-radius:10px;" )
				.append( "box-shadow:0 8px 30px rgba(0,0,0,0.5);\">" );

		b.append( "<details open style=\"margin:0\">" );

		// --- header ---
		b.append( "<summary style=\"" )
				.append( "cursor:pointer;list-style:none;padding:10px 14px;" )
				.append( "font:600 13px/1.2 system-ui,sans-serif;" )
				.append( "border-bottom:1px solid #3a3f4b;display:flex;justify-content:space-between;align-items:center\">" )
				.append( "<span>" ).append( ParsleyConstants.HERB ).append( " Parsley render heat map</span>" )
				.append( "<span style=\"color:#9aa0aa;font-weight:400\">" ).append( formatNanos( totalSelf ) ).append( " self</span>" )
				.append( "</summary>" );

		// --- binding summary ---
		b.append( "<div style=\"padding:8px 14px;color:#9aa0aa;border-bottom:1px solid #2a2e38\">" )
				.append( "bindings: " )
				.append( "<span style=\"color:#8fd3ff\">" ).append( result.bindingPullCount() ).append( " pulls</span> " )
				.append( formatNanos( result.bindingPullNanos() ) )
				.append( " &middot; " )
				.append( "<span style=\"color:#ffd28f\">" ).append( result.bindingPushCount() ).append( " pushes</span> " )
				.append( formatNanos( result.bindingPushNanos() ) )
				.append( "</div>" );

		// --- rows ---
		b.append( "<div style=\"padding:6px 8px\">" );

		final int shown = Math.min( rows.size(), MAX_ROWS );
		for( int i = 0; i < shown; i++ ) {
			final ParsleyRenderProfiler.Row row = rows.get( i );
			appendRow( b, row, hottest, totalSelf );
		}

		if( rows.size() > shown ) {
			b.append( "<div style=\"padding:6px 8px;color:#6b7280\">… " )
					.append( rows.size() - shown )
					.append( " more (not shown)</div>" );
		}

		b.append( "</div>" ); // rows
		b.append( "</details>" );
		b.append( "</aside>" );

		return b.toString();
	}

	private static void appendRow( final StringBuilder b, final ParsleyRenderProfiler.Row row, final long hottest, final long totalSelf ) {

		final double fractionOfHottest = hottest == 0 ? 0 : (double)row.totalNanos() / hottest;
		final double pctOfTotal = totalSelf == 0 ? 0 : 100.0 * row.totalNanos() / totalSelf;
		final int barPct = (int)Math.round( fractionOfHottest * 100 );

		b.append( "<div style=\"position:relative;padding:5px 8px;border-radius:5px;margin:1px 0;overflow:hidden\">" );

		// heat bar (behind the text)
		b.append( "<div style=\"position:absolute;inset:0;width:" ).append( barPct ).append( "%;" )
				.append( "background:" ).append( heatColor( fractionOfHottest ) ).append( ";opacity:0.30\"></div>" );

		// content (above the bar)
		b.append( "<div style=\"position:relative;display:flex;justify-content:space-between;gap:8px\">" );

		b.append( "<span style=\"white-space:nowrap;overflow:hidden;text-overflow:ellipsis\">" )
				.append( "<span style=\"color:#c8ccd4\">" ).append( escape( row.label() ) ).append( "</span>" )
				.append( "<span style=\"color:#6b7280\"> @" ).append( row.line() ).append( "</span>" )
				.append( row.count() > 1 ? "<span style=\"color:#6b7280\"> &times;" + row.count() + "</span>" : "" )
				.append( "<span style=\"color:#565b66\"> " ).append( row.phase().label() ).append( "</span>" )
				.append( "</span>" );

		b.append( "<span style=\"white-space:nowrap;color:#e6e6e6\">" )
				.append( formatNanos( row.totalNanos() ) )
				.append( " <span style=\"color:#6b7280\">" ).append( String.format( "%.0f%%", pctOfTotal ) ).append( "</span>" )
				.append( "</span>" );

		b.append( "</div>" ); // content
		b.append( "</div>" ); // row
	}

	/**
	 * @return a green→yellow→red color for the given 0..1 heat fraction.
	 */
	private static String heatColor( final double fraction ) {
		final double f = Math.max( 0, Math.min( 1, fraction ) );
		// 0 → green (120deg), 1 → red (0deg)
		final int hue = (int)Math.round( 120 * (1 - f) );
		return "hsl(" + hue + ",85%,55%)";
	}

	/**
	 * @return a compact human-readable duration (µs/ms), since nanos are noisy.
	 */
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
		if( s == null ) {
			return "";
		}
		return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
	}
}
