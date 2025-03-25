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
	 * The element currently being rendered
	 */
	public static WOElement currentElement;

	public ParsleyProxyElement( WOElement element ) {
		_element = element;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
		currentElement = this._element;

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
				List<String> suggestions = NGKeyValueCodingSupport.suggestions( uke.object(), uke.key() );

				message = """
						Key <strong>%s</strong><br>
						not found on <strong>%s</strong><br>
						while <strong>%s</strong> resolved keypath <strong>%s</strong><br>
						on component <strong>%s</strong><br>
						<br>
						Did you mean <strong>%s</strong>?<br>
						<stap style="display: inline-block; border-top: 1px solid rgba(255,255,255,0.5); margin-top: 10px; padding-top: 10px; font-size: smaller">%s</span><br>
						""".formatted( uke.key(), uke.object().getClass().getName(), ParsleyProxyElement.currentElement.getClass().getSimpleName(), uke.keyPath(), uke.component().name(), suggestions.getFirst(), uke.getMessage() );
			}
			else {
				message = """
							<strong>%s</strong><br>
							<strong>%s</strong><br>%s
						""".formatted( _element.getClass().getSimpleName(), e.getClass().getName(), e.getMessage() );
			}

			new ParsleyErrorMessageElement( message, e ).appendToResponse( response, context );
		}
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