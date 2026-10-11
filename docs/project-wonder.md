# Using Parsley with Project Wonder

Parsley replaces WOOgnl as your application's template parser. It reads the same inline binding syntax (`<wo:str value="$name" />`), so templates written for WOOgnl generally work unchanged. The two can't run side by side: WOOgnl installs its own parser when the application finishes launching, replacing Parsley's. So moving to Parsley means removing WOOgnl.

*If you're using [wonder-slim](https://github.com/undur/wonder-slim), none of this applies. Parsley is already there.*

## 1. Replace the WOOgnl dependency

Remove `WOOgnl` from your application's dependencies. ERExtensions doesn't depend on it, so nothing else needs it. Then add Parsley:

```xml
<dependency>
	<groupId>is.rebbi.parsley</groupId>
	<artifactId>parsley</artifactId>
	<version>1.6.4</version>
</dependency>
```

If your templates use OGNL expressions (bindings prefixed with `~`), add `parsley-ognl` as well. See [Enabling OGNL expressions](../README.md#enabling-ognl-expressions).

Parsley doesn't bring WebObjects with it. It expects your application to declare its WebObjects dependencies, as WebObjects applications already do.

## 2. Register Parsley

In your Application's constructor:

```java
public Application() {
	parsley.Parsley.configure()
		.inlineErrors( isDevelopmentModeSafe() )
		.controls( isDevelopmentModeSafe() )
		.register();
}
```

The `ognl.*` properties (`ognl.active`, `ognl.helperFunctions`, `ognl.inlineBindings`, `ognl.parseStandardTags`, `ognl.debugSupport`) configure WOOgnl, so with WOOgnl gone you can remove them. Inline bindings are always on in Parsley.

## 3. Register Wonder's tag shortcuts

Parsley ships the same tag shortcuts as WOOgnl (`str`, `if`, `repeat`, `link`…), with one difference: WOOgnl points a few of them at Wonder's own elements, and Parsley, which doesn't depend on Wonder, can't. Add a `parsley-tag-aliases.properties` file to your application's `src/main/resources`:

```properties
localized = ERXLocalizedString
else = ERXElse
WOConditional = ERXWOConditional
```

`WOConditional = ERXWOConditional` replaces the element rather than the shortcut, so Parsley's `if`, `conditional` and `condition` shortcuts all resolve to `ERXWOConditional`, as does a `<wo:WOConditional>` written out in full. That's also what makes `<wo:else>` work: `ERXElse` reads the result of the `ERXWOConditional` before it. Don't map `if = ERXWOConditional` directly. Parsley already defines `if`, and when two files map the same alias to different targets, the first one loaded wins (with a warning) and the classpath order isn't reliable.

Simple class names are enough: Parsley looks elements up the way WebObjects does for a WOD file. A fully qualified class name works too, for the rare class WebObjects can't find by simple name (see [Tag aliases](../README.md#tag-aliases)).

## Elements patched by ERXPatcher

These keep working without configuration. `ERXPatcher.setClassForName()` registers a patch class under the element's original name (`WOHyperlink`, `WOTextField`, `WOSubmitButton`…), and Parsley asks WebObjects for elements by name, so it gets the patched class just as WOOgnl did.

## What's not supported

- **Tag processors**, such as WOOgnl's `<wo:not>`. Use a conditional with `negate`: `<wo:if condition="$isEmpty" negate="$true">`.
- **Helper functions** (`$value|helperName`, enabled by `ognl.helperFunctions`). Move the formatting into a method on the component or the object.
- **`ognl.parseStandardTags`**, where WOOgnl treats plain HTML tags with bindings as dynamic elements.
- **Case-insensitive boolean constants.** Only exactly `$true` and `$false` are read as booleans; WOOgnl also accepted e.g. `$TRUE`.
- **ERXApplication's binding debugging**, which needs WOOgnl's parser with `ognl.debugSupport`.

## Inline errors and element wrapping

With `inlineErrors` on, Parsley wraps each dynamic element in a proxy that catches its rendering errors and shows them in place on the page. A few Wonder elements depend on being the direct child of a particular parent, which a proxy in between breaks: `ERXWOTemplate` must sit directly inside its component reference, and `ERXWOSwitch` rejects any child that isn't an `ERXWOCase`. Those two are never wrapped. If another element misbehaves only with inline errors on, exclude it:

```java
parsley.Parsley.configure()
	.excludeFromWrapping( "SomeElement" )
	.register();
```
