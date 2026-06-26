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

		// A resolved name containing '.' or '$' is a fully-qualified class name (e.g. an inner
		// patch class with no element name of its own). Instantiate it directly rather than
		// relying on WO's name registry, which doesn't reliably resolve nested-class FQNs. A
		// failure here means a faulty alias was registered, so we throw rather than fall back.
		if( elementName.indexOf( '.' ) >= 0 || elementName.indexOf( '$' ) >= 0 ) {
			return instantiateByClassName( elementName, associations, childElement );
		}

		final WOElement element = WOApplication.application().dynamicElementWithName( elementName, associations, childElement, languages );

		if( element == null ) {
			throw new ParsleyElementNotFoundException( "Cannot find element class or component named '%s' in runtime or in a loadable bundle".formatted( elementName ) );
		}

		return element;
	}

	/**
	 * Instantiates a {@link WOElement} from a fully-qualified class name via its standard
	 * {@code (String, NSDictionary, WOElement)} dynamic-element constructor. Throws
	 * {@link ParsleyElementNotFoundException} if the class can't be loaded or lacks that
	 * constructor — a faulty alias was registered.
	 */
	private static WOElement instantiateByClassName( final String className, final NSDictionary<String, WOAssociation> associations, final WOElement childElement ) {
		try {
			final Class<?> elementClass = Class.forName( className );
			return (WOElement) elementClass
					.getConstructor( String.class, NSDictionary.class, WOElement.class )
					.newInstance( className, associations, childElement );
		} catch( final Exception e ) {
			throw new ParsleyElementNotFoundException( "Could not instantiate element class '%s'".formatted( className ), e );
		}
	}
}