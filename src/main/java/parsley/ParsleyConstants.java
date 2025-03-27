package parsley;

public class ParsleyConstants {

	public static final String HERB = unicodeCharWithName( "HERB" );

	/**
	 * @return The unicode character corresponding to the given character name
	 */
	private static String unicodeCharWithName( final String unicodeCharacterName ) {
		final int codePoint = Character.codePointOf( unicodeCharacterName );
		return Character.toString( codePoint );
	}
}