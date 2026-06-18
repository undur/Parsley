package parsley;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOContext;
import com.webobjects.appserver.WOElement;
import com.webobjects.appserver.WOResponse;

/**
 * Tests for the registration builder ({@link Parsley#configure()} /
 * {@link ParsleyConfiguration}): config is built and installed atomically, defaults are
 * sensible, and the inline-errors flag drives association-factory wrapping.
 */
class TestParsleyConfiguration {

	@BeforeEach
	void reset() {
		// configure() amends the current config, so each test starts from defaults.
		ParsleyTestSupport.resetParsleyConfiguration();
	}

	@Test
	void defaultsToBareDefaultFactoryAndNoInlineErrors() {
		Parsley.configure().register();

		assertInstanceOf( ParsleyDefaultAssociationFactory.class, Parsley.effectiveAssociationFactory(),
				"default association factory, unwrapped when inline errors are off" );
		assertSame( false, Parsley.showInlineErrorMessages() );
		assertSame( false, Parsley.shouldWrapElements() );
	}

	@Test
	void inlineErrorsWrapsTheAssociationFactoryAndEnablesWrapping() {
		Parsley.configure().inlineErrors( true ).register();

		assertInstanceOf( ParsleyProxyAssociationFactory.class, Parsley.effectiveAssociationFactory() );
		assertTrue( Parsley.showInlineErrorMessages() );
		assertTrue( Parsley.shouldWrapElements(), "wrapping is on when inline errors are on" );
	}

	@Test
	void registerUsesTheGivenAssociationFactory() {
		final ParsleyAssociationFactory custom = new ParsleyDefaultAssociationFactory();
		Parsley.configure().associationFactory( custom ).register();
		assertSame( custom, Parsley.effectiveAssociationFactory(), "the configured factory is used as-is when inline errors are off" );
	}

	@Test
	void woNamespaceIsAlwaysPresentByDefault() {
		Parsley.configure().register();
		// Resolves without throwing — the default "wo" factory is always installed.
		assertInstanceOf( ParsleyDefaultElementFactory.class, Parsley.elementFactoryForNamespace( "wo" ) );
		assertTrue( Parsley.dynamicNamespaces().contains( "wo" ) );
	}

	@Test
	void additionalElementFactoryIsRegistered() {
		final ParsleyElementFactory custom = new ParsleyDefaultElementFactory();
		Parsley.configure().elementFactory( "x", custom ).register();
		assertSame( custom, Parsley.elementFactoryForNamespace( "x" ) );
		assertTrue( Parsley.dynamicNamespaces().contains( "wo" ), "the default wo namespace is still there too" );
	}

	@Test
	void unknownNamespaceThrows() {
		Parsley.configure().register();
		assertThrows( IllegalStateException.class, () -> Parsley.elementFactoryForNamespace( "nope" ) );
	}

	@Test
	void configureAmendsTheCurrentConfigurationRatherThanResetting() {
		// Framework-style base registration with inline errors on.
		Parsley.configure().inlineErrors( true ).register();

		// App-style amendment later: add a namespace. Must keep inline errors AND wo.
		final ParsleyElementFactory html = new ParsleyDefaultElementFactory();
		Parsley.configure().elementFactory( "html", html ).register();

		assertTrue( Parsley.showInlineErrorMessages(), "amendment kept inline errors from the prior registration" );
		assertSame( html, Parsley.elementFactoryForNamespace( "html" ), "amendment added the new namespace" );
		assertTrue( Parsley.dynamicNamespaces().contains( "wo" ), "and kept the wo namespace" );
	}

	@Test
	void amendmentCanReplaceTheAssociationFactory() {
		final ParsleyAssociationFactory replacement = new ParsleyDefaultAssociationFactory();
		Parsley.configure().elementFactory( "html", new ParsleyDefaultElementFactory() ).register();
		Parsley.configure().associationFactory( replacement ).register();

		assertSame( replacement, Parsley.effectiveAssociationFactory(), "association factory replaced" );
		assertTrue( Parsley.dynamicNamespaces().contains( "html" ), "while keeping the earlier amendment" );
	}

	@Test
	void configurationKnowsWhenItNeedsTheRequestObserver() {
		// The observer is installed only when a response-rewriting feature is active.
		Parsley.configure().inlineErrors( false ).controls( false ).register();
		assertSame( false, Parsley.activeConfigurationNeedsObserver() );

		Parsley.configure().inlineErrors( true ).register();
		assertTrue( Parsley.activeConfigurationNeedsObserver() );
	}

