# GitHub and DevOps Explanation

The source repository is [Tazkeen/AjouhaarButcher](https://github.com/Tazkeen/AjouhaarButcher). The project uses Git to track Android, API, database utility and documentation files. The team should use branches for assigned work, review pull requests, resolve conflicts and keep `main` in a buildable state.

The added workflow `.github/workflows/project-ci.yml` runs when code is pushed to `main`, a pull request targets `main`, or a maintainer starts it manually. Its Android job uses Java 17 and Android SDK Platform 36 to run `testDebugUnitTest` and `assembleDebug`. Its API job restores and builds the ASP.NET API. Both jobs use read-only repository contents permission.

The workflow does not deploy the API or app and does not use Firebase secrets. No successful Actions run is claimed here; the group must open the Actions tab after the workflow is pushed and record the real run result and commit.

Before the report is submitted, attach a screenshot/link of a successful Actions run if one has completed. If it fails, document the actual error and correction rather than marking it as passed. Document meeting dates and task allocation from the team's real records only.
