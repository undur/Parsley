package parsley;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOAssociationFactory;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WOResponse;
import com.webobjects.appserver._private.WODynamicGroup;
import com.webobjects.appserver._private.WOHTMLBareString;
import com.webobjects.appserver._private.WOHTMLCommentString;
import com.webobjects.appserver.parser.WOComponentTemplateParser;
import com.webobjects.appserver.parser.woml.WOMLNamespaceProvider;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableArray;
import com.webobjects.foundation.NSMutableDictionary;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;
import ng.appserver.templating.parser.NGTemplateParser;
import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PCommentNode;
import ng.appserver.templating.parser.model.PGroupNode;
import ng.appserver.templating.parser.model.PHTMLNode;
import ng.appserver.templating.parser.model.PNode;

/**
 * Bridges the "new and old world" for template parsing
 */

public class Parsley extends WOComponentTemplateParser {

	/**
	 * Registers this class as the template parser class for use in a wO project
	 */
	public static void register() {
		WOComponentTemplateParser.setWOHTMLTemplateParserClassName( Parsley.class.getName() );
	}

	/**
	 * Constructor invoked by the WO framework to create the parser instance
	 */
	public Parsley( String referenceName, String HTMLString, String declarationString, NSArray<String> languages, WOAssociationFactory associationFactory, WOMLNamespaceProvider namespaceProvider ) {
		super( referenceName, HTMLString, declarationString, languages, associationFactory, namespaceProvider );
	}

	/**
	 * @return A parsed element template
	 */
	@Override
	public WOElement parse() {
		try {
			final PNode rootNode = new NGTemplateParser( htmlString(), declarationString() ).parse();
			return toDynamicElement( rootNode );
		}
		catch( Exception e ) {
			// FIXME: Yuck. This is just here while we're creating some sensible error handling // Hugi 2024-11-24
			throw new RuntimeException( e );
		}
	}

	private WOElement toDynamicElement( final PNode node ) {
		return switch( node ) {
			case PBasicNode n -> toDynamicElement( n );
			case PGroupNode n -> toTemplate( n.children() );
			case PHTMLNode n -> new WOHTMLBareString( n.value() );
			case PCommentNode n -> new WOHTMLCommentString( n.value() );
		};
	}

	private WOElement toDynamicElement( final PBasicNode node ) {

		String type = node.type();

		// Cloning WOOGnl's template shortcutting
		final String actualType = ParsleyHelperFunctionTagRegistry.tagShortcutMap().objectForKey( type );

		if( actualType != null ) {
			type = actualType;
		}

		final NSDictionary<String, WOAssociation> associations = toAssociations( node.bindings(), node.isInline() );
		final WOElement childTemplate = toTemplate( node.children() );

		final WOElement de = WOApplication.application().dynamicElementWithName( type, associations, childTemplate, languages() );

		// FIXME: Placeholder until we have some real handling of missing elements // Hugi 2024-11-24
		if( de == null ) {
			return new WOElement() {
				@Override
				public void appendToResponse( WOResponse response, WOContext context ) {

				}
			};
		}

		return de;
	}

	private static NSDictionary<String, WOAssociation> toAssociations( final Map<String, NGBindingValue> bindings, final boolean isInline ) {
		final NSDictionary<String, WOAssociation> associations = new NSMutableDictionary<>();

		for( Entry<String, NGBindingValue> entry : bindings.entrySet() ) {
			final String bindingName = entry.getKey();
			final WOAssociation association = ParsleyAssociationFactory.associationForBindingValue( entry.getValue(), isInline );
			associations.put( bindingName, association );
		}

		return associations;
	}

	/**
	 * @return An element/template from the given list of nodes.
	 */
	private WOElement toTemplate( final List<PNode> nodes ) {

		final NSMutableArray<WOElement> elements = new NSMutableArray<>();

		for( final PNode pNode : nodes ) {
			final WOElement dynamicElement = toDynamicElement( pNode );
			elements.add( dynamicElement );
		}

		if( elements.size() == 1 ) {
			return elements.getFirst();
		}

		return new WODynamicGroup( null, null, elements );
	}
}