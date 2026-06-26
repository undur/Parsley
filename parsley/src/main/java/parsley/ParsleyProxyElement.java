package parsley;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSRange;

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

		// An exception can occur in the middle of an element rendering process, i.e. it
		// might already have appended something to the response. So we record the
		// response's length before rendering, letting us truncate back to it on failure
		// (an error message rendered in, say, the middle of a tag attribute value doesn't
		// look good). We capture the byte length only — NOT the full content string —
		// because this runs for EVERY wrapped element, and materializing the whole
		// growing response per element is O(n²) over a large page. Truncation only
		// happens on the rare exception path.
		final int responseLengthBeforeRender = response.content().length();

		final ParsleyRenderProfiler.Frame frame = ParsleyRenderProfiler.enterElement( _node, ParsleyRenderProfiler.Phase.APPEND, _componentName, _line, _bindingsSummary );

		// When profiling, bracket this element's rendered output with HTML comment
		// markers so the heat map's overlay can locate it in the page and highlight
		// it on click. Invisible and layout-neutral — BUT only where an HTML comment
		// is actually valid: inside <body>, at element-content level. Emitting one
		// inside <head>/<title> (RCDATA — comments aren't parsed, so the marker shows
		// as literal text, e.g. in the browser tab) or mid-tag (inside an attribute)
		// corrupts the output. We decide once, from the response state at entry, and
		// use the same decision for the closing marker so the two stay balanced.
		final boolean emitMarkers = frame != null && markersSafeAt( response );

		if( emitMarkers ) {
			response.appendContentString( "<!--p:" + frame.positionId() + "-->" );
		}

		try {
			_wrappedElement.appendToResponse( response, context );
		}
		catch( Exception e ) {

			// FIXME: we should be adding a mechanism to map exception types to their "handlers", i.e. message generators // Hugi 2025-03-29
			if( e instanceof ParsleyUnknownKeyException uke ) {
				// Dispose of whatever the failing component already rendered.
				truncateResponseContent( response, responseLengthBeforeRender );
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
				response.appendContentString( "<!--/p:" + frame.positionId() + "-->" );
			}
			ParsleyRenderProfiler.exitElement( frame );
		}
	}

	/**
	 * How much of the response tail the safety checks look at. Bounded so this is O(1)
	 * per element rather than O(response-size) — the difference between linear and
	 * quadratic over a large page. A start tag / raw-text block / authored comment that
	 * we'd be sitting inside is, in practice, well within this window of the response
	 * end; a pathological larger one would only cause us to <em>skip</em> a marker, never
	 * to corrupt output.
	 */
	private static final int SAFETY_WINDOW = 8192;

	/**
	 * @return true if it is safe to emit an HTML comment marker at the current end of the
	 *         response. An HTML comment is only valid (and only useful for highlighting)
	 *         inside the document body, at element-content level. We require:
	 *         <ul>
	 *           <li>{@code <body} has been opened — excludes the doctype, {@code <head>}
	 *               and {@code <title>} (RCDATA, where a comment renders as literal text);</li>
	 *           <li>we're not mid start/end tag (a comment would land inside an attribute);</li>
	 *           <li>we're not inside a {@code <script>}/{@code <style>} raw-text element
	 *               or an authored HTML comment.</li>
	 *         </ul>
	 *
	 * <p>All checks but the {@code <body>} latch read only a bounded {@link #SAFETY_WINDOW}
	 * tail of the response, so this is O(1) per element. A deliberately simple scan with
	 * no parser — it can be fooled by pathological content, but at worst skips a marker.
	 */
	private static boolean markersSafeAt( final WOResponse response ) {
		final NSData content = response.content();
		final int length = content.length();
		if( length == 0 ) {
			return false;
		}

		// "Are we inside <body>?" is a one-way latch per request — once body has opened
		// it stays open until </body>, which only the page root emits at the very end.
		// Caching it on the profiler avoids re-scanning the response for "<body" on every
		// one of thousands of elements (which would be O(n²) over a page render). Until
		// it's latched we only need to see whether the tail contains the <body open —
		// the document's <head> is small, so the open arrives within the window.
		final String tail = tailString( content, SAFETY_WINDOW );

		if( !ParsleyRenderProfiler.bodyHasOpened() ) {
			if( tail.indexOf( "<body" ) == -1 ) {
				return false;
			}
			ParsleyRenderProfiler.markBodyOpened();
		}

		// Are we mid-tag (inside an attribute)? An open '<' with no later '>' in the tail
		// means we'd be dropping a comment inside a tag.
		if( tail.lastIndexOf( '<' ) > tail.lastIndexOf( '>' ) ) {
			return false;
		}

		// Are we inside a raw-text element (<script> / <style>)? Like <title>, these
		// don't parse HTML comments — a marker dropped here becomes literal script or
		// CSS text. We're inside one if the most recent opening tag of either kind has no
		// matching close after it.
		if( insideRawTextElement( tail, "script" ) || insideRawTextElement( tail, "style" ) ) {
			return false;
		}

		// Are we inside an author's HTML comment? HTML comments DON'T nest: emitting our
		// <!--p:N--> inside <!-- … --> makes the browser end the comment at our
		// marker's "-->", spilling the rest of the author's comment as visible text. Our
		// own markers never trip this — they're self-balanced — so only an unclosed
		// authored "<!--" matches.
		if( insideOpenComment( tail ) ) {
			return false;
		}

		return true;
	}

	/**
	 * @return the last {@code maxChars} characters of the response content, decoded as
	 *         UTF-8. Reads only a bounded tail of the byte buffer — never the whole
	 *         (growing) response — so the marker-safety check stays O(1) per element.
	 */
	private static String tailString( final NSData content, final int maxChars ) {
		final int length = content.length();
		final int from = Math.max( 0, length - maxChars );
		return new String( content.bytes( from, length - from ), java.nio.charset.StandardCharsets.UTF_8 );
	}

	/**
	 * @return true if the content so far ends inside an unclosed <em>authored</em>
	 *         HTML comment.
	 *
	 * <p>We must ignore our own {@code <!--p:N-->} markers when scanning: they
	 * are self-closed, so a naive "last {@code <!--}" would land on one of our own
	 * markers (which has its own {@code -->}) and wrongly report "not in a comment",
	 * masking an authored {@code <!--} that opened earlier and is still unclosed.
	 * So we walk authored comment opens/closes only, tracking depth.
	 */
	private static boolean insideOpenComment( final String tail ) {
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
			if( tail.startsWith( "<!--p:", nextOpen ) || tail.startsWith( "<!--/p:", nextOpen ) ) {
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
	 * @return true if the given response tail ends inside an open {@code <tag>…} raw-text
	 *         element (i.e. the last {@code <tag} opener has no {@code </tag>} after it).
	 *         Case-insensitive on the tag name.
	 *
	 * <p>The caller passes a bounded tail of the response (not the whole growing
	 * response), so this stays cheap per element. The window comfortably exceeds any
	 * realistic inline {@code <script>}/{@code <style>} block; a script larger than the
	 * window simply won't suppress markers in its trailing part — a harmless cosmetic
	 * edge, not a correctness problem.
	 */
	private static boolean insideRawTextElement( final String tail, final String tag ) {
		final String lower = tail.toLowerCase();
		final int lastOpen = lower.lastIndexOf( "<" + tag );
		if( lastOpen == -1 ) {
			return false;
		}
		return lower.indexOf( "</" + tag, lastOpen ) == -1;
	}

	/**
	 * Truncates the response's content back to the given byte length, discarding
	 * anything appended after it (used to roll back a partially-rendered failed element).
	 */
	private static void truncateResponseContent( final WOResponse response, final int length ) {
		final NSData content = response.content();
		if( content.length() > length ) {
			response.setContent( content.subdataWithRange( new NSRange( 0, length ) ) );
		}
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