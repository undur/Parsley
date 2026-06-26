package parsley;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * Tests {@link ParsleyProxyElement}'s exception handling (a partially-rendered failed
 * element is rolled back — by truncating to the pre-render byte length, not capturing
 * the whole content string) and its render-time marker placement (markers emitted only
 * where safe, decided from a bounded tail of the response, not the whole growing one).
 */
class TestParsleyProxyElement {

	@AfterEach
	void disableProfiler() {
		ParsleyRenderProfiler.setEnabled( false );
		ParsleyRenderProfiler.reset();
	}

	private static PNode node() {
		return new PBasicNode( "wo", "str", Map.of(), List.of(), true, true, "str", new SourceRange( 0, 1 ) );
	}

	/** A trivial element that appends a fixed string. */
	private static WOElement appending( final String s ) {
		return new WOElement() {
			@Override
			public void appendToResponse( final WOResponse r, final WOContext c ) {
				r.appendContentString( s );
			}
		};
	}

	/**
	 * An element that appends some output and then throws an unknown-key exception —
	 * standing in for a component that fails partway through rendering.
	 */
	private static final class FailingElement extends WOElement {
		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {
			response.appendContentString( "PARTIAL-OUTPUT-THAT-SHOULD-BE-ROLLED-BACK" );
			throw new ParsleyUnknownKeyException( "no such key", new Object(), "missingKey", "person.missingKey", null, "value" );
		}

		@Override
		public void takeValuesFromRequest( final WORequest request, final WOContext context ) {}
	}

	@Test
	void rollsBackPartialOutputOfAFailedElement() {
		final WOResponse response = new WOResponse();
		response.appendContentString( "BEFORE" );

		// FailingElement appends partial output then throws. The proxy truncates the
		// response back to its pre-render length before rendering the inline error. (The
		// error message rendering itself needs a real WOComponent for component().name(),
		// which isn't available in a unit test, so it then NPEs — but the truncation has
		// already happened by that point, which is what we're verifying here.)
		final ParsleyProxyElement proxy = new ParsleyProxyElement( new FailingElement(), null );
		try {
			proxy.appendToResponse( response, null );
		}
		catch( final NullPointerException expectedInTestOnly ) {
			// message rendering needs a component we can't supply in a unit test
		}

		final String out = response.contentString();
		assertTrue( out.startsWith( "BEFORE" ), "content rendered before the failed element is kept: " + out );
		assertFalse( out.contains( "PARTIAL-OUTPUT-THAT-SHOULD-BE-ROLLED-BACK" ),
				"the failed element's partial output is rolled back: " + out );
	}

	@Test
	void leavesContentIntactWhenTheWrappedElementSucceeds() {
		final WOResponse response = new WOResponse();
		response.appendContentString( "BEFORE" );

		final WOElement ok = new WOElement() {
			@Override
			public void appendToResponse( final WOResponse r, final WOContext c ) {
				r.appendContentString( "-RENDERED" );
			}
		};
		new ParsleyProxyElement( ok, null ).appendToResponse( response, null );

		assertTrue( response.contentString().equals( "BEFORE-RENDERED" ), "successful render is untouched" );
	}

	@Test
	void emitsMarkersInNormalBodyFlowWhenProfiling() {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();

		final WOResponse response = new WOResponse();
		response.appendContentString( "<html><body><p>" );

		new ParsleyProxyElement( appending( "x" ), node() ).appendToResponse( response, null );

		final String out = response.contentString();
		assertTrue( out.contains( "<!--parsley:" ), "markers emitted in body flow: " + out );
		assertTrue( out.contains( "x" ) );
	}

	@Test
	void suppressesMarkersInsideAScriptElement() {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();

		// Body has opened, but we're now inside an unclosed <script> — a marker here would
		// corrupt the script. The bounded-tail safety check must still catch this.
		final WOResponse response = new WOResponse();
		response.appendContentString( "<html><body><script>var a = 1;" );

		new ParsleyProxyElement( appending( "more()" ), node() ).appendToResponse( response, null );

		final String out = response.contentString();
		assertFalse( out.contains( "<!--parsley:" ), "no markers inside <script>: " + out );
		assertTrue( out.contains( "more()" ), "the element still rendered" );
	}

	@Test
	void suppressesMarkersMidTag() {
		ParsleyRenderProfiler.setEnabled( true );
		ParsleyRenderProfiler.reset();

		// We're inside an open start tag (an unclosed '<') — a marker would land inside an
		// attribute. Must be suppressed.
		final WOResponse response = new WOResponse();
		response.appendContentString( "<html><body><div class=\"" );

		new ParsleyProxyElement( appending( "x" ), node() ).appendToResponse( response, null );

		assertFalse( response.contentString().contains( "<!--parsley:" ), "no markers mid-tag" );
	}
}
