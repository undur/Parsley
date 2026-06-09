package parsley;

import com.webobjects.appserver.WOAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

public interface ParsleyAssociationFactory {

	/**
	 * @param bindingName the name of the binding being resolved (the {@code value} in
	 *        {@code value="$x"}). Always present — it's the binding's key in the
	 *        element's declaration — and intrinsic to the association: a factory may use
	 *        it for the produced association's identity, and it's what lets error
	 *        reporting say <em>which</em> binding failed. Passing it here (rather than
	 *        post-stamping the association after construction) lets associations be born
	 *        complete.
	 */
	public WOAssociation associationForBindingValue( final String bindingName, final NGBindingValue bindingValue, final boolean isInline );
}