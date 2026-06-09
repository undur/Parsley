package parsley;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

/**
 * Wraps another {@link WOAssociation} to catch exceptions thrown while pulling or
 * pushing a binding's value, and decorate them with the binding's identity (its name
 * and key path) so an error page can map the failure back to the template source.
 *
 * <p>This is the binding-layer twin of {@link ParsleyProxyElement}: exception handling
 * is the <em>proxy's</em> responsibility, in one place, for <em>every</em> kind of
 * association — key-value, binding-name ({@code ^foo}), negated ({@code !foo}), OGNL,
 * or any custom association a registered factory produces. Previously this lived only
 * in a special {@code ParsleyKeyValueAssociation} subtype, so the same exception thrown
 * by, say, a {@code WOBindingNameAssociation} got no decoration. Wrapping uniformly
 * fixes that: whatever the factory returns is wrapped, and the wrapper decorates.
 *
 * <p>Decoration is <b>generic</b> — it reads everything it needs from the thrown
 * exception itself ({@link NSKeyValueCoding.UnknownKeyException} already carries the
 * failing object and key) plus the wrapped association's {@link #keyPath()} — so the
 * proxy never needs to know what kind of association it wraps. (If a future association
 * type needs decoration only it can produce, an opt-in hook can be added then; nothing
 * needs it today.)
 *
 * <p>The proxy faithfully delegates the entire {@link WOAssociation} surface to the
 * wrapped instance, so it is behaviourally identical except for the added decoration —
 * value resolution, settability, constness, and the abstract {@code keyPath()} /
 * {@code bindingInComponent()} all pass straight through to the real association.
 */
public class ParsleyProxyAssociation extends WOAssociation {

	/**
	 * The association wrapped by this proxy. All real behaviour is delegated to it.
	 */
	private final WOAssociation _wrappedAssociation;

	/**
	 * The name of the binding this association resolves (the {@code value} in
	 * {@code value="$x"}). Always known at construction — it's the binding's key in
	 * the element's declaration — and used to tell an error page which binding failed.
	 */
	private final String _bindingName;

	public ParsleyProxyAssociation( final WOAssociation wrappedAssociation, final String bindingName ) {
		_wrappedAssociation = wrappedAssociation;
		_bindingName = bindingName;
	}

	/**
	 * @return the association wrapped by this proxy.
	 */
	WOAssociation wrappedAssociation() {
		return _wrappedAssociation;
	}

	public String bindingName() {
		return _bindingName;
	}

	// =========================================================================
	// Value pull / push — wrapped to decorate exceptions
	// =========================================================================

	@Override
	public Object valueInComponent( final WOComponent component ) {
		try {
			return _wrappedAssociation.valueInComponent( component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			throw decorateUnknownKey( uke, component );
		}
		catch( RuntimeException e ) {
			attachBindingLocation( e );
			throw e;
		}
	}

	@Override
	public void setValue( final Object value, final WOComponent component ) {
		try {
			_wrappedAssociation.setValue( value, component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			throw decorateUnknownKey( uke, component );
		}
		catch( RuntimeException e ) {
			attachBindingLocation( e );
			throw e;
		}
	}

	/**
	 * Turns a raw {@link NSKeyValueCoding.UnknownKeyException} into the richer
	 * {@link ParsleyUnknownKeyException} (used by {@link ParsleyProxyElement} for inline
	 * error display), carrying the failing object/key (from the exception itself), the
	 * key path (from the wrapped association), the component, and this binding's name.
	 */
	private ParsleyUnknownKeyException decorateUnknownKey( final NSKeyValueCoding.UnknownKeyException uke, final WOComponent component ) {
		final ParsleyUnknownKeyException puke = new ParsleyUnknownKeyException( uke.getMessage(), uke.object(), uke.key(), keyPath(), component, _bindingName );
		attachBindingLocation( puke );
		return puke;
	}

	/**
	 * Attaches this binding's identity to the given throwable as a suppressed
	 * {@link ParsleyBindingLocation}, unless one is already present.
	 *
	 * <p>The innermost-wins guard mirrors {@link ParsleyProxyElement}: the association
	 * closest to the actual failure annotates it; as the exception propagates outward,
	 * anything that already carries a binding location leaves it be.
	 */
	private void attachBindingLocation( final Throwable throwable ) {
		if( ParsleyBindingLocation.attachedTo( throwable ) == null ) {
			throwable.addSuppressed( new ParsleyBindingLocation( _bindingName, keyPath() ) );
		}
	}

	// =========================================================================
	// Full WOAssociation surface — faithful delegation
	// =========================================================================

	@Override
	public boolean booleanValueInComponent( final WOComponent component ) {
		try {
			return _wrappedAssociation.booleanValueInComponent( component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			throw decorateUnknownKey( uke, component );
		}
		catch( RuntimeException e ) {
			attachBindingLocation( e );
			throw e;
		}
	}

	@Override
	public boolean isValueSettable() {
		return _wrappedAssociation.isValueSettable();
	}

	@Override
	public boolean isValueConstant() {
		return _wrappedAssociation.isValueConstant();
	}

	@Override
	public boolean isValueSettableInComponent( final WOComponent component ) {
		return _wrappedAssociation.isValueSettableInComponent( component );
	}

	@Override
	public boolean isValueConstantInComponent( final WOComponent component ) {
		return _wrappedAssociation.isValueConstantInComponent( component );
	}

	@Override
	public String keyPath() {
		return _wrappedAssociation.keyPath();
	}

	@Override
	public String bindingInComponent( final WOComponent component ) {
		return _wrappedAssociation.bindingInComponent( component );
	}

	@Override
	public String toString() {
		return "ParsleyProxyAssociation[binding=%s, wrapped=%s]".formatted( _bindingName, _wrappedAssociation );
	}
}
