package parsley;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.parser.WOComponentTemplateParser;
import com.webobjects.foundation.NSNotificationCenter;
import com.webobjects.foundation.NSSelector;
import com.webobjects.foundation._NSUtilities;

/**
 * Entry point and active configuration for the Parsley template library. Configure and
 * install Parsley with the fluent builder:
 *
 * <pre>{@code
 * Parsley.configure()
 *     .associationFactory( myFactory )   // optional; defaults to ParsleyDefaultAssociationFactory
 *     .elementFactory( "my", myFactory ) // optional; the "wo" namespace is always present
 *     .inlineErrors( true )              // optional; off by default
 *     .register();
 * }</pre>
 *
 * <p>{@link #register(ParsleyConfiguration)} installs the configuration atomically —
 * the parser ({@link ParsleyTemplateParser}) is registered with WO and the request
 * observer wired up in one step, so there's no window where Parsley is half-configured.
 * The parser reads the active configuration from here while building templates.
 */
public class Parsley {

	private static final Logger logger = LoggerFactory.getLogger( Parsley.class );

	/**
	 * Watches requests and handles rewriting of the response when required (inline
	 * error overlay, and — on the heat-map build — the render profiler overlay). Wired
	 * to the request notification once, at registration.
	 */
	public static ParsleyRequestObserver requestObserver = new ParsleyRequestObserver();

	/**
	 * The active configuration. Initialized to a default so Parsley is never
	 * unconfigured; {@link #register(ParsleyConfiguration)} replaces it.
	 */
	private static ParsleyConfiguration _configuration = ParsleyConfiguration.defaultConfiguration();

	/**
	 * @return a configuration builder seeded from the <em>current</em> configuration, so
	 *         setting a value amends rather than resets — e.g. an app can add an element
	 *         factory after the framework's base registration without losing it:
	 *
	 *         <pre>{@code Parsley.configure().elementFactory( "html", f ).register();}</pre>
	 *
	 *         Call {@code register()} on the returned builder to make it the active
	 *         configuration.
	 */
	public static ParsleyConfiguration.Builder configure() {
		return new ParsleyConfiguration.Builder( _configuration );
	}

	/**
	 * Installs the given configuration as the active Parsley registration: registers the
	 * template parser with WO and installs (or removes) the request observer to match the
	 * configuration. Replaces any previous registration. Normally reached via
	 * {@link #configure()}{@code .….register()}.
	 *
	 * <p>The request observer is installed only when the configuration needs it (a
	 * feature that rewrites the response is active) and removed otherwise, so when no
	 * such feature is on it doesn't run per request at all.
	 */
	public static void register( final ParsleyConfiguration configuration ) {
		_configuration = configuration;

		WOComponentTemplateParser.setWOHTMLTemplateParserClassName( ParsleyTemplateParser.class.getName() );
		registerControlsActionClass();

		// Drive the render profiler's master switch from the configuration.
		ParsleyRenderProfiler.setEnabled( configuration.renderProfiler() );

		// Start clean, then install only if needed — so toggling a feature off actually
		// removes the observer rather than leaving it firing on every request.
		NSNotificationCenter.defaultCenter().removeObserver( requestObserver );
		if( configuration.needsRequestObserver() ) {
			NSNotificationCenter.defaultCenter().addObserver(
					requestObserver,
					new NSSelector<>( "didHandleRequest", new Class[] { com.webobjects.foundation.NSNotification.class } ),
					WOApplication.ApplicationDidDispatchRequestNotification, null );
		}

		logger.info( "Sprinkled some fresh Parsley on your templates. Inline errors: {}, render profiler: {}", configuration.inlineErrors(), configuration.renderProfiler() );
	}

	/**
	 * Registers {@link ParsleyControlsAction} in WO's name→class table so the direct
	 * action handler can resolve it. WO only looks for action classes in <em>bundles</em>
	 * (frameworks/apps) — not plain library jars like Parsley — so without this explicit
	 * registration the controls action would be "class not found".
	 */
	private static void registerControlsActionClass() {
		_NSUtilities.setClassForName( ParsleyControlsAction.class, ParsleyControlsAction.class.getSimpleName() );
	}

	/**
	 * @return the effective association factory (proxy-wrapped when inline errors are on)
	 */
	static ParsleyAssociationFactory effectiveAssociationFactory() {
		return _configuration.associationFactory();
	}

	/**
	 * @return the namespaces with a registered element factory (the dynamic-tag namespaces)
	 */
	static Set<String> dynamicNamespaces() {
		return _configuration.dynamicNamespaces();
	}

	/**
	 * @return the element factory registered for the given namespace
	 */
	static ParsleyElementFactory elementFactoryForNamespace( final String namespace ) {
		return _configuration.elementFactoryForNamespace( namespace );
	}

	/**
	 * @return true if inline display of template errors is active
	 */
	public static boolean showInlineErrorMessages() {
		return _configuration.inlineErrors();
	}

	/**
	 * @return true if the development controls strip is active
	 */
	public static boolean showControls() {
		return _configuration.controls();
	}

	/**
	 * @return true if the render heat map (profiler) is active
	 */
	public static boolean showRenderProfiler() {
		return _configuration.renderProfiler();
	}

	/**
	 * @return whether the active configuration needs the request observer installed.
	 *         Package-private, for tests that verify the observer install/remove logic.
	 */
	static boolean activeConfigurationNeedsObserver() {
		return _configuration.needsRequestObserver();
	}

	/**
	 * @return true if elements should be wrapped in a {@link ParsleyProxyElement}.
	 *         Wrapping is the seam for both inline error display and the render profiler,
	 *         so either feature being active turns it on.
	 */
	public static boolean shouldWrapElements() {
		return showInlineErrorMessages() || showRenderProfiler();
	}

	/**
	 * @return true if the given element may be wrapped in a {@link ParsleyProxyElement},
	 *         i.e. it's not excluded from wrapping by the active configuration.
	 */
	static boolean shouldWrapElement( final WOElement element ) {
		return _configuration.shouldWrapElement( element );
	}
}
