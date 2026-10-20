#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nissaar
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Prints the CHANGELOG.md section for one version, without its heading.
#
#   tools/release-notes.sh 1.0.5
#
# Fails, printing nothing, if the version has no section or the section is empty:
# a release without notes for its users is not ready to go out.
set -euo pipefail

version="${1:?usage: release-notes.sh <version>}"
file="$(dirname "$0")/../CHANGELOG.md"

notes=$(awk -v v="$version" '
	/^## / {
		if (found) exit
		heading = $0
		sub(/^## \[?/, "", heading)
		sub(/\].*|[[:space:]].*/, "", heading)
		found = (heading == v)
		next
	}
	found { print }
' "$file" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d')

if [ -z "$notes" ]; then
	echo "CHANGELOG.md has no notes for $version" >&2
	exit 1
fi
printf '%s\n' "$notes"
