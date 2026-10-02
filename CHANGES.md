# Changelog

## Unreleased

## 2026-10-02 (1.6.2)

- **Inline error display keeps what was rendered before the failing element**
  The doctype, the head and the stylesheets are no longer lost when an element fails, and a
  synchronizing component that fails to push a binding back to its parent no longer cascades into
  further errors: the context is put back as it was before the element rendered. (#30)

- **Recorded problems name their element**
  An entry in ng-core's runtime problem buffer names the template location and tag
  (`Main.html:14:9 <wo:str>`), carries a plain-text message, and has an explicit kind, "Binding error"
  or "Template error". (#31)

- **Unknown-key messages explain collection operators and keys that can't be set**
  A KVC operator (`@count`, `@sum`…) applied to a `java.util` collection is explained: in WebObjects,
  operators only work on an NSArray. A key that can't be *set* is reported as such, rather than as
  missing. (#31)

- **Location markers in stack traces are readable**
  `at ClubBadge.html:1:21 (<wo:container>)` and `binding style = $badgeStyle`. (#32)

- **`ERXWOCase` is no longer wrapped** by Parsley's proxy element, like `ERXWOTemplate`. (#28)

- **Dependency updates**: ng-objects 0.1.3, slf4j 2.0.20.

## 2026-09-21 (1.6.1)

- **Rendered binding errors are recorded**
  Binding errors rendered inline are also recorded to ng-core's runtime problem buffer, so development
  tooling (e.g. wonder-slim's `/problems` dev endpoint) can list template errors observed at render
  time.

- **`if` targets `WOConditional`**
  The `if`/`condition`/`conditional` shortcuts now target `WOConditional`, and the `else` shortcut
  moved to the framework providing `ERXElse`. Frameworks supply or remap these through their own
  `parsley-tag-aliases.properties`.

- **Dependency updates**: ng-objects 0.1.2, slf4j 2.0.19, OGNL 3.4.13.

## 2026-06-26 (1.6.0)

- **Tag shortcuts and element replacements are declarative and recursive**
  Parsley's built-in shortcuts (e.g. `str` → `WOString`) live in a `parsley-tag-aliases.properties`
  resource, and any framework or app can contribute its own, including element-class replacements like
  `WOString` → `ERXWOString`, by dropping the same-named file into its `src/main/resources`. Aliases
  resolve recursively, so a shortcut and a replacement compose automatically
  (`str` → `WOString` → `ERXWOString`). See [Tag aliases](README.md#tag-aliases).

- **Better performance when using inline error messages.**

## 2026-06-18 (1.5.0)

- **Breaking: Parsley is registered with a fluent builder**
  `Parsley.configure().….register()` replaces the old `Parsley.register()` /
  `Parsley.showInlineRenderingErrors(…)` / `Parsley.registerElementFactory(…)` calls. The builder
  amends the current configuration, so a framework can register Parsley once and an app can add to
  that registration later. `ParsleyConfiguration.defaultDevConfiguration()` /
  `defaultProductionConfiguration()` provide ready-made starting points.

- **Binding failures are annotated for every association type**
  Annotation (for inline error display) is applied generically through a binding-layer proxy, rather
  than only to key-value associations.

- **Elements can be excluded from proxy wrapping** by class or simple name, via
  `.excludeFromWrapping(…)`.

- **In-page development controls**
  `.controls(true)` adds a small expanding control in the bottom-left corner for toggling Parsley's
  dev features at runtime.

- **The template parser is its own class**
  The parser was extracted into `ParsleyTemplateParser`; `Parsley` is now purely the library's entry
  point and configuration.

## 2026-06-01 (1.4.2)

- **Exceptions carry their template source location**
  Exceptions thrown during rendering, action invocation and value push/pull carry the source location
  (template line/column and binding) where they originated, making template errors much easier to
  diagnose.

- **`WOComponentReference` is wrapped** in `ParsleyProxyElement`, so source-location reporting also
  covers nested component references.

- **Dependency updates**: slf4j 2.0.18, junit 6.1.0.

## 2026-04-22 (1.4.1)

- **Duplicate attributes in dynamic tags are an error**
  The template parser fails on e.g. `<wo:str value="yeah" value="wat" />`. Previous versions (and
  WOOgnl) silently used the last declared binding value.

- **Dependency updates**: OGNL 3.4.11 (in `parsley-ognl`).

## 2026-04-07 (1.4.0)

- **Breaking: the Maven groupId is `is.rebbi.parsley`** (was `is.rebbi`).

- **Pluggable association factories** via `Parsley.register(factory)`.

- **Pluggable element factories per namespace** via
  `Parsley.registerElementFactory(namespace, factory)`.

- **OGNL support in a new `parsley-ognl` module**
  Optional OGNL expressions in binding values, prefixed with `~`.

- **A new parser, from `ng-template-parser`**
  The parser and template model classes come from the `ng-template-parser` dependency. The parser is
  rewritten as a single-pass recursive descent parser, replacing the old 3-stage pipeline
  (NGStringTokenizer → NGHTMLParser → callback → NGTemplateParser). With it come:
  - **`<p:raw>...</p:raw>`**: content passed through verbatim, without any template processing.
    Nests. Useful for `<script>` blocks or anything else that might confuse the parser.
  - **`<p:comment>...</p:comment>`**: developer comments, stripped entirely from rendered output.
    Nests. Unlike HTML comments, these are guaranteed to produce nothing in the output.
  - **Configurable dynamic namespaces**: the old parser hardcoded `wo:` (and `webobject`). Tags with
    unrecognized namespaces (e.g. `svg:rect`, `xsl:template`) pass through as plain HTML.
  - **Source position tracking**: every node carries a source range, and error messages include the
    line and column.
  - **Boolean (valueless) attributes** on inline tags, e.g. `<wo:Widget disabled />`.
  - **Self-closing tag tracking**: the parser records whether a tag was self-closing (`/>`).
  - **Better error detection**: malformed tags like `<wo: Repetition>` (space after the colon) and
    `</ wo:Conditional>` (space after `</`) get specific error messages, rather than silently
    misbehaving.
  - **HTML comments are no longer specially parsed**: `<!-- -->` flows through as plain HTML content.
    Use `<p:comment>` for comments you want stripped from output.

## 2025-11-15 (1.3.0)

- **`ERXWOTemplate` is excluded** from element proxying in development.

## 2025-10-15 (1.2.0)

- **Deployed to the WOCommunity Maven repository.**
- **Nicer messages for `UnknownKeyException`.**
- **Parser cleanup**: some generic parser logic tidied up.

## 2025-03-30 (1.1.0)

- **Only rendering exceptions Parsley explicitly knows how to handle are handled inline.**

## 2025-03-27 (1.0.0)

- **Initial release.**
