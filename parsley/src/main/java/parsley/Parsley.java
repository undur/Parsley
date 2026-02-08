package parsley;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
import com.webobjects.appserver._private.WOHTMLCommentString;
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
import ng.appserver.templating.parser.model.PRootNode;
import ng.appserver.templating.parser.model.PHTMLNode;
import ng.appserver.templating.parser.model.PNode;

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
	 * Registers this class as the template parser class for use in a WO project
	 */
	public static void register() {
		WOComponentTemplateParser.setWOHTMLTemplateParserClassName( Parsley.class.getName() );
		logger.info( "Sprinkled some fresh Parsley on your templates" );
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
			final PNode rootNode = new NGTemplateParser( htmlString(), declarationString() ).parse();
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
			case PCommentNode n -> new WOHTMLCommentString( n.value() );
		};
	}

	/**
	 * @return An element/template from the given basic node
	 */
	private WOElement toElement( final PBasicNode node ) {

		final String elementName = ParsleyTagRegistry.tagShortcutMap().getOrDefault( node.type(), node.type() ); // Emulates WOOgnl's template shortcutting
		final NSDictionary<String, WOAssociation> associations = toAssociations( node.bindings(), node.isInline() );
		final WOElement childElement = toElement( node.children() );

		// If inline error messages aren't enabled, we go directly to just finding an element as is and throwing if no component is found

		if( !showInlineErrorMessages() ) {
			final WOElement element = WOApplication.application().dynamicElementWithName( elementName, associations, childElement, languages() );

			// WebObjects/WOOgnl throw ClassNotFoundExcption if an element is not found. I can't get myself to mimic that, so we're throwing our own exception type
			if( element == null ) {
				throw new ParsleyElementNotFoundException( "Cannot find element class or component named '%s' in runtime or in a loadable bundle".formatted( elementName ) );
			}

			return element;
		}

		// Inline errors are enabled, let's go!

		WOElement element = null;

		try {
			element = WOApplication.application().dynamicElementWithName( elementName, associations, childElement, languages() );
		}
		catch( Exception e ) {
			// Check if this is an element creation error and attempt to render a nice inline error message
			if( e instanceof NSForwardException fwe ) {
				if( fwe.getCause() instanceof InvocationTargetException ite ) {
					if( ite.getTargetException() instanceof WODynamicElementCreationException dece ) {
						return new ParsleyErrorMessageElement( elementName + " : " + dece.getMessage(), dece );
					}
				}
			}

			// If we still don't have an element here, something worse than WODynamicElementCreationException happened, so throw.
			throw e;
		}

		// Render inline error message in case of missing element.
		if( element == null ) {
			return new ParsleyErrorMessageElement( "Element/component <strong>%s</strong> not found".formatted( elementName ) );
		}

		// Wrap the element in a "proxy" for catching exceptions that happen during rendering
		if( shouldWrapInProxyElement( element ) ) {
			element = new ParsleyProxyElement( element, node );
		}

		return element;
	}

	/**
	 * @return true if the element should be wrapped in a proxy element when inline error messages are enabled.
	 *
	 * FIXME: We need a more generic way to determine if an element should be wrapped. We should at least allow the user to exclude elements from wrapping // Hugi 2025-10-17 #8
	 */
	private static boolean shouldWrapInProxyElement( final WOElement element ) {

		// CHECKME: We currently don't wrap component references since it seems to mess with component state, at least for the root element/component. This doesn't really affect functionality, but I'd still like to figure out why this happens // Hugi 2025-03-26
		final boolean isWOComponentReference = element instanceof WOComponentReference;

		// FIXME: ERXWOTemplate won't render if wrapped in a proxy element (it depends on being the immediate child element of it's wrapper component). However, hardcoding specific element names isn't great // Hugi 2025-10-17 #8
		final boolean isERXWOTemplate = element.getClass().getSimpleName().equals( "ERXWOTemplate" );

		return !isWOComponentReference && !isERXWOTemplate;
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

			// …unless it's a WOComponentReference. For some reason we lose track of component instances if we return those unwrapped, so allow them to pass through and get that Dynamic Group hug
			if( !(element instanceof WOComponentReference) ) {
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
			final WOAssociation association = ParsleyAssociationFactory.associationForBindingValue( entry.getValue(), isInline );
			associations.put( bindingName, association );

			if( association instanceof ParsleyKeyValueAssociation pa ) {
				pa.setBindingName( bindingName );
			}
		}

		return associations;
	}
}