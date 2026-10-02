package parsley;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOComponent;
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
	 * Where the element sits in its template (component, line, column, tag), resolved at
	 * parse time. Null when constructed without one.
	 */
	private final ParsleyTemplatePosition _position;

	public ParsleyProxyElement( final WOElement element, final PNode node ) {
		this( element, node, null );
	}

	public ParsleyProxyElement( final WOElement element, final PNode node, final ParsleyTemplatePosition position ) {
		_wrappedElement = element;
		_node = node;
		_position = position;
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
		// might already have appended something to the response, and it may leave the
		// context mid-render. So we record what we need to roll back to: the response's
		// length (O(1) on the live content buffer — this runs for every wrapped element),
		// and the context's current component and element ID depth.
		final int responseLengthBeforeRender = ParsleyResponseContent.length( response );
		final WOComponent componentBeforeRender = context == null ? null : context.component();
		final int elementIDDepthBeforeRender = context == null ? 0 : elementIDDepth( context );

		try {
			_wrappedElement.appendToResponse( response, context );
		}
		catch( Exception e ) {

			// A component reference only restores the parent as the current component after
			// the child renders AND pushes its bindings back; if either throws, the context is
			// left on the child, and every later binding of the parent would resolve on it.
			// Element ID components appended by unwound dynamic groups would likewise skew
			// every later element ID. Put the context back as we found it.
			restoreContext( context, componentBeforeRender, elementIDDepthBeforeRender );

			// FIXME: we should be adding a mechanism to map exception types to their "handlers", i.e. message generators // Hugi 2025-03-29
			if( e instanceof ParsleyUnknownKeyException uke ) {
				// Dispose of whatever the failing component already rendered.
				ParsleyResponseContent.truncate( response, responseLengthBeforeRender );
				new ParsleyErrorMessageElement( "Binding error", describeElement(), messageforUnknownKeyException( uke ), plainMessageForUnknownKeyException( uke ), e ).appendToResponse( response, context );
			}
			else {
				annotateWithSourceLocation( e );
				throw e;
			}
		}
	}

	/**
	 * @return the number of components in the context's current element ID
	 */
	private static int elementIDDepth( final WOContext context ) {
		final String elementID = context.elementID();
		if( elementID == null || elementID.isEmpty() ) {
			return 0;
		}
		int depth = 1;
		for( int i = 0; i < elementID.length(); i++ ) {
			if( elementID.charAt( i ) == '.' ) {
				depth++;
			}
		}
		return depth;
	}

	/**
	 * Restores the context's current component and element ID depth to what they were
	 * before this element rendered.
	 */
	private static void restoreContext( final WOContext context, final WOComponent component, final int elementIDDepth ) {
		if( context == null ) {
			return;
		}
		if( component != null && context.component() != component ) {
			context._setCurrentComponent( component );
		}
		for( int depth = elementIDDepth( context ); depth > elementIDDepth; depth-- ) {
			context.deleteLastElementIDComponent();
		}
	}

	/**
	 * @return this element as one identifying string, e.g. {@code Main.html:14:9 <wo:str>}
	 */
	private String describeElement() {
		if( _position != null ) {
			return _position.describe();
		}
		return ParsleyTemplatePosition.of( null, null, _node ).describe();
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
			e.addSuppressed( new ParsleySourceLocation( _node, _position ) );
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
		final String suggestion = suggestionFor( e );
		return """
				<strong>UnknownKeyException</strong> in component <strong>%s</strong><br>
				- while <strong>%s</strong> resolved binding <strong>%s</strong> = <strong>%s</strong><br>
				- key <strong>%s</strong><br>
				- was not found on <strong>%s</strong><br>
				<br>
				%s
				<span style="display: inline-block; border-top: 1px solid rgba(255,255,255,0.5); margin-top: 10px; padding-top: 10px; font-size: smaller">%s</span><br>
				""".formatted(
				componentNameOf( e ),
				_wrappedElement.getClass().getSimpleName(),
				e.bindingName(),
				e.keyPath(),
				e.key(),
				objectClassNameOf( e ),
				suggestion == null ? "" : "Did you mean \"<strong>%s</strong>\"?<br>".formatted( suggestion ),
				e.getMessage() );
	}

	/**
	 * @return the unknown-key report as plain text, for development tooling that reads it
	 *         as data — the same facts as the box, without markup
	 */
	private String plainMessageForUnknownKeyException( final ParsleyUnknownKeyException e ) {
		final String suggestion = suggestionFor( e );
		return "Unknown key '%s' in component %s: %s resolved binding %s = $%s, but key '%s' was not found on %s.%s".formatted(
				e.key(),
				componentNameOf( e ),
				_wrappedElement.getClass().getSimpleName(),
				e.bindingName(),
				e.keyPath(),
				e.key(),
				objectClassNameOf( e ),
				suggestion == null ? "" : " Did you mean '%s'?".formatted( suggestion ) );
	}

	/**
	 * @return the closest existing key to the unknown one, or null if there's none
	 */
	private static String suggestionFor( final ParsleyUnknownKeyException e ) {
		if( e.object() == null || e.key() == null ) {
			return null;
		}
		final List<String> suggestions = NGKeyValueCodingSupport.suggestions( e.object(), e.key() );
		return suggestions.isEmpty() ? null : suggestions.getFirst();
	}

	/**
	 * @return the simple name (no package) of the component whose binding failed
	 */
	private static String componentNameOf( final ParsleyUnknownKeyException e ) {
		if( e.component() == null ) {
			return "(unknown)";
		}
		final String name = e.component().name();
		final int lastPeriodIndex = name.lastIndexOf( '.' );
		return lastPeriodIndex == -1 ? name : name.substring( lastPeriodIndex + 1 );
	}

	private static String objectClassNameOf( final ParsleyUnknownKeyException e ) {
		return e.object() == null ? "null" : e.object().getClass().getName();
	}

	@Override
	public void takeValuesFromRequest( WORequest request, WOContext context ) {
		try {
			_wrappedElement.takeValuesFromRequest( request, context );
		}
		catch( Exception e ) {
			annotateWithSourceLocation( e );
			throw e;
		}
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {
		try {
			return _wrappedElement.invokeAction( request, context );
		}
		catch( Exception e ) {
			annotateWithSourceLocation( e );
			throw e;
		}
	}
}