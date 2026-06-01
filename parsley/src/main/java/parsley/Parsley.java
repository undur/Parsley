package parsley;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOAssociationFactory;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver._private.WOComponentReference;
import com.webobjects.appserver._private.WODynamicElementCreationException;
import com.webobjects.appserver._private.WODynamicGroup;
import com.webobjects.appserver._private.WOHTMLBareString;
import com.webobjects.appserver.parser.WOComponentTemplateParser;
import com.webobjects.appserver.parser.WOParserException;
import com.webobjects.appserver.parser.woml.WOMLNamespaceProvider;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSForwardException;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;
import com.webobjects.foundation.NSNotificationCenter;
import com.webobjects.foundation.NSSelector;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;
import ng.appserver.templating.parser.NGDeclarationFormatException;
import ng.appserver.templating.parser.NGHTMLFormatException;
import ng.appserver.templating.parser.NGTemplateParser;
import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PCommentNode;
import ng.appserver.templating.parser.model.PHTMLNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.PRawNode;
import ng.appserver.templating.parser.model.PRootNode;

/**
 * Converts a parsed PNode template to a WO template
 */

public class Parsley extends WOComponentTemplateParser {

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
	 * A factory instance for generating associations
	 */
	private static ParsleyAssociationFactory _associationFactory;

	/**
	 * Maps namespace names to element factories responsible for generating elements in that namespace
	 */
	private static final Map<String, ParsleyElementFactory> _elementFactories = new HashMap<>();

	/**
	 * Registers this class as the template parser class for use in a WO project, with the default association factory
	 */
	public static void register() {
		register( new ParsleyDefaultAssociationFactory() );
	}

