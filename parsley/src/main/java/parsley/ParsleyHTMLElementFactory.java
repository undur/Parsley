package parsley;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver._private.WOConstantValueAssociation;
import com.webobjects.appserver._private.WODynamicGroup;
import com.webobjects.appserver._private.WOGenericContainer;
import com.webobjects.appserver._private.WOGenericElement;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;

public class ParsleyHTMLElementFactory implements ParsleyElementFactory {

	@Override
	public WOElement dynamicElementWithName( String namespace, String tagName, NSDictionary<String, WOAssociation> associations, WOElement childElement, NSArray<String> languages ) {
		associations = associations.mutableClone();
		associations.put( "elementName", new WOConstantValueAssociation( tagName ) );

		if( childElement instanceof WODynamicGroup dg && !dg.hasChildrenElements() ) {
			return new WOGenericElement( null, associations, childElement );
		}

		return new WOGenericContainer( null, associations, childElement );
	}
}