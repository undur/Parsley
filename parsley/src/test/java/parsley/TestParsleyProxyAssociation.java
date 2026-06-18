package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOComponent;
import com.webobjects.foundation.NSKeyValueCoding;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

/**
 * Tests for {@link ParsleyProxyAssociation} and {@link ParsleyProxyAssociationFactory}:
 * the binding-layer proxy that catches and decorates exceptions for <em>every</em> kind
 * of association, and faithfully delegates the rest of the {@link WOAssociation} surface.
 */
class TestParsleyProxyAssociation {

	/**
	 * A minimal association whose value-pull throws an unknown-key exception, so we can
	 * exercise the proxy's decoration without a live WOComponent/KVC stack. Stands in
	 * for any association that can raise {@link NSKeyValueCoding.UnknownKeyException} —
	 * a plain key-value association, a {@code ^binding-name} association, etc.
	 */
	private static final class ThrowingAssociation extends WOAssociation {

		private final Object _failingObject = new Object();
		private final String _key = "missingKey";

		@Override
		public Object valueInComponent( final WOComponent component ) {
			throw new NSKeyValueCoding.UnknownKeyException( "no such key", _failingObject, _key );
		}

		@Override
		public void setValue( final Object value, final WOComponent component ) {
			throw new NSKeyValueCoding.UnknownKeyException( "no such key", _failingObject, _key );
		}

		@Override
		public String keyPath() {
			return "person.missingKey";
		}

		@Override
		public String bindingInComponent( final WOComponent component ) {
			return null;
		}
	}

	/** Records how its surface methods were called, to prove faithful delegation. */
	private static final class RecordingAssociation extends WOAssociation {
		boolean settable = true;
		boolean constant = false;

		@Override
		public Object valueInComponent( final WOComponent component ) {
			return "v";
		}

		@Override
		public void setValue( final Object value, final WOComponent component ) {}

		@Override
		public boolean isValueSettable() {
			return settable;
		}

		@Override
		public boolean isValueConstant() {
			return constant;
		}

		@Override
		public String keyPath() {
			return "the.key.path";
		}

		@Override
		public String bindingInComponent( final WOComponent component ) {
			return "bound";
		}
	}

	@Test
	void decoratesUnknownKeyExceptionOnPull() {
		final ParsleyProxyAssociation proxy = new ParsleyProxyAssociation( new ThrowingAssociation(), "value" );

		final ParsleyUnknownKeyException e = assertThrows( ParsleyUnknownKeyException.class, () -> proxy.valueInComponent( null ) );

		assertEquals( "value", e.bindingName(), "the binding name that failed" );
		assertEquals( "person.missingKey", e.keyPath(), "the key path from the wrapped association" );
		assertEquals( "missingKey", e.key(), "the failing key, carried from the raw exception" );
		// And the suppressed binding-location marker is attached for the error page.
		assertEquals( 1, e.getSuppressed().length );
		assertInstanceOf( ParsleyBindingLocation.class, e.getSuppressed()[0] );
	}

	@Test
	void decoratesUnknownKeyExceptionOnPush() {
		final ParsleyProxyAssociation proxy = new ParsleyProxyAssociation( new ThrowingAssociation(), "value" );

		final ParsleyUnknownKeyException e = assertThrows( ParsleyUnknownKeyException.class, () -> proxy.setValue( "x", null ) );
		assertEquals( "value", e.bindingName() );
		assertEquals( "person.missingKey", e.keyPath() );
	}

	@Test
	void worksForAnyAssociationKind_notJustKeyValue() {
		// The old design only decorated a special ParsleyKeyValueAssociation subtype, so
		// the same exception from e.g. a WOBindingNameAssociation got nothing. The proxy
		// wraps uniformly: it doesn't care what it wraps, it decorates from the thrown
		// exception + keyPath(). ThrowingAssociation stands in for any such association.
		final ParsleyProxyAssociation proxy = new ParsleyProxyAssociation( new ThrowingAssociation(), "list" );
		final ParsleyUnknownKeyException e = assertThrows( ParsleyUnknownKeyException.class, () -> proxy.valueInComponent( null ) );
		assertEquals( "list", e.bindingName() );
	}

	@Test
	void delegatesFullSurfaceFaithfully() {
		final RecordingAssociation real = new RecordingAssociation();
		final ParsleyProxyAssociation proxy = new ParsleyProxyAssociation( real, "value" );

		assertEquals( "v", proxy.valueInComponent( null ), "pull delegates" );
		assertEquals( "the.key.path", proxy.keyPath(), "keyPath delegates" );
		assertEquals( "bound", proxy.bindingInComponent( null ), "bindingInComponent delegates" );

		real.settable = false;
		assertFalse( proxy.isValueSettable(), "isValueSettable reflects the wrapped association" );

		real.constant = true;
		assertTrue( proxy.isValueConstant(), "isValueConstant reflects the wrapped association" );

		assertSame( real, proxy.wrappedAssociation(), "exposes the wrapped association" );
	}

	@Test
	void nonUnknownKeyExceptionsPassThroughWithBindingLocationAttached() {
		final WOAssociation throwsRuntime = new WOAssociation() {
			@Override
			public Object valueInComponent( final WOComponent c ) {
				throw new IllegalStateException( "boom in a getter" );
			}

			@Override
			public void setValue( final Object v, final WOComponent c ) {}

			@Override
			public String keyPath() {
				return "x";
			}

			@Override
			public String bindingInComponent( final WOComponent c ) {
				return null;
			}
		};
		final ParsleyProxyAssociation proxy = new ParsleyProxyAssociation( throwsRuntime, "value" );

		// A non-unknown-key exception keeps its type (we don't translate it), but gets
		// the binding-location marker attached so the error page can still locate it.
		final IllegalStateException e = assertThrows( IllegalStateException.class, () -> proxy.valueInComponent( null ) );
		assertEquals( "boom in a getter", e.getMessage() );
		assertEquals( 1, e.getSuppressed().length );
		assertInstanceOf( ParsleyBindingLocation.class, e.getSuppressed()[0] );
	}

	@Test
	void proxyFactoryAlwaysWraps() {
		// The proxy factory is an unconditional decorator — it wraps every association,
		// no flag check. Whether it's installed at all is Parsley's decision, tested
		// separately below.
		final ParsleyProxyAssociationFactory factory = new ParsleyProxyAssociationFactory( new ParsleyDefaultAssociationFactory() );
		final WOAssociation wrapped = factory.associationForBindingValue( "value", new NGBindingValue.Value( false, "person.name" ), true );

		assertInstanceOf( ParsleyProxyAssociation.class, wrapped );
		assertEquals( "value", ((ParsleyProxyAssociation)wrapped).bindingName() );
	}

	@Test
	void parsleyInstallsProxyFactoryOnlyWhenInlineErrorsEnabled() {
		// The wrap-or-not policy lives in Parsley's configuration, not the factory: with
		// inline errors off the registered factory is used as-is; with them on it's
		// wrapped in the proxy factory.
		final ParsleyAssociationFactory registered = new ParsleyDefaultAssociationFactory();

		Parsley.configure().associationFactory( registered ).inlineErrors( false ).register();
		assertSame( registered, Parsley.effectiveAssociationFactory(), "off: the registered factory is used as-is" );

		Parsley.configure().associationFactory( registered ).inlineErrors( true ).register();
		assertInstanceOf( ParsleyProxyAssociationFactory.class, Parsley.effectiveAssociationFactory(), "on: wrapped in the proxy factory" );

		ParsleyTestSupport.resetParsleyConfiguration(); // don't leak inline-errors config into other tests
	}
}
