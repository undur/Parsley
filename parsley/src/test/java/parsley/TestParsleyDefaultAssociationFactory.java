package parsley;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.WOAssociation;
import com.webobjects.appserver._private.WOBindingNameAssociation;
import com.webobjects.appserver._private.WOConstantValueAssociation;
import com.webobjects.appserver._private.WOKeyValueAssociation;

import ng.appserver.templating.parser.NGDeclaration.NGBindingValue;

class TestParsleyDefaultAssociationFactory {

	private final ParsleyDefaultAssociationFactory factory = new ParsleyDefaultAssociationFactory();

	// ---- Inline bindings ----

	@Nested
	class InlineBindings {

		/**
		 * Quoted values without $ are constant strings — even if they look numeric
		 */
		@Test
		void quotedStringWithoutDollarIsConstantString() {
			WOAssociation assoc = inline( "hello", true );
			assertConstantValue( "hello", assoc );
		}

		@Test
		void quotedNumericWithoutDollarIsConstantString() {
			WOAssociation assoc = inline( "0", true );
			assertConstantValue( "0", assoc );
		}

		@Test
		void quotedNumericWithoutDollarIsConstantStringNot‌Number() {
			WOAssociation assoc = inline( "42", true );
			assertConstantValue( "42", assoc );
		}

		/**
		 * $ prefix makes values dynamic — numeric literals become numbers
		 */
		@Test
		void dollarZeroIsNumeric() {
			WOAssociation assoc = inline( "$0", true );
			assertConstantValue( 0, assoc );
		}

		@Test
		void dollarIntegerIsNumeric() {
			WOAssociation assoc = inline( "$42", true );
			assertConstantValue( 42, assoc );
		}

		@Test
		void dollarNegativeIntegerIsNumeric() {
			WOAssociation assoc = inline( "$-7", true );
			assertConstantValue( -7, assoc );
		}

		@Test
		void dollarDoubleIsNumeric() {
			WOAssociation assoc = inline( "$3.14", true );
			assertConstantValue( 3.14, assoc );
		}

		/**
		 * $ prefix with boolean literals
		 */
		@Test
		void dollarTrueIsBoolean() {
			WOAssociation assoc = inline( "$true", true );
			assertConstantValue( Boolean.TRUE, assoc );
		}

		@Test
		void dollarFalseIsBoolean() {
			WOAssociation assoc = inline( "$false", true );
			assertConstantValue( Boolean.FALSE, assoc );
		}

		@Test
		void dollarTrueIsCaseSensitive() {
			// $True should be treated as a keypath, not a boolean
			WOAssociation assoc = inline( "$True", true );
			assertInstanceOf( WOKeyValueAssociation.class, assoc );
		}

		/**
		 * $ prefix with keypaths
		 */
		@Test
		void dollarKeypathCreatesKeyValueAssociation() {
			WOAssociation assoc = inline( "$name", true );
			assertInstanceOf( WOKeyValueAssociation.class, assoc );
		}

		@Test
		void dollarCaretCreatesBindingNameAssociation() {
			WOAssociation assoc = inline( "$^parentBinding", true );
			assertInstanceOf( WOBindingNameAssociation.class, assoc );
		}

		@Test
		void dollarBangCreatesNegatedAssociation() {
			WOAssociation assoc = inline( "$!isDisabled", true );
			assertInstanceOf( ParsleyDefaultAssociationFactory.ParsleyNegatedBooleanAssociation.class, assoc );
		}

		/**
		 * Unquoted inline values without $ are also constant strings
		 */
		@Test
		void unquotedStringWithoutDollarIsConstantString() {
			WOAssociation assoc = inline( "hello", false );
			assertConstantValue( "hello", assoc );
		}

		/**
		 * Boolean presence attributes
		 */
		@Test
		void booleanPresenceIsTrue() {
			WOAssociation assoc = factory.associationForBindingValue( new NGBindingValue.BooleanPresence(), true );
			assertConstantValue( Boolean.TRUE, assoc );
		}
	}

	// ---- WOD bindings ----

	@Nested
	class WodBindings {

		/**
		 * Quoted WOD values are always constant strings
		 */
		@Test
		void quotedStringIsConstant() {
			WOAssociation assoc = wod( "hello world", true );
			assertConstantValue( "hello world", assoc );
		}

		@Test
		void quotedNumericStringIsConstantString() {
			WOAssociation assoc = wod( "42", true );
			assertConstantValue( "42", assoc );
		}

		/**
		 * Unquoted WOD values — numeric
		 */
		@Test
		void unquotedIntegerIsNumeric() {
			WOAssociation assoc = wod( "42", false );
			assertConstantValue( 42, assoc );
		}

		@Test
		void unquotedZeroIsNumeric() {
			WOAssociation assoc = wod( "0", false );
			assertConstantValue( 0, assoc );
		}

		@Test
		void unquotedDoubleIsNumeric() {
			WOAssociation assoc = wod( "3.14", false );
			assertConstantValue( 3.14, assoc );
		}

		@Test
		void unquotedNegativeIsNumeric() {
			WOAssociation assoc = wod( "-99", false );
			assertConstantValue( -99, assoc );
		}

		/**
		 * Unquoted WOD values — booleans (case-insensitive, multiple accepted values)
		 */
		@Test
		void unquotedTrueIsBoolean() {
			WOAssociation assoc = wod( "true", false );
			assertConstantValue( Boolean.TRUE, assoc );
		}

		@Test
		void unquotedTRUEIsBoolean() {
			WOAssociation assoc = wod( "TRUE", false );
			assertConstantValue( Boolean.TRUE, assoc );
		}

