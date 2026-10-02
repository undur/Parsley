package parsley;

import com.webobjects.appserver.WOActionResults;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;

import ng.dev.NGRuntimeProblems;

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

	/**
	 * What's recorded for development tooling (the problems endpoint): a kind, the element
	 * it concerns, and a plain-text message — read as data by agents, so no markup.
	 */
	private final String _kind;
	private final String _element;
	private final String _plainMessage;

	public ParsleyErrorMessageElement( final String message ) {
		this( "Binding error", "", message, plainText( message ), null );
	}

	public ParsleyErrorMessageElement( final String message, final Exception exception ) {
		this( exception != null ? "Template error" : "Binding error", "", message, plainText( message ), exception );
	}

	/**
	 * @param kind         what went wrong, e.g. "Binding error" or "Template error"
	 * @param element      the element concerned, e.g. {@code Main.html:14:9 <wo:str>}
	 * @param message      the box's message, as HTML
	 * @param plainMessage the same message as plain text, recorded for tooling
	 * @param exception    the underlying exception, if any; makes the box link to its page
	 */
	public ParsleyErrorMessageElement( final String kind, final String element, final String message, final String plainMessage, final Exception exception ) {
		_kind = kind;
		_element = element;
		_message = message;
		_plainMessage = plainMessage;
		_exception = exception;
	}

	/**
	 * @return {@code html} with tags removed, entities for {@code <>&"} decoded and
	 *         whitespace collapsed — a readable fallback when no plain form is given
	 */
	static String plainText( final String html ) {
		if( html == null ) {
			return "";
		}
		return html.replaceAll( "<br\\s*/?>", " " )
				.replaceAll( "<[^>]*>", "" )
				.replace( "&lt;", "<" ).replace( "&gt;", ">" ).replace( "&quot;", "\"" ).replace( "&amp;", "&" )
				.replaceAll( "\\s+", " " )
				.trim();
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

		// Also record into the cross-request buffer so the /problems dev endpoint can serve it back —
		// a tool notices template binding errors without scraping the rendered page. (The errors list
		// above is per-request and cleared at end of request; this store survives across requests.)
		// This buffer lives in ng-core, so both frameworks share one store and one shape.
		NGRuntimeProblems.record( _kind, _element, _plainMessage );
	}

	@Override
	public WOActionResults invokeAction( WORequest request, WOContext context ) {

		if( context.elementID().equals( context.senderID() ) ) {
			return WOApplication.application().handleException( _exception, context );
		}

		return super.invokeAction( request, context );
	}
}