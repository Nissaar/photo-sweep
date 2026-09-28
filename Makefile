# SPDX-FileCopyrightText: 2026 Nissaar
# SPDX-License-Identifier: AGPL-3.0-or-later

app_name = photosweep
# POSIX classes rather than \s, which BSD sed (macOS) does not understand and would
# silently leave the version empty.
version = $(shell sed -ne 's/^[[:space:]]*<version>\(.*\)<\/version>/\1/p' appinfo/info.xml)

project_dir = $(CURDIR)
build_dir = $(CURDIR)/build
package_dir = $(build_dir)/$(app_name)
artifact_dir = $(build_dir)/artifacts
tarball = $(artifact_dir)/$(app_name)-$(version).tar.gz

# Signing material. The key never enters the repository; CI writes it from a secret.
cert_dir = $(HOME)/.nextcloud/certificates

# The tarball is built reproducibly: the same tree gives the same bytes, whoever
# builds it. Every entry gets this timestamp — the last commit's, so it still means
# something — and root as its owner. That needs GNU tar; on macOS pass TAR=gtar.
TAR ?= tar
SOURCE_DATE_EPOCH ?= $(shell git log -1 --format=%ct 2>/dev/null || echo 0)

.PHONY: all
all: build

# --- development -----------------------------------------------------------

.PHONY: dev-setup
dev-setup: composer-install npm-install

.PHONY: composer-install
composer-install:
	composer install --prefer-dist

.PHONY: npm-install
npm-install:
	npm ci

.PHONY: build
build:
	npm run build

.PHONY: watch
watch:
	npm run watch

.PHONY: test
test: lint psalm phpunit

.PHONY: lint
lint:
	composer run cs:check
	npm run lint

.PHONY: psalm
psalm:
	composer run psalm

.PHONY: phpunit
phpunit:
	composer run test:unit

.PHONY: clean
clean:
	rm -rf $(build_dir) js css

# --- packaging -------------------------------------------------------------

# Assembles exactly what ships: no sources, no tests, no dev tooling. The app has
# no runtime composer dependencies, so there is no vendor/ directory to carry —
# Nextcloud autoloads OCA\PhotoSweep from lib/ by itself.
.PHONY: package
package: build
	rm -rf $(build_dir)
	mkdir -p $(package_dir) $(artifact_dir)
	cp -r appinfo $(package_dir)/
	cp -r lib $(package_dir)/
	cp -r templates $(package_dir)/
	cp -r img $(package_dir)/
	cp -r js $(package_dir)/
	# Source maps are 11MB of a 14MB app — four fifths of what every server would
	# download and store, to debug minified code almost nobody will debug. They stay
	# in the build directory and out of the release.
	find $(package_dir)/js -name '*.map' -delete
	[ -d l10n ] && cp -r l10n $(package_dir)/ || true
	cp COPYING $(package_dir)/
	cp README.md $(package_dir)/

# Signs the file listing inside the package. Needs a Nextcloud install to run occ
# against; the release workflow does this inside the official image.
.PHONY: sign
sign:
	@if [ ! -f $(cert_dir)/$(app_name).key ]; then \
		echo "No signing key at $(cert_dir)/$(app_name).key — see docs/PUBLISHING.md"; \
		exit 1; \
	fi
	@if [ -z "$(NEXTCLOUD_ROOT)" ] || [ ! -f "$(NEXTCLOUD_ROOT)/occ" ]; then \
		echo "Set NEXTCLOUD_ROOT to a Nextcloud server directory (the one holding occ)," \
			"e.g. make appstore NEXTCLOUD_ROOT=/var/www/nextcloud"; \
		exit 1; \
	fi
	php $(NEXTCLOUD_ROOT)/occ integrity:sign-app \
		--privateKey=$(cert_dir)/$(app_name).key \
		--certificate=$(cert_dir)/$(app_name).crt \
		--path=$(package_dir)

# Sorted entries, one fixed mtime, owner root and normalised modes, so that neither
# the build machine's clock nor the CI runner's uid 1001 ends up in the archive — an
# app directory extracted as root and owned by 1001 can break later updates. gzip -n
# leaves the file name and time out of the gzip header for the same reason.
.PHONY: tarball
tarball:
	mkdir -p $(artifact_dir)
	$(TAR) --sort=name --mtime=@$(SOURCE_DATE_EPOCH) \
		--owner=0 --group=0 --numeric-owner --mode=u+rwX,go+rX,go-w \
		-cf $(tarball:.gz=) -C $(build_dir) $(app_name)
	gzip -9 -n -f $(tarball:.gz=)
	@echo "built $(tarball)"
	@sha512sum $(tarball) | tee $(tarball).sha512

# The full release artefact: assemble, sign the contents, tar it up. The steps run in
# order through sub-makes rather than as prerequisites, which `make -j` would start
# side by side — signing a package that is still being copied, or tarring it first.
.PHONY: appstore
appstore:
	$(MAKE) package
	$(MAKE) sign
	$(MAKE) tarball
