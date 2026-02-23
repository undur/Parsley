package parsley.ognl;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;

import ognl.MemberAccess;
import ognl.OgnlContext;

/**
 * CHECKME: Should accessibility be configurable at all? // Hugi 2026-02-23
 */

public class DefaultMemberAccess implements MemberAccess {

	private final boolean _allowPrivateAccess;
	private final boolean _allowProtectedAccess;
	private final boolean _allowPackageProtectedAccess;

	public DefaultMemberAccess( boolean allowPrivateAccess, boolean allowProtectedAccess, boolean allowPackageProtectedAccess ) {
		_allowPrivateAccess = allowPrivateAccess;
		_allowProtectedAccess = allowProtectedAccess;
		_allowPackageProtectedAccess = allowPackageProtectedAccess;
	}

	@Override
	public Object setup( OgnlContext context, Object target, Member member, String propertyName ) {

		if( isAccessible( context, target, member, propertyName ) ) {
			final AccessibleObject accessible = (AccessibleObject)member;

			// CHECKME: We probably want to use .canAccess( target ) instead // Hugi 2026-02-23
			if( !accessible.isAccessible() ) {
				accessible.setAccessible( true );

				// CHECKME: Shouldn't we be returning false here? (i.e. the original accessibility state, matching the method's javadoc) // Hugi 2026-02-23
				return Boolean.TRUE;
			}
		}

		return null;
	}

	@Override
	public void restore( OgnlContext context, Object target, Member member, String propertyName, Object state ) {

		// CHECKME: Shouldn't this method be restoring the actual original accessibility of the method? // Hugi 2026-02-23
		if( state != null ) {
			((AccessibleObject)member).setAccessible( ((Boolean)state).booleanValue() );
		}
	}

	/**
	 * @return true if the given member is accessible or can be made accessible
	 */
	@Override
	public boolean isAccessible( OgnlContext context, Object target, Member member, String propertyName ) {
		final int modifiers = member.getModifiers();

		boolean result = Modifier.isPublic( modifiers );

		if( !result ) {
			if( Modifier.isPrivate( modifiers ) ) {
				result = _allowPrivateAccess;
			}
			else {
				if( Modifier.isProtected( modifiers ) ) {
					result = _allowProtectedAccess;
				}
				else {
					result = _allowPackageProtectedAccess;
				}
			}
		}

		return result;
	}
}