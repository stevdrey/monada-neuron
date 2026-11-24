# Provenance of `visual-fixtures/1`

This folder holds the frozen manifest of the visual fixture corpus of issue #142 (contract
`docs/specs/visual-geometry-v1.md`, Section 8). No image is stored here. The pixels are regenerated on demand and
checked against `manifest-v1.tsv`.

## What made the corpus

- **Generator class:** `monada.neuron.evaluation.visual.VisualFixtureGenerator`, Gradle task
  `:monada-neuron-evaluation:runVisualFixtureGenerator`. It draws every fixture from the literal catalogue
  `FixtureCatalogue` with its own render code and uses no type of `monada.neuron.visual`.
- **Source data:** none. Every pixel comes from a formula or a gold shape written in the contract. No external file
  was downloaded.
- **License:** CC0 1.0 Universal, see `LICENSE`. The project is the author.

## Seed scheme

- `seed` is the first 8 bytes of SHA-256 of the fixture id (UTF-8), big endian, as a signed `long`. It is written for
  every row.
- Only five ids draw random values: `m-neg-10`, `m-neg-11`, `m-neg-12`, `c-deg-04` and `c-deg-05`. They use
  `new SplittableRandom(seed)` through `nextLong()` only, with a fresh generator per size (contract Section 8.2).
- The split of an id comes from `b`, the first byte of SHA-256 of `visual-fixtures/1:<id>`, by the stratified rule of
  contract Section 8.10.

## Two kinds of column

- **Replayable (13):** `id`, `size`, `kind`, `group`, `seed`, `formula`, `gold_geometry`, `gold_transform`,
  `gold_accept`, `gold_abstain`, `gold_layers`, `split`, `pixel_sha256`. `VisualFixtureReplayTest` regenerates them on
  every test run and compares them with this manifest.
- **Recorded once at freeze time (3):** `png_bytes`, `webp_lossless_bytes`, `webp_q75_bytes`. They depend on the tools
  below and are not replayed. Treat the WebP sizes as externally produced data.

## Tools of the freeze run (2026-11-22)

- **JDK:** Eclipse Adoptium (Temurin) `27+35`, on macOS 27.2.1, `aarch64`.
- **PNG writer:** `javax.imageio` with the default PNG writer of that JDK. Masks are written as `TYPE_BYTE_GRAY`, RGBA
  images as `TYPE_4BYTE_ABGR` with straight alpha, both through the raster. `png_bytes` is the size of the file that
  run wrote.
- **`cwebp` version line:** `1.6.0` (Homebrew `webp` 1.6.0; the second line of `cwebp -version` was
  `libsharpyuv: 0.4.2`).
- **`cwebp` command lines**, run once per PNG with no shell:

  ```text
  cwebp -version
  cwebp -quiet -lossless -exact <id>-<size>.png -o <id>-<size>.lossless.webp
  cwebp -quiet -q 75 -exact <id>-<size>.png -o <id>-<size>.q75.webp
  ```

  `-exact` keeps the color of transparent pixels. The `.webp` files are not kept, only their sizes.

## How to regenerate and look at the images

```bash
./gradlew :monada-neuron-evaluation:runVisualFixtureGenerator
```

This writes `<id>-32.png` and `<id>-64.png` for every id, `manifest-v1.tsv` and `report-fragment.md` under
`monada-neuron-evaluation/build/visual-fixtures-v1/`. Without `cwebp` the two WebP columns are `NA`.

## How to freeze (a deliberate act, never a fix for a failing test)

A changed gold value or pixel is a new manifest version, not a new freeze of this one. A new freeze of
`visual-fixtures/1` is allowed only before the pull request of issue #142 merges.

1. Install `cwebp` and note its version line.
2. Run the generator on the project JDK with the binary:

   ```bash
   ./gradlew :monada-neuron-evaluation:runVisualFixtureGenerator -PbenchmarkArgs='--cwebp /path/to/cwebp'
   ```

3. Copy `monada-neuron-evaluation/build/visual-fixtures-v1/manifest-v1.tsv` over the file in this folder, by hand.
4. Put the new `# sha256:` value into `FixtureCataloguePinTest.COMMITTED_MANIFEST_SHA256`.
5. Update the tool lines above and `docs/benchmarks/visual-fixtures-v1.md` from `report-fragment.md`.

The frozen header hash is `431a6053c9817457985c705fc83fb5d13aabbe5006271baebb4773409c5a1759`.
