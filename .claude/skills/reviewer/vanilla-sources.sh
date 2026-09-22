#!/bin/sh
# Decompiles this branch's Minecraft client-only and common jars (the named jars Loom already
# built) with the Vineflower Loom already downloaded, once per MC version and mappings, and prints
# the source directory. It also holds the jars' assets (models/item/handheld_rod.json,
# textures/item/fishing_rod_cast.png, ...). Reviewers read vanilla there; exact descriptors still
# come from javap on the jars (printed too). About 30 s and 100 MB per version, cached in the
# Gradle cache so the review writes nothing into the repo.
#
# Usage: sh .claude/skills/reviewer/vanilla-sources.sh
set -eu

repo=$(git rev-parse --show-toplevel)
prop() { sed -n "s/^$1=//p" "$repo/gradle.properties" | tr -d '\r'; }
mc=$(prop minecraft_version)
yarn=$(prop yarn_mappings)
maven="$HOME/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft"

if [ -z "$yarn" ]; then
    # 26.1+: unobfuscated, Mojang names.
    key=$mc
    client="$maven/minecraft-clientonly-deobf/$mc/minecraft-clientonly-deobf-$mc.jar"
    common="$maven/minecraft-common-deobf/$mc/minecraft-common-deobf-$mc.jar"
else
    # Yarn branches: e.g. 1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.4-v2
    key="$mc-net.fabricmc.yarn.$(echo "$mc" | tr . _).$yarn-v2"
    client="$maven/minecraft-clientonly/$key/minecraft-clientonly-$key.jar"
    common="$maven/minecraft-common/$key/minecraft-common-$key.jar"
fi
for jar in "$client" "$common"; do
    if [ ! -f "$jar" ]; then
        echo "Missing $jar: the branch has to be built once (Loom creates it)." >&2
        exit 1
    fi
done

out="$HOME/.gradle/caches/fishingrodfix-review/vanilla-src/$key"
if [ ! -f "$out/.complete" ]; then
    vineflower=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.vineflower/vineflower/*/*/vineflower-*.jar 2>/dev/null \
        | grep -v -- '-sources\.jar$' | sort -V | tail -n 1)
    if [ -z "$vineflower" ]; then
        echo "No Vineflower jar in the Gradle cache: run ./gradlew genSources once." >&2
        exit 1
    fi
    java=${JAVA_HOME:+$JAVA_HOME/bin/}java
    rm -rf "$out"
    mkdir -p "$out"
    "$java" -jar "$vineflower" -s -e="$common" "$client" "$out" >&2
    "$java" -jar "$vineflower" -s -e="$client" "$common" "$out" >&2
    touch "$out/.complete"
fi

echo "sources: $out"
echo "client jar: $client"
echo "common jar: $common"
