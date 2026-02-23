package parsley;

import com.webobjects.appserver.WOAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

public interface ParsleyAssociationFactory {

	public WOAssociation associationForBindingValue( final NGBindingValue bindingValue, final boolean isInline );
}