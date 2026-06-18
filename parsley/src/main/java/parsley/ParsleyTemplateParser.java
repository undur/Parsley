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

			// Wrap the root so rendering it marks the response as Parsley-generated —
			// see ParsleyTemplateRootElement / ParsleyRequestObserver. This is what keeps
			// the dev overlays out of non-template responses (served resources, etc.).
			return new ParsleyTemplateRootElement( toElement( rootNode ) );
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
		final NSDictionary<String, WOAssociation> associations = toAssociations( node, node.bindings(), node.isInline() );
		final WOElement childElement = toElement( node.children() );

		// Wrap when inline errors OR the render profiler is active — both rely on
		// the proxy element as their interception seam.
		if( Parsley.shouldWrapElements() ) {
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
	private static NSDictionary<String, WOAssociation> toAssociations( final PNode owningNode, final Map<String, NGBindingValue> bindings, final boolean isInline ) {
		final NSDictionary<String, WOAssociation> associations = new NSMutableDictionary<>();

		for( final Entry<String, NGBindingValue> entry : bindings.entrySet() ) {
			final String bindingName = entry.getKey();
			final WOAssociation association = Parsley.effectiveAssociationFactory().associationForBindingValue( bindingName, entry.getValue(), isInline );
			associations.put( bindingName, association );

			if( association instanceof ParsleyProxyAssociation pa ) {
				// Stamp the owning node so the render profiler can attribute this
				// binding's pull/push time to the right element — see ParsleyRenderProfiler.
				pa.setOwningNode( owningNode );
			}
		}

		return associations;
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
}
