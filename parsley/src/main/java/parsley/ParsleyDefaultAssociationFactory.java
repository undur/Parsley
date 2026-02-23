package parsley;

import java.util.Objects;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver.WOComponent;
import com.webobjects.appserver._private.WOBindingNameAssociation;
import com.webobjects.appserver._private.WOConstantValueAssociation;
import com.webobjects.appserver._private.WOKeyValueAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

public class ParsleyDefaultAssociationFactory implements ParsleyAssociationFactory {

	private static final WOConstantValueAssociation TRUE = new WOConstantValueAssociation( Boolean.TRUE );
	private static final WOConstantValueAssociation FALSE = new WOConstantValueAssociation( Boolean.FALSE );

	/**
	 * @return An association for the given binding value
	 */
	@Override
	public WOAssociation associationForBindingValue( final NGBindingValue bindingValue, final boolean isInline ) {

		return switch( bindingValue ) {
			case NGBindingValue.BooleanPresence bork -> TRUE;
			case NGBindingValue.Value v -> associationForValue( v.value(), v.isQuoted(), isInline );
		};
	}

	/**
	 * @return An association for a binding value.
	 *
	 * For inline bindings, the '$' prefix is what determines if a value is dynamic or constant,
	 * regardless of whether the value was quoted or not. Quotes in inline bindings are just delimiters.
	 *
	 * For WOD bindings, quoted values are always constant strings. Unquoted values are dynamic.
	 */
	private static WOAssociation associationForValue( String value, final boolean isQuoted, final boolean isInline ) {
		Objects.requireNonNull( value );

		if( isInline ) {
			if( value.startsWith( "$" ) ) {
				// Inline value starting with $ — dynamic binding (key path)
				value = value.substring( 1 );
				return associationForDynamicValue( value, true );
			}

			// Inline value without $ prefix — constant string
			return associationForConstantStringValue( value );
		}

		// WOD binding
		if( isQuoted ) {
			return associationForConstantStringValue( value );
		}

		return associationForDynamicValue( value, false );
	}

	/**
	 * @return An association for the given constant string value, by applying the necessary escapes
	 */
	private static WOAssociation associationForConstantStringValue( String associationValue ) {
		associationValue = applyEscapes( associationValue );
		return associationForConstantValue( associationValue );
	}

	/**
	 * @return And association for the given dynamic value (a "dynamic value" being what we're calling any value following a $ in an inline binding or an unquoted value in a wod binding)
	 *
	 * CHECKME: I dislike the amount of values that represent booleans in wod files. Keeping it for now for legacy code compatibility, and I'd prefer not to introduce some lenient/"compatibility" mode // Hugi 2026-02-23
	 */
	private static WOAssociation associationForDynamicValue( final String associationValue, final boolean isInline ) {

		if( isNumeric( associationValue ) ) {
			final Number number = numericValueFromString( associationValue );
			return associationForConstantValue( number );
		}

		if( isInline ) {
			if( "true".equals( associationValue ) ) {
				return TRUE;
			}

			if( "false".equals( associationValue ) ) {
				return FALSE;
			}
		}
		else {
			if( "true".equalsIgnoreCase( associationValue ) || "yes".equalsIgnoreCase( associationValue ) ) {
				return TRUE;
			}

			if( "false".equalsIgnoreCase( associationValue ) || "no".equalsIgnoreCase( associationValue ) || "nil".equalsIgnoreCase( associationValue ) || "null".equalsIgnoreCase( associationValue ) ) {
				return FALSE;
			}
		}

		return associationForKeyPath( associationValue );
	}

	/**
	 * @return An association that returns the given value
	 */
	private static WOAssociation associationForConstantValue( final Object value ) {
		return new WOConstantValueAssociation( value );
	}

	/**
	 * @return An association for resolving the given keyPath
	 */
	private static WOAssociation associationForKeyPath( final String keyPath ) {

		if( keyPath.charAt( 0 ) == '^' ) {
			return new WOBindingNameAssociation( keyPath.substring( 1 ) );
		}

		if( keyPath.charAt( 0 ) == '!' ) {
			return new ParsleyNegatedBooleanAssociation( keyPath.substring( 1 ) );
		}

		if( Parsley.showInlineErrorMessages() ) {
			return new ParsleyKeyValueAssociation( keyPath );
		}

		return new WOKeyValueAssociation( keyPath );
	}

