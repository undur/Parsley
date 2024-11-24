package parsley;

import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WOResponse;

public class ParsleyErrorMessageElement extends WOElement {

	private final String _message;

	public ParsleyErrorMessageElement( final String message ) {
		_message = message;
	}

	@Override
	public void appendToResponse( WOResponse response, WOContext context ) {
		response.appendContentString( """
				<span style="display: inline-block; color: white; background-color: red; padding: 10px; margin: 10px">%s</span>
				""".formatted( u( "HERB" ) + " " + _message ) );
	}

	/**
	 * @return The unicode character corresponding to the given character name
	 */
	private static String u( final String unicodeCharacterName ) {
		final int codePoint = Character.codePointOf( unicodeCharacterName );
		return Character.toString( codePoint ).toString();
	}
}