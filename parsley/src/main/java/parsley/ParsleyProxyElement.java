package parsley;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import ng.appserver.templating.parser.model.PNode;
import ng.kvc.NGKeyValueCodingSupport;

/**
 * Used to wrap other elements in a template's element tree, catch exceptions
 * thrown by the wrapped element during any of the three request phases
 * (appendToResponse, takeValuesFromRequest, invokeAction) and annotate them with
 * the element's source position, so an error page can map the failure back to
 * the template source.
 *
 * <p>{@code appendToResponse} additionally tries to render certain exceptions
 * (unknown-key) inline as an error message element — that only makes sense
 * mid-render, so the take-values and invoke-action phases simply annotate the
 * exception and rethrow.
 */

public class ParsleyProxyElement extends WOElement {

	/**
	 * The element wrapped by this element
	 */
	private final WOElement _wrappedElement;

	/**
	 * The source node of the element — gives us the element's position in the
	 * template source (via {@link PNode#sourceRange()}). Used to annotate
	 * exceptions thrown during this element's render with their template
	 * location, so an error page can map the failure back to the source.
	 */
	private final PNode _node;

	/**
	 * PROTOTYPE — the simple name of the component this element was parsed from, and
	 * the 1-based source line of the element within that component's template.
	 * Carried so the render heat map can build a click-to-open-in-IDE link for the
	 * element (via the dev server's /openComponent handler). Resolved once at parse
	 * time (we have the template source then) rather than per render. // 2026-06-01
	 */
	private final String _componentName;
	private final int _line;
	private final String _bindingsSummary;

	public ParsleyProxyElement( final WOElement element, final PNode node ) {
		this( element, node, null, 0, null );
	}

	public ParsleyProxyElement( final WOElement element, final PNode node, final String componentName, final int line, final String bindingsSummary ) {
		_wrappedElement = element;
		_node = node;
		_componentName = componentName;
		_line = line;
		_bindingsSummary = bindingsSummary;
	}

