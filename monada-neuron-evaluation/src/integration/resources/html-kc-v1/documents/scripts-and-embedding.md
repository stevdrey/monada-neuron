## Inline versus external scripts

A `<script>` element either contains its code inline or loads it from a file named by its `src` attribute. An external file can be cached and reused by many pages, while inline code is repeated in every page that contains it. When `src` is present, any code written between the tags is ignored.

```
<script src="app.js"></script>
<script>
  console.log("inline code");
</script>
```

## Defer versus async

By default a classic script written in the HTML markup blocks parsing while it is fetched (if external) and run; scripts inserted by script code are async by default. The `defer` attribute downloads an external script in parallel and runs it after the document is parsed, in document order. The `async` attribute also downloads in parallel but runs the script as soon as it arrives, so the order between async scripts is not guaranteed.

## Scripts and Content Security Policy

A Content Security Policy whose `script-src` lacks `'unsafe-inline'` blocks inline `<script>` code and inline event handler attributes, while external scripts from allowed sources still run (unless `'strict-dynamic'` makes the policy ignore host allowlists). Moving code, including event handlers, into external files keeps a page working; a nonce or hash can allow a specific inline `<script>` block, but nonces never apply to event handler attributes and hashes apply to them only with `'unsafe-hashes'`.

## Sandboxing an iframe

The `sandbox` attribute on an `<iframe>` applies extra restrictions to the embedded document, such as blocking its scripts and form submission. Each token in the attribute value, for example `allow-scripts`, lifts one restriction again.
