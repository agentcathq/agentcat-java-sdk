# Contributing

## Build and test

You need JDK 17 or later and Maven 3.9. The release plugin does not work with Maven 4.

```sh
mvn verify                      # the full suite on MCP Java SDK 2.x
mvn test -Pmcp-1x               # the compatibility tests on MCP Java SDK 1.x
mvn test -Pmcp-018x             # the compatibility tests on MCP Java SDK 0.18.x
mvn package -P release,mcp-2.0.1 -DskipTests -Dgpg.skip   # the release jars, unsigned
```

Do not run `mvn deploy` on your machine. Maven Central never accepts a version twice, so a
local deploy can use up a version for good. Only the release workflow deploys.

## Record your change

Every change that a user can see adds an entry to the `[Unreleased]` section of `CHANGELOG.md`
in its pull request. The release notes come from this section.

## Release a version

The release workflow (`.github/workflows/release.yml`) does the release in two steps, with a pull
request between them:

1. In GitHub, open **Actions > Release > Run workflow** on `main`. Keep `patch`, or type `minor`,
   `major` or an exact version such as `1.2.0`. The words count from the newest `v*` tag. The
   first release has no tag yet: it is the version that `pom.xml` holds.
2. The workflow opens the pull request "Release vX.Y.Z". It sets the version in `pom.xml` and in
   the install snippet of `README.md`, and turns `[Unreleased]` into the dated section of the
   version. It also starts CI on the pull request. An `[Unreleased]` section with no entry, or a
   version that Maven Central already has, stops the workflow.
3. Read the pull request, and merge it when CI passes.
4. The merge starts the workflow again. It waits for CI on the merge commit and publishes the SDK
   to Maven Central. Then it creates the tag `vX.Y.Z` and its GitHub release, with the CHANGELOG
   section as the notes.

If a step fails, fix the cause and re-run the failed jobs of that run. The publish step skips a
version that Maven Central already has, and the tag comes last.

To run a step of the workflow by hand, use `java scripts/Release.java prepare <patch | minor |
major | X.Y.Z> <YYYY-MM-DD>`, `status` or `notes <X.Y.Z>`. `ReleaseTest` tests these steps in the
normal build.

### How the workflow gets the Central credentials

Maven Central has no trusted publishing (OIDC). Its upload API needs a user token, and every file
needs a GPG signature. The workflow keeps both out of GitHub:

1. The publish job runs in the `release` environment and asks GitHub for an OIDC token.
2. The job uses that token to assume the AWS role in the `AWS_RELEASE_ROLE_ARN` variable. The
   role trusts only the `release` environment of this repository.
3. The role reads the Central user token (`mcpcat/maven-central-token`) and the GPG key
   (`mcpcat/maven-gpg-key`) from AWS Secrets Manager. They stay in the shell of the publish step.

The job also attests the build provenance of the jars with Sigstore. To make sure that a jar came
from this workflow, run `gh attestation verify <jar> -R agentcathq/agentcat-java-sdk`.

### One-time setup

- In the repository settings, turn on **Actions > General > Allow GitHub Actions to create and
  approve pull requests**, so that the workflow can open the release pull request.
- Create a `release` environment, limited to the `main` branch.
- Create an AWS IAM role for the release, with this trust policy:

  ```json
  {
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:agentcathq/agentcat-java-sdk:environment:release"
      }
    }
  }
  ```

  Give the role `secretsmanager:GetSecretValue` on `mcpcat/maven-central-token` and
  `mcpcat/maven-gpg-key`, and nothing else.
- Set the repository variable `AWS_RELEASE_ROLE_ARN` to the ARN of that role.
