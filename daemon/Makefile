# Standalone build for local dev/testing on a regular Linux box - NOT how
# this gets built for a device (see Android.bp for the real AOSP module).
# Useful to sanity-check the protocol logic against a real Iris server
# before wiring anything into an OS source tree.

CC ?= cc
CFLAGS ?= -Wall -Wextra -O2 -pthread
LDLIBS := -lssl -lcrypto -lpthread

irisd: irisd.c
	$(CC) $(CFLAGS) -o irisd irisd.c $(LDLIBS)

clean:
	rm -f irisd

.PHONY: clean
