## 🌿 Parsley

Parsley is a template parser for WO. It's based on WOOgnl and thus supports it's inline binding syntax. It's a pure WO project, meaning it does not require Project Wonder (although, of course, it works fine with Project Wonder as well).

## Usage

*Note that If you're using [wonder-slim](https://github.com/undur/wonder-slim) you don't have to do do anything to add or enable Parsley. It's already there.*

Parsley releases are deployed to the WOCommunity maven repository, so if you've got your environment set up for WO development just add this dependency to your `pom`.

```xml
<dependency>
	<groupId>is.rebbi.parsley</groupId>
	<artifactId>parsley</artifactId>
	<version>1.6.0</version>
</dependency>
```

### Enabling Parsley in your project

Parsley is not enabled by default so to use it as your app's default template parser, you'll have to activate it somewhere during your application's initialization. For example in your Application's constructor:

```java
public Application() {
	parsley.Parsley.configure()
		.inlineErrors( isDevelopmentModeSafe() ) // Inline error reporting, usually only in dev mode
		.controls( isDevelopmentModeSafe() )     // The in-page Parsley dev controls strip
		.register();
}
```

`configure()` returns a builder that *amends the current configuration*, so the framework can register Parsley once and an app can add to that registration later without losing it (e.g. `Parsley.configure().elementFactory( "html", new MyHTMLElementFactory() ).register();`). The builder supports:

```java
parsley.Parsley.configure()
	.associationFactory( new MyAssociationFactory() )   // a custom association factory
	.elementFactory( "html", new MyHTMLElementFactory() ) // an element factory for a namespace (the "wo" namespace is always present)
	.inlineErrors( isDevelopmentModeSafe() )            // inline display of template/binding errors
	.controls( isDevelopmentModeSafe() )                // the in-page Parsley dev controls strip
	.excludeFromWrapping( SomeElement.class )           // exclude an element from proxy wrapping (by class)
	.excludeFromWrapping( "SomeElementName" )           //   …or by simple class name
	.register();
```

For convenience, `ParsleyConfiguration.defaultDevConfiguration()` and `defaultProductionConfiguration()` provide ready-made builders to start from — development mode turns on inline errors and the controls strip, production turns everything off.

### Enabling OGNL expressions

OGNL expression support (using the `~` prefix in binding values) is provided by the optional `parsley-ognl` plugin. Add its dependency to your `pom`:

```xml
<dependency>
	<groupId>is.rebbi.parsley</groupId>
	<artifactId>parsley-ognl</artifactId>
	<version>1.6.0</version>
</dependency>
```

Then register Parsley with the plugin's association factory:

```java
parsley.Parsley.configure()
	.associationFactory( new parsley.ognl.ParsleyOgnlAssociationFactory() )
	.register();
```

The OGNL factory falls back to the default association factory for any binding that isn't an OGNL expression, so all the usual binding syntax keeps working.

### Tag aliases

A tag in a template is resolved to an element through an *alias map* — a flat map of `alias → target`. This serves two purposes that are really the same operation:

* **Shortcuts** — a short, friendly tag name for an element: `str` → `WOString`, `if` → `ERXWOConditional`.
* **Element replacements** — substituting one element class for another: `WOString` → `ERXWOString` (the kind of swap ERExtensions does at runtime).

Because both are just name-to-name aliases, resolution is **recursive**: `str` follows `str` → `WOString` → `ERXWOString` to its final target, so a shortcut and a replacement compose automatically.

Aliases are declared in `parsley-tag-aliases.properties` resources. Parsley ships its own built-in shortcuts this way, and a framework or app contributes its own simply by dropping a `parsley-tag-aliases.properties` file into its `src/main/resources`. Every copy of the file on the classpath is loaded — each entry is `alias = target`:

```properties
# An app or framework's parsley-tag-aliases.properties
WOString = ERXWOString
myWidget = com.example.MyWidgetElement
```

If the same alias is mapped to different targets by different files, the first registration stands and the conflicting one is ignored with a warning (classpath order isn't reliable, so Parsley doesn't silently pick a winner).

<!--
### Using latest development version

To use the current development version of the parser, clone this repo and import the project to your workspace or `mvn install` it.

Then add this dependency to your `pom.xml`:

