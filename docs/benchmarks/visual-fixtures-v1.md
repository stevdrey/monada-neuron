# Visual Fixture Corpus `visual-fixtures/1` (Issue #142)

## Question

Does the fixture corpus of contract [`visual-geometry/1`](../specs/visual-geometry-v1.md) Section 8 exist as frozen,
license clear, reproducible data that later visual issues (#144 to #151) can load without trusting the generator?

This document is the evidence for gate V1 on the fixture side. It reports a corpus, not an algorithm: no fitter,
no segmentation and no model is run here, and no number below is a quality claim.

## What was built

- A generator, `monada.neuron.evaluation.visual.VisualFixtureGenerator`, that draws 120 ids (72 masks, 48 RGB images)
  at 32x32 and 64x64 from a literal catalogue copied from the contract tables.
- A frozen manifest, `monada-neuron-evaluation/src/main/resources/visual-fixtures-v1/manifest-v1.tsv`, with one row
  per `(id, size)`: gold columns, seed, split and the SHA-256 of the decoded pixels.
- A loader, `VisualFixtureCorpus`, that regenerates the pixels in memory and returns them only when they hash to
  their manifest row. Every read names its split.
- No PNG file is committed. The contract first asked for 240 committed PNG files; that is about 260 changed files
  against the 40 file limit of the issue. The pixel hashes already give the same evidence, so the corpus is
  regenerated instead (contract Section 8.1 and its `/1` change record).

The generator shares no code with `monada.neuron.visual` (#143). It has its own implementation of the render
arithmetic of contract Section 4.4, so a bug in one side cannot hide in the gold of the other.

## Running

```bash
# Regenerate the PNG files, a manifest and a report fragment under monada-neuron-evaluation/build/visual-fixtures-v1/
./gradlew :monada-neuron-evaluation:runVisualFixtureGenerator

# The same, recording the WebP baseline sizes with a cwebp binary
./gradlew :monada-neuron-evaluation:runVisualFixtureGenerator -PbenchmarkArgs='--cwebp /path/to/cwebp'

# The corpus tests, also part of ./gradlew test
./gradlew :monada-neuron-evaluation:test --tests 'monada.neuron.evaluation.visual.*'
```

The generator exits with status `1` on any failure and leaves its output directory exactly as it was. Freezing a
manifest is one manual copy, described in `PROVENANCE.md` beside the manifest.

## Report fields (contract Section 13)

| Field | Value |
| :--- | :--- |
| Contract version | `visual-geometry/1` |
| Manifest version | `visual-fixtures/1` |
| Manifest sha256 | `431a6053c9817457985c705fc83fb5d13aabbe5006271baebb4773409c5a1759` |
| Split key | `visual-fixtures/1` |
| Per id split | the per fixture table below; 64 train, 28 validation and 28 test ids |
| Seeds | first 8 bytes of SHA-256 of the id, big endian; the five drawing ids match contract Section 8.2 |
| JDK vendor and version | Eclipse Adoptium (Temurin) `27+35` |
| OS and architecture | macOS 27.2.1 (Darwin 27.2.0), `aarch64` |
| CPU model | Apple M2 Pro |
| Available processors | 10 |
| Heap and GC settings | max heap 4294967296 bytes; default collector; JVM input arguments `--add-modules=jdk.incubator.vector -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant` |
| `cwebp` version | `1.6.0` |
| Neuron commit | `f06dd31-dirty` (the freeze ran on the uncommitted change of issue #142 on top of `f06dd31`) |
| Determinism check result | pass: `VisualFixtureReplayTest` regenerates all 240 fixtures twice in one JVM and gets the committed value of every replayable column |

Fields of Section 13 that do not apply to a fixture corpus: arms and their selection, `τ_fit` and `τ_margin`,
thresholds, pose error statistics and JMH settings. Nothing is fitted, selected or scored here.

## Bounds

| Measure | Value |
| :--- | :--- |
| Fixtures | 240 (120 ids at 32x32 and 64x64) |
| Decoded bytes, whole corpus | 1,351,680 |
| Largest single pixel array | 16,384 bytes (one 64x64 RGBA fixture) |
| Total PNG bytes | 105,765 |
| Total WebP lossless bytes (`-lossless -exact`) | 58,576 |
| Total WebP lossy bytes (`-q 75 -exact`) | 72,870 |
| Generation time | 81 ms |
| Files committed for the corpus data | 3 (`manifest-v1.tsv`, `LICENSE`, `PROVENANCE.md`) |

The generation time is the render and hash of all 240 fixtures, without PNG or `cwebp` work. It is one run measured as
wall clock with `System.nanoTime()`, not a benchmark, and it carries no warm up. It is here to show the size of the
cost every corpus load pays, not to support a performance claim.

Bytes are reported, never gated (contract Section 12). `png_bytes` and the two WebP columns were recorded once at
freeze time. They depend on the PNG encoder of the JDK and on the `cwebp` build, so the tests check their format only.
The WebP sizes are externally produced data.

## Clipped poses

A positive or composite pose is clipped when the same render, taken over pixel indices `-N` to `2N - 1`, lights a
pixel outside `0` to `N - 1`. The clipped `(id, pose, size)` triples:

| Id | Pose | Sizes | Why |
| :--- | :--- | :--- | :--- |
| `m-pos-02` | 2 | 32, 64 | circle moved right by `0.2` |
| `m-pos-06` | 6 | 32, 64 | circle moved to the left and bottom edges |
| `m-pos-08` | 8 | 32, 64 | circle moved right by `0.4` |
| `m-pos-10` | 2 | 32, 64 | square moved right by `0.2` |
| `m-pos-13` | 5 | 64 | square turned 30 degrees; its corners pass the edge by about `0.012` |
| `m-pos-14` | 6 | 64 | square turned 45 degrees; its corners pass the edge by about `0.03` |
| `m-pos-16` | 8 | 32, 64 | square moved right by `0.4` |
| `m-pos-18` | 2 | 32, 64 | `T1` moved right by `0.2` |
| `m-pos-24` | 8 | 32, 64 | `T3` moved right by `0.4` |
| `m-cmp-02` | 2 | 32, 64 | duck moved right by `0.2` |

Two results differ from the prose of contract Section 8.4 as first written, and both are recorded there now:

- The 30 degree square (`m-pos-13`) also clips, at 64 only. The contract listed the 45 degree square but not this one.
- Both turned squares clip at 64 and not at 32. At 32 no pixel center falls inside the thin corner tips that pass the
  edge, so the 32x32 renders lose no pixel.

Edge touching predicates, which have no pose and are listed by hand: `m-amb-08` (a circle of radius `0.5` that touches
the four frame edges) and `m-amb-10` (two circles that pass the left and right edges by `0.05`).

## What the tests check

| Check | Test | Result |
| :--- | :--- | :--- |
| Every replayable column of a fresh generation equals the committed manifest, twice | `VisualFixtureReplayTest` | pass |
| No two ids share pixels at either size (contract Section 8.11) | `VisualFixtureReplayTest` | pass |
| The five seeds and the 120 `(id, b, split)` triples equal a second literal copy of the contract tables | `FixtureCataloguePinTest` | pass |
| The committed manifest file has the pinned header hash | `FixtureCataloguePinTest` | pass |
| Each composite gold, and `m-amb-09` and `m-amb-10`, follows from its unsnapped decomposition without any #143 type | `CompositeGoldDerivationTest` | pass |
| A wrong body hash, unsorted rows, a duplicate row, an unknown column or a wrong column count is rejected with its line | `VisualFixtureManifestTest` | pass |
| A mask PNG that is not 8 bit gray, has another size or holds a value other than `0` or `255` throws | `FixturePngTest` | pass |
| A loaded fixture whose pixels do not hash to its row is never returned | `VisualFixtureCorpusTest` | pass |
| A test source outside the allow list that names the test split fails the scan | `FixtureLeakageScanTest` | pass |
| A shape render layer never carries a held out fixture into another split (contract Section 8.8) | `FixtureLeakageScanTest` | pass |
| A failing `cwebp` stops the run and leaves the output directory as it was | `VisualFixtureGeneratorTest` | pass |

## Agreement with #143

Before the freeze, the generator was checked against the branch of issue #143 (`71a92fc`) in a separate checkout.
For all 28 distinct gold shapes (the five catalogue primitives, the 21 composite decompositions and the two
overlapping circle pairs), #143's constructors accepted the shape, `VisualGeometry.canonicalize` returned the same
numerators with the identity pose, and `canonicalMask()` equalled this generator's own 64x64 render at the identity
transform, bit for bit.

#143 merged on 2026-11-24 (`a3e68d1`, after two more review rounds that refactored its canonicalizer).
`VisualGeometryAgreementTest` now repeats the check on every run against the merged code, and it passes for all 28
shapes with no change to the generator or the frozen manifest.

The #143 conformance test pins the witness mask of every gold composite, five of which are in the test split
(`m-cmp-06`, `m-cmp-11`, `m-cmp-14`, `m-cmp-15`, `m-cmp-18`). It selects nothing and scores no algorithm, so the
leakage scan lists it as exempt, in the allow list of `FixtureLeakageScanTest`.

## Limits and negative results

- The corpus is synthetic and tiny by design. It says nothing about photographs or real segmentation.
- `png_bytes` and the WebP sizes are trusted, not replayed. They are reproducible only with the same JDK PNG writer
  and the same `cwebp` build.
- The leakage scan matches text. It stops an accidental read of the test split, not a deliberate one.
- The render arithmetic of contract Section 4.4 now exists twice on purpose. A contract change to it must be made in
  both places, and the agreement test is what catches a miss.
- An empty 64x64 mask and a transparent black 32x32 RGBA image are both 4,096 zero bytes, so `m-neg-01` at 64 and
  `c-deg-01` at 32 share a pixel hash, and so do `m-neg-02` at 64 and `c-flat-02` at 32 (4,096 bytes of `255`). They
  differ in kind and size, so Section 8.11 (no shared pixels at either size) holds.

## Per fixture table

| id | size | split | pixel_sha256 (first 12) | png_bytes | webp_lossless_bytes | webp_q75_bytes |
|---|---|---|---|---|---|---|
| `c-chan-01` | 32 | validation | `97e785f297ad` | 105 | 36 | 64 |
| `c-chan-01` | 64 | validation | `c7654c159328` | 193 | 42 | 72 |
| `c-chan-02` | 32 | train | `56419a079049` | 133 | 36 | 84 |
| `c-chan-02` | 64 | train | `3d558540e8c5` | 193 | 42 | 94 |
| `c-chan-03` | 32 | train | `590d140f13d7` | 135 | 36 | 82 |
| `c-chan-03` | 64 | train | `c34fb4331b2d` | 194 | 42 | 88 |
| `c-chan-04` | 32 | test | `c079e89d3e89` | 135 | 34 | 120 |
| `c-chan-04` | 64 | test | `0f675a2a912d` | 194 | 40 | 140 |
| `c-chan-05` | 32 | test | `1b7382d46343` | 146 | 36 | 120 |
| `c-chan-05` | 64 | test | `7b77d42f5889` | 218 | 42 | 140 |
| `c-chan-06` | 32 | validation | `78aacbc3fb34` | 144 | 38 | 92 |
| `c-chan-06` | 64 | validation | `9febfa442e39` | 215 | 44 | 106 |
| `c-chk-01` | 32 | train | `519335bb592a` | 154 | 54 | 302 |
| `c-chk-01` | 64 | train | `ccdd5da6310e` | 232 | 56 | 970 |
| `c-chk-02` | 32 | test | `c6a448881484` | 162 | 52 | 304 |
| `c-chk-02` | 64 | test | `541b69a9cef7` | 233 | 52 | 996 |
| `c-chk-03` | 32 | train | `aa4e191bdfa3` | 208 | 52 | 68 |
| `c-chk-03` | 64 | train | `70b77c03606d` | 328 | 52 | 104 |
| `c-chk-04` | 32 | validation | `9df63805d468` | 204 | 50 | 70 |
| `c-chk-04` | 64 | validation | `831f17377168` | 722 | 62 | 104 |
| `c-deg-01` | 32 | train | `ad7facb2586f` | 83 | 34 | 94 |
| `c-deg-01` | 64 | train | `4fe7b59af6de` | 96 | 40 | 108 |
| `c-deg-02` | 32 | train | `d0b6ed6b2c9a` | 135 | 36 | 94 |
| `c-deg-02` | 64 | train | `6d7d89dfde15` | 194 | 42 | 108 |
| `c-deg-03` | 32 | test | `689baeb9ff8d` | 104 | 36 | 94 |
| `c-deg-03` | 64 | test | `a5936ba4e60f` | 193 | 42 | 108 |
| `c-deg-04` | 32 | validation | `d7ff7fa576db` | 3635 | 3176 | 822 |
| `c-deg-04` | 64 | validation | `261043dc4bac` | 14184 | 12416 | 2832 |
| `c-deg-05` | 32 | train | `060a8271e2b7` | 4196 | 4224 | 1884 |
| `c-deg-05` | 64 | train | `4a6d33313dd8` | 16521 | 16538 | 7036 |
| `c-flat-01` | 32 | validation | `df894663e850` | 135 | 34 | 58 |
| `c-flat-01` | 64 | validation | `62fb561c59d0` | 194 | 40 | 66 |
| `c-flat-02` | 32 | train | `f47a8ec3e9af` | 102 | 38 | 58 |
| `c-flat-02` | 64 | train | `0fbba07a833d` | 190 | 44 | 66 |
| `c-flat-03` | 32 | test | `0f5bfe013c26` | 147 | 38 | 56 |
| `c-flat-03` | 64 | test | `d8d553546719` | 218 | 44 | 64 |
| `c-flat-04` | 32 | train | `4ab589b8423c` | 104 | 36 | 84 |
| `c-flat-04` | 64 | train | `0bcc07de1631` | 192 | 42 | 98 |
| `c-grad-01` | 32 | test | `0e8ffe4c2445` | 204 | 48 | 76 |
| `c-grad-01` | 64 | test | `e5b7b802d9f8` | 394 | 52 | 104 |
| `c-grad-02` | 32 | train | `4fca1267ae6c` | 222 | 64 | 80 |
| `c-grad-02` | 64 | train | `48c7c35c4026` | 401 | 60 | 108 |
| `c-grad-03` | 32 | test | `0484534e9b4e` | 368 | 62 | 84 |
| `c-grad-03` | 64 | test | `0f8abde2fb80` | 679 | 58 | 142 |
| `c-grad-04` | 32 | validation | `eeb7b6fed02a` | 944 | 336 | 134 |
| `c-grad-04` | 64 | validation | `825da1269681` | 2971 | 828 | 216 |
| `c-grad-05` | 32 | train | `f872360986df` | 2737 | 204 | 148 |
| `c-grad-05` | 64 | train | `583c2de6f991` | 12015 | 232 | 240 |
| `c-grad-06` | 32 | validation | `dc847dab0903` | 166 | 58 | 138 |
| `c-grad-06` | 64 | validation | `ebe4ccafb223` | 317 | 58 | 154 |
| `c-imp-01` | 32 | train | `a580f31c7f69` | 145 | 46 | 74 |
| `c-imp-01` | 64 | train | `a27068f78664` | 208 | 48 | 80 |
| `c-imp-02` | 32 | validation | `1b5781ac66e6` | 140 | 46 | 72 |
| `c-imp-02` | 64 | validation | `fff709fa115c` | 204 | 46 | 80 |
| `c-imp-03` | 32 | test | `ca2b0b8bf8ff` | 152 | 48 | 88 |
| `c-imp-03` | 64 | test | `9d47b0bf56df` | 217 | 52 | 92 |
| `c-shp-01` | 32 | validation | `4a3ceb3b96d7` | 185 | 108 | 438 |
| `c-shp-01` | 64 | validation | `d5f1abb02d09` | 319 | 156 | 696 |
| `c-shp-02` | 32 | train | `1df19c848bae` | 210 | 88 | 430 |
| `c-shp-02` | 64 | train | `15186b207706` | 363 | 140 | 614 |
| `c-shp-03` | 32 | test | `89703a5d8d05` | 185 | 68 | 142 |
| `c-shp-03` | 64 | test | `c3f4407e2298` | 328 | 78 | 174 |
| `c-shp-04` | 32 | validation | `82cb584880af` | 152 | 86 | 302 |
| `c-shp-04` | 64 | validation | `e7966c3e5c58` | 238 | 124 | 448 |
| `c-shp-05` | 32 | train | `05b2ed495c4e` | 193 | 120 | 400 |
| `c-shp-05` | 64 | train | `3631be1f0351` | 351 | 176 | 666 |
| `c-shp-06` | 32 | train | `a5f828356040` | 207 | 92 | 338 |
| `c-shp-06` | 64 | train | `9a0866671222` | 441 | 144 | 494 |
| `c-shp-07` | 32 | train | `948f4fd93684` | 211 | 72 | 586 |
| `c-shp-07` | 64 | train | `d204404b66e6` | 322 | 76 | 1638 |
| `c-shp-08` | 32 | test | `705cb2f4603d` | 249 | 100 | 426 |
| `c-shp-08` | 64 | test | `d2d3ad965941` | 553 | 154 | 716 |
| `c-step-01` | 32 | validation | `7be30efd1d0e` | 185 | 48 | 62 |
| `c-step-01` | 64 | validation | `a6a5d41a5ffa` | 313 | 48 | 70 |
| `c-step-02` | 32 | train | `86b61217c833` | 109 | 48 | 70 |
| `c-step-02` | 64 | train | `079d7d0e710a` | 315 | 50 | 80 |
| `c-step-03` | 32 | train | `f3fa5dba3907` | 119 | 50 | 62 |
| `c-step-03` | 64 | train | `93c0a0fbe5c2` | 204 | 50 | 70 |
| `c-step-04` | 32 | test | `2e46bbc3a6a1` | 207 | 90 | 152 |
| `c-step-04` | 64 | test | `8afe6ddbdcdd` | 370 | 122 | 224 |
| `c-tone-01` | 32 | test | `a10af2c4fc58` | 182 | 74 | 82 |
| `c-tone-01` | 64 | test | `fd6e8a0715c2` | 336 | 86 | 114 |
| `c-tone-02` | 32 | train | `d7ca597fda82` | 213 | 94 | 82 |
| `c-tone-02` | 64 | train | `558ed9662f6c` | 352 | 124 | 112 |
| `c-tone-03` | 32 | validation | `4eec356b8277` | 182 | 74 | 100 |
| `c-tone-03` | 64 | validation | `2d09f3187464` | 336 | 112 | 132 |
| `c-tone-04` | 32 | validation | `1e0632f4a775` | 289 | 112 | 144 |
| `c-tone-04` | 64 | validation | `bf4c4b68eaf9` | 544 | 206 | 254 |
| `c-tone-05` | 32 | train | `e3489bfcf1db` | 180 | 74 | 86 |
| `c-tone-05` | 64 | train | `73c09b17390d` | 330 | 86 | 120 |
| `c-tone-06` | 32 | train | `7904294e8461` | 110 | 40 | 122 |
| `c-tone-06` | 64 | train | `23460275bd5d` | 285 | 46 | 158 |
| `c-tone-07` | 32 | train | `d017201a0a9a` | 183 | 74 | 78 |
| `c-tone-07` | 64 | train | `8bfb105e5723` | 328 | 78 | 102 |
| `c-tone-08` | 32 | test | `b7e07e1c40b5` | 1130 | 358 | 266 |
| `c-tone-08` | 64 | test | `0ff23615cbe5` | 3807 | 712 | 478 |
| `m-amb-01` | 32 | train | `065f9917d5a5` | 127 | 84 | 210 |
| `m-amb-01` | 64 | train | `7a57f4e2fe4d` | 194 | 120 | 254 |
| `m-amb-02` | 32 | train | `f0fa4f00952d` | 111 | 72 | 130 |
| `m-amb-02` | 64 | train | `52cb3847d65f` | 164 | 104 | 178 |
| `m-amb-03` | 32 | train | `7c2a96e34141` | 130 | 86 | 226 |
| `m-amb-03` | 64 | train | `a25619411311` | 218 | 136 | 376 |
| `m-amb-04` | 32 | test | `e701f3214644` | 104 | 70 | 198 |
| `m-amb-04` | 64 | test | `da5e4ef5f8b3` | 189 | 104 | 240 |
| `m-amb-05` | 32 | validation | `52cd63163971` | 133 | 90 | 228 |
| `m-amb-05` | 64 | validation | `081b828dc754` | 260 | 136 | 388 |
| `m-amb-06` | 32 | validation | `96a77cae4266` | 132 | 84 | 220 |
| `m-amb-06` | 64 | validation | `4ccecd1fa860` | 248 | 132 | 342 |
| `m-amb-07` | 32 | train | `7f8a55f10cd1` | 140 | 94 | 306 |
| `m-amb-07` | 64 | train | `ae93c639f7be` | 244 | 148 | 420 |
| `m-amb-08` | 32 | test | `71889522988c` | 147 | 102 | 298 |
| `m-amb-08` | 64 | test | `ead159c78b0d` | 253 | 160 | 524 |
| `m-amb-09` | 32 | train | `4801628da4c7` | 121 | 86 | 296 |
| `m-amb-09` | 64 | train | `07fa442841b4` | 201 | 132 | 414 |
| `m-amb-10` | 32 | train | `7104e02554c0` | 116 | 74 | 268 |
| `m-amb-10` | 64 | train | `2485623798b5` | 167 | 108 | 500 |
| `m-cmp-01` | 32 | train | `28ce60cdeb92` | 138 | 96 | 274 |
| `m-cmp-01` | 64 | train | `b241e3cb6475` | 253 | 142 | 420 |
| `m-cmp-02` | 32 | validation | `e4a844dc4fea` | 135 | 92 | 258 |
| `m-cmp-02` | 64 | validation | `718a9fe60090` | 252 | 138 | 356 |
| `m-cmp-03` | 32 | train | `9b065194c387` | 108 | 68 | 176 |
| `m-cmp-03` | 64 | train | `8523e039a1b3` | 156 | 104 | 246 |
| `m-cmp-04` | 32 | train | `88bf689a42c5` | 130 | 86 | 238 |
| `m-cmp-04` | 64 | train | `5f8b127a9e01` | 214 | 130 | 364 |
| `m-cmp-05` | 32 | train | `04957dc3c433` | 151 | 102 | 298 |
| `m-cmp-05` | 64 | train | `5f191688a479` | 270 | 156 | 524 |
| `m-cmp-06` | 32 | test | `169c5d8739b1` | 143 | 102 | 354 |
| `m-cmp-06` | 64 | test | `7c896046aa86` | 278 | 168 | 536 |
| `m-cmp-07` | 32 | train | `c210464842e3` | 144 | 92 | 308 |
| `m-cmp-07` | 64 | train | `761cc591d065` | 269 | 178 | 606 |
| `m-cmp-08` | 32 | train | `21197f146662` | 93 | 62 | 136 |
| `m-cmp-08` | 64 | train | `b54203ffab6a` | 176 | 62 | 98 |
| `m-cmp-09` | 32 | train | `8f6f73f7e946` | 94 | 60 | 136 |
| `m-cmp-09` | 64 | train | `65c773486380` | 234 | 64 | 144 |
| `m-cmp-10` | 32 | train | `dd14557525d4` | 122 | 84 | 300 |
| `m-cmp-10` | 64 | train | `71cb09725bc9` | 193 | 114 | 378 |
| `m-cmp-11` | 32 | test | `abc3948acb58` | 118 | 82 | 256 |
| `m-cmp-11` | 64 | test | `7edeb2f8e5b0` | 173 | 114 | 348 |
| `m-cmp-12` | 32 | validation | `9cd101521002` | 107 | 70 | 190 |
| `m-cmp-12` | 64 | validation | `9a2852f0f903` | 149 | 82 | 298 |
| `m-cmp-13` | 32 | train | `8a71eab01ede` | 131 | 92 | 274 |
| `m-cmp-13` | 64 | train | `8303fc118660` | 242 | 146 | 422 |
| `m-cmp-14` | 32 | test | `7f7f95e85d69` | 140 | 100 | 298 |
| `m-cmp-14` | 64 | test | `763457b65701` | 245 | 154 | 478 |
| `m-cmp-15` | 32 | test | `f19eaa14ef55` | 131 | 94 | 258 |
| `m-cmp-15` | 64 | test | `2f732316ae76` | 239 | 134 | 394 |
| `m-cmp-16` | 32 | train | `9e871565c868` | 128 | 84 | 248 |
| `m-cmp-16` | 64 | train | `13904ec24426` | 222 | 134 | 362 |
| `m-cmp-17` | 32 | train | `9811b12f10cb` | 90 | 60 | 112 |
| `m-cmp-17` | 64 | train | `6b4114a29976` | 129 | 60 | 122 |
| `m-cmp-18` | 32 | test | `6ae5b7bdc6dc` | 109 | 58 | 96 |
| `m-cmp-18` | 64 | test | `dffb75aa3fe6` | 122 | 58 | 136 |
| `m-cmp-19` | 32 | train | `1a95c81daa35` | 91 | 58 | 94 |
| `m-cmp-19` | 64 | train | `d8787443226d` | 113 | 58 | 114 |
| `m-cmp-20` | 32 | train | `1d08e30f3216` | 91 | 56 | 74 |
| `m-cmp-20` | 64 | train | `d6ca8e46edb6` | 183 | 54 | 88 |
| `m-cmp-21` | 32 | validation | `071bbb6efc22` | 117 | 82 | 266 |
| `m-cmp-21` | 64 | validation | `2c9db68f991b` | 170 | 112 | 348 |
| `m-cmp-22` | 32 | train | `9f336e36f52b` | 99 | 72 | 186 |
| `m-cmp-22` | 64 | train | `1a59808fc685` | 162 | 92 | 312 |
| `m-cmp-23` | 32 | validation | `a56a5d8db684` | 83 | 54 | 88 |
| `m-cmp-23` | 64 | validation | `9f21fbee658d` | 96 | 54 | 86 |
| `m-cmp-24` | 32 | validation | `41286c01ddbf` | 92 | 60 | 110 |
| `m-cmp-24` | 64 | validation | `c923c82918f4` | 143 | 60 | 152 |
| `m-neg-01` | 32 | train | `5f70bf18a086` | 75 | 34 | 58 |
| `m-neg-01` | 64 | train | `ad7facb2586f` | 84 | 40 | 66 |
| `m-neg-02` | 32 | train | `5f4ecdb7b71c` | 87 | 38 | 58 |
| `m-neg-02` | 64 | train | `f47a8ec3e9af` | 108 | 44 | 66 |
| `m-neg-03` | 32 | train | `0101ed99f82e` | 91 | 48 | 72 |
| `m-neg-03` | 64 | train | `6c3bdba982b7` | 116 | 50 | 78 |
| `m-neg-04` | 32 | test | `264970d74e44` | 89 | 50 | 122 |
| `m-neg-04` | 64 | test | `1655002aecad` | 105 | 56 | 132 |
| `m-neg-05` | 32 | test | `b795d2ea9340` | 79 | 46 | 74 |
| `m-neg-05` | 64 | test | `52cd93f3d7de` | 89 | 48 | 80 |
| `m-neg-06` | 32 | train | `633a1f9a48a9` | 76 | 46 | 72 |
| `m-neg-06` | 64 | train | `ac0f563e1449` | 84 | 46 | 80 |
| `m-neg-07` | 32 | test | `553ba2a831b8` | 80 | 48 | 72 |
| `m-neg-07` | 64 | test | `62efaf02c67b` | 91 | 48 | 80 |
| `m-neg-08` | 32 | train | `1748af3d584a` | 84 | 64 | 140 |
| `m-neg-08` | 64 | train | `1f6f1e965bcf` | 106 | 72 | 180 |
| `m-neg-09` | 32 | train | `7fa2a2aab306` | 89 | 54 | 302 |
| `m-neg-09` | 64 | train | `2a9047fcc0f6` | 110 | 56 | 970 |
| `m-neg-10` | 32 | train | `05e4a04fe4b7` | 277 | 192 | 882 |
| `m-neg-10` | 64 | train | `44f1dc6f4308` | 817 | 574 | 2950 |
| `m-neg-11` | 32 | validation | `9b1c6a26b1f1` | 271 | 188 | 882 |
| `m-neg-11` | 64 | validation | `cb65c845de0e` | 808 | 574 | 3000 |
| `m-neg-12` | 32 | validation | `3fdf51bb8440` | 167 | 100 | 490 |
| `m-neg-12` | 64 | validation | `71ac2ed0cd20` | 382 | 218 | 1388 |
| `m-neg-13` | 32 | validation | `126f5e08de46` | 87 | 46 | 72 |
| `m-neg-13` | 64 | validation | `a1a2dd9ae431` | 108 | 46 | 80 |
| `m-neg-14` | 32 | train | `63d0f2db26a1` | 80 | 52 | 74 |
| `m-neg-14` | 64 | train | `8ecc50b5d02a` | 91 | 52 | 86 |
| `m-pos-01` | 32 | train | `c3aa35bbf4c8` | 135 | 92 | 250 |
| `m-pos-01` | 64 | train | `ac384a4f28d9` | 238 | 142 | 374 |
| `m-pos-02` | 32 | test | `439a2666ef83` | 134 | 90 | 230 |
| `m-pos-02` | 64 | test | `60e7eaf20bda` | 276 | 136 | 364 |
| `m-pos-03` | 32 | train | `5ea2145a82de` | 108 | 72 | 182 |
| `m-pos-03` | 64 | train | `6e941267b2bd` | 184 | 100 | 236 |
| `m-pos-04` | 32 | train | `53f8dbb5329c` | 90 | 60 | 128 |
| `m-pos-04` | 64 | train | `b16e2b8fb9fe` | 122 | 72 | 172 |
| `m-pos-05` | 32 | test | `931e4fa9a7fd` | 130 | 86 | 276 |
| `m-pos-05` | 64 | test | `255defe0513e` | 265 | 140 | 444 |
| `m-pos-06` | 32 | validation | `5b5af209680b` | 112 | 70 | 152 |
| `m-pos-06` | 64 | validation | `505359b20208` | 227 | 96 | 206 |
| `m-pos-07` | 32 | train | `3e99b8a42afd` | 108 | 70 | 182 |
| `m-pos-07` | 64 | train | `8b9d6294aa40` | 187 | 102 | 256 |
| `m-pos-08` | 32 | train | `ac17f0d4aa41` | 129 | 74 | 142 |
| `m-pos-08` | 64 | train | `a767c856aa86` | 275 | 100 | 268 |
| `m-pos-09` | 32 | train | `5d388a67a384` | 84 | 56 | 74 |
| `m-pos-09` | 64 | train | `6d06a2badb20` | 102 | 56 | 86 |
| `m-pos-10` | 32 | train | `1dce8d9df4ad` | 84 | 54 | 98 |
| `m-pos-10` | 64 | train | `67b2738bbf15` | 241 | 58 | 114 |
| `m-pos-11` | 32 | train | `f50f48045e57` | 86 | 52 | 126 |
| `m-pos-11` | 64 | train | `1f6ab7309dc0` | 169 | 56 | 80 |
| `m-pos-12` | 32 | train | `71bf2798672a` | 83 | 54 | 114 |
| `m-pos-12` | 64 | train | `9f2ab29222f6` | 100 | 54 | 126 |
| `m-pos-13` | 32 | validation | `aba02001ece0` | 161 | 114 | 336 |
| `m-pos-13` | 64 | validation | `e5cfcf7d53c1` | 296 | 178 | 532 |
| `m-pos-14` | 32 | train | `5991ebb278fa` | 162 | 114 | 234 |
| `m-pos-14` | 64 | train | `27271ed84b9d` | 311 | 204 | 478 |
| `m-pos-15` | 32 | train | `690135224859` | 119 | 72 | 186 |
| `m-pos-15` | 64 | train | `2d95adcaa1f5` | 204 | 104 | 286 |
| `m-pos-16` | 32 | test | `2329776b7bd0` | 83 | 54 | 76 |
| `m-pos-16` | 64 | test | `bf80f076966d` | 245 | 56 | 88 |
| `m-pos-17` | 32 | test | `f3edf3b92626` | 130 | 90 | 220 |
| `m-pos-17` | 64 | test | `663ba1eb4fa2` | 239 | 128 | 344 |
| `m-pos-18` | 32 | test | `3c01b68f2927` | 132 | 90 | 218 |
| `m-pos-18` | 64 | test | `c2d3313b65a3` | 238 | 134 | 302 |
| `m-pos-19` | 32 | train | `614e92ebcfa3` | 108 | 74 | 166 |
| `m-pos-19` | 64 | train | `66b727818c3f` | 156 | 90 | 210 |
| `m-pos-20` | 32 | validation | `47e9969a50c1` | 93 | 60 | 132 |
| `m-pos-20` | 64 | validation | `41465f975b8c` | 118 | 74 | 156 |
| `m-pos-21` | 32 | train | `3b594d9bf7b0` | 132 | 88 | 232 |
| `m-pos-21` | 64 | train | `631afd2292ea` | 213 | 126 | 354 |
| `m-pos-22` | 32 | train | `dfa5c06fab52` | 125 | 82 | 124 |
| `m-pos-22` | 64 | train | `20905d6bcd7c` | 207 | 96 | 158 |
| `m-pos-23` | 32 | validation | `a81473794b7f` | 107 | 66 | 136 |
| `m-pos-23` | 64 | validation | `faa9966f4901` | 143 | 96 | 218 |
| `m-pos-24` | 32 | validation | `60b1d29879cf` | 118 | 78 | 142 |
| `m-pos-24` | 64 | validation | `eeae52542543` | 187 | 98 | 192 |
