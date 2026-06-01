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
 * PROTOTYPE — not a test, a preview generator. Builds a realistic-looking profile
 * with a spread of hot/warm/cold elements and writes a full HTML page (host page +
 * overlay) to target/ so it can be opened in a browser to eyeball the design.
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

	private static void time( final PNode n, final ParsleyRenderProfiler.Phase phase, final long nanos ) {
		final ParsleyRenderProfiler.Frame f = ParsleyRenderProfiler.enterElement( n, phase );
		busy( nanos );
		ParsleyRenderProfiler.exitElement( f );
	}

	@Test
	void writePreviewHtml() throws Exception {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();

		// A page-ish spread: one very hot element, a couple warm, several cold,
		// a repetition, and some binding activity.
		time( node( "WOComponentReference:SearchResults", 1840 ), ParsleyRenderProfiler.Phase.APPEND, 8_200_000 );
		time( node( "wo:repetition", 920 ), ParsleyRenderProfiler.Phase.APPEND, 3_100_000 );
		for( int i = 0; i < 240; i++ ) {
			time( node( "wo:str", 1015 ), ParsleyRenderProfiler.Phase.APPEND, 9_000 );
		}
		time( node( "wo:if", 610 ), ParsleyRenderProfiler.Phase.APPEND, 1_250_000 );
		time( node( "wo:WOString", 1320 ), ParsleyRenderProfiler.Phase.APPEND, 740_000 );
		time( node( "wo:link", 1455 ), ParsleyRenderProfiler.Phase.APPEND, 300_000 );
		time( node( "wo:textfield", 480 ), ParsleyRenderProfiler.Phase.TAKE_VALUES, 120_000 );
		time( node( "wo:submit", 505 ), ParsleyRenderProfiler.Phase.INVOKE_ACTION, 60_000 );

		for( int i = 0; i < 312; i++ ) {
			ParsleyRenderProfiler.recordBindingPull( 4_500 );
		}
		for( int i = 0; i < 6; i++ ) {
			ParsleyRenderProfiler.recordBindingPush( 22_000 );
		}

		final ParsleyRenderProfiler.Result result = ParsleyRenderProfiler.takeResult();
		final String overlay = ParsleyRenderHeatmapOverlay.render( result );

		final String page = """
				<!doctype html><html><head><meta charset="utf-8"><title>Parsley heat map preview</title></head>
				<body style="font-family:system-ui;background:#f3f4f6;margin:0;padding:40px">
					<h1>Host page (pretend this is your app)</h1>
					<p>The Parsley render heat map overlay is pinned bottom-right.</p>
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
