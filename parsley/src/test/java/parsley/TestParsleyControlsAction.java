package parsley;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the controls-strip toggle logic ({@link ParsleyControlsAction#toggleInlineErrors()}
 * / {@link ParsleyControlsAction#hideControls()}) — in particular that the toggles no-op
 * when the controls strip is disabled, so a stray URL can't flip Parsley's features (the
 * direct-action methods are thin wrappers over these statics). The action class itself
 * isn't constructed here because instantiating a WODirectAction pulls in the full WO
 * runtime.
 */
class TestParsleyControlsAction {

	@BeforeEach
	void reset() {
		ParsleyTestSupport.resetParsleyConfiguration();
	}

	@Test
	void toggleInlineErrorsNoOpsWhenControlsDisabled() {
		Parsley.configure().controls( false ).inlineErrors( false ).register();

		assertFalse( ParsleyControlsAction.toggleInlineErrors(), "refused: controls are off" );
		assertFalse( Parsley.showInlineErrorMessages(), "state unchanged" );
	}

	@Test
	void hideControlsNoOpsWhenControlsDisabled() {
		Parsley.configure().controls( false ).register();
		assertFalse( ParsleyControlsAction.hideControls(), "refused: controls already off" );
	}

	@Test
	void toggleInlineErrorsFlipsWhenControlsEnabled() {
		Parsley.configure().controls( true ).inlineErrors( false ).register();

		assertTrue( ParsleyControlsAction.toggleInlineErrors() );
		assertTrue( Parsley.showInlineErrorMessages(), "toggled on" );

		assertTrue( ParsleyControlsAction.toggleInlineErrors() );
		assertFalse( Parsley.showInlineErrorMessages(), "toggled back off" );
	}

	@Test
	void hideControlsTurnsControlsOff() {
		Parsley.configure().controls( true ).register();
		assertTrue( ParsleyControlsAction.hideControls() );
		assertFalse( Parsley.showControls(), "controls hidden" );
	}
}
