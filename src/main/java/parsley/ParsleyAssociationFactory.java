package parsley;

import java.util.Objects;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver._private.WOBindingNameAssociation;
import com.webobjects.appserver._private.WOConstantValueAssociation;
import com.webobjects.appserver._private.WOKeyValueAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;
import parsley.experimental.ParsleyKeyValueAssociation;

/**
 * FIXME: Everything here needs to be refactored and fixed up // Hugi 2024-11-24
 */

public class ParsleyAssociationFactory {

	public static final WOConstantValueAssociation TRUE = new WOConstantValueAssociation( Boolean.TRUE );
	public static final WOConstantValueAssociation FALSE = new WOConstantValueAssociation( Boolean.FALSE );

	public static WOAssociation constantValueAssociationWithValue( Object obj ) {
		return new WOConstantValueAssociation( obj );
	}

	public static WOAssociation associationWithKeyPath( String keyPath ) {

		if( keyPath.charAt( 0 ) == '^' ) {
			return new WOBindingNameAssociation( keyPath.substring( 1 ) ); // FIXME: WOOgnl actually uses a different class here with some fixes that might matter // Hugi 2024-11-24
		}

		if( Parsley.showInlineErrorMessagesForRenderingErrors ) {
			return new ParsleyKeyValueAssociation( keyPath );
		}

		return new WOKeyValueAssociation( keyPath );
	}

	/**
	 * FIXME: This is kind of shitty // Hugi 2024-11-16
	 */
	public static WOAssociation associationForBindingValue( final NGBindingValue bindingValue, final boolean isInline ) {

		if( isInline ) {
			return associationForInlineBindingValue( bindingValue.value() );
		}

		return associationForWodBindingValue( bindingValue.value(), bindingValue.isQuoted() );
	}

	/**
	 * FIXME: This is kind of shitty // Hugi 2024-11-16
	 */
	private static WOAssociation associationForInlineBindingValue( String value ) {
		Objects.requireNonNull( value );

		if( value.startsWith( "\"" ) ) {
			value = value.substring( 1 );

			if( value.endsWith( "\"" ) ) {
				value = value.substring( 0, value.length() - 1 );
			}
			else {
				throw new IllegalArgumentException( value + " starts with quote but does not end with one. The parser should have already failed on this" );
			}

			if( value.startsWith( "$" ) ) {
				value = value.substring( 1 );

				if( value.endsWith( "VALID" ) ) {
					value = value.replaceFirst( "\\s*//\\s*VALID", "" );
				}

				return associationForWodBindingValue( value, false );
			}
			else {
				// FIXME: Figure out what the absolute ding-diddly we're doing here // Hugi 2024-11-23
				value = value.replaceAll( "\\\\\\$", "\\$" );
				value = value.replaceAll( "\\\"", "\"" );
				return associationForWodBindingValue( value, true );
			}
		}

		return associationForWodBindingValue( value, false );
	}

	/**
	 * FIXME: This is kind of shitty // Hugi 2024-11-16
	 */
	private static WOAssociation associationForWodBindingValue( String associationValue, final boolean isQuoted ) {
		Objects.requireNonNull( associationValue );

		if( isQuoted ) {
			// MS: WO 5.4 converts \n to an actual newline. I don't know if WO 5.3 does, too, but let's go ahead and be compatible with them as long as nobody is yelling.
			// FIXME: Escaping needs to be thought through and standardized, for both wod and inline bindings // Hugi 2024-11-23
			associationValue = applyEscapes( associationValue );
			return constantValueAssociationWithValue( associationValue );
		}

		if( isNumeric( associationValue ) ) {
			final Number number;

			if( associationValue.contains( "." ) ) {
				number = Double.valueOf( associationValue );
			}
			else {
				number = Integer.parseInt( associationValue );
			}

			return constantValueAssociationWithValue( number );
		}

		if( "true".equalsIgnoreCase( associationValue ) || "yes".equalsIgnoreCase( associationValue ) ) {
			return TRUE;
		}

		if( "false".equalsIgnoreCase( associationValue ) || "no".equalsIgnoreCase( associationValue ) || "nil".equalsIgnoreCase( associationValue ) || "null".equalsIgnoreCase( associationValue ) ) {
			return FALSE;
		}

		return associationWithKeyPath( associationValue );
	}

	/**
	 * @return The given string with escape sequences \r, \n and \t converted to what they represent
	 */
	private static String applyEscapes( String string ) {
		int backslashIndex = string.indexOf( '\\' );

		if( backslashIndex != -1 ) {
			StringBuilder sb = new StringBuilder( string );
			int length = sb.length();

			for( int i = backslashIndex; i < length; i++ ) {
				char ch = sb.charAt( i );
				if( ch == '\\' && i < length ) {
					char nextCh = sb.charAt( i + 1 );
					if( nextCh == 'n' ) {
						sb.replace( i, i + 2, "\n" );
					}
					else if( nextCh == 'r' ) {
						sb.replace( i, i + 2, "\r" );
					}
					else if( nextCh == 't' ) {
						sb.replace( i, i + 2, "\t" );
					}
					else {
						sb.replace( i, i + 2, String.valueOf( nextCh ) );
					}
					length--;
				}
			}

			string = sb.toString();
		}

		return string;
	}

	/**
	 * @return true if this is a numeric string. Note that a signed number (i.e. prefixed with a plus or a minus) is considered numeric
	 */
	static boolean isNumeric( final String string ) {
		int length = string.length();

		if( length == 0 ) {
			return false;
		}

		boolean dot = false;
		int i = 0;
		char character = string.charAt( 0 );

		if( (character == '-') || (character == '+') ) {
			i = 1;
		}
		else if( character == '.' ) {
			i = 1;
			dot = true;
		}

		while( i < length ) {
			character = string.charAt( i++ );

			if( character == '.' ) {
				if( dot ) {
					return false;
				}
				dot = true;
			}
			else if( !(Character.isDigit( character )) ) {
				return false;
			}
		}

		return true;
	}
}