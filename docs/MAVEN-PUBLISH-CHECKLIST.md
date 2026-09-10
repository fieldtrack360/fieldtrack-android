# Maven Publish Preflight Checklist

Run these `gradlew` commands **in order** before publishing, to guarantee the publish
will not fail (and, just as important, will not *succeed while shipping a broken or
unconfigured artifact*).

On Windows use `gradlew.bat`; on macOS/Linux use `./gradlew`. Commands below are written
for Windows since that is the primary dev environment for this repo.

Published modules: `fieldtrack-geo`, `fieldtrack-core`, `fieldtrack-maps`,
`fieldtrack-sync`, `fieldtrack-snap`, `fieldtrack` (umbrella). Coordinates:
`com.github.fieldtrack360.fieldtrack:<module>:<version>`.

---

## 0. Clean slate

Windows file locks from a stale daemon are the most common cause of spurious failures
(`The process cannot access the file because it is being used by another process`).

```bash
gradlew.bat --stop
gradlew.bat clean
```

If the lock persists after `--stop`, close Android Studio (it runs its own daemon) and
retry.

## 1. Verify license configuration is present

Two license values are compiled into `fieldtrack-core`'s `BuildConfig` at build time.
**A blank value does not fail the build** — it silently ships an AAR with the license
layer inert: no request is made, no response is ever trusted, and every start carries on.
Check the committed `configuration.properties` at the repository root contains both before
building:

```properties
FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
FIELDTRACK_RESPONSE_KEY=Base64OfThirtyTwoRawBytes=
```

Resolution order (first non-blank wins): Gradle property (`-PfieldtrackLicenseUrl`,
`-PfieldtrackResponseKey`) → environment variable → `configuration.properties`.
**`local.properties` is not consulted for either** — see BUILD.md §1. Because the file is
committed, the normal case is that both are already correct in a fresh clone and this step
is a check rather than a chore.

`FIELDTRACK_LICENSE_KEYS` used to be a third value here and is **no longer read by
anything**. The offline token-signature gate it fed was removed; the response key above is
the only key the SDK still compiles in. A value set for it has no effect on the artifact.

Quick sanity check that the URL actually landed in the artifact (run after step 3):

```bash
# BuildConfig is compiled into the AAR's classes.jar; grep the generated source instead:
findstr /s /c:"LICENSE_BASE_URL" fieldtrack-core\build\generated\source\buildConfig\release\*.java
```

## 2. Confirm the version being published

The publish version comes from `-Pversion` if passed, otherwise from the `traker`
version in `gradle/libs.versions.toml` (currently `0.1.1-alpha01`). Note this is **not**
the `fieldtrack` catalog version — that one only feeds `BuildConfig.SDK_VERSION`.

```bash
# See resolved coordinates without publishing anything:
gradlew.bat :fieldtrack-core:generatePomFileForReleasePublication
type fieldtrack-core\build\publications\release\pom-default.xml
```

Check `<groupId>`, `<artifactId>`, `<version>` are what you expect. To publish a
specific version:

```bash
gradlew.bat publishToMavenLocal -Pversion=v1.0.1-alpha-05
```

## 3. Build every release AAR

Publishing uses the `release` variant only. Build them all first so any compile,
R8, or lint-vital failure surfaces here rather than mid-publish:

```bash
gradlew.bat :fieldtrack-geo:assembleRelease :fieldtrack-core:assembleRelease :fieldtrack-maps:assembleRelease :fieldtrack-sync:assembleRelease :fieldtrack-snap:assembleRelease :fieldtrack:assembleRelease
```

Or simply everything (includes the sample app as an extra smoke test):

```bash
gradlew.bat assembleRelease
```

## 4. Run the tests

```bash
gradlew.bat test
```

Release publishing does not run tests automatically — do it yourself.

## 5. Run the publish gate tasks explicitly

Every `publish*` task depends on these two root tasks; if they fail, the publish fails.
Running them alone gives a clearer error:

```bash
gradlew.bat verifyReleaseObfuscation archiveReleaseMappings
```

`verifyReleaseObfuscation` audits that the AARs are R8-obfuscated and that the javadoc
jar contains no `.kt`/`.java` sources. `archiveReleaseMappings` preserves the R8 mapping
files for the release.

