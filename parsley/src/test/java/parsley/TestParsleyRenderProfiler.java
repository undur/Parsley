package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * PROTOTYPE tests for the tree-structured render profiler. They drive the
 * enter/exit hooks directly (no running WO app) to verify the two things that make
 * the tree meaningful: self-time excludes children, and the tree is reconstructed
 * by template position (PNode identity), collapsing repeated renders.
 */
class TestParsleyRenderProfiler {

	private static PNode node( final String type, final int off ) {
		return new PBasicNode( "wo", type, Map.of(), List.of(), true, true, type, new SourceRange( off, off + 1 ) );
	}

	private static void busy( final long nanos ) {
		final long end = System.nanoTime() + nanos;
		while( System.nanoTime() < end ) {
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
	void buildsTreeByPositionWithSelfTime() {
		final PNode parent = node( "Parent", 10 );
		final PNode child = node( "Child", 50 );

		// Parent: ~3ms inclusive, ~2ms of it in the child → ~1ms self.
		final ParsleyRenderProfiler.Frame pf = ParsleyRenderProfiler.enterElement( parent, ParsleyRenderProfiler.Phase.APPEND );
		busy( 1_000_000 );
		final ParsleyRenderProfiler.Frame cf = ParsleyRenderProfiler.enterElement( child, ParsleyRenderProfiler.Phase.APPEND );
		busy( 2_000_000 );
		ParsleyRenderProfiler.exitElement( cf );
		ParsleyRenderProfiler.exitElement( pf );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();

		// Tree shape: root → Parent → Child.
		assertEquals( 1, result.root().children().size(), "one top-level node" );
		final ParsleyRenderProfiler.TreeNode parentNode = result.root().children().get( 0 );
		assertEquals( "wo:Parent", parentNode.label() );
		assertEquals( 1, parentNode.children().size(), "parent has one child" );
		final ParsleyRenderProfiler.TreeNode childNode = parentNode.children().get( 0 );
		assertEquals( "wo:Child", childNode.label() );

		// Inclusive: parent > child. Self: child > parent (the actionable signal).
		assertTrue( parentNode.inclusiveNanos() > childNode.inclusiveNanos(), "parent inclusive should exceed child inclusive" );
		assertTrue( childNode.selfNanos() > parentNode.selfNanos(), "child self should exceed parent self" );
		assertTrue( parentNode.selfNanos() < 2_000_000, "parent self should exclude child's 2ms" );
	}

	@Test
	void samePositionRenderedManyTimesCollapsesToOneNode() {
		final PNode container = node( "Repetition", 100 );
		final PNode item = node( "Item", 130 ); // one template position, rendered 5x

		final ParsleyRenderProfiler.Frame rf = ParsleyRenderProfiler.enterElement( container, ParsleyRenderProfiler.Phase.APPEND );
		for( int i = 0; i < 5; i++ ) {
			final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( item, ParsleyRenderProfiler.Phase.APPEND );
			busy( 200_000 );
			ParsleyRenderProfiler.exitElement( f );
		}
		ParsleyRenderProfiler.exitElement( rf );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final ParsleyRenderProfiler.TreeNode rep = result.root().children().get( 0 );

		assertEquals( 1, rep.children().size(), "repeated item collapses to ONE child node" );
		assertEquals( 5, rep.children().get( 0 ).count(), "with an occurrence count of 5" );
	}

	@Test
	void bindingTimeIsCreditedToOwningNode() {
		final PNode n = node( "WOString", 200 );
		final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( n, ParsleyRenderProfiler.Phase.APPEND );
		ParsleyRenderProfiler.recordBindingPull( 500_000, n );
		ParsleyRenderProfiler.exitElement( f );

		final ParsleyRenderProfiler.TreeNode tn = ParsleyRenderProfiler.takeResult().root().children().get( 0 );
		assertEquals( 500_000, tn.bindingNanos() );
		// Invariant: bind ⊆ self, even when the binding was ~all of the element's time.
		assertTrue( tn.bindingNanos() <= tn.selfNanos(), "bind (" + tn.bindingNanos() + ") must be <= self (" + tn.selfNanos() + ")" );
	}

	@Test
	void componentBindingCreditedToComponent_andStaysWithinSelf() {
		// Simulate WO pulling a component's binding while an inner element renders:
		// component frame on the stack, an inner element on top, the pull attributed
		// to the component's node. The component's row must show the bind time AND
		// keep bind ⊆ self; the inner element must NOT absorb that binding time.
		final PNode component = node( "SomeComponent", 10 );
		final PNode inner = node( "innerThing", 40 );

		final ParsleyRenderProfiler.Frame cf = ParsleyRenderProfiler.enterElement( component, ParsleyRenderProfiler.Phase.APPEND );
		busy( 200_000 ); // a little of the component's own work
		final ParsleyRenderProfiler.Frame inf = ParsleyRenderProfiler.enterElement( inner, ParsleyRenderProfiler.Phase.APPEND );
		busy( 100_000 );
		// The component's binding is pulled now — inner is on top, attributed to component.
		ParsleyRenderProfiler.recordBindingPull( 2_000_000, component );
		ParsleyRenderProfiler.exitElement( inf );
		ParsleyRenderProfiler.exitElement( cf );

		final ParsleyRenderProfiler.TreeNode comp = ParsleyRenderProfiler.takeResult().root().children().get( 0 );
		final ParsleyRenderProfiler.TreeNode innerNode = comp.children().get( 0 );

		assertEquals( 2_000_000, comp.bindingNanos(), "component owns its binding time" );
		assertEquals( 0, innerNode.bindingNanos(), "inner element didn't declare the binding" );
		assertTrue( comp.bindingNanos() <= comp.selfNanos(), "bind <= self on the component row" );
		assertTrue( innerNode.bindingNanos() <= innerNode.selfNanos(), "bind <= self on the inner row" );
	}

	@Test
	void overlayRendersTreeHtml() {
		final PNode outer = node( "Wrapper", 5 );
		final PNode inner = node( "SlowThing", 42 );

		final ParsleyRenderProfiler.Frame of = ParsleyRenderProfiler.enterElement( outer, ParsleyRenderProfiler.Phase.APPEND );
		busy( 200_000 );
		final ParsleyRenderProfiler.Frame inf = ParsleyRenderProfiler.enterElement( inner, ParsleyRenderProfiler.Phase.APPEND );
		busy( 800_000 );
		ParsleyRenderProfiler.exitElement( inf );
		ParsleyRenderProfiler.exitElement( of );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final String html = ParsleyRenderHeatmapOverlay.render( result );

		assertTrue( html.startsWith( "<script" ), "overlay starts with the inlined opener script" );
		assertTrue( html.contains( "render tree" ) );
		assertTrue( html.contains( "wo:Wrapper" ) );
		assertTrue( html.contains( "wo:SlowThing" ) );
		assertTrue( html.contains( "<details" ), "parent with children should be collapsible" );
		assertTrue( html.trim().endsWith( "</aside>" ) );
	}

	@Test
	void clickableRowLinksToOpenComponent() {
		final PNode n = node( "SlowThing", 42 );
		final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( n, ParsleyRenderProfiler.Phase.APPEND, "ASISearchPage", 17, "value=\"$x\"" );
		busy( 300_000 );
		ParsleyRenderProfiler.exitElement( f );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final ParsleyRenderProfiler.TreeNode treeNode = result.root().children().get( 0 );
		assertEquals( "ASISearchPage", treeNode.componentName() );
		assertEquals( 17, treeNode.line() );

		final String html = ParsleyRenderHeatmapOverlay.render( result, "MyApp" );
		// The & in the URL is HTML-escaped to &amp; inside the onclick attribute (correct HTML).
		assertTrue( html.contains( "/openComponent?app=MyApp&amp;component=ASISearchPage&amp;lineNumber=17" ), "row should link to the dev-server open URL: " + html );
		assertTrue( html.contains( "parsleyOpen(" ), "row should use the fire-and-forget opener" );
	}

	@Test
	void stripMarkersInUnsafeContexts_removesScriptStyleTitleMarkersOnly() {
		// Markers inside <script>/<style>/<title> must be removed (they'd corrupt
		// raw-text content); markers in normal body flow must be kept.
		final String in = "<body><script>x('<!--parsley:5-->','<!--/parsley:5-->')</script>"
				+ "<p><!--parsley:6-->hi<!--/parsley:6--></p></body>";
		final String out = ParsleyRenderHeatmapOverlay.stripMarkersInUnsafeContexts( in );

		assertTrue( out.contains( "x('','')" ), "script markers should be stripped: " + out );
		assertTrue( out.contains( "<!--parsley:6-->hi<!--/parsley:6-->" ), "body markers should be kept: " + out );
	}

	@Test
	void stripMarkers_removesMarkersInsideAuthoredComments() {
		// An author's <!-- ... --> with our marker inside it: the marker must go
		// (HTML comments don't nest), but a marker right after the comment stays.
		final String in = "<body><!-- FIXME <div><!--parsley:9-->x<!--/parsley:9--> --> "
				+ "<p><!--parsley:10-->ok<!--/parsley:10--></p></body>";
		final String out = ParsleyRenderHeatmapOverlay.stripMarkersInUnsafeContexts( in );

		assertTrue( out.contains( "<!-- FIXME <div>x --> " ), "marker inside authored comment stripped: " + out );
		assertTrue( out.contains( "<!--parsley:10-->ok<!--/parsley:10-->" ), "marker after comment kept: " + out );
	}

	@Test
	void stripMarkers_handlesEmptyRawTextElements() {
		// Empty <title></title> / <script></script> were an edge that could corrupt
		// the opening tag if reassembled naively.
		final String in = "<title></title><script></script><body><!--parsley:1-->t<!--/parsley:1--></body>";
		final String out = ParsleyRenderHeatmapOverlay.stripMarkersInUnsafeContexts( in );

		assertTrue( out.contains( "<title></title>" ), "empty title intact: " + out );
		assertTrue( out.contains( "<script></script>" ), "empty script intact: " + out );
		assertTrue( out.contains( "<!--parsley:1-->t<!--/parsley:1-->" ), "body marker kept: " + out );
	}

	@Test
	void disabledHooksAreNoOps() {
		ParsleyRenderProfiler.setEnabled( false );
		final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( node( "X", 1 ), ParsleyRenderProfiler.Phase.APPEND );
		assertSame( null, f, "enterElement returns null when disabled" );
		ParsleyRenderProfiler.exitElement( f ); // must not throw
		assertSame( null, ParsleyRenderProfiler.takeResult() );
	}
}
