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
 * Used to wrap other elements in a template's element tree, catch exceptions that happen during the wrapped element's appendToResponse() phase and see if the exception can be displayed inline
 */

public class ParsleyProxyElement extends WOElement {

	/**
	 * The element wrapped by this element
	 */
	private final WOElement _wrappedElement;

	/**
	 * The source node of the element.
	 *
	 * FIXME: Currently unused, but stays in since it will give us the reference to the template (and the line number of the parsed token) // Hugi 2025-03-30
	 */
	private final PNode _node;

	public ParsleyProxyElement( final WOElement element, final PNode node ) {
		_wrappedElement = element;
		_node = node;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {

		// An exception can occur in the middle of an element rendering process, i.e. it might already have added something to the response.
		// So. We get a hold of the response's content before the element is rendered, meaning we can throw out whatever it did in case of an exception.
		// Why? Well, an error message element rendered in, for example, the middle of a tag attribute value doesn't actually look that good.
		final String originalResponseContent = response.contentString();

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
				throw e;
			}
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
		_wrappedElement.takeValuesFromRequest( request, context );
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {
		return _wrappedElement.invokeAction( request, context );
	}
}