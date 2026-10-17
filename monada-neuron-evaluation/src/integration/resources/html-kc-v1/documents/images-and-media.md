## The image element

The `<img>` element embeds a picture. It is a void element, so it has no closing tag, and its `src` attribute gives the address of the image file.

## Alternative text is required

The `alt` attribute is required on every `<img>`. It provides a text replacement that screen readers announce and that browsers show when the image fails to load. An image that is purely decorative still needs the attribute, written empty as `alt=""`, so assistive technology knows to skip it.

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
