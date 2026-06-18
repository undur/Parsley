package parsley;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

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
 * The WO template parser: converts a parsed PNode template tree into a tree of WO
 * elements. WO instantiates this class (by name, via the registration in {@link Parsley})
 * once per template. Configuration — the element factories, the association factory, and
 * whether inline errors are enabled — is read from {@link Parsley}, the library's entry
 * point.
 */
public class ParsleyTemplateParser extends WOComponentTemplateParser {

	/**
	 * Constructor invoked by the WO framework
	 */
	public ParsleyTemplateParser( String name, String htmlString, String declarationString, NSArray<String> languages, WOAssociationFactory associationFactory, WOMLNamespaceProvider namespaceProvider ) {
		super( name, htmlString, declarationString, languages, associationFactory, namespaceProvider );
	}

	/**
	 * Constructor for parsing a simple inline template string
	 */
	public ParsleyTemplateParser( final String htmlString ) {
		super( null, htmlString, "", null, null, null );
	}

	/**
	 * @return A parsed element template
	 */
	@Override
	public WOElement parse() {
		try {
			final Set<String> dynamicNamespaces = Set.copyOf( Parsley.dynamicNamespaces() );
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

		// Inline errors are enabled, let's go!
		if( Parsley.showInlineErrorMessages() ) {
			return wrappedElement( node, elementName, associations, childElement );
		}

		return Parsley.elementFactoryForNamespace( node.namespace() ).dynamicElementWithName( node.namespace(), elementName, associations, childElement, languages() );
	}

	/**
	 * @return A wrapped element
	 */
	private WOElement wrappedElement( final PBasicNode node, final String elementName, final NSDictionary<String, WOAssociation> associations, final WOElement childElement ) {

		try {
			final WOElement element = Parsley.elementFactoryForNamespace( node.namespace() ).dynamicElementWithName( node.namespace(), elementName, associations, childElement, languages() );

			// Some elements may not work with the proxy element. In that case, just return the element unwrapped
			if( !Parsley.shouldWrapElement( element ) ) {
				return element;
			}

			// Wrapping the element in a "proxy" allows us to catch exceptions thrown during rendering
			return new ParsleyProxyElement( element, node );
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
	 * @return If proxy element, the wrapped element. If any other element, the element itself
	 */
	private static WOElement unwrap( final WOElement element ) {
		if( element instanceof ParsleyProxyElement proxy ) {
			return proxy.wrappedElement();
		}

		return element;
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
			final WOAssociation association = Parsley.effectiveAssociationFactory().associationForBindingValue( bindingName, entry.getValue(), isInline );
			associations.put( bindingName, association );
		}

		return associations;
	}
}
