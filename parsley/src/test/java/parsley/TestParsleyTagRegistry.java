package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ParsleyTagRegistry}'s recursive alias resolution and conflict handling.
 */
public class TestParsleyTagRegistry {

	@Test
	public void resolvesBuiltInShortcut() {
		// A plain shortcut resolves to its element.
		assertEquals( "WOString", ParsleyTagRegistry.resolve( "string" ) );
	}

	@Test
	public void unknownNameResolvesToItself() {
		assertEquals( "SomeUnknownElement", ParsleyTagRegistry.resolve( "SomeUnknownElement" ) );
	}

	@Test
	public void resolvesRecursivelyThroughAReplacement() {
		// Register an element replacement on top of an existing shortcut:
		// "str" -> "WOString" (built-in) and "WOString" -> "ERXWOString" (replacement)
		// should resolve all the way to "ERXWOString".
		ParsleyTagRegistry.registerTagShortcut( "ERXWOString", "WOString" );
		try {
			assertEquals( "ERXWOString", ParsleyTagRegistry.resolve( "str" ) );
			assertEquals( "ERXWOString", ParsleyTagRegistry.resolve( "WOString" ) );
		} finally {
			// Leave the shared static map as we found it for other tests.
			ParsleyTagRegistry.tagShortcutMap().put( "WOString", "WOString" );
			ParsleyTagRegistry.tagShortcutMap().remove( "WOString" );
		}
	}

	@Test
	public void conflictingRegistrationIsIgnored() {
		ParsleyTagRegistry.registerTagShortcut( "FirstTarget", "myConflictAlias" );
		// A second, different target for the same alias must NOT override the first.
		ParsleyTagRegistry.registerTagShortcut( "SecondTarget", "myConflictAlias" );
		try {
			assertEquals( "FirstTarget", ParsleyTagRegistry.resolve( "myConflictAlias" ) );
		} finally {
			ParsleyTagRegistry.tagShortcutMap().remove( "myConflictAlias" );
		}
	}

	@Test
	public void cycleDoesNotHang() {
		// a -> b -> a : resolve must terminate (and not loop forever).
		ParsleyTagRegistry.tagShortcutMap().put( "cycleA", "cycleB" );
		ParsleyTagRegistry.tagShortcutMap().put( "cycleB", "cycleA" );
		try {
			// Either endpoint is acceptable; the point is that it returns.
			final String resolved = ParsleyTagRegistry.resolve( "cycleA" );
			assertEquals( true, resolved.equals( "cycleA" ) || resolved.equals( "cycleB" ) );
		} finally {
			ParsleyTagRegistry.tagShortcutMap().remove( "cycleA" );
			ParsleyTagRegistry.tagShortcutMap().remove( "cycleB" );
		}
	}
}
