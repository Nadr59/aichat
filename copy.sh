#!/bin/bash
S=~/-UniversalDocExtractor
D=~/aichat
cd "$D"
cp "$S"/build.gradle.kts . 2>/dev/null
cp "$S"/settings.gradle.kts . 2>/dev/null
cp "$S"/gradle.properties . 2>/dev/null
cp "$S"/gradlew . 2>/dev/null
cp "$S"/gradlew.bat . 2>/dev/null
cp "$S"/.gitignore . 2>/dev/null
cp -r "$S"/gradle . 2>/dev/null
cp -r "$S"/.github . 2>/dev/null
cp "$S"/app/build.gradle.kts app/ 2>/dev/null
cp -r "$S"/app/src/main/res app/src/main/ 2>/dev/null
cp "$S"/app/src/main/AndroidManifest.xml app/src/main/ 2>/dev/null
chmod +x gradlew
echo "Done!"
find . -type f ! -path "./.git/*" | sort
bash copy.sh

