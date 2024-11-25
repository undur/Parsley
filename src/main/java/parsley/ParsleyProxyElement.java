package parsley;

import com.webobjects.appserver.WOElement;

public class ParsleyProxyElement extends WOElement {

	private final WOElement _element;

	public ParsleyProxyElement( WOElement element ) {
		_element = element;
	}

	@Override
	public void appendToResponse( com.webobjects.appserver.WOResponse response, com.webobjects.appserver.WOContext context ) {
		try {
			_element.appendToResponse( response, context );
		}
		catch( Exception e ) {
			new ParsleyErrorMessageElement( e.getMessage() ).appendToResponse( response, context );
		}
	}

	@Override
	public void takeValuesFromRequest( com.webobjects.appserver.WORequest request, com.webobjects.appserver.WOContext context ) {
		_element.takeValuesFromRequest( request, context );
	}

	@Override
	public com.webobjects.appserver.WOActionResults invokeAction( com.webobjects.appserver.WORequest request, com.webobjects.appserver.WOContext context ) {
		return _element.invokeAction( request, context );
	}
}