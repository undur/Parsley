package parsley;

import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Exception created by ParsleyProxyAssociation, containing a little more information about the error's context
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

	/**
	 * True if the failure was setting a value (pushing it to the key), false if getting one
	 */
	private final boolean _setting;

	public ParsleyUnknownKeyException( String message, Object object, String key, String keyPath, WOComponent component, String bindingName ) {
		this( message, object, key, keyPath, component, bindingName, false );
	}

	public ParsleyUnknownKeyException( String message, Object object, String key, String keyPath, WOComponent component, String bindingName, boolean setting ) {
		super( message, object, key );
		_keyPath = keyPath;
		_component = component;
		_bindingName = bindingName;
		_setting = setting;
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

	public boolean setting() {
		return _setting;
	}
}