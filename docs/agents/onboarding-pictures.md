# Onboarding pictures

The setup's answers and the quick tour's pages (CP67, CP69) show cropped, marked screenshots of the promo
build: one spec each in `scripts/annotations/` (`setup-android-*`, `setup-desktop-*`,
`tour-android-<page>[-dark|-tv]`, `tour-desktop-<page>-light|dark`), drawn by
`scripts/annotate-shot.py` into `app/src/main/res/drawable-nodpi/` and
`ui/src/jvmMain/resources/onboarding/`. A tour spec marks each thing its page talks about with one
`circle`, in the order of the page's numbered lines; the app draws the numbers over the picture,
so no number or word goes into an image. After retaking or re-marking a tour shot, run
`scripts/tour-badges.py` (`--check` says whether it is due).
