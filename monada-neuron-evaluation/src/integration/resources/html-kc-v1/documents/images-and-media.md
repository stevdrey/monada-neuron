## The image element

The `<img>` element embeds a picture. It is a void element, so it has no closing tag, and its `src` attribute gives the address of the image file.

## Alternative text for images

Give every `<img>` an `alt` attribute. It provides a text replacement that screen readers announce and that browsers show when the image fails to load. A purely decorative image gets an empty `alt=""` so assistive technology skips it. The HTML Standard allows leaving `alt` out only in narrow cases where no text alternative is available at all, such as a user-uploaded photo with no description placed inside a `<figure>` with a non-empty `<figcaption>`; a caption or `title` is never an automatic replacement for useful alternative text, and browsers do not generate it from the file name.

```
<img src="dinosaur.png" alt="A red Tyrannosaurus skeleton">
<img src="divider.png" alt="">
```

## Width and height on images

Writing the intrinsic `width` and `height` attributes on an `<img>` lets the browser reserve the right amount of space before the file arrives, which prevents the page layout from jumping while images load.

## Figures and captions

Wrap an image, diagram or code listing in `<figure>` when it is referenced from the main text, and put its caption inside `<figcaption>`. The caption belongs to the figure as a unit, which is different from the `alt` text that replaces the image itself.

## Video and audio

The `<video>` and `<audio>` elements embed media. Add the `controls` attribute to show the browser's play, pause and volume controls, and list several `<source>` children so the browser can pick a format it supports.
