## The form element

A `<form>` collects user input and submits it. Its `action` attribute is the URL that receives the data, and its `method` attribute chooses how the data is sent.

## GET versus POST submission

With `method="get"` the form data is appended to the action URL as a query string, which suits searches that people may bookmark. With `method="post"` the data travels in the request body, which suits submissions that change data on the server. When no method is given, a form uses GET.

## Labels for inputs

Every visible form control needs a label. Connect a `<label>` to its control by setting the label's `for` attribute to the input's `id`; clicking the label then focuses the input and screen readers announce the label text.

```
<label for="email">Email address</label>
<input id="email" name="email" type="email" required>
```

## Input types and validation

The `type` attribute of `<input>` selects the control, for example `text`, `email`, `number`, `date` or `checkbox`. The `required` attribute makes the browser block submission while the field is empty, unless the form disables validation with `novalidate`, but server-side validation is still necessary because client checks can be bypassed.

## Button types

A `<button>` inside a form defaults to `type="submit"`, so clicking it submits the form. Write `type="button"` for a button that only runs a script, and `type="reset"` to restore the initial values.

## Grouping controls

The `<fieldset>` element groups related controls, such as a set of radio buttons, and its first child `<legend>` gives the group a caption that assistive technology reads together with each control.
