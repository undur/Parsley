package parsley.ognl;

import com.webobjects.appserver.WOAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;
import parsley.ParsleyAssociationFactory;
import parsley.ParsleyDefaultAssociationFactory;

/**
 * Intercepts association creation to allow generation of WOOgnl-style associations
 */

public class ParsleyOGNLAssociationFactory implements ParsleyAssociationFactory {

	/**
	 * The default factory instance to delegate to if we don't generate an association ourselves
	 */
	private static final ParsleyAssociationFactory DEFAULT_FACTORY = new ParsleyDefaultAssociationFactory();

	@Override
	public WOAssociation associationForBindingValue( NGBindingValue bindingValue, boolean isInline ) {

		if( bindingValue instanceof NGBindingValue.Value bv ) {
			final String value = bv.value();

			if( value != null && value.startsWith( "~" ) ) {
				return new ParsleyOgnlAssociation( value.substring( 1 ) );
			}
		}

		return DEFAULT_FACTORY.associationForBindingValue( bindingValue, isInline );
	}
}