package parsley;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver._private.WOKeyValueAssociation;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Used instead of WOKeyValueAssociation when inline rendering error display is active.
 * Allows us to grab and report exceptions that happen during pulling of bindings.
 */

public class ParsleyKeyValueAssociation extends WOKeyValueAssociation {

	/**
	 * Keep track of the binding name for debugging
	 */
	private String _bindingName;

	public ParsleyKeyValueAssociation( String keyPath ) {
		super( keyPath );
	}

	@Override
	public Object valueInComponent( WOComponent component ) {
		try {
			return super.valueInComponent( component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			throw new ParsleyUnknownKeyException( uke.getMessage(), uke.object(), uke.key(), keyPath(), component, bindingName() );
		}
	}

	/**
	 * CHECKME: I'd much prefer to set this during the association's construction, but for that we'll have to change the association construction process a little // Hugi 2025-03-30
	 */
	public void setBindingName( final String bindingName ) {
		_bindingName = bindingName;
	}

	public String bindingName() {
		return _bindingName;
	}
}