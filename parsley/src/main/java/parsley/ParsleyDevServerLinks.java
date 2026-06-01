package parsley;

/**
 * PROTOTYPE — builds dev-server URLs for opening a template in the IDE, matching
 * the {@code /openComponent} handler that the WOLips/Parsley dev server exposes
 * (default port 9485). Used by the render heat map to make each tree row a
 * click-to-open link.
 *
 * <p>FIXME: This duplicates the URL scheme currently also built in wonder-slim's
 * {@code WOExceptionPage} (openComponentURL). The two will drift. The scheme is a
 * contract between the running app and the Eclipse dev server, so it should live in
 * one shared place that both the exception page and this heat map consume — a good
 * candidate for the IDE-agnostic core. For now we mirror it so the prototype can be
 * felt end-to-end. // 2026-06-01
 */
final class ParsleyDevServerLinks {

	/** Default WOLips/Parsley dev-server port, overridable via the wolips.port property. */
	private static final int DEFAULT_PORT = 9485;

	private ParsleyDevServerLinks() {}

	/**
	 * @param appName       the running application's name (for the {@code app} param)
	 * @param componentName the component's simple name (the dev server resolves it)
	 * @param line          1-based line to reveal, or {@code <= 0} to just open it
	 * @return a bare {@code /openComponent} URL, or null if there isn't enough info
	 */
	static String openComponentURL( final String appName, final String componentName, final int line ) {
		return openComponentURL( appName, componentName, line, -1, 0 );
	}

	/**
	 * As {@link #openComponentURL(String, String, int)}, but able to carry an exact
	 * character {@code offset} (and selection {@code length}) into the HTML template
	 * so the IDE lands the caret precisely on the element rather than just its line.
	 *
	 * @param offset 0-based char offset into the HTML template, or {@code < 0} to omit
	 * @param length characters to select from {@code offset} (0 = caret only)
	 */
	static String openComponentURL( final String appName, final String componentName, final int line, final int offset, final int length ) {
		if( componentName == null || componentName.isEmpty() ) {
			return null;
		}

		final int port = portFromProperty();

		final StringBuilder url = new StringBuilder( "http://localhost:" )
				.append( port )
				.append( "/openComponent?app=" ).append( appName == null ? "" : appName )
				.append( "&component=" ).append( componentName );

		if( line > 0 ) {
			url.append( "&lineNumber=" ).append( line );
		}

		// Precise position: the offset lands the caret exactly on the element; length
		// selects its source span. The handler prefers offset over line when present.
		if( offset >= 0 ) {
			url.append( "&offset=" ).append( offset );
			if( length > 0 ) {
				url.append( "&length=" ).append( length );
			}
		}

		// Classic-WOLips compatibility: include the password only if configured.
		final String password = System.getProperty( "wolips.password" );
		if( password != null ) {
			url.append( "&pw=" ).append( password );
		}

		return url.toString();
	}

	/**
	 * Reads the dev-server port from the {@code wolips.port} system property,
	 * falling back to the default. (The exception page reads it via ERXProperties;
	 * for the prototype a plain system property keeps parsley free of a Wonder
	 * dependency.)
	 */
	private static int portFromProperty() {
		final String value = System.getProperty( "wolips.port" );
		if( value != null ) {
			try {
				return Integer.parseInt( value.trim() );
			}
			catch( final NumberFormatException e ) {
				// fall through to default
			}
		}
		return DEFAULT_PORT;
	}

	/**
	 * @return the 1-based line number containing the given character offset in the
	 *         source, or 0 if it can't be determined. Counts {@code \n} up to the
	 *         offset — the same offset→line resolution the exception page performs.
	 */
	static int lineForOffset( final String source, final int offset ) {
		if( source == null || offset < 0 ) {
			return 0;
		}

		final int cap = Math.min( offset, source.length() );
		int line = 1;
		for( int i = 0; i < cap; i++ ) {
			if( source.charAt( i ) == '\n' ) {
				line++;
			}
		}
		return line;
	}
}
