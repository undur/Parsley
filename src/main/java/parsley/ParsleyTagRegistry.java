package parsley;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps tag names to actual element names
 */

public class ParsleyTagRegistry {

	private static Map<String, String> _tagShortcutMap = new HashMap<>();

	public static Map<String, String> tagShortcutMap() {
		return _tagShortcutMap;
	}

	public static void registerTagShortcut( String fullElementType, String shortcutElementType ) {
		_tagShortcutMap.put( shortcutElementType, fullElementType );
	}

	static {
		registerTagShortcut( "ERXElse", "else" );
		registerTagShortcut( "ERXWOConditional", "condition" ); // not in 5.4
		registerTagShortcut( "ERXWOConditional", "conditional" );
		registerTagShortcut( "ERXWOConditional", "if" );
		registerTagShortcut( "WOActionURL", "actionURL" );
		registerTagShortcut( "WOActiveImage", "activeImage" );
		registerTagShortcut( "WOApplet", "applet" );
		registerTagShortcut( "WOBody", "body" );
		registerTagShortcut( "WOBrowser", "browser" );
		registerTagShortcut( "WOCheckBox", "checkBox" );
		registerTagShortcut( "WOCheckBox", "checkbox" ); // not in 5.4 (5.4 is case insensitive)
		registerTagShortcut( "WOCheckBoxList", "checkBoxList" );
		registerTagShortcut( "WOComponentContent", "componentContent" );
		registerTagShortcut( "WOComponentContent", "content" );
		registerTagShortcut( "WOEmbeddedObject", "embedded" );
		registerTagShortcut( "WOEmbeddedObject", "embeddedObject" );
		registerTagShortcut( "WOFileUpload", "fileUpload" );
		registerTagShortcut( "WOFileUpload", "upload" );
		registerTagShortcut( "WOForm", "form" );
		registerTagShortcut( "WOFrame", "frame" );
		registerTagShortcut( "WOGenericContainer", "container" );
		registerTagShortcut( "WOGenericContainer", "genericContainer" );
		registerTagShortcut( "WOGenericElement", "element" );
		registerTagShortcut( "WOGenericElement", "genericElement" );
		registerTagShortcut( "WOHiddenField", "hidden" ); // not in 5.4
		registerTagShortcut( "WOHiddenField", "hiddenField" );
		registerTagShortcut( "WOHTMLCommentString", "comment" );
		registerTagShortcut( "WOHTMLCommentString", "commentString" );
		registerTagShortcut( "WOHyperlink", "hyperlink" );
		registerTagShortcut( "WOHyperlink", "link" );
		registerTagShortcut( "WOImage", "image" );
		registerTagShortcut( "WOImage", "img" ); // not in 5.4
		registerTagShortcut( "WOImageButton", "imageButton" );
		registerTagShortcut( "WOInputList", "inputList" );
		registerTagShortcut( "WOJavaScript", "javaScript" );
		registerTagShortcut( "WONestedList", "nestedList" );
		registerTagShortcut( "WONoContentElement", "noContent" );
		registerTagShortcut( "WONoContentElement", "noContentElement" );
		registerTagShortcut( "WOParam", "param" );
		registerTagShortcut( "WOPasswordField", "password" );
		registerTagShortcut( "WOPasswordField", "passwordField" );
		registerTagShortcut( "WOPopUpButton", "popUpButton" );
		registerTagShortcut( "WOPopUpButton", "select" ); // not in 5.4
		registerTagShortcut( "WOQuickTime", "quickTime" );
		registerTagShortcut( "WORadioButton", "radio" );
		registerTagShortcut( "WORadioButton", "radioButton" );
		registerTagShortcut( "WORadioButtonList", "radioButtonList" );
		registerTagShortcut( "WORepetition", "foreach" );
		registerTagShortcut( "WORepetition", "loop" ); // not in 5.4
		registerTagShortcut( "WORepetition", "repeat" );
		registerTagShortcut( "WORepetition", "repetition" );
		registerTagShortcut( "WOResetButton", "reset" );
		registerTagShortcut( "WOResetButton", "resetButton" );
		registerTagShortcut( "WOResourceURL", "resourceURL" );
		registerTagShortcut( "WOSearchField", "search" );
		registerTagShortcut( "WOSearchField", "searchfield" );
		registerTagShortcut( "WOString", "str" ); // not in 5.4
		registerTagShortcut( "WOString", "string" );
		registerTagShortcut( "WOSubmitButton", "submit" );
		registerTagShortcut( "WOSubmitButton", "submitButton" );
		registerTagShortcut( "WOSwitchComponent", "switch" );
		registerTagShortcut( "WOSwitchComponent", "switchComponent" );
		registerTagShortcut( "WOText", "text" );
		registerTagShortcut( "WOTextField", "textField" );
		registerTagShortcut( "WOTextField", "textfield" ); // not in 5.4 (5.4 is case insensitive)
		registerTagShortcut( "WOVBScript", "VBScript" );
		registerTagShortcut( "WOXMLNode", "XMLNode" );

	}
}