	@Test
	void controlsStripAloneNeedsTheObserver() {
		// The controls strip is injected into the response, so it needs the observer even
		// when inline errors are off.
		Parsley.configure().inlineErrors( false ).controls( true ).register();
		assertTrue( Parsley.activeConfigurationNeedsObserver() );
		assertTrue( Parsley.showControls() );
	}

	@Test
	void devConfigurationEnablesInlineErrorsAndControls() {
		ParsleyConfiguration.defaultDevConfiguration().register();
		assertTrue( Parsley.showInlineErrorMessages() );
		assertTrue( Parsley.showControls() );
	}

	@Test
	void productionConfigurationEnablesNothing() {
		ParsleyConfiguration.defaultProductionConfiguration().register();
		assertSame( false, Parsley.showInlineErrorMessages() );
		assertSame( false, Parsley.showControls() );
		assertSame( false, Parsley.activeConfigurationNeedsObserver() );
	}

	@Test
	void renderProfilerFlagDrivesProfilerAndWrappingAndObserver() {
		try {
			Parsley.configure().renderProfiler( true ).register();
			assertTrue( Parsley.showRenderProfiler() );
			assertTrue( ParsleyRenderProfiler.isEnabled(), "the config flag drives the profiler's master switch" );
			assertTrue( Parsley.shouldWrapElements(), "the profiler needs elements wrapped" );
			assertTrue( Parsley.activeConfigurationNeedsObserver(), "the overlay needs the request observer" );

			Parsley.configure().renderProfiler( false ).register();
			assertSame( false, Parsley.showRenderProfiler() );
			assertSame( false, ParsleyRenderProfiler.isEnabled() );
		}
		finally {
			ParsleyTestSupport.resetParsleyConfiguration();
		}
	}

	@Test
	void devConfigurationDoesNotEnableRenderProfilerByDefault() {
		// The heat map is opt-in (via the controls strip), not on automatically in dev.
		ParsleyConfiguration.defaultDevConfiguration().register();
		assertSame( false, Parsley.showRenderProfiler() );
	}

	@Test
	void ordinaryElementsAreWrappedByDefault() {
		Parsley.configure().register();
		assertTrue( Parsley.shouldWrapElement( new StubElement() ) );
	}

	@Test
	void erxwoTemplateIsAlwaysExcludedFromWrapping() {
		// Built-in exclusion: ERXWOTemplate can't function inside a proxy. Matched by
		// simple name since the class lives in ERExtensions, which Parsley doesn't depend on.
		Parsley.configure().register();
		assertFalse( Parsley.shouldWrapElement( new ERXWOTemplate() ) );
	}

	@Test
	void excludeFromWrappingByClass() {
		Parsley.configure().excludeFromWrapping( StubElement.class ).register();
		assertFalse( Parsley.shouldWrapElement( new StubElement() ), "the excluded class isn't wrapped" );
		assertTrue( Parsley.shouldWrapElement( new OtherStubElement() ), "other elements still are" );
	}

	@Test
	void excludeFromWrappingBySimpleName() {
		Parsley.configure().excludeFromWrapping( "StubElement" ).register();
		assertFalse( Parsley.shouldWrapElement( new StubElement() ) );
		assertTrue( Parsley.shouldWrapElement( new OtherStubElement() ) );
	}

	@Test
	void exclusionsSurviveAmendmentAndKeepTheBuiltIn() {
		// Amending the config (the configure()-from-current model) keeps prior exclusions
		// AND the built-in ERXWOTemplate one.
		Parsley.configure().excludeFromWrapping( StubElement.class ).register();
		Parsley.configure().excludeFromWrapping( "OtherStubElement" ).register();

		assertFalse( Parsley.shouldWrapElement( new StubElement() ), "earlier class exclusion kept" );
		assertFalse( Parsley.shouldWrapElement( new OtherStubElement() ), "later name exclusion added" );
		assertFalse( Parsley.shouldWrapElement( new ERXWOTemplate() ), "built-in exclusion still there" );
	}

	// --- Stub elements (their simple class names matter for the simple-name tests) ---

	private static final class StubElement extends WOElement {
		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {}
	}

	private static final class OtherStubElement extends WOElement {
		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {}
	}

	/** Stands in for ERExtensions' ERXWOTemplate; only the simple name matters here. */
	private static final class ERXWOTemplate extends WOElement {
		@Override
		public void appendToResponse( final WOResponse response, final WOContext context ) {}
	}
}
