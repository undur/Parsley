package parsley;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

/**
 * Tests {@link ParsleyProxyElement}'s exception handling — in particular that a
 * partially-rendered failed element is rolled back (truncated) before the inline error
 * message is rendered in its place. The rollback captures only the response's byte
 * length before rendering (not the whole content string), so it's cheap per element.
 */
class TestParsleyProxyElement {

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
}
