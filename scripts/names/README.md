# Name reports

`ingest.mjs` turns a folder of title reports (the "Wrong title?" sheet, N1) into a ranked list of
name shapes, so the shape that most viewers hit is fixed first.

```sh
node scripts/names/ingest.mjs <folder> [--top 30] [--examples 3] [--min 2]
```

- Input: files in the folder, one report per line, `raw file name<TAB>title the viewer chose`.
  Blank lines and `#` lines are skipped; the same pair twice counts once.
- Output: the shapes by how many reports share them, with a few examples each. A shape is the name
  with letters taken to `a` or `A`, digits to `9`, separators kept and runs collapsed (the same
  rules as `TitleShape` in core, which has the tests), so `@CC_Night_Train.mp4` and
  `@CC_Amber_Gate.mp4` are both `@A_Aa_Aa.a9`.
- It only groups and ranks. It does not run the Kotlin parser, so it cannot tell which names are read
  wrong. It has no dependencies (Node 18 or newer).

Fixing a shape:

1. Take a few reports from the top shape. Add each as a row to
   `core/src/jvmTest/resources/names/corpus.tsv` when the right answer is certain, or to
   `ambiguous.tsv` when it is not.
2. Run `./gradlew :core:jvmTest --tests '*NameGoldenCorpusTest*' --max-workers=3`. It fails with a diff of
   every row the parser reads differently, and prints what it reads for the unasserted files.
3. Change `MediaName.kt` until the corpus is green, and run the whole `:core:jvmTest`.

No report leaves a device without the viewer ticking it and seeing it first (`TitleReport` scrubs
phone numbers and @usernames). Keep reports out of the repository: the folder you point the script at
belongs under `private/`.