		@Test
		void unquotedYesIsBoolean() {
			WOAssociation assoc = wod( "yes", false );
			assertConstantValue( Boolean.TRUE, assoc );
		}

		@Test
		void unquotedFalseIsBoolean() {
			WOAssociation assoc = wod( "false", false );
			assertConstantValue( Boolean.FALSE, assoc );
		}

		@Test
		void unquotedNoIsBoolean() {
			WOAssociation assoc = wod( "no", false );
			assertConstantValue( Boolean.FALSE, assoc );
		}

		@Test
		void unquotedNilIsBoolean() {
			WOAssociation assoc = wod( "nil", false );
			assertConstantValue( Boolean.FALSE, assoc );
		}

		@Test
		void unquotedNullIsBoolean() {
			WOAssociation assoc = wod( "null", false );
			assertConstantValue( Boolean.FALSE, assoc );
		}

		/**
		 * Unquoted WOD values — keypaths
		 */
		@Test
		void unquotedKeypathCreatesKeyValueAssociation() {
			WOAssociation assoc = wod( "someProperty", false );
			assertInstanceOf( WOKeyValueAssociation.class, assoc );
		}

		@Test
		void unquotedCaretCreatesBindingNameAssociation() {
			WOAssociation assoc = wod( "^parentBinding", false );
			assertInstanceOf( WOBindingNameAssociation.class, assoc );
		}
	}

	// ---- Escape handling ----

	@Nested
	class EscapeHandling {

		@Test
		void newlineEscape() {
			assertEquals( "a\nb", ParsleyDefaultAssociationFactory.applyEscapes( "a\\nb" ) );
		}

		@Test
		void carriageReturnEscape() {
			assertEquals( "a\rb", ParsleyDefaultAssociationFactory.applyEscapes( "a\\rb" ) );
		}

		@Test
		void tabEscape() {
			assertEquals( "a\tb", ParsleyDefaultAssociationFactory.applyEscapes( "a\\tb" ) );
		}

		@Test
		void backslashEscape() {
			assertEquals( "a\\b", ParsleyDefaultAssociationFactory.applyEscapes( "a\\\\b" ) );
		}

		@Test
		void quoteEscape() {
			assertEquals( "a\"b", ParsleyDefaultAssociationFactory.applyEscapes( "a\\\"b" ) );
		}

		@Test
		void dollarEscape() {
			assertEquals( "a$b", ParsleyDefaultAssociationFactory.applyEscapes( "a\\$b" ) );
		}

		@Test
		void noEscapesReturnsOriginal() {
			assertEquals( "hello", ParsleyDefaultAssociationFactory.applyEscapes( "hello" ) );
		}

		@Test
		void unknownEscapeThrows() {
			assertThrows( IllegalArgumentException.class, () -> ParsleyDefaultAssociationFactory.applyEscapes( "a\\xb" ) );
		}
	}

	// ---- Numeric detection ----

	@Nested
	class NumericDetection {

		@Test
		void integer() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( "42" ) );
		}

		@Test
		void negativeInteger() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( "-7" ) );
		}

		@Test
		void negativeDouble() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( "-7.18" ) );
		}

		@Test
		void positiveSign() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( "+7" ) );
		}

		@Test
		void decimal() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( "3.14" ) );
		}

		@Test
		void leadingDot() {
			assertEquals( true, ParsleyDefaultAssociationFactory.isNumeric( ".5" ) );
		}

		@Test
		void notNumericWord() {
			assertEquals( false, ParsleyDefaultAssociationFactory.isNumeric( "abc" ) );
		}

		@Test
		void notNumericEmpty() {
			assertEquals( false, ParsleyDefaultAssociationFactory.isNumeric( "" ) );
		}

		@Test
		void notNumericJustSign() {
			assertEquals( false, ParsleyDefaultAssociationFactory.isNumeric( "-" ) );
		}

		@Test
		void notNumericJustDot() {
			assertEquals( false, ParsleyDefaultAssociationFactory.isNumeric( "." ) );
		}

		@Test
		void notNumericDoubleDot() {
			assertEquals( false, ParsleyDefaultAssociationFactory.isNumeric( "1.2.3" ) );
		}
	}

	// ---- Numeric conversion ----

	@Nested
	class NumericConversion {

		@Test
		void integerString() {
			assertEquals( 42, ParsleyDefaultAssociationFactory.numericValueFromString( "42" ) );
		}

		@Test
		void doubleString() {
			assertEquals( 3.14, ParsleyDefaultAssociationFactory.numericValueFromString( "3.14" ) );
		}

		@Test
		void integerReturnsInteger() {
			assertInstanceOf( Integer.class, ParsleyDefaultAssociationFactory.numericValueFromString( "42" ) );
		}

		@Test
		void decimalReturnsDouble() {
			assertInstanceOf( Double.class, ParsleyDefaultAssociationFactory.numericValueFromString( "3.14" ) );
		}
	}

	// ---- Helpers ----

	private WOAssociation inline( String value, boolean isQuoted ) {
		return factory.associationForBindingValue( new NGBindingValue.Value( isQuoted, value ), true );
	}

	private WOAssociation wod( String value, boolean isQuoted ) {
		return factory.associationForBindingValue( new NGBindingValue.Value( isQuoted, value ), false );
	}

	private static void assertConstantValue( Object expected, WOAssociation assoc ) {
		assertInstanceOf( WOConstantValueAssociation.class, assoc );
		assertEquals( expected, assoc.valueInComponent( null ) );
	}
}