	/**
	 * Registers this class as the template parser class for use in a WO project, using the given association factory
	 */
	public static void register( final ParsleyAssociationFactory associationFactory ) {
		WOComponentTemplateParser.setWOHTMLTemplateParserClassName( Parsley.class.getName() );
		_associationFactory = associationFactory;
		_elementFactories.put( "wo", new ParsleyDefaultElementFactory() );
		logger.info( "Sprinkled some fresh Parsley on your templates. Using association factory '%s'".formatted( associationFactory.getClass().getName() ) );
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
	 * @return The element factory registered for the given namespace
	 */
	private static ParsleyElementFactory elementFactoryForNamespace( final String namespace ) {
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

	/**
	 * Constructor invoked by the WO framework
	 */
	public Parsley( String name, String htmlString, String declarationString, NSArray<String> languages, WOAssociationFactory associationFactory, WOMLNamespaceProvider namespaceProvider ) {
		super( name, htmlString, declarationString, languages, associationFactory, namespaceProvider );
	}

	/**
	 * Constructor for parsing a simple inline template string
	 */
	public Parsley( final String htmlString ) {
		super( null, htmlString, "", null, null, null );
	}

	/**
	 * @return A parsed element template
	 */
	@Override
	public WOElement parse() {
		try {
			final Set<String> dynamicNamespaces = Set.copyOf( _elementFactories.keySet() );
			final PNode rootNode = new NGTemplateParser( htmlString(), declarationString(), dynamicNamespaces ).parse();
			return toElement( rootNode );
		}
		catch( NGDeclarationFormatException | NGHTMLFormatException e ) {
			// FIXME: Clean up error handling // Hugi 2024-11-24
			throw new WOParserException( e );
		}
	}

	/**
	 * @return An element/template from the given node
	 */
	private WOElement toElement( final PNode node ) {
		return switch( node ) {
			case PBasicNode n -> toElement( n );
			case PRootNode n -> toElement( n.children() );
			case PHTMLNode n -> new WOHTMLBareString( n.value() );
			case PRawNode n -> new WOHTMLBareString( n.value() );
			case PCommentNode n -> new WOHTMLBareString( "" );
		};
	}

	/**
	 * @return An element/template from the given basic node
	 */
	private WOElement toElement( final PBasicNode node ) {

		final String elementName = ParsleyTagRegistry.tagShortcutMap().getOrDefault( node.type(), node.type() ); // Emulates WOOgnl's template shortcutting
		final NSDictionary<String, WOAssociation> associations = toAssociations( node.bindings(), node.isInline() );
		final WOElement childElement = toElement( node.children() );

		// Wrap when inline errors OR the render profiler is active — both rely on
		// the proxy element as their interception seam.
		if( shouldWrapElements() ) {
			return wrappedElement( node, elementName, associations, childElement );
		}

		return elementFactoryForNamespace( node.namespace() ).dynamicElementWithName( node.namespace(), elementName, associations, childElement, languages() );
	}

	/**
	 * @return A wrapped element
	 */
	private WOElement wrappedElement( final PBasicNode node, final String elementName, final NSDictionary<String, WOAssociation> associations, final WOElement childElement ) {

		try {
			final WOElement element = elementFactoryForNamespace( node.namespace() ).dynamicElementWithName( node.namespace(), elementName, associations, childElement, languages() );

			// Some elements may not work with the proxy element. In that case, just return the element unwrapped
			if( !shouldWrapInProxyElement( element ) ) {
				return element;
			}

			// Wrapping the element in a "proxy" allows us to catch exceptions thrown during rendering.
			// We also stamp the component name + resolved source line onto the proxy so the render
			// heat map can offer a click-to-open-in-IDE link for the element. The line is resolved
			// here (parse time) because we have the template source via htmlString() right now;
			// resolving per-render would be wasteful and the proxy doesn't keep the source around.
			final String componentName = simpleComponentName( referenceName() );
			final int line = ParsleyDevServerLinks.lineForOffset( htmlString(), node.sourceRange() == null ? -1 : node.sourceRange().start() );
			final String bindingsSummary = bindingsSummary( node );
			return new ParsleyProxyElement( element, node, componentName, line, bindingsSummary );
		}
		catch( Exception e ) {

			// Render inline error message in case of missing element.
			if( e instanceof ParsleyElementNotFoundException ) {
				return new ParsleyErrorMessageElement( "Element/component <strong>%s</strong> not found".formatted( elementName ) );
			}

			// Render inline error message in case of an element creation error
			if( e instanceof NSForwardException fwe ) {
				if( fwe.getCause() instanceof InvocationTargetException ite ) {
					if( ite.getTargetException() instanceof WODynamicElementCreationException dece ) {
						return new ParsleyErrorMessageElement( elementName + " : " + dece.getMessage(), dece );
					}
				}
			}

			// If we're here, something has happened that we can't handle so just throw the exception up the stack (deferring to WO's regular exception handling)
			throw e;
		}
	}

	/**
	 * @return true if the element should be wrapped in a proxy element when inline error messages are enabled.
	 *
	 * FIXME: We need a more generic way to determine if an element should be wrapped. We should at least allow the user to exclude elements from wrapping // Hugi 2025-10-17 #8
	 */
	private static boolean shouldWrapInProxyElement( final WOElement element ) {
		return !element.getClass().getSimpleName().equals( "ERXWOTemplate" ); // ERXWOTemplate depends on being the immediate child element of it's wrapper component
	}

	/**
	 * @return If proxy element, the wrapped element. If any other element, the element itself
	 */
	private static WOElement unwrap( final WOElement element ) {
		if( element instanceof ParsleyProxyElement proxy ) {
			return proxy.wrappedElement();
		}

		return element;
	}

	/**
	 * @return the simple (unqualified) component name from a possibly
	 *         package-qualified reference name, which is what the dev server's
	 *         /openComponent handler resolves against. Null-safe.
	 */
	private static String simpleComponentName( final String referenceName ) {
		if( referenceName == null ) {
			return null;
		}
		final int lastDot = referenceName.lastIndexOf( '.' );
		return lastDot == -1 ? referenceName : referenceName.substring( lastDot + 1 );
	}

	/**
	 * @return a compact, human-readable summary of a node's bindings for the render
	 *         heat map — e.g. {@code value="$resultsString" list="$entityDefinitions"}.
	 *         Used as an orientation hint so a row reads as more than a bare element
	 *         name. Computed at parse time (we have the node here); kept short so it
	 *         doesn't overwhelm the row. Returns "" when there are no bindings.
	 */
	private static String bindingsSummary( final PBasicNode node ) {
		final Map<String, NGBindingValue> bindings = node.bindings();
		if( bindings == null || bindings.isEmpty() ) {
			return "";
		}

		final StringBuilder b = new StringBuilder();
		for( final Entry<String, NGBindingValue> entry : bindings.entrySet() ) {
			if( b.length() > 0 ) {
				b.append( ' ' );
			}
			b.append( entry.getKey() ).append( '=' ).append( bindingValueString( entry.getValue() ) );
		}
		return b.toString();
	}

	/**
	 * @return a display string for a single binding value. Dynamic values keep the
	 *         author's exact text ($keyPath / unquoted); quoted constants are shown
	 *         in quotes. Falls back to the value's toString for any other subtype.
	 */
	private static String bindingValueString( final NGBindingValue value ) {
		if( value instanceof NGBindingValue.Value v ) {
			return v.isQuoted() ? "\"" + v.value() + "\"" : v.value();
		}
		return String.valueOf( value );
	}

	/**
	 * @return An element/template from the given list of nodes
	 */
	private WOElement toElement( final List<PNode> nodes ) {

		final NSMutableArray<WOElement> elements = new NSMutableArray<>();

		for( final PNode node : nodes ) {
			elements.add( toElement( node ) );
		}

		// If there's only one element, there's no need to wrap it in a dynamic group…
		if( elements.size() == 1 ) {
			final WOElement element = elements.getFirst();

			// …unless it's a WOComponentReference which depends on WODynamicGroup for
			// getting a stable elementID, without which everything goes to holy hell.
			// Since elements are already wrapped by here (if wrapping) we must unwrap
			// before the check
			if( !(unwrap( element ) instanceof WOComponentReference) ) {
				return element;
			}
		}

		return new WODynamicGroup( null, null, elements );
	}

	/**
	 * @return The given bindings as a map of associations
	 */
	private static NSDictionary<String, WOAssociation> toAssociations( final Map<String, NGBindingValue> bindings, final boolean isInline ) {
		final NSDictionary<String, WOAssociation> associations = new NSMutableDictionary<>();

		for( final Entry<String, NGBindingValue> entry : bindings.entrySet() ) {
			final String bindingName = entry.getKey();
			final WOAssociation association = _associationFactory.associationForBindingValue( entry.getValue(), isInline );
			associations.put( bindingName, association );

			if( association instanceof ParsleyKeyValueAssociation pa ) {
				pa.setBindingName( bindingName );
			}
		}

		return associations;
	}
}