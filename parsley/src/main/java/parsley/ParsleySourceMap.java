package parsley;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROTOTYPE — the render source map (docs/render-source-map.md): per element, the character
 * ranges its output occupies in the served page, relative to {@code <body}, plus the page
 * text itself from {@code <body} on. The overlay fetches it lazily (via
 * {@link ParsleyControlsAction#sourceMapAction()}) and maps ranges onto the live DOM, so
 * elements can be located without any markers in the page.
 *
 * <p>Kept server-side for the few most recent profiled requests only, keyed by an
 * unguessable token handed to that page's overlay. Development tooling: bounded, in-memory,
 * lost on restart.
 */
final class ParsleySourceMap {

	/** How many recent pages' maps are retained. Each holds its page text. */
	private static final int RETAINED = 4;

	private static final SecureRandom RANDOM = new SecureRandom();

	private record PageMap( String bodyText, ParsleyRenderProfiler.TreeNode root ) {}

	private static final Map<String, PageMap> _entries = new LinkedHashMap<>( 8, 0.75f, true ) {
		@Override
		protected boolean removeEldestEntry( final Map.Entry<String, PageMap> eldest ) {
			return size() > RETAINED;
		}
	};

	private ParsleySourceMap() {}

	/**
	 * Retains the map for a rendered page.
	 *
	 * @param content the final response text, before the overlay is injected
	 * @return the token to fetch it with, or null if the page has no {@code <body}
	 */
	static String store( final ParsleyRenderProfiler.Result result, final String content ) {
		final int body = content.indexOf( "<body" );
		if( body < 0 ) {
			return null;
		}
		final byte[] bytes = new byte[16];
		RANDOM.nextBytes( bytes );
		final String token = HexFormat.of().formatHex( bytes );
		synchronized( _entries ) {
			_entries.put( token, new PageMap( content.substring( body ), result.root() ) );
		}
		return token;
	}

	/**
	 * @return the map as JSON — {@code {"text": "<body…", "ranges": {"id": [start, end, …]}}},
	 *         offsets being indices into {@code text} — or null for an unknown/expired token
	 */
	static String json( final String token ) {
		final PageMap entry;
		synchronized( _entries ) {
			entry = token == null ? null : _entries.get( token );
		}
		if( entry == null ) {
			return null;
		}
		final StringBuilder b = new StringBuilder( entry.bodyText().length() + entry.bodyText().length() / 2 );
		b.append( "{\"text\":" );
		appendJSONString( b, entry.bodyText() );
		b.append( ",\"ranges\":{" );
		final boolean[] first = { true };
		appendRanges( b, entry.root(), first );
		b.append( "}}" );
		return b.toString();
	}

	private static void appendRanges( final StringBuilder b, final ParsleyRenderProfiler.TreeNode node, final boolean[] first ) {
		if( node.rangeCount() > 0 ) {
			if( !first[0] ) {
				b.append( ',' );
			}
			first[0] = false;
			b.append( '"' ).append( node.id() ).append( "\":[" );
			for( int i = 0; i < node.rangeCount(); i++ ) {
				if( i > 0 ) {
					b.append( ',' );
				}
				b.append( node.rangeStart( i ) ).append( ',' ).append( node.rangeEnd( i ) );
			}
			b.append( ']' );
		}
		for( final ParsleyRenderProfiler.TreeNode child : node.children() ) {
			appendRanges( b, child, first );
		}
	}

	private static void appendJSONString( final StringBuilder b, final String s ) {
		b.append( '"' );
		for( int i = 0; i < s.length(); i++ ) {
			final char c = s.charAt( i );
			switch( c ) {
				case '"' -> b.append( "\\\"" );
				case '\\' -> b.append( "\\\\" );
				case '\n' -> b.append( "\\n" );
				case '\r' -> b.append( "\\r" );
				case '\t' -> b.append( "\\t" );
				default -> {
					if( c < 0x20 || c == ' ' || c == ' ' ) {
						b.append( String.format( "\\u%04x", (int)c ) );
					}
					else {
						b.append( c );
					}
				}
			}
		}
		b.append( '"' );
	}
}
