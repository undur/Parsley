package parsley;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver._private.WOKeyValueAssociation;
import com.webobjects.foundation.NSKeyValueCoding;

import ng.appserver.templating.parser.model.PNode;

/**
 * Used instead of WOKeyValueAssociation when inline rendering error display is active.
 * Allows us to grab and report exceptions that happen during binding pushing/pulling.
 */

public class ParsleyKeyValueAssociation extends WOKeyValueAssociation {

	/**
	 * Keep track of the binding name for debugging
	 */
	private String _bindingName;

	/**
	 * The template node this binding belongs to (the element that declared it). Used
	 * by the render profiler to attribute binding-pull time to the correct row even
	 * for component bindings — which are pulled by WO machinery while some inner
	 * element is on the render stack, not the owning element. With the owning node
	 * stamped here, the profiler can credit the right element symmetrically whether
	 * it's a dynamic element or a component reference. Null if not stamped.
	 */
	private PNode _owningNode;

	public ParsleyKeyValueAssociation( String keyPath ) {
		super( keyPath );
	}

	@Override
	public Object valueInComponent( WOComponent component ) {
		final long start = ParsleyRenderProfiler.isEnabled() ? System.nanoTime() : 0L;
		try {
			return super.valueInComponent( component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			// Unknown key: turn it into the richer ParsleyUnknownKeyException
			// (used by ParsleyProxyElement for inline error display), and also
			// annotate it with the binding identity so an exception page can
			// report which binding failed.
			final ParsleyUnknownKeyException puke = new ParsleyUnknownKeyException( uke.getMessage(), uke.object(), uke.key(), keyPath(), component, bindingName() );
			attachBindingLocation( puke );
			throw puke;
		}
		catch( RuntimeException e ) {
			// Any other exception thrown while resolving this binding — most
			// commonly an exception inside the accessor itself (e.g. an NPE in a
			// getter), not an unknown key. Attach the binding identity as a
			// suppressed marker and rethrow untouched, so the exception page can
			// show "while resolving binding value=$keyPath" alongside the
			// element's source location. Non-destructive: the original exception
			// keeps its type, message, cause chain, and stack trace.
			attachBindingLocation( e );
			throw e;
		}
		finally {
			if( start != 0L ) {
				ParsleyRenderProfiler.recordBindingPull( System.nanoTime() - start, _owningNode );
			}
		}
	}

	@Override
	public void setValue( Object value, WOComponent component ) {
		final long start = ParsleyRenderProfiler.isEnabled() ? System.nanoTime() : 0L;
		try {
			super.setValue( value, component );
		}
		catch( NSKeyValueCoding.UnknownKeyException uke ) {
			// Unknown key on the write path: mirror valueInComponent — turn it into
			// the richer ParsleyUnknownKeyException and annotate it with the binding
			// identity, so the exception page can report which binding failed while
			// pushing the submitted value back through it.
			final ParsleyUnknownKeyException puke = new ParsleyUnknownKeyException( uke.getMessage(), uke.object(), uke.key(), keyPath(), component, bindingName() );
			attachBindingLocation( puke );
			throw puke;
		}
		catch( RuntimeException e ) {
			// Any other exception thrown while pushing this binding — most commonly an
			// exception inside the setter itself (e.g. validation, or a deliberately
			// throwing setValueForBinding). Attach the binding identity as a suppressed
			// marker and rethrow untouched, so the exception page can show "while
			// resolving binding value=$keyPath" alongside the element's source location.
			// Non-destructive: the original exception keeps its type, message, cause
			// chain, and stack trace.
			attachBindingLocation( e );
			throw e;
		}
		finally {
			if( start != 0L ) {
				ParsleyRenderProfiler.recordBindingPush( System.nanoTime() - start, _owningNode );
			}
		}
	}

	/**
	 * Attaches this association's binding identity to the given throwable as a
	 * suppressed {@link ParsleyBindingLocation}, unless one is already present.
	 *
	 * <p>The innermost-wins guard mirrors {@link ParsleyProxyElement}: the
	 * association closest to the actual failure annotates it; as the exception
	 * propagates outward, anything that already carries a binding location leaves
	 * it be.
	 */
	private void attachBindingLocation( final Throwable throwable ) {
		if( ParsleyBindingLocation.attachedTo( throwable ) == null ) {
			throwable.addSuppressed( new ParsleyBindingLocation( bindingName(), keyPath() ) );
		}
	}

	/**
	 * CHECKME: I'd much prefer to set this during the association's construction, but for that we'll have to change the association construction process a little // Hugi 2025-03-30
	 */
	public void setBindingName( final String bindingName ) {
		_bindingName = bindingName;
	}

	public String bindingName() {
		return _bindingName;
	}

	/**
	 * Stamps the template node this binding belongs to, so the render profiler can
	 * attribute its pull/push time to the owning element.
	 */
	public void setOwningNode( final PNode owningNode ) {
		_owningNode = owningNode;
	}
}