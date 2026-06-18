package parsley;

/**
 * Test-only helpers for working with Parsley's global registration state.
 */
final class ParsleyTestSupport {

	private ParsleyTestSupport() {}

	/**
	 * Registers the default configuration, giving a test a clean registration state.
	 * Needed because {@link Parsley#configure()} amends the current configuration, so
	 * configuration set by one test would otherwise leak into the next.
	 */
	static void resetParsleyConfiguration() {
		Parsley.register( ParsleyConfiguration.defaultConfiguration() );
	}
}
