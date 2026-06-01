package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * PROTOTYPE tests for the render profiler. These drive the profiler's enter/exit
 * hooks directly (no running WO app) to verify the self-time accounting — the part
 * that's easy to get wrong and the part that makes the heat map meaningful.
 */
class TestParsleyRenderProfiler {

	private static PNode node( final String type, final int startOffset ) {
		return new PBasicNode( "wo", type, Map.of(), List.of(), true, true, type, new SourceRange( startOffset, startOffset + 1 ) );
	}

	private static void busy( final long nanos ) {
		// Spin so wall-clock actually advances (Thread.sleep is too coarse/unreliable
		// for sub-ms, and we only need *relative* ordering to hold).
		final long end = System.nanoTime() + nanos;
		while( System.nanoTime() < end ) {
			// spin
		}
	}

	@BeforeEach
	void enable() {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();
	}

	@AfterEach
	void disable() {
		ParsleyRenderProfiler.reset();
		ParsleyRenderProfiler.setEnabled( false );
	}

	@Test
	void selfTimeExcludesChildren() {
		final PNode parent = node( "Parent", 10 );
		final PNode child = node( "Child", 50 );

		// Parent runs for ~3ms total, but ~2ms of that is the child. Parent's
		// SELF time should therefore be ~1ms, not ~3ms.
		final ParsleyRenderProfiler.Frame pf = ParsleyRenderProfiler.enterElement( parent, ParsleyRenderProfiler.Phase.APPEND );
		busy( 1_000_000 ); // 1ms in parent before child
		final ParsleyRenderProfiler.Frame cf = ParsleyRenderProfiler.enterElement( child, ParsleyRenderProfiler.Phase.APPEND );
		busy( 2_000_000 ); // 2ms in child
		ParsleyRenderProfiler.exitElement( cf );
		ParsleyRenderProfiler.exitElement( pf );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final Map<String, Long> byLabel = byLabel( result );

		final long parentSelf = byLabel.get( "wo:Parent" );
		final long childSelf = byLabel.get( "wo:Child" );

		// Child should have ~2ms, parent ~1ms — i.e. child is HOTTER than parent
		// even though the parent's inclusive time is larger. This is the whole point.
		assertTrue( childSelf > parentSelf, "child self-time (" + childSelf + ") should exceed parent self-time (" + parentSelf + ")" );

		// Parent self-time should be well under its 3ms inclusive time.
		assertTrue( parentSelf < 2_000_000, "parent self-time (" + parentSelf + ") should be < 2ms (child excluded)" );

		// Hottest row should be the child.
		assertEquals( "wo:Child", result.rows().get( 0 ).label() );
	}

	@Test
	void repeatedNodeAggregatesByLine() {
		final PNode repeated = node( "Item", 100 );

		// Same node "rendered" 5 times (as in a repetition) — should aggregate to
		// ONE row with count=5, not five rows.
		for( int i = 0; i < 5; i++ ) {
			final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( repeated, ParsleyRenderProfiler.Phase.APPEND );
			busy( 200_000 );
			ParsleyRenderProfiler.exitElement( f );
		}

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		assertEquals( 1, result.rows().size(), "repeated node should aggregate to one row" );
		assertEquals( 5, result.rows().get( 0 ).count() );
	}

	@Test
	void bindingTimingAccumulates() {
		ParsleyRenderProfiler.enterElement( node( "X", 1 ), ParsleyRenderProfiler.Phase.APPEND );
		ParsleyRenderProfiler.recordBindingPull( 500_000 );
		ParsleyRenderProfiler.recordBindingPull( 300_000 );
		ParsleyRenderProfiler.recordBindingPush( 100_000 );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		assertEquals( 2, result.bindingPullCount() );
		assertEquals( 800_000, result.bindingPullNanos() );
		assertEquals( 1, result.bindingPushCount() );
		assertEquals( 100_000, result.bindingPushNanos() );
	}

	@Test
	void overlayRendersValidHtml() {
		final PNode hot = node( "SlowThing", 42 );
		final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( hot, ParsleyRenderProfiler.Phase.APPEND );
		busy( 500_000 );
		ParsleyRenderProfiler.exitElement( f );
		ParsleyRenderProfiler.recordBindingPull( 250_000 );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final String html = ParsleyRenderHeatmapOverlay.render( result );

		assertTrue( html.startsWith( "<aside" ), "overlay should be an <aside>" );
		assertTrue( html.contains( "wo:SlowThing" ), "overlay should name the hot element" );
		assertTrue( html.contains( "@42" ), "overlay should show the source offset" );
		assertTrue( html.contains( "render heat map" ), "overlay should have a title" );
		assertTrue( html.trim().endsWith( "</aside>" ) );

		// Emit it so a human can eyeball the actual markup.
		System.out.println( "----- HEATMAP OVERLAY HTML -----" );
		System.out.println( html );
		System.out.println( "--------------------------------" );
	}

	private static Map<String, Long> byLabel( final ParsleyRenderProfiler.Result result ) {
		final java.util.HashMap<String, Long> map = new java.util.HashMap<>();
		for( final ParsleyRenderProfiler.Row row : result.rows() ) {
			map.merge( row.label(), row.totalNanos(), Long::sum );
		}
		return map;
	}
}
