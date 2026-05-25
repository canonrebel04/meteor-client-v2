mkdir test_build
cd test_build
git clone -b 26.1 https://github.com/canonrebel04/baritone-v2
cd baritone-v2
./gradlew build publishToMavenLocal
