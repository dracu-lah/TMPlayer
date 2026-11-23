# Known failures of the tv-ux-review machine suite (WP-16, com.tmplayer.tvux.TvUx*).
#
# One file per package (WP-03.txt, WP-04.txt, ...), so two packages in one batch never write the
# same list. Each line is
#
#   screen | rule | node | reason
#
# where * in the first three fields matches any run of characters and # starts a comment. A
# finding that no line covers fails the suite; a line that matches nothing on its screen is printed
# as stale. Each TV package deletes its own lines as it fixes them: every file is empty by device
# checkpoint 4. The suite writes each screen's findings, as lines, to app/build/tvux/<screen>.txt.
#
# Run: ./gradlew :app:testDebugUnitTest -Ptvux=true --tests 'com.tmplayer.tvux.*'
