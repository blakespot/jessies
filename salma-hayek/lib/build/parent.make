.DEFAULT:
.DELETE_ON_ERROR:
.SECONDARY:

SUBDIRS += salma-hayek

SUBDIRS += evergreen
SUBDIRS += scm
SUBDIRS += terminator

SUBDIRS := $(wildcard $(SUBDIRS))

SIMPLE_TARGETS += build
SIMPLE_TARGETS += clean
SIMPLE_TARGETS += install
SIMPLE_TARGETS += installer
SIMPLE_TARGETS += native
SIMPLE_TARGETS += native-dist
SIMPLE_TARGETS += test

define MAKE_SIMPLE_TARGET
.PHONY: $(1)
$(1): recurse.$(1)
endef

# variables
# ======================================================================
# rules

.PHONY: default
default: update build

$(foreach SIMPLE_TARGET,$(SIMPLE_TARGETS),$(eval $(call MAKE_SIMPLE_TARGET,$(SIMPLE_TARGET))))

.PHONY: recurse.%
recurse.%:
	$(foreach SUBDIR,$(SUBDIRS),$(MAKE) -C $(SUBDIR) $*;)

.PHONY: update
update:
	( cd salma-hayek && git pull )
	( cd scm && git pull ) || true
