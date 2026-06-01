package parsley;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * PROTOTYPE — preview generator (not an assertion test). Simulates rendering a
 * realistic nested page and writes a full HTML page (host + tree overlay) to
 * target/heatmap-preview.html so the design can be eyeballed in a browser.
 */
class TestParsleyHeatmapPreview {

	private static PNode node( final String type, final int off ) {
		return new PBasicNode( "wo", type, Map.of(), List.of(), true, true, type, new SourceRange( off, off + 1 ) );
	}

	private static void busy( final long nanos ) {
		final long end = System.nanoTime() + nanos;
		while( System.nanoTime() < end ) {
		}
	}

	private ParsleyRenderProfiler.Frame enter( final PNode n ) {
		return ParsleyRenderProfiler.enterElement( n, ParsleyRenderProfiler.Phase.APPEND );
	}

	@Test
	void writePreviewHtml() throws Exception {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();

		// Page structure (mirrors a real ASI-ish page):
		// USViewLook
		//   form (cheap)
		//     textfield, submit
		//   SearchResults (component ref — the hot region)
		//     repetition  (240 rows)
		//       resultRow
		//         WOString (binding-heavy)

		final PNode look = node( "USViewLook", 5 );
		final PNode form = node( "form", 120 );
		final PNode textfield = node( "textfield", 160 );
		final PNode submit = node( "submit", 210 );
		final PNode results = node( "SearchResults", 480 ); // component reference
		final PNode repetition = node( "repetition", 520 );
		final PNode resultRow = node( "resultRow", 560 );
		final PNode str = node( "WOString", 600 );

		final ParsleyRenderProfiler.Frame fLook = enter( look );
		busy( 150_000 ); // look chrome itself

		// cheap form subtree
		final ParsleyRenderProfiler.Frame fForm = enter( form );
		busy( 40_000 );
		ParsleyRenderProfiler.exitElement( enter( textfield ) );
		ParsleyRenderProfiler.exitElement( enter( submit ) );
		ParsleyRenderProfiler.exitElement( fForm );

		// expensive results subtree
		final ParsleyRenderProfiler.Frame fResults = enter( results );
		busy( 300_000 ); // component ref overhead
		final ParsleyRenderProfiler.Frame fRep = enter( repetition );
		busy( 120_000 );
		for( int i = 0; i < 240; i++ ) {
			final ParsleyRenderProfiler.Frame fRow = enter( resultRow );
			busy( 18_000 );
			final ParsleyRenderProfiler.Frame fStr = enter( str );
			busy( 12_000 );
			ParsleyRenderProfiler.recordBindingPull( 6_000 ); // each row pulls a binding
			ParsleyRenderProfiler.exitElement( fStr );
			ParsleyRenderProfiler.exitElement( fRow );
		}
		ParsleyRenderProfiler.exitElement( fRep );
		ParsleyRenderProfiler.exitElement( fResults );

		ParsleyRenderProfiler.exitElement( fLook );

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final String overlay = ParsleyRenderHeatmapOverlay.render( result );

		final String page = """
				<!doctype html><html><head><meta charset="utf-8"><title>Parsley heat map preview</title></head>
				<body style="font-family:system-ui;background:#f3f4f6;margin:0;padding:40px">
					<h1>Host page (pretend this is your app)</h1>
					<p>The Parsley render <strong>tree</strong> overlay is pinned bottom-right. Expand/collapse the hot subtree.</p>
				%s</body></html>
				""".formatted( overlay );

		final Path out = Path.of( "target", "heatmap-preview.html" );
		Files.createDirectories( out.getParent() );
		Files.writeString( out, page );
		System.out.println( "PREVIEW WRITTEN: " + out.toAbsolutePath() );

		ParsleyRenderProfiler.reset();
		ParsleyRenderProfiler.setEnabled( false );
	}
}
