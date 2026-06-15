package parsley;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.parser.WOComponentTemplateParser;
import com.webobjects.foundation.NSNotificationCenter;
import com.webobjects.foundation.NSSelector;

/**
 * Entry point and configuration for the Parsley template library. Registers the
 * template parser ({@link ParsleyTemplateParser}) with WO, holds the association and
 * element factories, and exposes the inline-error/profiling switches. The parsing
 * itself lives in {@link ParsleyTemplateParser}, which reads its configuration from
 * here.
 */
public class Parsley {

	private static final Logger logger = LoggerFactory.getLogger( Parsley.class );

	/**
	 * Indicates if we want to enable inline display of exceptions that happen during rendering (in addition to missing element/element creation errors)
	 */
	private static boolean _showInlineErrorMessages;

	/**
	 * Watches requests and handles rewriting of the response when required
	 */
	public static ParsleyRequestObserver requestObserver = new ParsleyRequestObserver();

	/**
	 * The association factory actually used to build associations. Either the
	 * registered factory as-is (when inline errors are off), or that factory wrapped in
	 * a ParsleyProxyAssociationFactory (when inline errors are on).
	 */
	private static ParsleyAssociationFactory _associationFactory;

	/**
	 * The raw factory passed to {@link #register(ParsleyAssociationFactory)}, kept so we
	 * can re-derive {@link #_associationFactory} if inline errors are toggled after
	 * registration.
	 */
	private static ParsleyAssociationFactory _registeredAssociationFactory;

	/**
	 * Maps namespace names to element factories responsible for generating elements in that namespace
	 */
	private static final Map<String, ParsleyElementFactory> _elementFactories = new HashMap<>();

	/**
	 * Registers Parsley as the template parser for use in a WO project, with the default association factory
	 */
	public static void register() {
		register( new ParsleyDefaultAssociationFactory() );
	}

	/**
	 * Registers Parsley as the template parser for use in a WO project, using the given association factory
	 */
	public static void register( final ParsleyAssociationFactory associationFactory ) {
		WOComponentTemplateParser.setWOHTMLTemplateParserClassName( ParsleyTemplateParser.class.getName() );

		_registeredAssociationFactory = associationFactory;
		updateEffectiveAssociationFactory();
		_elementFactories.put( "wo", new ParsleyDefaultElementFactory() );
		logger.info( "Sprinkled some fresh Parsley on your templates. Using association factory '%s'".formatted( associationFactory.getClass().getName() ) );
	}

	/**
	 * Effective association factory from the registered one: wrapped in a
	 * ParsleyProxyAssociationFactory if inline errors are enabled, otherwise the
	 * registered factory used as-is.
	 *
	 * FIXME: Stopgap until Parsley registration process is redesigned // Hugi 2026-06-09
	 */
	private static void updateEffectiveAssociationFactory() {
		if( _registeredAssociationFactory == null ) {
			return;
		}
		_associationFactory = showInlineErrorMessages()
				? new ParsleyProxyAssociationFactory( _registeredAssociationFactory )
				: _registeredAssociationFactory;
	}

	/**
	 * @return the effective association factory (proxy-wrapped or bare)
	 */
	static ParsleyAssociationFactory effectiveAssociationFactory() {
		return _associationFactory;
	}

	/**
	 * Registers an element factory for the given namespace.
	 * Elements using this namespace in templates will be created using the given factory.
	 */
	public static void registerElementFactory( final String namespace, final ParsleyElementFactory elementFactory ) {
		Objects.requireNonNull( namespace );
		Objects.requireNonNull( elementFactory );
		_elementFactories.put( namespace, elementFactory );
	}

	/**
	 * @return The namespaces with a registered element factory (the dynamic-tag namespaces)
	 */
	static Set<String> dynamicNamespaces() {
		return _elementFactories.keySet();
	}

	/**
	 * @return The element factory registered for the given namespace
	 */
	static ParsleyElementFactory elementFactoryForNamespace( final String namespace ) {
		final ParsleyElementFactory factory = _elementFactories.get( namespace );

		if( factory == null ) {
			throw new IllegalStateException( "No element factory registered for namespace '%s'".formatted( namespace ) );
		}

		return factory;
	}

	/**
	 * Indicates if we want to enable inline display of exceptions that happen during rendering (in addition to missing element/element creation errors)
	 */
	public static void showInlineRenderingErrors( boolean value ) {
		_showInlineErrorMessages = value;

		updateEffectiveAssociationFactory();

		if( value ) {
			NSNotificationCenter.defaultCenter().addObserver(
					requestObserver,
					new NSSelector<>( "didHandleRequest", new Class[] { com.webobjects.foundation.NSNotification.class } ),
					WOApplication.ApplicationDidDispatchRequestNotification, null );

			logger.info( "Enabled inline exception messages for template rendering" );
		}
		else {
			logger.info( "Disabled inline exception messages for template rendering" );
		}
	}

	/**
	 * @return true if inline display of template errors is active
	 */
	public static boolean showInlineErrorMessages() {
		return _showInlineErrorMessages;
	}

	/**
	 * PROTOTYPE — enables/disables the inline render heat map (see
	 * {@link ParsleyRenderProfiler}). Like inline error messages, the heat map
	 * relies on elements being wrapped in {@link ParsleyProxyElement} (that's the
	 * timing seam), so this also turns wrapping on via {@link #shouldWrapElements()}.
	 * It registers the same request observer used for inline errors, which renders
	 * the overlay once the response is complete. // 2026-06-01
	 */
	public static void showRenderProfiler( boolean value ) {
		ParsleyRenderProfiler.setEnabled( value );

		if( value ) {
			NSNotificationCenter.defaultCenter().addObserver(
					requestObserver,
					new NSSelector<>( "didHandleRequest", new Class[] { com.webobjects.foundation.NSNotification.class } ),
					WOApplication.ApplicationDidDispatchRequestNotification, null );

			logger.info( "Enabled inline render heat map (prototype)" );
		}
		else {
			logger.info( "Disabled inline render heat map (prototype)" );
		}
	}

	/**
	 * @return true if elements should be wrapped in a {@link ParsleyProxyElement}.
	 *         Wrapping is the shared seam for both inline error display and the
	 *         render profiler, so either feature being active turns it on.
	 */
	public static boolean shouldWrapElements() {
		return _showInlineErrorMessages || ParsleyRenderProfiler.isEnabled();
	}
}
