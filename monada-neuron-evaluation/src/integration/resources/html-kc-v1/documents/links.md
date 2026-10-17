## The anchor element

A hyperlink is written with the `<a>` element. Its `href` attribute holds the destination URL, and the text between the opening and closing tags is the link text that people click.

```
<a href="https://example.com/guide">Read the guide</a>
```

## Absolute and relative URLs

An absolute URL contains the scheme and host, such as `https://example.com/about.html`, and points to the same place from any page. A relative URL such as `projects/index.html` is resolved against the location of the current document, so it keeps working when a whole site is moved to another domain.

## Linking to a fragment

A link can jump to a specific element on a page. Give the target element an `id` attribute and put a hash sign and that id at the end of the URL, for example `href="#contact"` on the same page or `href="about.html#team"` on another page.

## Opening a new tab safely

Setting `target="_blank"` on a link opens the destination in a new tab or window. Add `rel="noopener"` so the opened page cannot reach back to the original page through `window.opener`; modern browsers apply this behavior by default, but writing it explicitly documents the intent.

## Email and download links

A `mailto:` URL such as `href="mailto:team@example.com"` opens the visitor's email program instead of a web page. The `download` attribute on a link asks the browser to save the linked resource as a file instead of navigating to it.
