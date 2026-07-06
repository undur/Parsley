package parsley;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests the incremental marker-safety state machine directly. This is the code that decides
 * whether a heat-map marker can be safely emitted at the current end of the response; getting
 * it wrong corrupts output (the historical comment-spill bug), so it's tested in isolation
 * across the tricky contexts — and specifically that the answer is maintained correctly when
 * the response is fed in <em>multiple appends</em> (the real usage: one call per element).
 */
class TestParsleyMarkerScanState {

	/** Feeds the whole string as one append and returns the safety verdict at its end. */
	private static boolean safeAfter( final String html ) {
		final ParsleyMarkerScanState s = new ParsleyMarkerScanState();
		return s.safeHere( new StringBuilder( html ) );
	}

	/** Feeds the response in successive appends (cumulative), returning the final verdict. */
	private static boolean safeAfterAppends( final String... chunks ) {
		final ParsleyMarkerScanState s = new ParsleyMarkerScanState();
		final StringBuilder sb = new StringBuilder();
		boolean last = false;
		for( final String chunk : chunks ) {
			sb.append( chunk );
			last = s.safeHere( sb );
		}
		return last;
	}

	@Test
	void unsafeBeforeBodyOpens() {
		assertFalse( safeAfter( "<!doctype html><html><head><title>x</title></head>" ), "no body yet" );
	}

	@Test
	void safeInBodyContentFlow() {
		assertTrue( safeAfter( "<html><body><p>hello</p>" ), "in body, at content level" );
	}

	@Test
	void unsafeMidTag() {
		assertFalse( safeAfter( "<html><body><div class=\"" ), "inside an open start tag" );
		assertTrue( safeAfter( "<html><body><div class=\"x\">" ), "tag closed — now safe" );
	}

	@Test
	void unsafeInsideScript() {
		assertFalse( safeAfter( "<html><body><script>var a = 1;" ), "inside <script>" );
		assertTrue( safeAfter( "<html><body><script>var a = 1;</script>" ), "script closed" );
	}

	@Test
	void unsafeInsideStyle() {
		assertFalse( safeAfter( "<html><body><style>.a{color:red;" ), "inside <style>" );
		assertTrue( safeAfter( "<html><body><style>.a{}</style>" ), "style closed" );
	}

	@Test
	void unsafeInsideAuthoredComment() {
		assertFalse( safeAfter( "<html><body><!-- commented <div>stuff " ), "inside an authored comment" );
		assertTrue( safeAfter( "<html><body><!-- commented --> " ), "comment closed" );
	}

	@Test
	void ourOwnMarkersDoNotCountAsComments() {
		// Our self-closed <!--p:N--> markers must NOT be treated as opening an authored
		// comment — otherwise every element after the first would be judged "in a comment".
		assertTrue( safeAfter( "<html><body><!--p:1-->hi<!--/p:1-->" ), "own markers are transparent" );
	}

	@Test
	void caseInsensitiveTagsAndScript() {
		assertTrue( safeAfter( "<HTML><BODY><P>x</P>" ), "uppercase body still opens" );
		assertFalse( safeAfter( "<html><body><SCRIPT>x" ), "uppercase script still suppresses" );
	}

	// ---- the incremental property: same verdict regardless of how appends are chunked ----

	@Test
	void scriptStateSurvivesAcrossAppends() {
		// <script> opened in one append, still open in the next — must stay unsafe even though
		// the second append alone contains no <script.
		assertFalse( safeAfterAppends( "<html><body><script>var a=1;", " var b=2;" ), "still in script" );
		assertTrue( safeAfterAppends( "<html><body><script>x", "</script><p>" ), "script closed across appends" );
	}

	@Test
	void commentStateSurvivesAcrossAppends() {
		assertFalse( safeAfterAppends( "<html><body><!-- open ", "still going " ), "still in comment" );
		assertTrue( safeAfterAppends( "<html><body><!-- open ", "--> done" ), "comment closed across appends" );
	}

	@Test
	void bodyLatchSurvivesAcrossAppends() {
		// body opens in the first append; a later append with no <body must still read safe.
		assertTrue( safeAfterAppends( "<html><body>", "<p>later</p>" ), "body stays open" );
	}

	@Test
	void midTagSplitAcrossAppends() {
		assertFalse( safeAfterAppends( "<html><body><div ", "class=\"x\"" ), "tag still open across appends" );
		assertTrue( safeAfterAppends( "<html><body><div ", "class=\"x\">" ), "tag closes in second append" );
	}
}
