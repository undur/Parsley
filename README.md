## Parsley

Parsley is a template parser for WO templates. It's based on WOOgnl and thus supports it's inline binding syntax. It's a pure WO project and thus does not require Project Wonder.

Currently at development stage so mostly useful for testing, brainstorming and whatever other fun things you usually do with experimental parsers.

## Usage

To use the parser, clone this repo and import this project to your workspace or `mvn install` it.

Then add this to your pom.xml

```xml
<plugin>
  <groupId>is.rebbi</groupId>
  <artifactId>parsley</artifactId>
  <version>0.1.0</version>
</plugin>
```

And this somewhere in your application's initialization, for example in the Application class constructor.

```java
public Application() {
	parsley.Parsley.register();
}
```

<!--
## Differences from WOOGNL


* We don't support OGNL
-->