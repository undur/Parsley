package parsley;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOElement;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSDictionary;

public class ParsleyDefaultElementFactory implements ParsleyElementFactory {

	/**
	 * @return An element instance initialized with the given parameters
	 *
	 * Mimics WOApplication's dynamicElementWithName, but throws ParsleyElementNotFoundException if the named element is not found.
	 */
	@Override
	public WOElement dynamicElementWithName( final String namespace, final String elementName, final NSDictionary<String, WOAssociation> associations, final WOElement childElement, final NSArray<String> languages ) {

		final WOElement element = WOApplication.application().dynamicElementWithName( elementName, associations, childElement, languages );

		if( element == null ) {
			throw new ParsleyElementNotFoundException( "Cannot find element class or component named '%s' in runtime or in a loadable bundle".formatted( elementName ) );
		}

		return element;
	}
}