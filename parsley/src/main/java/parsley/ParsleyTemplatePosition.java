package parsley;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * Where an element sits in its template: the component and the 1-based line and column
 * of its start tag, plus a short rendering of the tag itself. Resolved once at parse time,
 * while the template source is at hand, so error reports and stack traces can point at the
 * template without re-reading it.
 *
 * @param componentName the component's simple name (no package), e.g. {@code Main}
 * @param line          1-based line of the element's start tag, or 0 if unknown
 * @param column        1-based column of the element's start tag, or 0 if unknown
 * @param tag           the element's tag, e.g. {@code <wo:str>}, or null if unknown
 */
public record ParsleyTemplatePosition( String componentName, int line, int column, String tag ) {

	/**
	 * @return the position of {@code node} within {@code templateSource}, the template of
	 *         the component named {@code referenceName} (package prefix is dropped)
	 */
	static ParsleyTemplatePosition of( final String referenceName, final String templateSource, final PNode node ) {
		final SourceRange range = node == null ? null : node.sourceRange();
		int line = 0;
		int column = 0;
		if( range != null && range.start() >= 0 && templateSource != null && range.start() <= templateSource.length() ) {
			line = 1;
			int lineStart = 0;
			for( int i = 0; i < range.start(); i++ ) {
				if( templateSource.charAt( i ) == '\n' ) {
					line++;
					lineStart = i + 1;
				}
			}
			column = range.start() - lineStart + 1;
		}
		return new ParsleyTemplatePosition( simpleName( referenceName ), line, column, tagOf( node ) );
	}

	/**
	 * @return {@code Main.html:14:9} — the file, line and column — falling back to whatever
	 *         is known
	 */
	public String location() {
		final StringBuilder b = new StringBuilder( componentName == null ? "(unknown component)" : componentName + ".html" );
		if( line > 0 ) {
			b.append( ':' ).append( line );
			if( column > 0 ) {
				b.append( ':' ).append( column );
			}
		}
		return b.toString();
	}

	/**
	 * @return {@code Main.html:14:9 <wo:str>} — location and tag, as one identifying string
	 */
	public String describe() {
		return tag == null ? location() : location() + " " + tag;
	}

	private static String tagOf( final PNode node ) {
		return node instanceof PBasicNode b ? "<" + b.namespace() + ":" + b.type() + ">" : null;
	}

	private static String simpleName( final String referenceName ) {
		if( referenceName == null ) {
			return null;
		}
		final int dot = referenceName.lastIndexOf( '.' );
		return dot == -1 ? referenceName : referenceName.substring( dot + 1 );
	}
}