	/**
	 * @return The element wrapped by this proxy.
	 */
	WOElement wrappedElement() {
		return _wrappedElement;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {

		// An exception can occur in the middle of an element rendering process, i.e. it might already have added something to the response.
		// So. We get a hold of the response's content before the element is rendered, meaning we can throw out whatever it did in case of an exception.
		// Why? Well, an error message element rendered in, for example, the middle of a tag attribute value doesn't actually look that good.
		final String originalResponseContent = response.contentString();

		final ParsleyRenderProfiler.Frame frame = ParsleyRenderProfiler.enterElement( _node, ParsleyRenderProfiler.Phase.APPEND, _componentName, _line, _bindingsSummary );

		// When profiling, bracket this element's rendered output with HTML comment
		// markers so the heat map's overlay can locate it in the page and highlight
		// it on click. Invisible and layout-neutral — BUT only where an HTML comment
		// is actually valid: inside <body>, at element-content level. Emitting one
		// inside <head>/<title> (RCDATA — comments aren't parsed, so the marker shows
		// as literal text, e.g. in the browser tab) or mid-tag (inside an attribute)
		// corrupts the output. We decide once, from the response state at entry, and
		// use the same decision for the closing marker so the two stay balanced.
		final boolean emitMarkers = frame != null && markersSafeAt( originalResponseContent );

		if( emitMarkers ) {
			response.appendContentString( "<!--parsley:" + frame.positionId() + "-->" );
		}

		try {
			_wrappedElement.appendToResponse( response, context );
		}
		catch( Exception e ) {

			// FIXME: we should be adding a mechanism to map exception types to their "handlers", i.e. message generators // Hugi 2025-03-29
			if( e instanceof ParsleyUnknownKeyException uke ) {
				// Dispose of whatever the failing component already rendered.
				response.setContent( originalResponseContent );
				String message = messageforUnknownKeyException( uke );
				new ParsleyErrorMessageElement( message, e ).appendToResponse( response, context );
			}
			else {
				annotateWithSourceLocation( e );
				throw e;
			}
		}
		finally {
			if( emitMarkers ) {
				response.appendContentString( "<!--/parsley:" + frame.positionId() + "-->" );
			}
			ParsleyRenderProfiler.exitElement( frame );
		}
	}

	/**
	 * @return true if it is safe to emit an HTML comment marker at the current end
	 *         of the response, given everything rendered so far.
	 *
	 * <p>An HTML comment is only valid (and only useful for highlighting) inside the
	 * document body, at element-content level. We require:
	 * <ul>
	 *   <li>{@code <body} has been opened — excludes the doctype, {@code <head>} and
	 *       {@code <title>} (the last being RCDATA, where a comment renders as
	 *       literal text — the browser-tab leak);</li>
	 *   <li>{@code </body>} has not yet been emitted — excludes trailing scripts;</li>
	 *   <li>we are not in the middle of a start/end tag — i.e. the last {@code <}
	 *       has a matching {@code >} after it, so we're not about to drop a comment
	 *       inside an attribute value.</li>
	 * </ul>
	 *
	 * <p>This is a deliberately simple string scan over the response-so-far. It can
	 * be fooled by pathological content (e.g. a literal "&lt;body" inside a script),
	 * but for the dev-only heat map that's an acceptable trade for having no parser.
	 */
	private static boolean markersSafeAt( final String contentSoFar ) {
		if( contentSoFar == null || contentSoFar.isEmpty() ) {
			return false;
		}

		// "Are we inside <body>?" is a one-way latch per request — once body has
		// opened it stays open until </body>, which only the page root emits at the
		// very end. Caching it on the profiler avoids re-scanning the (growing)
		// response string for "<body" on every one of thousands of elements, which
		// would be O(n²) over a page render.
		if( !ParsleyRenderProfiler.bodyHasOpened() ) {
			if( contentSoFar.indexOf( "<body" ) == -1 ) {
				return false;
			}
			ParsleyRenderProfiler.markBodyOpened();
		}

		// Cheap local check only: are we mid-tag (inside an attribute)? Look at just
		// the tail of the content, not the whole string — an open '<' with no later
		// '>' means we'd be dropping a comment inside a tag. A short suffix is enough
		// because a start tag is never longer than a few hundred chars in practice.
		final int window = Math.min( contentSoFar.length(), 512 );
		final String tail = contentSoFar.substring( contentSoFar.length() - window );
		if( tail.lastIndexOf( '<' ) > tail.lastIndexOf( '>' ) ) {
			return false;
		}

		// Are we inside a raw-text element (<script> / <style>)? Like <title>, these
		// don't parse HTML comments — a marker dropped here becomes literal script or
		// CSS text (and can corrupt it). We're inside one if the most recent opening
		// tag of either kind has no matching close after it.
		if( insideRawTextElement( contentSoFar, "script" ) || insideRawTextElement( contentSoFar, "style" ) ) {
			return false;
		}

		// Are we inside an author's HTML comment (e.g. a commented-out block of
		// markup the parser still walks)? HTML comments DON'T nest: emitting our
		// <!--parsley:N--> inside <!-- … --> makes the browser end the comment at our
		// marker's "-->", spilling the rest of the author's comment as visible text.
		// We're inside one if the last comment-open in the tail has no "-->" after it.
		// Our own markers never trip this — they're always self-balanced (each carries
		// its own "-->"), so only an unclosed authored "<!--" matches.
		if( insideOpenComment( contentSoFar ) ) {
			return false;
		}

		return true;
	}

	/**
	 * @return true if the content so far ends inside an unclosed <em>authored</em>
	 *         HTML comment.
	 *
	 * <p>We must ignore our own {@code <!--parsley:N-->} markers when scanning: they
	 * are self-closed, so a naive "last {@code <!--}" would land on one of our own
	 * markers (which has its own {@code -->}) and wrongly report "not in a comment",
	 * masking an authored {@code <!--} that opened earlier and is still unclosed.
	 * So we walk authored comment opens/closes only, tracking depth.
	 */
	private static boolean insideOpenComment( final String content ) {
		final int window = Math.min( content.length(), 65_536 );
		final String tail = content.substring( content.length() - window );

		int i = 0;
		boolean open = false;
		while( i < tail.length() ) {
			final int nextOpen = tail.indexOf( "<!--", i );
			final int nextClose = tail.indexOf( "-->", i );

			if( nextOpen == -1 && nextClose == -1 ) {
				break;
			}

			// A close before the next open: closes the current authored comment.
			if( nextClose != -1 && (nextOpen == -1 || nextClose < nextOpen) ) {
				open = false;
				i = nextClose + 3;
				continue;
			}

			// An open. Skip our own self-closed markers entirely — they aren't
			// authored comments and don't change comment state.
			if( tail.startsWith( "<!--parsley:", nextOpen ) || tail.startsWith( "<!--/parsley:", nextOpen ) ) {
				final int markerEnd = tail.indexOf( "-->", nextOpen + 4 );
				i = markerEnd == -1 ? tail.length() : markerEnd + 3;
				continue;
			}

			// An authored comment open.
			open = true;
			i = nextOpen + 4;
		}

		return open;
	}

	/**
	 * @return true if the content so far ends inside an open {@code <tag>…} raw-text
	 *         element (i.e. the last {@code <tag} opener has no {@code </tag>} after
	 *         it). Case-insensitive on the tag name.
	 *
	 * <p>Operates on a bounded tail of the content rather than the whole (growing)
	 * response, so it stays cheap per element. The window comfortably exceeds any
	 * realistic inline {@code <script>}/{@code <style>} block; a script larger than
	 * the window simply won't suppress markers in its trailing part, which is a
	 * harmless cosmetic edge, not a correctness problem for the page.
	 */
	private static boolean insideRawTextElement( final String content, final String tag ) {
		final int window = Math.min( content.length(), 65_536 );
		final String tail = content.substring( content.length() - window ).toLowerCase();
		final int lastOpen = tail.lastIndexOf( "<" + tag );
		if( lastOpen == -1 ) {
			return false;
		}
		return tail.indexOf( "</" + tag, lastOpen ) == -1;
	}

	/**
	 * Annotates the exception with this element's source position so an error
	 * page can map the failure back to the template source. We attach it as a
	 * suppressed throwable (it survives cause-unwrapping and doesn't alter the
	 * original exception). Only the innermost proxy — the one wrapping the
	 * actually-failing element — attaches a location; as the exception propagates
	 * up through outer proxies, they see one is already present and leave it be.
	 */
	private void annotateWithSourceLocation( final Exception e ) {
		if( _node != null && ParsleySourceLocation.attachedTo( e ) == null ) {
			e.addSuppressed( new ParsleySourceLocation( _node ) );
		}
	}

	/**
	 * @return The generic exception message for any Exception
	 */
	//	private String messageForGenericException( final Exception e ) {
	//
	//		final String classSimpleName = _element.getClass().getSimpleName();
	//		final String exceptionClassName = e.getClass().getName();
	//		final String exceptionMessage = e.getMessage();
	//
	//		return """
	//					<strong>%s</strong><br>
	//					<strong>%s</strong><br>%s
	//				""".formatted( classSimpleName, exceptionClassName, exceptionMessage );
	//	}

	/**
	 * @return An exception message for an unknownKeyException
	 */
	private String messageforUnknownKeyException( final ParsleyUnknownKeyException e ) {

		// Generate a key suggestion
		final List<String> suggestions = NGKeyValueCodingSupport.suggestions( e.object(), e.key() );
		final String suggestionString = suggestions.isEmpty() ? "" : "Did you mean \"<strong>%s</strong>\"?<br>".formatted( suggestions.getFirst() );

		// Remove the java package name if present in the component name
		String componentName = e.component().name();

		final int lastPeriodIndex = componentName.lastIndexOf( '.' );

		if( lastPeriodIndex != -1 ) {
			componentName = componentName.substring( lastPeriodIndex + 1 );
		}

		return """
				<strong>UnknownKeyException</strong> in component <strong>%s</strong><br>
				- while <strong>%s</strong> resolved binding <strong>%s</strong> = <strong>%s</strong><br>
				- key <strong>%s</strong><br>
				- was not found on <strong>%s</strong><br>
				<br>
				%s
				<stap style="display: inline-block; border-top: 1px solid rgba(255,255,255,0.5); margin-top: 10px; padding-top: 10px; font-size: smaller">%s</span><br>
				""".formatted(
				componentName,
				_wrappedElement.getClass().getSimpleName(),
				e.bindingName(),
				e.keyPath(),
				e.key(),
				e.object().getClass().getName(),
				suggestionString,
				e.getMessage() );
	}

	@Override
	public void takeValuesFromRequest( WORequest request, WOContext context ) {
		final ParsleyRenderProfiler.Frame frame = ParsleyRenderProfiler.enterElement( _node, ParsleyRenderProfiler.Phase.TAKE_VALUES, _componentName, _line, _bindingsSummary );
		try {
			_wrappedElement.takeValuesFromRequest( request, context );
		}
		catch( Exception e ) {
			annotateWithSourceLocation( e );
			throw e;
		}
		finally {
			ParsleyRenderProfiler.exitElement( frame );
		}
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {
		final ParsleyRenderProfiler.Frame frame = ParsleyRenderProfiler.enterElement( _node, ParsleyRenderProfiler.Phase.INVOKE_ACTION, _componentName, _line, _bindingsSummary );
		try {
			return _wrappedElement.invokeAction( request, context );
		}
		catch( Exception e ) {
			annotateWithSourceLocation( e );
			throw e;
		}
		finally {
			ParsleyRenderProfiler.exitElement( frame );
		}
	}
}