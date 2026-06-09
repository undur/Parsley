package parsley;

/**
 * A marker carrying the identity of the binding that was being resolved when an
 * exception was thrown — the "which binding triggered this" companion to
 * {@link ParsleySourceLocation}'s "where in the template did it happen".
 *
 * <p>Attached to a render-time exception as a <em>suppressed</em> throwable by
 * {@link ParsleyProxyAssociation} when an exception escapes
 * {@code valueInComponent}. Where {@code ParsleySourceLocation} tells an
 * exception page <em>which element</em> failed, this tells it <em>which binding
 * on that element</em> was being pulled — e.g. that the failure happened while
 * resolving {@code value="$propValue"}, not just somewhere in the element.
 *
 * <h2>Why a suppressed Throwable?</h2>
 *
 * Same reasoning as {@link ParsleySourceLocation}: the binding identity has to
 * ride <em>on</em> the original exception, not wrap it. Exception-handling code
 * commonly unwraps to the root cause by walking {@code getCause()} (Wonder's
 * {@code ERXExceptionUtilities.originalThrowable()} does exactly this), which
 * would discard a wrapping carrier before the handler saw it. Suppressed
 * throwables survive cause-walking and are readable via
 * {@link Throwable#getSuppressed()}, and the attachment is non-destructive — the
 * original exception's type, message, cause chain, and stack trace are untouched.
 *
 * <p>It is a {@link Throwable} only because that is the type the JDK's
 * suppressed-exception mechanism requires. It is a data marker, not an error —
 * so it fills in no stack trace of its own.
 */
public final class ParsleyBindingLocation extends Throwable {

	/**
	 * The binding name (the attribute on the element, e.g. {@code value} in
	 * {@code value="$propValue"}), or null if unknown.
	 */
	private final transient String _bindingName;

	/**
	 * The key path being resolved (e.g. {@code propValue}), or null if unknown.
	 */
	private final transient String _keyPath;

	public ParsleyBindingLocation( final String bindingName, final String keyPath ) {
		// No message, no cause, no suppression, and no writable stack trace:
		// this is a data marker, not a thrown error.
		super( null, null, false, false );
		_bindingName = bindingName;
		_keyPath = keyPath;
	}

	/**
	 * @return The binding name (element attribute), or null if unknown.
	 */
	public String bindingName() {
		return _bindingName;
	}

	/**
	 * @return The key path that was being resolved, or null if unknown.
	 */
	public String keyPath() {
		return _keyPath;
	}

	/**
	 * Finds the {@link ParsleyBindingLocation} already attached to the given
	 * throwable as a suppressed exception, if any.
	 *
	 * @param throwable the throwable to inspect (may be null)
	 * @return the attached location, or null if none is present
	 */
	public static ParsleyBindingLocation attachedTo( final Throwable throwable ) {
		if( throwable == null ) {
			return null;
		}

		for( final Throwable suppressed : throwable.getSuppressed() ) {
			if( suppressed instanceof ParsleyBindingLocation location ) {
				return location;
			}
		}

		return null;
	}
}
