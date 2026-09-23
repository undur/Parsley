package parsley;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PROTOTYPE — drift check for the source-map design (docs/render-source-map.md §4.1, §6
 * step 2). Throwaway once the design is decided.
 *
 * <p>When enabled ({@code -Dparsley.sourcemap.check}), each recorded element output range
 * carries a fingerprint of the output at the moment the element finished rendering. At end
 * of request — after any post-render response rewriting — {@link #check} verifies every
 * range still frames that output in the final response, and logs how many drifted. That
 * answers whether ranges captured at render time can be trusted against the served page.
 */
final class ParsleySourceMapCheck {

	private static final Logger logger = LoggerFactory.getLogger( ParsleySourceMapCheck.class );

	static volatile boolean enabled = Boolean.getBoolean( "parsley.sourcemap.check" );

	/** Chars taken from each end of an element's output for its fingerprint. */
	private static final int EDGE = 32;

	/** How many drifted ranges to log in detail. */
	private static final int MAX_REPORTED = 10;

	private ParsleySourceMapCheck() {}

	/**
	 * @return a cheap fingerprint of {@code content[start, end)}: its length plus up to
	 *         {@link #EDGE} chars from each end. O(1) regardless of output size.
	 */
	static String fingerprint( final CharSequence content, final int start, final int end ) {
		final int len = end - start;
		if( len <= EDGE * 2 ) {
			return len + ":" + content.subSequence( start, end );
		}
		return len + ":" + content.subSequence( start, start + EDGE ) + "…" + content.subSequence( end - EDGE, end );
	}

	/**
	 * Verifies every recorded range against the final response text and logs a summary.
	 */
	static void check( final ParsleyRenderProfiler.Result result, final String finalContent ) {
		if( !enabled || result == null || finalContent == null ) {
			return;
		}
		final int[] counts = new int[3]; // [0]=ranges, [1]=drifted, [2]=reported
		final int[] firstDrift = { Integer.MAX_VALUE };
		walk( result.root(), finalContent, counts, firstDrift );
		logger.info( "SOURCEMAP check: {} ranges, {} drifted ({}%), response {} chars{}",
				counts[0], counts[1],
				counts[0] == 0 ? 0 : Math.round( counts[1] * 1000.0 / counts[0] ) / 10.0,
				finalContent.length(),
				counts[1] == 0 ? "" : ", earliest drift at offset " + firstDrift[0] );
	}

	private static void walk( final ParsleyRenderProfiler.TreeNode node, final String content, final int[] counts, final int[] firstDrift ) {
		for( int i = 0; i < node.rangeCount(); i++ ) {
			counts[0]++;
			final int start = node.rangeStart( i );
			final int end = node.rangeEnd( i );
			final String expected = node.fingerprint( i );
			final boolean inBounds = start >= 0 && end <= content.length() && start <= end;
			if( expected == null || (inBounds && expected.equals( fingerprint( content, start, end ) )) ) {
				continue;
			}
			counts[1]++;
			firstDrift[0] = Math.min( firstDrift[0], start );
			if( counts[2]++ < MAX_REPORTED ) {
				logger.info( "SOURCEMAP drift: {} (component {}, line {}) range [{},{}): expected {} but found {}",
						node.label(), node.componentName(), node.line(), start, end,
						abbreviate( expected ), inBounds ? abbreviate( fingerprint( content, start, end ) ) : "<out of bounds>" );
			}
		}
		for( final ParsleyRenderProfiler.TreeNode child : node.children() ) {
			walk( child, content, counts, firstDrift );
		}
	}

	private static String abbreviate( final String s ) {
		final String oneLine = s.replace( '\n', ' ' ).replace( '\t', ' ' );
		return oneLine.length() > 90 ? oneLine.substring( 0, 90 ) + "…" : oneLine;
	}
}
