## Ordered versus unordered lists

Use `<ol>` when the order of the items matters, such as the steps of a recipe, and `<ul>` when it does not, such as a shopping list. Both contain `<li>` items; only the ordered list is numbered by default.

```
<ol>
  <li>Preheat the oven</li>
  <li>Mix the dough</li>
</ol>
```

## Numbering an ordered list

An `<ol>` accepts a `start` attribute to begin counting at another number and a `reversed` attribute to count down. The `type` attribute switches the marker style, for example to letters or Roman numerals.

## Description lists

A description list `<dl>` pairs terms with their descriptions. Each term goes in a `<dt>` element and each description in a `<dd>` element, which suits glossaries and key-value metadata.

## Strong versus b

The `<strong>` element marks content of strong importance, seriousness or urgency. The `<b>` element only draws attention to text, such as keywords in a summary, without adding any importance. Both are usually rendered bold, so the choice is about meaning, not appearance.

## Emphasis versus i

The `<em>` element adds stress emphasis that changes the meaning of a sentence, like spoken stress. The `<i>` element marks text in an alternate voice, such as a technical term, a foreign phrase or a ship name. Both are usually rendered in italics.

## Code and preformatted text

The `<code>` element marks a fragment of computer code inside a sentence. The `<pre>` element keeps the whitespace and line breaks of its content exactly as written, so longer code listings are usually written as `<pre><code>...</code></pre>`.
