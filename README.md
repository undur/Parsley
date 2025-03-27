## Parsley

Parsley is a template parser for WO. It's based on WOOgnl and thus supports it's inline binding syntax. It's a pure WO project, meaning it does not require Project Wonder (although, of course, it works fine with Project Wonder as well).

## Why?

To get nice inline error messages when template parser errors occur (rather than huge stack-tracey exception pages). Currently, this only applies when you attempt to use an element/component that doesn't exist and for handling `UnknownKeyEception` (badly formed keypaths in bindings) and `WODynamicElementCreationException` which for well designed elements will cover things like wrong binding configuration.

![parsley_inline_error_screenshot](https://github.com/user-attachments/assets/f0614844-6941-4ab0-99cb-4a4713ee9186)
![unknowkeyexception](https://github.com/user-attachments/assets/6ce9393c-4ee9-46ce-9484-0d7ba2681d7b)

_Actually_, this isn't the real "why" of the project. But it's currently the nicest byproduct visible to the user, making for a good cover story.

## Usage

To use the parser, clone this repo and import the project to your workspace or `mvn install` it.

Then add this dependency to your `pom.xml`:

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>Parsley</artifactId>
	<version>0.1.0-SNAPSHOT</version>
</dependency>
```

…and add this somewhere in your application's initialization, for example in the Application class constructor:

```java
public Application() {
	parsley.Parsley.register();
	parsley.Parsley.showInlineRenderingErrors( isDevelopmentModeSafe() ); // For enabling inline error reporting in dev mode
}
```

## Differences from WOOgnl

* We don't support OGNL expressions in binding paths. Support could be added as a plugin, but I'm not a fan of it myself so...
* We don't support WOOGnl's `parseStandardTags` behaviour. Might be looked into if anyone actually uses it, which I doubt.
* We don't support tag processors (WOOgnl's `<wo:not>` being an example use). Never used them but the idea isn't that bad. However functionality of that kind needs work in the parser so that's for later.
* For inline bindings, only exactly `$true` and `$false` will get interpreted as booleans.
