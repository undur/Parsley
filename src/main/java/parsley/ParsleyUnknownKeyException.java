package parsley;

import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Exception thrown by ParsleyKeyValueAssociation, containing a little more information about the error's context
 */

public class ParsleyUnknownKeyException extends NSKeyValueCoding.UnknownKeyException {

	/**
	 * The actual keyPath that's being resolved when the exception is thrown
	 */
	private final String _keyPath;

	/**
	 * The component the entire keyPath is being resolved against
	 */
	private final WOComponent _component;

	/**
	 * The name of the binding trying to resolve the keyPath
	 */
	private final String _bindingName;

	public ParsleyUnknownKeyException( String message, Object object, String key, String keyPath, WOComponent component, String bindingName ) {
		super( message, object, key );
		_keyPath = keyPath;
		_component = component;
		_bindingName = bindingName;
	}

	public WOComponent component() {
		return _component;
	}

	public String keyPath() {
		return _keyPath;
	}

	public String bindingName() {
		return _bindingName;
	}
}