	public static class ParsleyNegatedBooleanAssociation extends WOKeyValueAssociation {

		public ParsleyNegatedBooleanAssociation( String keyPath ) {
			super( keyPath );
		}

		@Override
		public Object valueInComponent( WOComponent component ) {
			final Object value = super.valueInComponent( component );
			final boolean booleanValue = isTruthy( value );
			return !booleanValue;
		}

		@Override
		public boolean isValueSettable() {
			return false;
		}

		/**
		 * @return true if the given object is "truthy" for conditionals
		 *
		 * The only conditions for returning false are:
		 * - Boolean false (box or primitive)
		 * - A number that's exactly zero
		 * - null
		 */
		private static boolean isTruthy( Object object ) {

			if( object == null ) {
				return false;
			}

			if( object instanceof Boolean b ) {
				return b;
			}

			if( object instanceof Number number ) {
				// Note that Number.doubleValue() might return Double.NaN which is... Troublesome. Trying to decide if NaN is true or false is almost a philosophical question.
				// I'm still leaning towards keeping our definition of "only exactly zero is false", meaning NaN is true, making this code currently fine.
				return number.doubleValue() != 0;
			}

			return true;
		}
	}

	/**
	 * @return The given string with escape sequences converted to what they represent.
	 *
	 * Supports: \n (newline), \r (carriage return), \t (tab), \\ (backslash), \" (quote), \$ (dollar sign)
	 */
	static String applyEscapes( String string ) {

		int firstBackslashIndex = string.indexOf( '\\' );

		if( firstBackslashIndex == -1 ) {
			return string;
		}

		final StringBuilder sb = new StringBuilder( string );

		for( int i = firstBackslashIndex; i < sb.length(); i++ ) {
			if( sb.charAt( i ) == '\\' && i + 1 < sb.length() ) {
				char nextChar = sb.charAt( i + 1 );

				switch( nextChar ) {
					case 'n' -> sb.replace( i, i + 2, "\n" );
					case 'r' -> sb.replace( i, i + 2, "\r" );
					case 't' -> sb.replace( i, i + 2, "\t" );
					case '\\' -> sb.replace( i, i + 2, "\\" );
					case '"' -> sb.replace( i, i + 2, "\"" );
					case '$' -> sb.replace( i, i + 2, "$" );
					default -> throw new IllegalArgumentException( "Unknown escape character: '%s' (%s) ".formatted( nextChar, Character.getName( nextChar ) ) );
				}
			}
		}

		return sb.toString();
	}

	/**
	 * @return The given string converted to a number. If the number contains a decimal separator (period), returns a Double, if no decimal separator, returns an Integer.
	 */
	static Number numericValueFromString( final String numericString ) {

		if( numericString.contains( "." ) ) {
			return Double.valueOf( numericString );
		}

		// CHECKME: Determine the number's size and return a Long if it doesn't fit in an int? Or just always return a Long? // Hugi 2025-03-19
		return Integer.valueOf( numericString );
	}

	/**
	 * @return true if this is a numeric string. Note that a signed number (i.e. prefixed with a plus or a minus) is considered numeric
	 */
	static boolean isNumeric( final String string ) {

		int length = string.length();

		if( length == 0 ) {
			return false;
		}

		boolean dotAlreadySpotted = false;

		int i = 0;
		char character = string.charAt( i );

		if( (character == '-') || (character == '+') ) {
			i = 1;
		}
		else if( character == '.' ) {
			i = 1;
			dotAlreadySpotted = true;
		}

		// If we've already advanced and the string's length is only 1, the string is merely a period or a sign and not numeric.
		if( i == 1 && length == 1 ) {
			return false;
		}

		while( i < length ) {
			character = string.charAt( i++ );

			if( character == '.' ) {
				if( dotAlreadySpotted ) {
					return false;
				}
				dotAlreadySpotted = true;
			}
			else if( !(Character.isDigit( character )) ) {
				return false;
			}
		}

		return true;
	}
}