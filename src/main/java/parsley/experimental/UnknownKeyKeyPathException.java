package parsley.experimental;

import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Exception thrown by ParsleyKeyValueAssociation, containing a little more information about the error's context
 */

public class UnknownKeyKeyPathException extends NSKeyValueCoding.UnknownKeyException {

	private String _keyPath;
	private WOComponent _component;

	public UnknownKeyKeyPathException( String message, Object object, String key, String keyPath, WOComponent component ) {
		super( message, object, key );
		_keyPath = keyPath;
		_component = component;
	}

	public WOComponent component() {
		return _component;
	}

	public String keyPath() {
		return _keyPath;
	}
}