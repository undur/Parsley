package parsley;

/**
 * Thrown if an element is not found and inline error messages are disabled
 */

public class ParsleyElementNotFoundException extends RuntimeException {

	public ParsleyElementNotFoundException( String message ) {
		super( message );
	}
}