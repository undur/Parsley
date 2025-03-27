package parsley;

import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Exception thrown by ParsleyKeyValueAssociation, containing a little more information about the error's context
 */

public class ParsleyUnknownKeyException extends NSKeyValueCoding.UnknownKeyException {

	private String _keyPath;
	private WOComponent _component;
	private String _bindingName;

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