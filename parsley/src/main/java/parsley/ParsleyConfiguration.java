package parsley;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for a Parsley registration: the association factory, the
 * per-namespace element factories, and whether inline error display is enabled. Built
 * via {@link Parsley#configure()} and installed atomically by
 * {@link ParsleyConfiguration.Builder#register()}, so there's no window where Parsley
 * is half-configured.
 *
 * <p>The {@code wo} namespace always has the default element factory; a builder may add
 * more namespaces or replace the association factory. Inline error display defaults off.
 */
public final class ParsleyConfiguration {

	private final ParsleyAssociationFactory _associationFactory;
	private final Map<String, ParsleyElementFactory> _elementFactories;
	private final boolean _inlineErrors;
	private final boolean _controls;

	private ParsleyConfiguration( final ParsleyAssociationFactory associationFactory, final Map<String, ParsleyElementFactory> elementFactories, final boolean inlineErrors, final boolean controls ) {
		_associationFactory = associationFactory;
		_elementFactories = Map.copyOf( elementFactories );
		_inlineErrors = inlineErrors;
		_controls = controls;
	}

	/**
	 * @return the default configuration — the default association factory, the {@code wo}
	 *         element factory, and inline errors off. Used as Parsley's initial
	 *         configuration so it's never unconfigured/null, and as the seed the first
	 *         {@link Parsley#configure()} amends.
	 */
	static ParsleyConfiguration defaultConfiguration() {
		return new ParsleyConfiguration(
				new ParsleyDefaultAssociationFactory(),
				Map.of( "wo", new ParsleyDefaultElementFactory() ),
				false,
				false );
	}

	/**
	 * @return a builder pre-set for development: inline error display and the dev
	 *         controls strip are on. The framework (ERExtensions) starts from this in
	 *         development mode. Override anything you like before {@code register()}.
	 */
	public static ParsleyConfiguration.Builder defaultDevConfiguration() {
		return new Builder( defaultConfiguration() ).inlineErrors( true ).controls( true );
	}

	/**
	 * @return a builder pre-set for production: all development features off. The
	 *         framework starts from this in production mode.
	 */
	public static ParsleyConfiguration.Builder defaultProductionConfiguration() {
		return new Builder( defaultConfiguration() ).inlineErrors( false ).controls( false );
	}

	/**
	 * The association factory to build associations with — wrapped in a
	 * {@link ParsleyProxyAssociationFactory} when inline errors are enabled, so the
	 * caller's factory is decorated uniformly without knowing it.
	 */
	ParsleyAssociationFactory associationFactory() {
		return _inlineErrors
				? new ParsleyProxyAssociationFactory( _associationFactory )
				: _associationFactory;
	}

	ParsleyElementFactory elementFactoryForNamespace( final String namespace ) {
		final ParsleyElementFactory factory = _elementFactories.get( namespace );

		if( factory == null ) {
			throw new IllegalStateException( "No element factory registered for namespace '%s'".formatted( namespace ) );
		}

		return factory;
	}

	Set<String> dynamicNamespaces() {
		return _elementFactories.keySet();
	}

	boolean inlineErrors() {
		return _inlineErrors;
	}

	boolean controls() {
		return _controls;
	}

	/**
	 * @return true if this configuration needs the request observer installed — i.e. it
	 *         has a feature that rewrites the response (the inline-error overlay or the
	 *         dev controls strip). When nothing needs it, the observer is removed so it
	 *         doesn't run per-request. (The heat-map build ORs in the render profiler.)
	 */
	boolean needsRequestObserver() {
		return _inlineErrors || _controls;
	}

	/**
	 * Fluent builder for a {@link ParsleyConfiguration}. Obtain via
	 * {@link Parsley#configure()} — which seeds the builder from the <em>current</em>
	 * configuration, so setting a value amends rather than resets (adding an element
	 * factory keeps the existing ones; changing inline errors keeps the factories).
	 * Call {@link #register()} to make the result the active configuration.
	 */
	public static final class Builder {

		private ParsleyAssociationFactory _associationFactory;
		private final Map<String, ParsleyElementFactory> _elementFactories;
		private boolean _inlineErrors;
		private boolean _controls;

		/**
		 * Seeds the builder from an existing configuration so changes are amendments to
		 * it. {@link Parsley#configure()} passes the current configuration here.
		 */
		Builder( final ParsleyConfiguration base ) {
			_associationFactory = base._associationFactory;
			_elementFactories = new HashMap<>( base._elementFactories );
			_inlineErrors = base._inlineErrors;
			_controls = base._controls;
		}

		/**
		 * Sets the association factory used to build associations from binding values.
		 * Defaults to {@link ParsleyDefaultAssociationFactory}.
		 */
		public Builder associationFactory( final ParsleyAssociationFactory associationFactory ) {
			_associationFactory = Objects.requireNonNull( associationFactory );
			return this;
		}

		/**
		 * Registers an element factory for the given namespace, in addition to the
		 * default {@code wo} factory. Elements using this namespace in templates will be
		 * created by the given factory.
		 */
		public Builder elementFactory( final String namespace, final ParsleyElementFactory elementFactory ) {
			Objects.requireNonNull( namespace );
			Objects.requireNonNull( elementFactory );
			_elementFactories.put( namespace, elementFactory );
			return this;
		}

		/**
		 * Enables inline display of exceptions that happen during rendering (in addition
		 * to missing-element / element-creation errors). Off by default.
		 */
		public Builder inlineErrors( final boolean value ) {
			_inlineErrors = value;
			return this;
		}

		/**
		 * Enables the development controls strip — a small expanding control in the
		 * page's bottom-left corner for toggling Parsley's dev features at runtime. Off
		 * by default; on in {@link ParsleyConfiguration#defaultDevConfiguration()}.
		 */
		public Builder controls( final boolean value ) {
			_controls = value;
			return this;
		}

		/**
		 * Builds the configuration and installs it as the active Parsley registration,
		 * atomically. See {@link Parsley#register(ParsleyConfiguration)}.
		 */
		public void register() {
			Parsley.register( new ParsleyConfiguration( _associationFactory, _elementFactories, _inlineErrors, _controls ) );
		}
	}
}
