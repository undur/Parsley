package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.WOResponse;
import com.webobjects.foundation.NSData;

import ng.appserver.templating.parser.model.PBasicNode;
import ng.appserver.templating.parser.model.PNode;
import ng.appserver.templating.parser.model.SourceRange;
import ng.dev.NGRuntimeProblems;

/**
 * Tests how {@link ParsleyProxyElement} recovers when a wrapped element fails mid-render
 * and the failure is shown inline: the response keeps what was rendered before it (#30),
 * the context is put back as it was (#30), the problem is recorded with a usable element
 * and plain-text message (#31), and location markers print readably (#32).
 *
 * <p>A real {@link WOContext} or {@link WOComponent} needs a running application, so the
 * tests use small stand-ins that override just the methods involved, instantiated without
 * running their constructors.
 */
class TestParsleyErrorRecovery {

	private static final String PREFIX = "<!DOCTYPE html><html><head><link rel=\"stylesheet\" href=\"app.css\"></head><body><header>Page header</header>";

	@BeforeEach
	void clearProblems() {
		NGRuntimeProblems.clear();
	}

	@Test
	void failingChildComponentLeavesTheParentAsTheCurrentComponent() {
		final FakeComponent parent = FakeComponent.named( "app.Main" );
		final FakeComponent child = FakeComponent.named( "app.StandingsTable" );
		final FakeContext context = FakeContext.on( parent, "0", "3" );
		final WOResponse response = response( PREFIX );

		proxy( new FailingComponentReference( child ) ).appendToResponse( response, context );

		assertSame( parent, context.component(), "the parent is current again, so its later bindings resolve on it" );
		assertEquals( "0.3", context.elementID(), "element ID back at the depth it had before the failure" );
	}

	@Test
	void rollbackKeepsEverythingRenderedBeforeTheFailure() {
		final FakeContext context = FakeContext.on( FakeComponent.named( "app.Main" ), "0" );
		final WOResponse response = response( PREFIX );

		proxy( new FailingComponentReference( FakeComponent.named( "app.StandingsTable" ) ) ).appendToResponse( response, context );

		final String out = response.contentString();
		assertTrue( out.startsWith( PREFIX ), "doctype, head and header are kept: " + out );
		assertFalse( out.contains( "PARTIAL-CHILD-OUTPUT" ), "the failed element's partial output is rolled back" );
		assertTrue( out.contains( "UnknownKeyException" ), "the error box is rendered in its place" );
	}

	@Test
	void rollbackKeepsContentThatWasSetAsData() {
		final FakeContext context = FakeContext.on( FakeComponent.named( "app.Main" ), "0" );
		final WOResponse response = new WOResponse();
		response.setContent( new NSData( PREFIX.getBytes( StandardCharsets.UTF_8 ) ) );

		proxy( new FailingComponentReference( FakeComponent.named( "app.StandingsTable" ) ) ).appendToResponse( response, context );

		assertTrue( response.contentString().startsWith( PREFIX ), "content held as data survives the rollback" );
	}

	@Test
	void recordsTheElementAndAPlainTextMessage() {
		final FakeContext context = FakeContext.on( FakeComponent.named( "app.Main" ), "0" );
		proxy( new FailingComponentReference( FakeComponent.named( "app.StandingsTable" ) ) ).appendToResponse( response( PREFIX ), context );

		final List<NGRuntimeProblems.Problem> problems = NGRuntimeProblems.snapshot( null, 0 );
		assertEquals( 1, problems.size() );
		final NGRuntimeProblems.Problem problem = problems.getFirst();
		assertEquals( "Binding error", problem.kind(), "an unknown key is a binding error" );
		assertEquals( "Main.html:3:5 <wo:StandingsTable>", problem.element() );
		assertFalse( problem.message().contains( "<" ), "plain text, no markup: " + problem.message() );
		assertTrue( problem.message().contains( "'standings'" ) && problem.message().contains( "StandingsTable" ), problem.message() );
	}

	@Test
	void errorBoxMarkupIsBalanced() {
		final WOResponse response = response( "" );
		proxy( new FailingComponentReference( FakeComponent.named( "app.StandingsTable" ) ) ).appendToResponse( response, FakeContext.on( FakeComponent.named( "app.Main" ), "0" ) );
		final String out = response.contentString();
		assertFalse( out.contains( "<stap" ) );
		assertEquals( count( out, "<span" ), count( out, "</span>" ), "every span is closed: " + out );
	}

	@Test
	void explainsAnOperatorOnAJavaCollection() {
		final Object players = List.of( "a", "b" );
		proxy( new FailingLookup( players, "@count", "team.players.@count" ) ).appendToResponse( response( "" ), FakeContext.on( FakeComponent.named( "app.Main" ), "0" ) );

		final String message = NGRuntimeProblems.snapshot( null, 0 ).getFirst().message();
		assertTrue( message.contains( "'@count' is a KVC operator" ) && message.contains( "'team.players' is a java.util.List" ), message );
	}

	@Test
	void noOperatorExplanationForAnNSArray() {
		// NSArray implements java.util.List but does support operators; the hint must not fire.
		final Object players = new com.webobjects.foundation.NSArray<>( new String[] { "a", "b" } );
		proxy( new FailingLookup( players, "@bogus", "team.players.@bogus" ) ).appendToResponse( response( "" ), FakeContext.on( FakeComponent.named( "app.Main" ), "0" ) );

		assertFalse( NGRuntimeProblems.snapshot( null, 0 ).getFirst().message().contains( "KVC operator" ) );
	}