```xml
<dependency>
	<groupId>is.rebbi.parsley</groupId>
	<artifactId>parsley</artifactId>
	<version>1.4.1-SNAPSHOT</version>
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

* OGNL expressions are supported via the optional `parsley-ognl` module (see below).
* We don't support WOOGnl's `parseStandardTags` behaviour.
* We don't support tag processors (WOOgnl's `<wo:not>` being an example use). Never used them but the idea isn't that bad. However functionality of that kind needs a little work in the parser.
* For inline constant bindings, only exactly `$true` and `$false` will get interpreted as booleans (these were case insensitive in WOOgnl).

## Release notes

### 1.6.0 - 2026-06-26

* Tag shortcuts and element replacements are now declarative and recursive. Parsley's built-in shortcuts (e.g. `str` → `WOString`) live in a `parsley-tag-aliases.properties` resource, and any framework or app can contribute its own — including element-class replacements like `WOString` → `ERXWOString` — by dropping the same-named file into its `src/main/resources`. Aliases resolve recursively, so a shortcut and a replacement compose automatically (`str` → `WOString` → `ERXWOString`). See [Tag aliases](#tag-aliases).
* Better performance when using inline error messages.

### 1.5.0 - 2026-06-18

* **Breaking:** Parsley is now registered with a fluent builder — `Parsley.configure().…​.register()` — replacing the old `Parsley.register()` / `Parsley.showInlineRenderingErrors(…)` / `Parsley.registerElementFactory(…)` calls. The builder amends the current configuration, so a framework can register Parsley once and an app can add to that registration later. `ParsleyConfiguration.defaultDevConfiguration()` / `defaultProductionConfiguration()` provide ready-made starting points.
* Binding-failure annotation (for inline error display) is now applied generically to *every* association type via a binding-layer proxy, rather than only key-value associations.
* Elements can be excluded from proxy wrapping by class or simple name via `.excludeFromWrapping(…)`.
* New in-page development controls strip (`.controls(true)`) — a small expanding control in the bottom-left corner for toggling Parsley's dev features at runtime.
* The template parser was extracted into its own `ParsleyTemplateParser` class; `Parsley` is now purely the library's entry point and configuration.

### 1.4.2 - 2026-06-01

* Exceptions thrown during rendering, action invocation and value push/pull now carry the source location (template line/column and binding) where they originated, making template errors much easier to diagnose.
* `WOComponentReference` is now wrapped in `ParsleyProxyElement`, so source-location reporting also covers nested component references.
* Update slf4j to 2.0.18
* Update junit to 6.1.0

### 1.4.1 - 2026-04-22

* Template parser now fails on duplicate attributes in dynamic tags, e.g. `<wo:str value="yeah" value="wat" />`. Previous versions (and WOOgnl) silently ignored duplicate bindings and just used the last declared binding value.
* Update ognl version used by `parsley-ognl` to 3.4.11

### 1.4.0 - 2026-04-07

* **Breaking:** Maven groupId changed from `is.rebbi` to `is.rebbi.parsley`
* Pluggable association factories via `Parsley.register(factory)`
* Pluggable element factories per namespace via `Parsley.registerElementFactory(namespace, factory)`
* New `parsley-ognl` module providing optional OGNL expression support (prefix `~` in binding values)
* Parser and template model classes now provided by the `ng-template-parser` dependency. The parser has been completely rewritten as a single-pass recursive descent parser, replacing the old 3-stage pipeline (NGStringTokenizer → NGHTMLParser → callback → NGTemplateParser). New parser features (via ng-template-parser):
	- **`<p:raw>...</p:raw>` directive** — Wraps content that should be passed through verbatim without any template processing. Supports nesting. Useful for wrapping `<script>` blocks or any content that might contain characters that could confuse the parser.
	* **`<p:comment>...</p:comment>` directive** — Developer comments that are stripped entirely from rendered output. Also supports nesting. Unlike HTML comments, these are guaranteed to produce nothing in the output.
	* **Configurable dynamic namespaces** — The old parser hardcoded `wo:` (and `webobject`). The new parser accepts a configurable set of namespace names. Tags with unrecognized namespaces (e.g. `svg:rect`, `xsl:template`) pass through as plain HTML.
	* **Source position tracking** — Every node carries a source range. Error messages now include line and column numbers. The old parser just said "something's wrong"; the new one says "line 42, column 15".
	* **Boolean (valueless) attributes** — Inline tags now support HTML-style boolean attributes, e.g. `<wo:Widget disabled />`. The old parser required every binding to have a value.
	* **Self-closing tag tracking** — The parser now records whether a tag was self-closing (`/>`).
	* **Better error detection** — Catches malformed tags like `<wo: Repetition>` (space after colon) and `</ wo:Conditional>` (space after `</`) with specific error messages, rather than silently misbehaving.
	* **HTML comments no longer specially parsed** — `<!-- -->` comments flow through as plain HTML content rather than being tracked by the parser. Use `<p:comment>` for comments you want stripped from output.

### 1.3.0 - 2025-11-15

* Exclude ERXWOTemplate from element proxying in dev mode

### 1.2.0 - 2025-10-15

* Deploy to WOCommunity maven repo
* Nicer messages for UnknownKeyException
* Some generic parser logic cleanup

### 1.1.0 - 2025-03-30

* Only handle rendering exceptions inline that we excplicitly know how to handle

### 1.0.0 - 2025-03-27

* Initial release