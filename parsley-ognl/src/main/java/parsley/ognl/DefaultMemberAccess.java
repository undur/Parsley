package parsley.ognl;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;

import ognl.MemberAccess;
import ognl.OgnlContext;

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
		Object result = null;

		if( isAccessible( context, target, member, propertyName ) ) {
			final AccessibleObject accessible = (AccessibleObject)member;

			if( !accessible.isAccessible() ) {
				result = Boolean.TRUE;
				accessible.setAccessible( true );
			}
		}

		return result;
	}

	@Override
	public void restore( OgnlContext context, Object target, Member member, String propertyName, Object state ) {
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