package parsley;

import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;

/**
 * A marker carrying the source-template position of the element that was
 * rendering when an exception was thrown.
 *
 * <p>This is attached to a render-time exception as a <em>suppressed</em>
 * throwable by {@link ParsleyProxyElement}, so that downstream exception
 * handling (e.g. an exception page in development) can map the failure back to
 * the exact spot in the template source.
 *
 * <h2>Why a suppressed Throwable?</h2>
 *
 * The position has to ride <em>on</em> the original exception, not wrap it.
 * Exception-handling code commonly "unwraps" an exception to its root cause by
 * walking {@code getCause()} (Wonder's {@code ERXExceptionUtilities.originalThrowable()}
 * does exactly this), so a wrapping carrier exception would be unwrapped away
 * before the handler ever saw it. Suppressed throwables, by contrast, are never
 * touched by cause-walking — they survive intact and are readable via
 * {@link Throwable#getSuppressed()}.
 *
 * <p>This also keeps the attachment <b>non-destructive</b>: the original
 * exception's type, message, cause chain, and stack trace are all untouched, so
 * the Java stack trace shown on an error page is unaffected.
 *
 * <p>It is a {@link Throwable} only because that is the type the JDK's
 * suppressed-exception mechanism requires. It is a data marker, not an error —
 * so it fills in no stack trace of its own.
 */
public final class ParsleySourceLocation extends Throwable {

	/**
	 * The range in the source template that the failing element was parsed from.
	 */
	private final transient SourceRange _sourceRange;

	/**
	 * The parsed node of the failing element. Held in addition to the range
	 * because it carries the element's type and bindings, useful for richer
	 * diagnostics later.
	 */
	private final transient PNode _node;
	private final transient ParsleyTemplatePosition _position;

	public ParsleySourceLocation( final PNode node ) {
		this( node, null );
	}

	/**
	 * @param position where the element sits in its template, if known. It becomes the
	 *        message, so a printed stack trace reads e.g.
	 *        {@code Suppressed: parsley.ParsleySourceLocation: at Main.html:14:9 (<wo:str>)}.
	 */
	public ParsleySourceLocation( final PNode node, final ParsleyTemplatePosition position ) {
		// No cause, no suppression, and — crucially — no writable stack trace: this is a
		// position marker, not a thrown error, so filling in a stack trace would be wasted
		// work. The message carries the location, so it shows wherever the trace is printed.
		super( message( node, position ), null, false, false );
		_node = node;
		_sourceRange = node == null ? null : node.sourceRange();
		_position = position;
	}

	private static String message( final PNode node, final ParsleyTemplatePosition position ) {
		final ParsleyTemplatePosition p = position != null ? position : ParsleyTemplatePosition.of( null, null, node );
		if( position != null ) {
			return p.tag() == null ? "at " + p.location() : "at " + p.location() + " (" + p.tag() + ")";
		}
		final SourceRange range = node == null ? null : node.sourceRange();
		final String where = range == null ? "at unknown position" : "at template offset " + range.start();
		return p.tag() == null ? where : where + " (" + p.tag() + ")";
	}

	/**
	 * @return where the element sits in its template, or null if unknown
	 */
	public ParsleyTemplatePosition position() {
		return _position;
	}

	/**
	 * @return The source range (start/end character offsets into the template
	 *         string) of the failing element, or null if unavailable.
	 */
	public SourceRange sourceRange() {
		return _sourceRange;
	}

	/**
	 * @return The parsed node of the failing element, or null if unavailable.
	 */
	public PNode node() {
		return _node;
	}

	/**
	 * Finds the {@link ParsleySourceLocation} already attached to the given
	 * throwable as a suppressed exception, if any.
	 *
	 * @param throwable the throwable to inspect (may be null)
	 * @return the attached location, or null if none is present
	 */
	public static ParsleySourceLocation attachedTo( final Throwable throwable ) {
		if( throwable == null ) {
			return null;
		}

		for( final Throwable suppressed : throwable.getSuppressed() ) {
			if( suppressed instanceof ParsleySourceLocation location ) {
				return location;
			}
		}

		return null;
	}
}
