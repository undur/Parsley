package parsley;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

public class ParsleyProxyElement extends WOElement {

	private final WOElement _element;

	public ParsleyProxyElement( WOElement element ) {
		_element = element;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
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

			final String message = "<strong>%s</strong><br>%s".formatted( _element.getClass().getSimpleName(), e.getMessage() );
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