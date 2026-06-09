package parsley;

import com.webobjects.appserver.WOAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

/**
 * A decorating association factory: it produces nothing itself, it delegates to a real
 * factory and wraps whatever that factory returns in a {@link ParsleyProxyAssociation}.
 *
 * <p>This is the binding-layer counterpart of how {@link ParsleyProxyElement} wraps the
 * element factory's output: proxying is <em>Parsley's</em> responsibility, applied by
 * composing this decorator over the registered factory — the wrapped factory (the
 * default one, the OGNL one, or any custom factory registered via
 * {@link Parsley#register(ParsleyAssociationFactory)}) never knows it's being proxied,
 * and a user's custom factory gets exception decoration for free.
 *
 * <p>This factory is an <em>unconditional</em> decorator: every association it produces
 * is wrapped. Whether proxying is wanted at all is decided in {@link Parsley} (which
 * owns the inline-errors flag) — when off, {@code Parsley} simply uses the registered
 * factory directly and never installs this one. Keeping the policy out of here lets the
 * decorator stay a pure "wrap whatever I delegate to". Constants and every other
 * association kind are wrapped uniformly — the cost of the extra indirection is
 * immaterial because proxying only runs in development.
 */
public class ParsleyProxyAssociationFactory implements ParsleyAssociationFactory {

	/**
	 * The real factory whose associations we wrap. Never itself a proxy factory.
	 */
	private final ParsleyAssociationFactory _delegate;

	public ParsleyProxyAssociationFactory( final ParsleyAssociationFactory delegate ) {
		_delegate = delegate;
	}

	/**
	 * @return the underlying factory this decorator delegates to (unwrapped).
	 */
	ParsleyAssociationFactory delegate() {
		return _delegate;
	}

	@Override
	public WOAssociation associationForBindingValue( final String bindingName, final NGBindingValue bindingValue, final boolean isInline ) {
		final WOAssociation association = _delegate.associationForBindingValue( bindingName, bindingValue, isInline );
		return new ParsleyProxyAssociation( association, bindingName );
	}
}
