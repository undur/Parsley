package parsley;

import java.util.List;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import parsley.experimental.NGKeyValueCodingSupport;
import parsley.experimental.UnknownKeyKeyPathException;

public class ParsleyProxyElement extends WOElement {

	/**
	 * The element wrapped by this element
	 */
	private final WOElement _element;

	/**
	 * Keep track of the element currently being rendered (to reference in error messages).
	 *
	 * FIXME: Doing this feels absolutely horrid, but due to WO's "procedural" rendering it actually works. I'd much prefer not to do this some other way though... // Hugi 2025-03-25
	 */
	public static ThreadLocal<WOElement> currentElement = new ThreadLocal<>();

	public ParsleyProxyElement( WOElement element ) {
		_element = element;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
		currentElement.set( this._element );

		// An exception can occur in the middle of an element rendering process, i.e. it might already have added something to the response.
		// So. We get a hold of the response's content before the element is rendered, meaning we can throw out whatever it did in case of an exception.
		// Why? Well, an error message element rendered in, for example, the middle of a tag attribute value doesn't actually look that good.
		final String originalResponseContent = response.contentString();

		try {
			_element.appendToResponse( response, context );
		}
		catch( Exception e ) {
			// Dispose of whatever the failing component already rendered.
			response.setContent( originalResponseContent );

			String message;

			// FIXME: Handling specific exception types would be really, really nice
			if( e instanceof UnknownKeyKeyPathException uke ) {
				message = messageforUnknownKeyException( uke );
			}
			else {
				message = messageForGenericException( e );
			}

			new ParsleyErrorMessageElement( message, e ).appendToResponse( response, context );
		}
	}

	/**
	 * @return The generic exception message for any Exception
	 */
	public String messageForGenericException( final Exception e ) {
		return """
					<strong>%s</strong><br>
					<strong>%s</strong><br>%s
				""".formatted( _element.getClass().getSimpleName(), e.getClass().getName(), e.getMessage() );
	}

	/**
	 * @return An exception message for an unknownKeyException
	 */
	private String messageforUnknownKeyException( final UnknownKeyKeyPathException e ) {

		final List<String> suggestions = NGKeyValueCodingSupport.suggestions( e.object(), e.key() );

		final String suggestionString = suggestions.isEmpty() ? "" : "Did you mean \"<strong>%s</strong>\"?<br>".formatted( suggestions.getFirst() );

		return """
				<strong>UnknownKeyException</strong><br>
				- while <strong>%s</strong> resolved binding <strong>%s</strong> = <strong>%s</strong><br>
				- in component <strong>%s</strong><br>
				- key <strong>%s</strong><br>
				- was not found on <strong>%s</strong><br>
				<br>
				%s
				<stap style="display: inline-block; border-top: 1px solid rgba(255,255,255,0.5); margin-top: 10px; padding-top: 10px; font-size: smaller">%s</span><br>
				""".formatted(
				ParsleyProxyElement.currentElement.get().getClass().getSimpleName(),
				e.bindingName(),
				e.keyPath(),
				e.component().name(),
				e.key(),
				e.object().getClass().getName(),
				suggestionString,
				e.getMessage() );
	}

	@Override
	public void takeValuesFromRequest( WORequest request, WOContext context ) {
		_element.takeValuesFromRequest( request, context );
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {
		return _element.invokeAction( request, context );
	}
}