	@Test
	void locationMarkersPrintWhereTheyPoint() {
		final ParsleyTemplatePosition position = new ParsleyTemplatePosition( "ClubBadge", 1, 21, "<wo:container>" );
		assertEquals( "parsley.ParsleySourceLocation: at ClubBadge.html:1:21 (<wo:container>)", new ParsleySourceLocation( node(), position ).toString() );
		assertEquals( "parsley.ParsleyBindingLocation: binding style = $badgeStyle", new ParsleyBindingLocation( "style", "badgeStyle" ).toString() );
	}

	@Test
	void positionIsResolvedFromTheTemplateSource() {
		final String template = "<div>\n\t\t<wo:str value=\"$x\" />\n</div>";
		final PNode str = new PBasicNode( "wo", "str", Map.of(), List.of(), true, true, "str", new SourceRange( template.indexOf( "<wo:str" ), template.indexOf( "/>" ) + 2 ) );
		final ParsleyTemplatePosition position = ParsleyTemplatePosition.of( "app.components.Main", template, str );
		assertEquals( "Main.html:2:3 <wo:str>", position.describe() );
	}

	// --- Helpers ---

	/** A {@code <wo:StandingsTable>} at Main.html:3:5. */
	private static ParsleyProxyElement proxy( final WOElement element ) {
		return new ParsleyProxyElement( element, node(), new ParsleyTemplatePosition( "Main", 3, 5, "<wo:StandingsTable>" ) );
	}

	private static PNode node() {
		return new PBasicNode( "wo", "StandingsTable", Map.of(), List.of(), true, true, "StandingsTable", new SourceRange( 0, 1 ) );
	}

	private static WOResponse response( final String prefix ) {
		final WOResponse response = new WOResponse();
		response.appendContentString( prefix );
		return response;
	}

	private static int count( final String s, final String sub ) {
		int n = 0;
		for( int i = s.indexOf( sub ); i != -1; i = s.indexOf( sub, i + 1 ) ) {
			n++;
		}
		return n;
	}

	/**
	 * Behaves like a WOComponentReference whose child renders, then fails pushing a binding
	 * back to the parent (a synchronizing component bound to a read-only keypath): the child
	 * is left as the current component, with its element ID component still appended.
	 */
	private static final class FailingComponentReference extends WOElement {

		private final WOComponent _child;

		FailingComponentReference( final WOComponent child ) {
			_child = child;
		}

		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {
			context._setCurrentComponent( _child );
			context.appendZeroElementIDComponent();
			response.appendContentString( "PARTIAL-CHILD-OUTPUT" );
			throw new ParsleyUnknownKeyException( "no setter for standings", new Object(), "standings", "league.standings", _child, "standings" );
		}

		@Override
		public void takeValuesFromRequest( final WORequest request, final WOContext context ) {}
	}

	/** An element whose binding lookup fails with an unknown key on the given object. */
	private static final class FailingLookup extends WOElement {

		private final Object _object;
		private final String _key;
		private final String _keyPath;

		FailingLookup( final Object object, final String key, final String keyPath ) {
			_object = object;
			_key = key;
			_keyPath = keyPath;
		}

		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {
			throw new ParsleyUnknownKeyException( "unknown key", _object, _key, _keyPath, context.component(), "value" );
		}
	}

	/** A component that only knows its name. */
	static class FakeComponent extends WOComponent {

		private String _name;

		static FakeComponent named( final String name ) {
			final FakeComponent component = allocate( FakeComponent.class );
			component._name = name;
			return component;
		}

		@Override
		public String name() {
			return _name;
		}
	}

	/** A context that only tracks its current component and element ID. */
	static class FakeContext extends WOContext {

		private WOComponent _current;
		private List<String> _elementID;

		FakeContext() {
			super( null ); // never runs: instances are allocated without a constructor
		}

		static FakeContext on( final WOComponent current, final String... elementID ) {
			final FakeContext context = allocate( FakeContext.class );
			context._current = current;
			context._elementID = new ArrayList<>( List.of( elementID ) );
			return context;
		}

		@Override
		public WOComponent component() {
			return _current;
		}

		@Override
		public void _setCurrentComponent( final WOComponent component ) {
			_current = component;
		}

		@Override
		public String elementID() {
			return String.join( ".", _elementID );
		}

		@Override
		public void appendZeroElementIDComponent() {
			_elementID.add( "0" );
		}

		@Override
		public void deleteLastElementIDComponent() {
			_elementID.removeLast();
		}

		@Override
		public String componentActionURL() {
			return "/fake/" + elementID();
		}
	}

	/** Instantiates a class without running any constructor (they'd need a running application). */
	@SuppressWarnings( "unchecked" )
	private static <T> T allocate( final Class<T> type ) {
		try {
			final Field field = Class.forName( "sun.misc.Unsafe" ).getDeclaredField( "theUnsafe" );
			field.setAccessible( true );
			final Object unsafe = field.get( null );
			return (T)unsafe.getClass().getMethod( "allocateInstance", Class.class ).invoke( unsafe, type );
		}
		catch( final Exception e ) {
			throw new IllegalStateException( "Can't allocate " + type, e );
		}
	}
}
