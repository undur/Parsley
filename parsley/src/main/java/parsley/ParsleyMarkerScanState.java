package parsley;

/**
 * Tracks, incrementally, whether it is currently safe to emit a heat-map position marker
 * ({@code <!--p:N-->}) at the end of the response being built.
 *
 * <p>A marker is an HTML comment, so it is only valid — and only useful for highlighting —
 * inside the document body, at element-content level. It must NOT be emitted:
 * <ul>
 *   <li>before {@code <body>} opens (doctype/{@code <head>}/{@code <title>} — the last is
 *       RCDATA, where a comment renders as literal text);</li>
 *   <li>mid start/end tag (it would land inside an attribute);</li>
 *   <li>inside a {@code <script>}/{@code <style>} raw-text element (a comment there becomes
 *       literal script/CSS text);</li>
 *   <li>inside an authored HTML comment (comments don't nest — our {@code -->} would end the
 *       author's comment early, spilling its content as visible text).</li>
 * </ul>
 *
 * <p><b>Why this class exists.</b> The previous implementation re-decided all of the above on
 * every element by decoding and scanning an 8&nbsp;KB tail of the response (plus two
 * {@code toLowerCase()} allocations). That is O(window) per element and dominated render time
 * on large pages. Because the response is append-only and elements render in document order,
 * the same facts can be maintained <em>incrementally</em>: each character of the response is
 * scanned exactly once, total, across the whole request. {@link #safeHere(CharSequence)}
 * consumes only the characters appended since the previous call, reading them by index
 * straight from the response's live content buffer, so the per-element cost is O(1) amortized
 * with no allocation and no re-encoding.
 *
 * <p>The scan is a deliberately simple state machine, not an HTML parser. Every character it
 * keys on ({@code < > ! - / b o d y s c r i p t t y l e}) is ASCII. It can be fooled by
 * pathological content (e.g. a literal {@code "<script"} inside an attribute value), but the
 * worst outcome is skipping a marker, never corrupting output.
 *
 * <p>Not thread-safe: one instance belongs to one in-flight request (held on the profiler's
 * per-request state and discarded when the request ends).
 */
final class ParsleyMarkerScanState {

	// Whole-document, one-way-ish structural state, updated as bytes stream past.
	private boolean bodyOpened;
	private boolean midTag;      // inside a start/end tag: seen '<' with no matching '>' yet
	private boolean inComment;   // inside an authored <!-- … --> (not one of our own markers)
	private boolean inScript;    // inside <script> … </script>
	private boolean inStyle;     // inside <style> … </style>

	// Set when a <script / <style start tag is seen; consumed when its '>' arrives, at which
	// point we enter the element's raw-text body.
	private boolean pendingScript;
	private boolean pendingStyle;

	// How many characters of the response we have already consumed.
	private int scannedLength;

	/**
	 * Advances the scan over any newly-appended response characters and reports whether a
	 * marker may be emitted at the current end of the response.
	 *
	 * @param content the response's live content buffer (read by index — never copied)
	 */
	boolean safeHere( final CharSequence content ) {
		final int length = content.length();
		if( length > scannedLength ) {
			consume( content, scannedLength, length );
			scannedLength = length;
		}
		return bodyOpened && !midTag && !inComment && !inScript && !inStyle;
	}

	/**
	 * Feeds characters {@code [from, to)} of the response through the state machine. Only
	 * structural characters matter; everything else just advances position. The checks are
	 * ordered so the most constraining context wins (inside a comment, a stray {@code '<'} is
	 * not a tag start).
	 */
	private void consume( final CharSequence b, final int from, final int to ) {
		int i = from;
		final int n = to;
		while( i < n ) {
			if( inComment ) {
				// Only "-->" ends an authored comment.
				if( matches( b, i, "-->" ) ) {
					inComment = false;
					i += 3;
					continue;
				}
				i++;
				continue;
			}
			if( inScript ) {
				if( matchesIgnoreCase( b, i, "</script" ) ) {
					inScript = false;
					i += 8;
					continue;
				}
				i++;
				continue;
			}
			if( inStyle ) {
				if( matchesIgnoreCase( b, i, "</style" ) ) {
					inStyle = false;
					i += 7;
					continue;
				}
				i++;
				continue;
			}

			final char c = b.charAt( i );

			if( c == '<' ) {
				// An authored HTML comment open — but NOT one of our own <!--p: / <!--/p:
				// markers, which are self-closed and don't change comment state.
				if( matches( b, i, "<!--" ) ) {
					if( matches( b, i, "<!--p:" ) || matches( b, i, "<!--/p:" ) ) {
						// Skip our marker whole; it neither opens a comment nor a tag.
						final int end = indexOf( b, i + 4, "-->" );
						i = end == -1 ? n : end + 3;
						continue;
					}
					inComment = true;
					i += 4;
					continue;
				}
				if( matchesIgnoreCase( b, i, "<script" ) ) {
					// We're now mid start-tag; we enter script raw-text mode when it closes ('>').
					midTag = true;
					pendingScript = true;
					i += 7;
					continue;
				}
				if( matchesIgnoreCase( b, i, "<style" ) ) {
					midTag = true;
					pendingStyle = true;
					i += 6;
					continue;
				}
				if( matchesIgnoreCase( b, i, "<body" ) ) {
					bodyOpened = true;
					midTag = true;
					i += 5;
					continue;
				}
				midTag = true;
				i++;
				continue;
			}

			if( c == '>' ) {
				// A tag just closed. If it was a <script>/<style> start tag, we now enter its
				// raw-text body (the pending flag was set when we saw the opener).
				midTag = false;
				if( pendingScript ) {
					inScript = true;
					pendingScript = false;
				}
				if( pendingStyle ) {
					inStyle = true;
					pendingStyle = false;
				}
				i++;
				continue;
			}

			i++;
		}
	}

	private static boolean matches( final CharSequence b, final int at, final String s ) {
		if( at + s.length() > b.length() ) {
			return false;
		}
		for( int k = 0; k < s.length(); k++ ) {
			if( b.charAt( at + k ) != s.charAt( k ) ) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesIgnoreCase( final CharSequence b, final int at, final String s ) {
		if( at + s.length() > b.length() ) {
			return false;
		}
		for( int k = 0; k < s.length(); k++ ) {
			if( lower( b.charAt( at + k ) ) != lower( s.charAt( k ) ) ) {
				return false;
			}
		}
		return true;
	}

	private static int indexOf( final CharSequence b, final int from, final String s ) {
		for( int i = from; i + s.length() <= b.length(); i++ ) {
			if( matches( b, i, s ) ) {
				return i;
			}
		}
		return -1;
	}

	private static char lower( final char c ) {
		return (c >= 'A' && c <= 'Z') ? (char)(c + 32) : c;
	}
}