## 6. Dry-run: publish to Maven Local

`publishToMavenLocal` needs no credentials and exercises the entire publication
(POM generation, javadoc jar, metadata, gate tasks):

```bash
gradlew.bat publishToMavenLocal
```

Then verify the artifacts actually landed:

```bash
dir %USERPROFILE%\.m2\repository\com\github\fieldtrack360\fieldtrack
```

Expect one folder per module, each containing `<module>-<version>.aar`,
`<module>-<version>-javadoc.jar`, `<module>-<version>.pom`, and `.module` metadata.
There must be **no `-sources.jar`** — sources are deliberately not published
(R8-obfuscated artifacts, see `gradle/publish.gradle.kts`).

Consume the local build from the sample/host app (`mavenLocal()` is already in
`settings.gradle.kts`) before pushing anywhere remote.

## 7. Publish to the remote repository

The remote repo is only wired in when its URL is configured — without it,
`gradlew.bat publish` publishes nowhere remote (no error, by design). Provide URL and
credentials via properties:

```bash
gradlew.bat publish -PtrakerMavenUrl=https://maven.pkg.github.com/fieldtrack360/fieldtrack -PtrakerMavenUser=USERNAME -PtrakerMavenToken=TOKEN
```

or via environment (what CI uses): `TRAKER_MAVEN_URL`, `TRAKER_MAVEN_USER`,
`TRAKER_MAVEN_TOKEN`. Never put these in `local.properties`, in `configuration.properties`,
or in any other file in the repo — the second of those is committed.

To publish a single module rather than all six:

```bash
gradlew.bat :fieldtrack-core:publishReleasePublicationToTrakerRepository -PtrakerMavenUrl=... -PtrakerMavenUser=... -PtrakerMavenToken=...
```

Note: the `...ToTrakerRepository` task only exists when `trakerMavenUrl` is set —
`gradlew.bat tasks --group publishing` without the property will not list it.

## 8. Post-publish verification

```bash
# List every publish-related task that exists, to confirm task names:
gradlew.bat tasks --group publishing

# JitPack builds: JITPACK=true excludes :sample-android from the build.
# Simulate locally (PowerShell):
#   $env:JITPACK = "true"; .\gradlew.bat publishToMavenLocal; Remove-Item Env:JITPACK
```

---

## Quick one-liner (local publish, all gates)

```bash
gradlew.bat --stop && gradlew.bat clean test verifyReleaseObfuscation archiveReleaseMappings publishToMavenLocal
```

(PowerShell 5.1 has no `&&`: run `gradlew.bat --stop` first, then the rest as one
command.)

## Common failure causes

| Symptom | Cause | Fix |
|---|---|---|
| `The process cannot access the file ... lint.jar` | Stale Gradle daemon holds a file lock | `gradlew.bat --stop`, close Android Studio, rebuild |
| POM version is `0.1.1-alpha01` when you expected another | `-Pversion` not passed; catalog `traker` version is the default | Pass `-Pversion=...` |
| Publish "succeeds" but nothing reaches the remote | `trakerMavenUrl` not set — remote repo is only added when configured | Set `-PtrakerMavenUrl` / `TRAKER_MAVEN_URL` |
| 401/403 on remote publish | Missing/expired token, or token lacks `write:packages` | Regenerate token, pass `-PtrakerMavenUser` / `-PtrakerMavenToken` |
| AAR ships with license layer inert | Blank `FIELDTRACK_LICENSE_URL` / `FIELDTRACK_RESPONSE_KEY` at build time | Fill `configuration.properties` (step 1) and rebuild before publishing |
| `verifyReleaseObfuscation` fails | Release AAR not minified, or sources leaked into javadoc jar | Check `isMinifyEnabled = true` and the javadoc jar contents |
| `javaDocReleaseGeneration` fails: `PermittedSubclasses requires ASM9` | Sealed Kotlin types compiled at jvmTarget 17+; AGP's embedded Dokka cannot read the attribute | Library modules must use `libs.versions.javaBytecode` (11) for `jvmTarget`/`compileOptions`, never `javaTarget` |
| Group is `com.github.fieldtrack360` (no `.fieldtrack`) | Something overrode the group | Group is hardcoded in `gradle/publish.gradle.kts`; `-Pgroup` is deliberately ignored |
