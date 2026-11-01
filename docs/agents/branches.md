# Branches and worktrees

`main` is the only long lived branch. Work done in a worktree or on a side branch (an agent's
`worktree-agent-*` branch, a `diag/*` test build, a branch held back until a release) is tidied
away as soon as it is merged or no longer needed:

- After merging: `git worktree remove <path>`, `git branch -d <branch>`, and if it was pushed,
  `gh api -X DELETE repos/dracu-lah/TMPlayer/git/refs/heads/<branch>`. Then `git fetch --prune`.
- A branch kept on purpose (such as `site-after-<version>`, merged on release day) is named in
  `private/PLAN.md` (the working notes, kept off GitHub) with the day it goes, and deleted the
  same way once merged.
- Before deleting a branch that was never merged, say so, list its commit hashes in the report,
  and ask first.
- Check with `git worktree list`, `git branch` and
  `gh api repos/dracu-lah/TMPlayer/branches --jq '.[].name'` that only `main` (and any branch
  kept on purpose) is left.
