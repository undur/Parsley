package parsley;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
