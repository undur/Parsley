package parsley.ognl;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver._private.WOKeyValueAssociation;
import com.webobjects.foundation._NSUtilities;

import ognl.ClassResolver;
import ognl.Ognl;
import ognl.OgnlContext;
import ognl.OgnlException;

public class ParsleyOgnlAssociation extends WOKeyValueAssociation {

	public ParsleyOgnlAssociation( final String key ) {
		super( key );
	}

	@Override
	public Object clone() {
		return new ParsleyOgnlAssociation( keyPath() );
	}

	@Override
	public Object valueInComponent( WOComponent component ) {
		return getValue( keyPath(), component );
	}

	@Override
	public void setValue( Object object, WOComponent component ) {
		setValue( keyPath(), component, object );
	}

	public Object getValue( String expression, Object obj ) {
		try {
			return Ognl.getValue( expression, createOgnlContext(), obj );
		}
		catch( OgnlException ex ) {
			final String message = ex.getMessage();
			// MS: This is SUPER SUPER LAME, but I don't see any other way in OGNL to
			// make keypaths with null components behave like NSKVC (i.e. returning null
			// vs throwing an exception).  They have something called nullHandlers
			// in OGNL, but it appears that you have to register it per-class and you
			// can't override the factory.
			if( message != null && message.startsWith( "source is null for getProperty(null, " ) ) {
				return null;
			}

			throw new RuntimeException( "Failed to get value '" + expression + "' on " + obj, ex );
		}
	}

	private void setValue( String expression, Object obj, Object value ) {
		try {
			Ognl.setValue( expression, createOgnlContext(), obj, value );
		}
		catch( OgnlException ex ) {
			throw new RuntimeException( "Failed to set value '" + expression + "' on " + obj, ex );
		}
	}

	/**
	 * @return new OgnlContext that allows access to everything not declared private
	 */
	private static OgnlContext createOgnlContext() {
		return new OgnlContext( OGNL_CLASS_RESOLVER, null, new DefaultMemberAccess( false, true, true ) );
	}

	/**
	 * Used by ognl to resolve classes
	 */
	private static final ClassResolver OGNL_CLASS_RESOLVER = new ClassResolver() {
		@Override
		public Class classForName( String className, OgnlContext context ) throws ClassNotFoundException {
			final Class clazz = _NSUtilities.classWithName( className );

			if( clazz == null ) {
				throw new ClassNotFoundException( className );
			}

			return clazz;
		}
	};
}