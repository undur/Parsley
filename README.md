## 🌿 Parsley

Parsley is a template parser for WO. It's based on WOOgnl and thus supports it's inline binding syntax. It's a pure WO project, meaning it does not require Project Wonder (although, of course, it works fine with Project Wonder as well).

## Usage

*Note that If you're using [wonder-slim](https://github.com/undur/wonder-slim) you don't have to do do anything to add or enable Parsley. It's already there.*

Parsley releases are deployed to the WOCommunity maven repository, so if you've got your environment set up for WO development just add this dependency to your `pom`.

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>parsley</artifactId>
	<version>1.2.0</version>
</dependency>
```

### Enabling Parsley in your project

Parsley is not enabled by default so to use it as your app's default template parser, you'll have to activate it somewhere during your application's initialization. For example in your Application's constructor:

```java
public Application() {
	parsley.Parsley.register();
	parsley.Parsley.showInlineRenderingErrors( isDevelopmentModeSafe() ); // For enabling inline error reporting in dev mode
}
```

<!--
### Using latest development version

To use the current development version of the parser, clone this repo and import the project to your workspace or `mvn install` it.

Then add this dependency to your `pom.xml`:

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>parsley</artifactId>
	<version>1.3.0-SNAPSHOT</version>
</dependency>
```
-->

## Why?

To get nice inline error messages when template parser errors occur (rather than huge stack-tracey exception pages). Currently, this only applies when you attempt to use an element/component that doesn't exist and for handling `UnknownKeyEception` (badly formed keypaths in bindings) and `WODynamicElementCreationException` which for well designed elements will cover things like wrong binding configuration.

_Actually_, this isn't the real "why" of the project. But it's currently the nicest byproduct visible to the user, making for a good cover story.

![parsley_inline_error_screenshot](https://github.com/user-attachments/assets/f0614844-6941-4ab0-99cb-4a4713ee9186)
![unknowkeyexception](https://github.com/user-attachments/assets/6ce9393c-4ee9-46ce-9484-0d7ba2681d7b)

### A little video demonstration

[![IMAGE ALT TEXT HERE](https://img.youtube.com/vi/OwL2PRel0mU/0.jpg)](https://www.youtube.com/watch?v=OwL2PRel0mU)

## Differences from WOOgnl

* We don't support OGNL expressions in binding paths. Support could be added as a plugin feature if anyone *really* wants it.
* We don't support WOOGnl's `parseStandardTags` behaviour.
* We don't support tag processors (WOOgnl's `<wo:not>` being an example use). Never used them but the idea isn't that bad. However functionality of that kind needs a little work in the parser.
* For inline constant bindings, only exactly `$true` and `$false` will get interpreted as booleans (these were case insensitive in WOOgnl).

## Release notes

### 1.2.0 - 2025-10-15

* Deploy to WOCommunity maven repo
* Nicer messages for UnknownKeyException
* Some generic parser logic cleanup

### 1.1.0 - 2025-03-30

* Only handle rendering exceptions inline that we excplicitly know how to handle

### 1.0.0 - 2025-03-27

* Initial release