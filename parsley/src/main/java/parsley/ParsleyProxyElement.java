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

		// An exception can occur in the middle of an element rendering process, i.e. it
		// might already have appended something to the response. So we record the
		// response's length before rendering, letting us truncate back to it on failure
		// (an error message rendered in, say, the middle of a tag attribute value doesn't
		// look good). We read the length from the live content buffer in O(1) — NOT via
		// response.content(), which copies and re-encodes the whole growing response on
		// every call, i.e. O(n²) over a large page (this runs for EVERY wrapped element).
		// Truncation only happens on the rare exception path.
		final int responseLengthBeforeRender = ParsleyRenderProfiler.contentLength( response );

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
				ParsleyRenderProfiler.truncateContent( response, responseLengthBeforeRender );
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
	 * @return true if it is safe to emit a heat-map position marker at the current end of the
	 *         response. A marker is an HTML comment, valid (and useful for highlighting) only
	 *         inside {@code <body>}, at element-content level — not before body opens, not mid
	 *         start/end tag, and not inside a {@code <script>}/{@code <style>} element or an
	 *         authored HTML comment. The decision is maintained incrementally by
	 *         {@link ParsleyRenderProfiler#markerSafeHere} (each response byte scanned once per
	 *         request), so it's O(1) amortized per element with no allocation.
	 */
	private static boolean markersSafeAt( final WOResponse response ) {
		return ParsleyRenderProfiler.markerSafeHere( response );
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