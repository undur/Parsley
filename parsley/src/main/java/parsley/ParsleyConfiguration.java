package parsley;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.webobjects.appserver.WOElement;

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

	/**
	 * Element simple-name that's always excluded from proxy wrapping. ERXWOTemplate
	 * depends on being the immediate child element of its wrapper component, which a
	 * proxy wrapper would break — so this is a correctness requirement, not a user
	 * preference. Matched by simple name because the class lives in ERExtensions, which
	 * Parsley doesn't depend on.
	 */
	private static final String ALWAYS_EXCLUDED_SIMPLE_NAME = "ERXWOTemplate";

	private final ParsleyAssociationFactory _associationFactory;
	private final Map<String, ParsleyElementFactory> _elementFactories;
	private final boolean _inlineErrors;
	private final boolean _controls;
	private final boolean _renderProfiler;
	private final Set<Class<?>> _wrappingExclusionsByClass;
	private final Set<String> _wrappingExclusionsBySimpleName;

	private ParsleyConfiguration( final ParsleyAssociationFactory associationFactory, final Map<String, ParsleyElementFactory> elementFactories, final boolean inlineErrors, final boolean controls, final boolean renderProfiler, final Set<Class<?>> wrappingExclusionsByClass, final Set<String> wrappingExclusionsBySimpleName ) {
		_associationFactory = associationFactory;
		_elementFactories = Map.copyOf( elementFactories );
		_inlineErrors = inlineErrors;
		_controls = controls;
		_renderProfiler = renderProfiler;
		_wrappingExclusionsByClass = Set.copyOf( wrappingExclusionsByClass );
		_wrappingExclusionsBySimpleName = Set.copyOf( wrappingExclusionsBySimpleName );
	}

	/**
	 * @return the default configuration — the default association factory, the {@code wo}
	 *         element factory, inline errors off, and the built-in wrapping exclusion for
	 *         {@link #ALWAYS_EXCLUDED_SIMPLE_NAME}. Used as Parsley's initial
	 *         configuration so it's never unconfigured/null, and as the seed the first
	 *         {@link Parsley#configure()} amends.
	 */
	static ParsleyConfiguration defaultConfiguration() {
		return new ParsleyConfiguration(
				new ParsleyDefaultAssociationFactory(),
				Map.of( "wo", new ParsleyDefaultElementFactory() ),
				false,
				false,
				false,
				Set.of(),
				Set.of( ALWAYS_EXCLUDED_SIMPLE_NAME ) );
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

	/**
	 * @return true if the given element should be wrapped in a {@link ParsleyProxyElement}
	 *         — i.e. it isn't excluded from wrapping (by class or by simple name). Some
	 *         elements can't be proxied (see {@link #ALWAYS_EXCLUDED_SIMPLE_NAME}), and an
	 *         app may exclude its own via {@link Builder#excludeFromWrapping}.
	 */
	boolean shouldWrapElement( final WOElement element ) {
		return !_wrappingExclusionsByClass.contains( element.getClass() )
				&& !_wrappingExclusionsBySimpleName.contains( element.getClass().getSimpleName() );
	}

	boolean controls() {
		return _controls;
	}

	boolean renderProfiler() {
		return _renderProfiler;
	}

	/**
	 * @return true if this configuration needs the request observer installed — i.e. it
	 *         has a feature that rewrites the response (the inline-error overlay, the dev
	 *         controls strip, or the render heat-map overlay). When nothing needs it, the
	 *         observer is removed so it doesn't run per-request.
	 */
	boolean needsRequestObserver() {
		return _inlineErrors || _controls || _renderProfiler;
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
		private boolean _renderProfiler;
		private final Set<Class<?>> _wrappingExclusionsByClass;
		private final Set<String> _wrappingExclusionsBySimpleName;

		/**
		 * Seeds the builder from an existing configuration so changes are amendments to
		 * it. {@link Parsley#configure()} passes the current configuration here.
		 */
		Builder( final ParsleyConfiguration base ) {
			_associationFactory = base._associationFactory;
			_elementFactories = new HashMap<>( base._elementFactories );
			_inlineErrors = base._inlineErrors;
			_controls = base._controls;
			_renderProfiler = base._renderProfiler;
			_wrappingExclusionsByClass = new HashSet<>( base._wrappingExclusionsByClass );
			_wrappingExclusionsBySimpleName = new HashSet<>( base._wrappingExclusionsBySimpleName );
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
		 * Enables the render heat map (profiler) — times each template position's render
		 * and overlays the result. Like inline errors and the controls strip, it relies on
		 * elements being wrapped in {@link ParsleyProxyElement}. Off by default.
		 */
		public Builder renderProfiler( final boolean value ) {
			_renderProfiler = value;
			return this;
		}

		/**
		 * Excludes elements of the given class from being wrapped in a
		 * {@link ParsleyProxyElement}. Use this for elements that can't function inside a
		 * proxy. Adds to any existing exclusions.
		 */
		public Builder excludeFromWrapping( final Class<? extends WOElement> elementClass ) {
			_wrappingExclusionsByClass.add( Objects.requireNonNull( elementClass ) );
			return this;
		}

		/**
		 * Excludes elements whose class has the given simple name from being wrapped in a
		 * {@link ParsleyProxyElement}. The simple-name form is for classes Parsley can't
		 * reference directly (e.g. classes in frameworks it doesn't depend on). Adds to
		 * any existing exclusions.
		 */
		public Builder excludeFromWrapping( final String elementClassSimpleName ) {
			_wrappingExclusionsBySimpleName.add( Objects.requireNonNull( elementClassSimpleName ) );
			return this;
		}

		/**
		 * Builds the configuration and installs it as the active Parsley registration,
		 * atomically. See {@link Parsley#register(ParsleyConfiguration)}.
		 */
		public void register() {
			Parsley.register( new ParsleyConfiguration( _associationFactory, _elementFactories, _inlineErrors, _controls, _renderProfiler, _wrappingExclusionsByClass, _wrappingExclusionsBySimpleName ) );
		}
	}
}
