package parsley;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOElement;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;

public interface ParsleyElementFactory {

	public WOElement dynamicElementWithName( final String namespace, final String elementName, final NSDictionary<String, WOAssociation> associations, final WOElement childElement, final NSArray<String> languages );
}