package parsley;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps tag names to actual element names.
 *
 * <p>This is a single flat map of <em>alias &rarr; target</em>, and it deliberately serves
 * two purposes that are really the same operation:
 *
 * <ul>
 *   <li><b>Tag shortcuts</b> &mdash; a short, friendly tag name for an element, e.g.
 *       {@code str &rarr; WOString}.</li>
 *   <li><b>Element replacements</b> &mdash; a framework/app substituting one element class
 *       for another, e.g. {@code WOString &rarr; ERXWOString} (the kind of swap ERExtensions
 *       does at runtime via {@code ERXPatcher.replaceClass}).</li>
 * </ul>
 *
 * <p>Because both are just name-to-name aliases, lookup is <b>recursive</b>: resolving
 * {@code str} follows {@code str &rarr; WOString &rarr; ERXWOString} to its fixed point
 * (see {@link #resolve(String)}). A shortcut and a replacement compose automatically.
 *
 * <p>Beyond the built-in shortcuts registered below, aliases are contributed declaratively:
 * every {@code parsley-tag-aliases.properties} resource found on the classpath is loaded
 * (see {@link #ALIASES_RESOURCE}), so a framework or application can ship its own
 * replacements/shortcuts simply by dropping that file into its {@code src/main/resources}.
 *
 * <p><b>Conflicts and ordering.</b> When several files map the <em>same</em> alias to
 * <em>different</em> targets, there is no reliable way to honour classpath/dependency order
 * from runtime information ({@code ClassLoader.getResources} order is unspecified). So we do
 * not silently pick a "winner": the first registration for an alias stands and any later,
 * <em>conflicting</em> one is ignored with a warning. Non-conflicting declarations compose
 * freely. (Proper dependency-ordered precedence is deferred to a future tag-library manifest
 * that can declare identity/ordering.)
 */
public class ParsleyTagRegistry {

	private static final Logger logger = LoggerFactory.getLogger( ParsleyTagRegistry.class );

	/**
	 * Classpath resource name that frameworks/apps drop into {@code src/main/resources} to
	 * declare tag aliases. Each entry is {@code alias = target} (e.g. {@code WOString = ERXWOString}).
	 * Every copy of this resource on the classpath is loaded.
	 */
	public static final String ALIASES_RESOURCE = "parsley-tag-aliases.properties";

	private static Map<String, String> _tagShortcutMap = new HashMap<>();

	public static Map<String, String> tagShortcutMap() {
		return _tagShortcutMap;
	}

	/**
	 * Registers an alias from {@code shortcutElementType} (the name written in a template, or
	 * an element being replaced) to {@code fullElementType} (what it resolves to).
	 *
	 * <p>If the alias is already registered to a <em>different</em> target, the existing
	 * mapping is kept and the conflicting one is ignored with a warning &mdash; see the class
	 * note on conflicts and ordering.
	 */
	public static void registerTagShortcut( String fullElementType, String shortcutElementType ) {
		final String existing = _tagShortcutMap.get( shortcutElementType );
		if( existing != null && !existing.equals( fullElementType ) ) {
			logger.warn( "Ignoring conflicting tag alias '{}' -> '{}'; already registered as '{}' -> '{}'",
					shortcutElementType, fullElementType, shortcutElementType, existing );
			return;
		}
		_tagShortcutMap.put( shortcutElementType, fullElementType );
	}

	/**
	 * Resolves a tag/element name to its final target by following alias chains recursively
	 * to a fixed point. {@code str -> WOString -> ERXWOString} resolves to {@code ERXWOString}.
	 * A name with no alias resolves to itself. Cycles are broken defensively (a warning is
	 * logged and the last name in the chain is returned), so a bad declaration can't hang the
	 * parser.
	 *
	 * @param name the tag/element name as written in the template
	 * @return the fully-resolved element name
	 */
	public static String resolve( final String name ) {
		String current = name;
		final Set<String> seen = new HashSet<>();
		seen.add( current );
		String next;
		while( (next = _tagShortcutMap.get( current )) != null ) {
			if( !seen.add( next ) ) {
				logger.warn( "Cycle detected resolving tag alias for '{}' (at '{}'); stopping.", name, next );
				break;
			}
			current = next;
		}
		return current;
	}

	/**
	 * Loads every {@link #ALIASES_RESOURCE} on the classpath and registers its entries.
	 * Each property is {@code alias = target}. Invalid/blank entries are skipped.
	 */
	private static void loadAliasResources() {
		try {
			final ClassLoader cl = ParsleyTagRegistry.class.getClassLoader();
			final Enumeration<URL> resources = cl.getResources( ALIASES_RESOURCE );
			while( resources.hasMoreElements() ) {
				final URL url = resources.nextElement();
				try( InputStream in = url.openStream() ) {
					final Properties props = new Properties();
					props.load( in );
					for( final String alias : props.stringPropertyNames() ) {
						final String target = props.getProperty( alias );
						if( alias.isBlank() || target == null || target.isBlank() ) {
							continue;
						}
						// Properties are alias=target; registerTagShortcut takes (target, alias).
						registerTagShortcut( target.trim(), alias.trim() );
					}
					logger.debug( "Loaded tag aliases from {}", url );
				} catch( final IOException e ) {
					logger.warn( "Failed to read tag alias resource {}", url, e );
				}
			}
		} catch( final IOException e ) {
			logger.warn( "Failed to enumerate {} resources on the classpath", ALIASES_RESOURCE, e );
		}
	}

	static {
		// All aliases — including Parsley's own built-in shortcuts — are declared in
		// parsley-tag-aliases.properties resources and loaded here. Parsley ships its set in
		// its own resources; frameworks/apps contribute theirs (e.g. ERExtensions' element
		// replacements) by dropping the same-named file on the classpath. There are no
		// hardcoded registrations: the registry is purely data-driven.
		loadAliasResources();
	}
}
