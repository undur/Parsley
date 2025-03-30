package parsley;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

/**
 * An element inserted to display an error message
 */

public class ParsleyErrorMessageElement extends WOElement {

	/**
	 * The message to display
	 */
	private final String _message;

	/**
	 * The exception we're showing an error for (if any). Primarily here so we can generate a link allowing the user to see the actual exception page/full stack trace
	 */
	private final Exception _exception;

	public ParsleyErrorMessageElement( final String message ) {
		_message = message;
		_exception = null;
	}

	public ParsleyErrorMessageElement( final String message, final Exception exception ) {
		_message = message;
		_exception = exception;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {

		// If we have an exception, make it clickable
		final String elementName = _exception != null ? "a" : "span";
		final String urlString = _exception != null ? "href=\"%s\"".formatted( context.componentActionURL() ) : "";

		response.appendContentString( """
				<%1$s %3$s style="display: inline-block; font-size: 16px !important; text-align: left !important; color: white; background-color: rgba(255,0,0,0.8); padding: 10px; margin: 10px">
					<span style="display: inline-block; width: 28px; vertical-align: top">%4$s</span>
					<span style="display: inline-block">%2$s</span>
				</%1$s>
				""".formatted( elementName, _message, urlString, ParsleyConstants.HERB ) );

		Parsley.requestObserver.errors.get().add( _message );
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {

		if( context.elementID().equals( context.senderID() ) ) {
			return WOApplication.application().handleException( _exception, context );
		}

		return super.invokeAction( request, context );
	}
}