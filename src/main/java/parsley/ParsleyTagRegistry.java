package parsley;

import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSMutableDictionary;

/**
 * FIXME: Class shamelessly ripped from WOOgnl just to maintain working tag shortcuts. Needs some integration work // Hugi 2024-11-24
 */

public class ParsleyTagRegistry {

	private static NSMutableDictionary<String, String> _tagShortcutMap = new NSMutableDictionary<>();

	public static NSDictionary<String, String> tagShortcutMap() {
		return _tagShortcutMap;
	}

	public static void registerTagShortcut( String fullElementType, String shortcutElementType ) {
		_tagShortcutMap.setObjectForKey( fullElementType, shortcutElementType );
	}

	static {
		registerTagShortcut( "ERXElse", "else" );
		registerTagShortcut( "ERXWOConditional", "if" );
		registerTagShortcut( "ERXWOConditional", "conditional" );
		registerTagShortcut( "ERXWOConditional", "condition" ); // not in 5.4
		registerTagShortcut( "WORepetition", "foreach" );
		registerTagShortcut( "WORepetition", "repeat" );
		registerTagShortcut( "WORepetition", "repetition" );
		registerTagShortcut( "WORepetition", "loop" ); // not in 5.4
		registerTagShortcut( "WOComponentContent", "content" );
		registerTagShortcut( "WOComponentContent", "componentContent" );
		registerTagShortcut( "WOString", "str" ); // not in 5.4
		registerTagShortcut( "WOString", "string" );
		registerTagShortcut( "WOSwitchComponent", "switchComponent" );
		registerTagShortcut( "WOSwitchComponent", "switch" );
		registerTagShortcut( "WOXMLNode", "XMLNode" );
		registerTagShortcut( "WONestedList", "nestedList" );
		registerTagShortcut( "WOParam", "param" );
		registerTagShortcut( "WOApplet", "applet" );
		registerTagShortcut( "WOQuickTime", "quickTime" );
		registerTagShortcut( "WOHTMLCommentString", "commentString" );
		registerTagShortcut( "WOHTMLCommentString", "comment" );
		registerTagShortcut( "WONoContentElement", "noContentElement" );
		registerTagShortcut( "WONoContentElement", "noContent" );
		registerTagShortcut( "WOBody", "body" );
		registerTagShortcut( "WOEmbeddedObject", "embeddedObject" );
		registerTagShortcut( "WOEmbeddedObject", "embedded" );
		registerTagShortcut( "WOFrame", "frame" );
		registerTagShortcut( "WOImage", "image" );
		registerTagShortcut( "WOImage", "img" ); // not in 5.4
		registerTagShortcut( "WOForm", "form" );
		registerTagShortcut( "WOJavaScript", "javaScript" );
		registerTagShortcut( "WOVBScript", "VBScript" );
		registerTagShortcut( "WOResourceURL", "resourceURL" );
		registerTagShortcut( "WOGenericElement", "genericElement" );
		registerTagShortcut( "WOGenericElement", "element" );
		registerTagShortcut( "WOGenericContainer", "genericContainer" );
		registerTagShortcut( "WOGenericContainer", "container" );
		registerTagShortcut( "WOActiveImage", "activeImage" );
		registerTagShortcut( "WOCheckBox", "checkBox" );
		registerTagShortcut( "WOCheckBox", "checkbox" ); // not in 5.4 (5.4 is case insensitive)
		registerTagShortcut( "WOFileUpload", "fileUpload" );
		registerTagShortcut( "WOFileUpload", "upload" );
		registerTagShortcut( "WOHiddenField", "hiddenField" );
		registerTagShortcut( "WOHiddenField", "hidden" ); // not in 5.4
		registerTagShortcut( "WOImageButton", "imageButton" );
		registerTagShortcut( "WOInputList", "inputList" );
		registerTagShortcut( "WOBrowser", "browser" );
		registerTagShortcut( "WOCheckBoxList", "checkBoxList" );
		registerTagShortcut( "WOPopUpButton", "popUpButton" );
		registerTagShortcut( "WOPopUpButton", "select" ); // not in 5.4
		registerTagShortcut( "WORadioButtonList", "radioButtonList" );
		registerTagShortcut( "WOPasswordField", "passwordField" );
		registerTagShortcut( "WOPasswordField", "password" );
		registerTagShortcut( "WORadioButton", "radioButton" );
		registerTagShortcut( "WORadioButton", "radio" );
		registerTagShortcut( "WOResetButton", "resetButton" );
		registerTagShortcut( "WOResetButton", "reset" );
		registerTagShortcut( "WOSubmitButton", "submitButton" );
		registerTagShortcut( "WOSubmitButton", "submit" );
		registerTagShortcut( "WOText", "text" );
		registerTagShortcut( "WOTextField", "textField" );
		registerTagShortcut( "WOTextField", "textfield" ); // not in 5.4 (5.4 is case insensitive)
		registerTagShortcut( "WOSearchField", "search" );
		registerTagShortcut( "WOSearchField", "searchfield" );
		registerTagShortcut( "WOHyperlink", "hyperlink" );
		registerTagShortcut( "WOHyperlink", "link" );
		registerTagShortcut( "WOActionURL", "actionURL" );
	}
}