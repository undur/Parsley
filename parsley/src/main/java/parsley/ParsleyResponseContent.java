package parsley;

import java.lang.reflect.Field;

import com.webobjects.appserver.WOMessage;
import com.webobjects.appserver.WOResponse;

/**
 * Cheap, correct access to a response's content while it's being rendered.
 *
 * <p>{@link WOMessage} keeps content in two places: rendered strings accumulate in a
 * {@code StringBuilder}, while {@code setContent(NSData)} clears that and holds bytes in a
 * data buffer instead. {@code content()} and {@code contentString()} answer from the data
 * buffer only while the string buffer is empty. So truncating with
 * {@code setContent(NSData)} and then appending moves the kept prefix out of sight: the
 * response becomes just what was appended afterwards.
 *
 * <p>This class works on the live string buffer directly: an O(1) length and an in-place
 * truncation, with no copy of the growing response (calling {@code content()} per element
 * copies and re-encodes everything rendered so far, which is quadratic over a large page).
 * If content is held as data, it's moved into the string buffer first.
 */
final class ParsleyResponseContent {

	/** WOMessage's string buffer, read reflectively; null if it can't be reached. */
	private static final Field CONTENT_FIELD = contentField();

	private ParsleyResponseContent() {}

	/**
	 * @return the length, in characters, of what's been rendered into the response so far
	 */
	static int length( final WOResponse response ) {
		final StringBuilder buffer = buffer( response );
		return buffer != null ? buffer.length() : response.contentString().length();
	}

	/**
	 * Discards everything after the first {@code length} characters (as measured by
	 * {@link #length}), keeping the response appendable.
	 */
	static void truncate( final WOResponse response, final int length ) {
		final StringBuilder buffer = buffer( response );
		if( buffer != null ) {
			if( buffer.length() > length ) {
				buffer.setLength( length );
			}
			return;
		}
		final String content = response.contentString();
		if( content.length() > length ) {
			response.setContent( content.substring( 0, length ) );
		}
	}

	/**
	 * @return the response's string buffer, holding all of its content — content held as
	 *         data is moved into it first — or null if the buffer can't be reached
	 */
	private static StringBuilder buffer( final WOResponse response ) {
		if( CONTENT_FIELD == null ) {
			return null;
		}
		try {
			StringBuilder buffer = (StringBuilder)CONTENT_FIELD.get( response );
			if( buffer != null && buffer.length() == 0 && response.content().length() > 0 ) {
				// Content is held as data (someone called setContent(NSData)): move it into
				// the string buffer, so length and truncation see all of it.
				response.setContent( response.contentString() );
				buffer = (StringBuilder)CONTENT_FIELD.get( response );
			}
			return buffer;
		}
		catch( final Exception e ) {
			return null;
		}
	}

	private static Field contentField() {
		try {
			final Field field = WOMessage.class.getDeclaredField( "_content" );
			field.setAccessible( true );
			return field.getType() == StringBuilder.class ? field : null;
		}
		catch( final Exception e ) {
			return null;
		}
	}
}
