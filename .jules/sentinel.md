## 2026-06-12 - CI Fix for Baritone MavenLocal Publish
**Vulnerability:** The CI workflow failed because the custom `baritone-v2` dependency was not successfully published to MavenLocal.
**Learning:** When building the custom `baritone-v2` dependency locally in GitHub workflows (`pull-request.yml`, `build.yml`, `release.yml`), `./gradlew build` must be executed before `./gradlew publishToMavenLocal` to ensure the required `.jar` artifacts are correctly generated. Also, `temurin` distribution for Java 25 lacks required `jmods` for the `proguard` task in `baritone-v2`.
**Prevention:** Use `zulu` distribution for Java 25 and always execute `./gradlew build` before `./gradlew publishToMavenLocal` when building `baritone-v2`.
