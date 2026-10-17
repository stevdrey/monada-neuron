## Heading levels

HTML has six heading elements, from `<h1>` to `<h6>`. The number is the heading's rank, not a font size: `<h1>` is the most important heading and `<h6>` the least important. Change the visual size with CSS instead of picking a heading level for its look.

## One main heading and no skipped levels

A page normally has a single `<h1>` that names its main topic. Below it, headings should descend one level at a time, so an `<h2>` section contains `<h3>` subsections rather than jumping straight to `<h4>`. Screen reader users often navigate a page by its heading list, and skipped levels make that list confusing.

## Landmark elements

The elements `<header>`, `<nav>`, `<main>` and `<footer>` mark the large regions of a page; a `<header>` or `<footer>` nested inside an `<article>` or `<section>` belongs to that part instead of the whole page. A document should contain only one visible `<main>` element, and it holds the content that is unique to that page.

```
<body>
  <header>Site name</header>
  <nav>Primary links</nav>
  <main>Page content</main>
  <footer>Contact details</footer>
</body>
```

## Article versus section

Use `<article>` for a self-contained composition that would still make sense if it were syndicated on its own, such as a blog post or a forum message. Use `<section>` for a thematic group of content inside a larger whole, and give each section its own heading.

## Grouping with div versus embedding with iframe

A `<div>` is a generic container with no meaning of its own; it only groups elements of the current document, usually for styling or scripting. An `<iframe>` does something different: it embeds a separate HTML document, loaded from its `src` URL or given inline with `srcdoc`, inside the page, with its own browsing context. Choose `<div>` to group content and `<iframe>` only when another document must be shown.
