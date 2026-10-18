## The form element

A `<form>` collects user input and submits it. Its `action` attribute is the URL that receives the data, and its `method` attribute chooses how the data is sent.

## GET versus POST submission

With `method="get"` the form data becomes the query string of the action URL, replacing any query already there, which suits searches that people may bookmark. With `method="post"` the data travels in the request body, which suits submissions that change data on the server. When neither the form nor the submit button's `formmethod` attribute sets a method, the form uses GET.

## Labels for inputs

Form controls such as text fields, checkboxes and selects need a label. Connect a `<label>` to its control by setting the label's `for` attribute to the control's `id`, or by placing the control inside the `<label>`; clicking the label then focuses or activates the control, and screen readers announce the label text.

```
<label for="email">Email address</label>
<input id="email" name="email" type="email" required>
```

## Input types and validation

The `type` attribute of `<input>` selects the control, for example `text`, `email`, `number`, `date` or `checkbox`. The `required` attribute marks a field that must be filled in: during ordinary interactive submission, the browser's constraint validation blocks the form while the field is empty. Validation can be skipped in several ways, for example `novalidate` on the form, `formnovalidate` on the submit button or a script calling `form.submit()`, and disabled or read-only controls are not validated at all, so server-side validation is still necessary.

## Button types

A `<button>` inside a form without a `type` attribute acts as a submit button, so clicking it submits the form, unless it has a `command` or `commandfor` attribute or is a direct child of a `<select>`. Write `type="button"` for a button that only runs a script, and `type="reset"` to restore the initial values.

## Grouping controls

The `<fieldset>` element groups related controls, such as a set of radio buttons, and its first child `<legend>` gives the group a caption that assistive technology typically announces together with each control.
