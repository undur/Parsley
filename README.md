## Parsley

Parsley is a template parser for WO templates. It's based on WOOgnl and thus supports it's inline binding syntax. It's a pure WO project and thus does not require Project Wonder.

Currently at development stage so mostly useful for testing, brainstorming and whatever other fun things you usually do with experimental parsers.

## Usage

To use the parser, clone this repo and import this project to your workspace or `mvn install` it.

Then add this dependency to your pom.xml

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>Parsley</artifactId>
	<version>0.1.0-SNAPSHOT</version>
</dependency>
```

And this somewhere in your application's initialization, for example in the Application class constructor.

```java
public Application() {
	parsley.Parsley.register();
}
```

## Why?

To get nice inline error messages when template parser errors occur (instead of a huge stack-tracey exception page). Currently, this only applies when you attempt to use an element/component that doesn't exists and to `WODynamicElementCreationException`, which for well designed elements will cover things like wrong binding configuration.

![parsley_inline_error_screenshot](https://github.com/user-attachments/assets/f0614844-6941-4ab0-99cb-4a4713ee9186)

_Actually_, this isn't the real "why" of the project. But it's currently the nicest byproduct visible to the user, making for a good cover story.

## Differences from WOOGNL

* We don't support OGNL binding paths. Support could be added as a plugin, but I'm not a fan of it myself so...
* We don't currently support WOOGnl's `parseStandardTags` bbehaviour. Might be looked into if anyone actually uses it, which I doubt.
* We don't support tag processors ( WOOgnl's `<wo:not` being an example usage). Never used them but the idea isn't that bad. But functionality of that kind needs to be developed in the parser so that's for later.
