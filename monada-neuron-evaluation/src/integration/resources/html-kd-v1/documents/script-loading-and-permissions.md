## Module scripts

A `<script type="module">` element loads a JavaScript module. Module scripts are deferred by default: they are fetched in parallel with parsing and run after the document is parsed, in document order, so the `defer` attribute has no effect on them. The `async` attribute does apply to modules, inline or external, and makes a module run as soon as it and its imports are ready. Module code runs in strict mode with its own scope, so a classic script does not always work unchanged as a module.

## Defer and async on inline scripts

The `defer` and `async` attributes take effect on a classic script only when it has a `src` attribute. On an inline classic script, written between the tags without `src`, both attributes are ignored and the code runs immediately where the parser reaches it. To delay inline code until the document is parsed, move it into an external file loaded with `defer`, or make it a module.

## Permissions for an embedded document

The `allow` attribute on an `<iframe>` sets a permissions policy for the embedded document, granting features such as `camera`, `geolocation` or `fullscreen` only when they are listed. It is separate from `sandbox`: `sandbox` removes abilities such as running scripts, while `allow` decides which powerful features the embedded document may use. An embed that needs no such feature should list none, because each granted permission widens what the embedded document can do.

## Showing content from another page

An `<iframe>` keeps the embedded document isolated: the page's styles do not apply inside it, and it is sized and scrolled as its own box. When content from another page must share the page's styles, layout and accessibility tree, it has to become part of the current document instead, for example by copying its markup into a `<div>` on the server, and then it is no longer isolated. Which one fits depends on whether isolation or integration matters more.
