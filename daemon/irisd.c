/*
 * irisd - native Iris push daemon (Tier 2 of OS_INTEGRATION_PLAN.md).
 *
 * Holds the persistent TLS/WebSocket connection(s) to Iris server(s) at
 * init-daemon privilege instead of inside a Java/ART app process, so the
 * socket survives Doze/App Standby without an app needing special
 * exemptions. Delivers incoming pushes to on-device apps via a small local
 * control socket ("iris_daemon") that a thin, signature-permission-holding
 * Java component (IrisBridgeService in this repo's app module) connects to
 * and turns into the existing de.letheapp.iris.PUSH broadcast.
 *
 * One connection per registered app/device pair, read from a directory of
 * small config files (see load_token_cfg()). The directory is watched via
 * inotify so apps can be registered/unregistered without restarting the
 * daemon.
 *
 * Build:
 *   - On-device (AOSP tree):      see Android.bp in this directory.
 *   - Standalone (dev/test only): `make` in this directory (needs
 *     libssl-dev). Falls back to a plain AF_UNIX socket at
 *     $IRIS_BRIDGE_SOCKET_PATH (default /tmp/iris_daemon.sock) instead of
 *     the init-provided control socket used on-device.
 *
 * Scope note: this is a first working version, not a hardened production
 * daemon. Known simplifications are called out inline and in README.md.
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h>
#include <errno.h>
#include <time.h>
#include <dirent.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/inotify.h>
#include <netdb.h>
#include <arpa/inet.h>

#include <openssl/ssl.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/rand.h>

#define LOG_TAG "irisd"
#ifdef ANDROID_BUILD
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { fprintf(stderr, "I/" LOG_TAG ": " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGW(...) do { fprintf(stderr, "W/" LOG_TAG ": " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { fprintf(stderr, "E/" LOG_TAG ": " __VA_ARGS__); fprintf(stderr, "\n"); } while (0)
#endif

#define WS_GUID "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
#define PING_INTERVAL_SEC 45
#define RECV_TIMEOUT_SEC 5
#define RECONNECT_DELAY_SEC 5
#define MAX_BRIDGE_CLIENTS 8
#define MAX_LINE 8192
#define IRISD_VERSION "1.1.0"

/* ---------- bridge (daemon -> on-device app) client registry ---------- */

static pthread_mutex_t g_bridge_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_bridge_fds[MAX_BRIDGE_CLIENTS];
static int g_bridge_fd_count = 0;

static void bridge_add_client(int fd) {
    pthread_mutex_lock(&g_bridge_mutex);
    if (g_bridge_fd_count < MAX_BRIDGE_CLIENTS) {
        g_bridge_fds[g_bridge_fd_count++] = fd;
    } else {
        close(fd); /* no room - oldest-wins would need more bookkeeping, not worth it for v1 */
    }
    pthread_mutex_unlock(&g_bridge_mutex);
}

/* Broadcasts one newline-terminated JSON line to every connected bridge
 * client, pruning any that error out (the usual case: the app process was
 * killed/restarted and hasn't reconnected yet). */
static void bridge_broadcast(const char *line) {
    char buf[MAX_LINE + 1];
    int n = snprintf(buf, sizeof(buf), "%s\n", line);
    if (n <= 0) return;
    size_t len = (size_t)n;

    pthread_mutex_lock(&g_bridge_mutex);
    int kept = 0;
    for (int i = 0; i < g_bridge_fd_count; i++) {
        int fd = g_bridge_fds[i];
        ssize_t w = write(fd, buf, len);
        if (w == (ssize_t)len) {
            g_bridge_fds[kept++] = fd;
        } else {
            LOGW("bridge client fd=%d write failed (errno=%d), dropping", fd, errno);
            close(fd);
        }
    }
    g_bridge_fd_count = kept;
    pthread_mutex_unlock(&g_bridge_mutex);
}

/* Removes one client (e.g. once its reader thread sees EOF) without closing
 * it a second time - the reader thread already owns the close(). */
static void bridge_remove_client(int fd) {
    pthread_mutex_lock(&g_bridge_mutex);
    int kept = 0;
    for (int i = 0; i < g_bridge_fd_count; i++) {
        if (g_bridge_fds[i] != fd) g_bridge_fds[kept++] = g_bridge_fds[i];
    }
    g_bridge_fd_count = kept;
    pthread_mutex_unlock(&g_bridge_mutex);
}

/* Writes one newline-terminated line to exactly one bridge client (a STATUS
 * reply), under the same mutex as bridge_broadcast so it can't interleave
 * with a push line landing on the same fd concurrently. */
static void bridge_send_to(int fd, const char *line) {
    char buf[MAX_LINE + 1];
    int n = snprintf(buf, sizeof(buf), "%s\n", line);
    if (n <= 0) return;
    pthread_mutex_lock(&g_bridge_mutex);
    write(fd, buf, (size_t)n);
    pthread_mutex_unlock(&g_bridge_mutex);
}

/* ---------- tiny JSON field extraction (trusted-ish server input) ----------
 * Deliberately not a general JSON parser - we only ever need a handful of
 * top-level string fields plus the raw "data" object out of payloads this
 * daemon's own server side produces. Good enough for v1; swap for a real
 * parser (e.g. cJSON) if this ever needs to accept push payloads from
 * untrusted third parties. */

static int json_find_string(const char *json, const char *key, char *out, size_t out_sz) {
    char needle[128];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *p = strstr(json, needle);
    if (!p) return 0;
    p = strchr(p + strlen(needle), ':');
    if (!p) return 0;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '"') return 0;
    p++;
    size_t i = 0;
    while (*p && *p != '"' && i + 1 < out_sz) {
        if (*p == '\\' && p[1]) p++;
        out[i++] = *p++;
    }
    out[i] = '\0';
    return 1;
}

/* Extracts the raw substring of a top-level JSON object value (balanced
 * braces), e.g. for re-embedding "data":{...} as-is without reparsing it. */
static int json_find_raw_object(const char *json, const char *key, char *out, size_t out_sz) {
    char needle[128];
    snprintf(needle, sizeof(needle), "\"%s\"", key);
    const char *p = strstr(json, needle);
    if (!p) return 0;
    p = strchr(p + strlen(needle), ':');
    if (!p) return 0;
    p++;
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '{') return 0;
    const char *start = p;
    int depth = 0;
    do {
        if (*p == '{') depth++;
        else if (*p == '}') depth--;
        else if (*p == '\0') return 0;
        p++;
    } while (depth > 0);
    size_t len = (size_t)(p - start);
    if (len + 1 > out_sz) return 0;
    memcpy(out, start, len);
    out[len] = '\0';
    return 1;
}

/* ---------- minimal base64 (for the Sec-WebSocket-Key/-Accept handshake) ---------- */

static int b64_encode(const unsigned char *in, int in_len, char *out, int out_sz) {
    int n = EVP_EncodeBlock((unsigned char *)out, in, in_len);
    if (n < 0 || n >= out_sz) return -1;
    out[n] = '\0';
    return n;
}

/* ---------- blocking I/O helpers, transparent over plain TCP or TLS ---------- */

typedef struct {
    int fd;
    SSL *ssl; /* NULL for plaintext (ws://, dev/test only) */
} io_ctx_t;

static ssize_t io_read(io_ctx_t *io, void *buf, size_t n) {
    if (io->ssl) {
        int r = SSL_read(io->ssl, buf, (int)n);
        if (r <= 0) return -1;
        return r;
    }
    return read(io->fd, buf, n);
}

static ssize_t io_write(io_ctx_t *io, const void *buf, size_t n) {
    if (io->ssl) {
        int r = SSL_write(io->ssl, buf, (int)n);
        if (r <= 0) return -1;
        return r;
    }
    return write(io->fd, buf, n);
}

static int io_read_exact(io_ctx_t *io, void *buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = io_read(io, (char *)buf + got, n - got);
        if (r <= 0) {
            if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                /* recv timeout fired with nothing read - let caller decide
                 * whether that's a ping-interval tick or a real stall. */
                return -2;
            }
            return -1;
        }
        got += (size_t)r;
    }
    return 0;
}

/* ---------- per-token config, loaded from <config_dir>/<name>.conf ----------
 * Format: one "key=value" per line.
 *   server_url=https://letheapp.de
 *   token=<from /push/register>
 *   package_name=de.letheapp.lethe
 */

typedef struct {
    char filename[256];
    char host[256];
    int port;
    int use_tls;
    char path[512]; /* /iris/connect/<token> */
    char package_name[256];
} token_cfg_t;

static int parse_server_url(const char *url, char *host, size_t host_sz, int *port, int *use_tls) {
    const char *p = url;
    if (strncmp(p, "https://", 8) == 0) { *use_tls = 1; *port = 443; p += 8; }
    else if (strncmp(p, "http://", 7) == 0) { *use_tls = 0; *port = 80; p += 7; }
    else { *use_tls = 1; *port = 443; }

    const char *end = p;
    while (*end && *end != '/' && *end != ':') end++;
    size_t hlen = (size_t)(end - p);
    if (hlen == 0 || hlen + 1 > host_sz) return -1;
    memcpy(host, p, hlen);
    host[hlen] = '\0';

    if (*end == ':') {
        *port = atoi(end + 1);
    }
    return 0;
}

static int load_token_cfg(const char *dir, const char *filename, token_cfg_t *cfg) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, filename);
    FILE *f = fopen(path, "r");
    if (!f) return -1;

    memset(cfg, 0, sizeof(*cfg));
    strncpy(cfg->filename, filename, sizeof(cfg->filename) - 1);

    char server_url[256] = "";
    char token[256] = "";
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        char *eq = strchr(line, '=');
        if (!eq) continue;
        *eq = '\0';
        char *key = line;
        char *val = eq + 1;
        size_t vlen = strlen(val);
        while (vlen > 0 && (val[vlen - 1] == '\n' || val[vlen - 1] == '\r' || val[vlen - 1] == ' ')) {
            val[--vlen] = '\0';
        }
        if (strcmp(key, "server_url") == 0) strncpy(server_url, val, sizeof(server_url) - 1);
        else if (strcmp(key, "token") == 0) strncpy(token, val, sizeof(token) - 1);
        else if (strcmp(key, "package_name") == 0) strncpy(cfg->package_name, val, sizeof(cfg->package_name) - 1);
    }
    fclose(f);

    if (server_url[0] == '\0' || token[0] == '\0' || cfg->package_name[0] == '\0') {
        LOGW("%s: incomplete config (need server_url, token, package_name) - skipping", filename);
        return -1;
    }
    if (parse_server_url(server_url, cfg->host, sizeof(cfg->host), &cfg->port, &cfg->use_tls) != 0) {
        LOGW("%s: unparsable server_url '%s'", filename, server_url);
        return -1;
    }
    snprintf(cfg->path, sizeof(cfg->path), "/iris/connect/%s", token);
    return 0;
}

/* ---------- WebSocket framing ---------- */

static int ws_send_text(io_ctx_t *io, const char *text) {
    size_t len = strlen(text);
    if (len > 65535) return -1; /* outbound is always "ping", never hit in practice */

    unsigned char header[10];
    size_t hlen = 0;
    header[hlen++] = 0x81; /* FIN=1, opcode=text */
    if (len < 126) {
        header[hlen++] = (unsigned char)(0x80 | len);
    } else {
        header[hlen++] = 0x80 | 126;
        header[hlen++] = (unsigned char)((len >> 8) & 0xFF);
        header[hlen++] = (unsigned char)(len & 0xFF);
    }
    unsigned char mask[4];
    RAND_bytes(mask, 4);
    memcpy(header + hlen, mask, 4);
    hlen += 4;

    unsigned char *masked = malloc(len);
    if (!masked) return -1;
    for (size_t i = 0; i < len; i++) masked[i] = ((unsigned char)text[i]) ^ mask[i % 4];

    int rc = 0;
    if (io_write(io, header, hlen) != (ssize_t)hlen) rc = -1;
    if (rc == 0 && len > 0 && io_write(io, masked, len) != (ssize_t)len) rc = -1;
    free(masked);
    return rc;
}

static int ws_send_pong(io_ctx_t *io, const unsigned char *payload, size_t len) {
    unsigned char header[10];
    size_t hlen = 0;
    header[hlen++] = 0x8A; /* FIN=1, opcode=pong */
    header[hlen++] = (unsigned char)(0x80 | (len < 126 ? len : 0)); /* control frames are always tiny */
    unsigned char mask[4];
    RAND_bytes(mask, 4);
    memcpy(header + hlen, mask, 4);
    hlen += 4;
    if (io_write(io, header, hlen) != (ssize_t)hlen) return -1;
    if (len > 0) {
        unsigned char masked[128];
        if (len > sizeof(masked)) len = sizeof(masked);
        for (size_t i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i % 4];
        if (io_write(io, masked, len) != (ssize_t)len) return -1;
    }
    return 0;
}

/* Reads exactly one frame. Returns opcode, fills payload (caller-owned
 * buffer) and payload_len. Returns -1 on error/EOF, -2 on recv timeout
 * (caller should treat this as "nothing to do right now", not a failure). */
static int ws_read_frame(io_ctx_t *io, unsigned char *payload, size_t payload_cap,
                          size_t *payload_len, int *opcode) {
    unsigned char hdr[2];
    int rc = io_read_exact(io, hdr, 2);
    if (rc != 0) return rc;

    *opcode = hdr[0] & 0x0F;
    int masked = (hdr[1] & 0x80) != 0;
    uint64_t len = hdr[1] & 0x7F;

    if (len == 126) {
        unsigned char ext[2];
        if (io_read_exact(io, ext, 2) != 0) return -1;
        len = ((uint64_t)ext[0] << 8) | ext[1];
    } else if (len == 127) {
        unsigned char ext[8];
        if (io_read_exact(io, ext, 8) != 0) return -1;
        len = 0;
        for (int i = 0; i < 8; i++) len = (len << 8) | ext[i];
    }

    unsigned char mask_key[4] = {0, 0, 0, 0};
    if (masked) {
        if (io_read_exact(io, mask_key, 4) != 0) return -1;
    }

    if (len > payload_cap) {
        /* Drain and discard an oversized frame rather than overflow/err out. */
        unsigned char scratch[256];
        uint64_t remaining = len;
        while (remaining > 0) {
            size_t chunk = remaining < sizeof(scratch) ? remaining : sizeof(scratch);
            if (io_read_exact(io, scratch, chunk) != 0) return -1;
            remaining -= chunk;
        }
        *payload_len = 0;
        return 0;
    }

    if (len > 0 && io_read_exact(io, payload, (size_t)len) != 0) return -1;
    if (masked) {
        for (uint64_t i = 0; i < len; i++) payload[i] ^= mask_key[i % 4];
    }
    *payload_len = (size_t)len;
    return 0;
}

/* ---------- per-connection worker thread ---------- */

typedef struct {
    token_cfg_t cfg;
    volatile int stop;
    volatile int connected; /* 1 while the WS handshake has succeeded and no error/close seen yet */
} conn_arg_t;

static int set_recv_timeout(int fd, int seconds) {
    struct timeval tv = { .tv_sec = seconds, .tv_usec = 0 };
    return setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

static int tcp_connect(const char *host, int port) {
    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", port);
    struct addrinfo hints = { .ai_family = AF_UNSPEC, .ai_socktype = SOCK_STREAM };
    struct addrinfo *res = NULL;
    if (getaddrinfo(host, portstr, &hints, &res) != 0 || !res) return -1;

    int fd = -1;
    for (struct addrinfo *rp = res; rp; rp = rp->ai_next) {
        fd = socket(rp->ai_family, rp->ai_socktype, rp->ai_protocol);
        if (fd < 0) continue;
        if (connect(fd, rp->ai_addr, rp->ai_addrlen) == 0) break;
        close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    return fd;
}

static int ws_handshake(io_ctx_t *io, const char *host, const char *path) {
    unsigned char key_raw[16];
    RAND_bytes(key_raw, sizeof(key_raw));
    char key_b64[64];
    b64_encode(key_raw, sizeof(key_raw), key_b64, sizeof(key_b64));

    char req[1024];
    int n = snprintf(req, sizeof(req),
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n", path, host, key_b64);
    if (io_write(io, req, (size_t)n) != n) return -1;

    /* Read the HTTP response headers byte-by-byte until the blank line.
     * Status-line/headers are small and this only runs once per connect. */
    char resp[4096];
    size_t resp_len = 0;
    int saw_blank = 0;
    while (resp_len + 1 < sizeof(resp)) {
        char c;
        ssize_t r = io_read(io, &c, 1);
        if (r != 1) return -1;
        resp[resp_len++] = c;
        if (resp_len >= 4 &&
            resp[resp_len - 4] == '\r' && resp[resp_len - 3] == '\n' &&
            resp[resp_len - 2] == '\r' && resp[resp_len - 1] == '\n') {
            saw_blank = 1;
            break;
        }
    }
    resp[resp_len] = '\0';
    if (!saw_blank || strncmp(resp, "HTTP/1.1 101", 12) != 0) {
        LOGW("ws handshake: unexpected response: %.60s", resp);
        return -1;
    }

    /* Validate Sec-WebSocket-Accept against the key we sent. strcasestr is
     * declared by <string.h> under _GNU_SOURCE (glibc) / unconditionally
     * (bionic), both defined/available in the targets this builds for. */
    const char *accept_hdr = strcasestr(resp, "Sec-WebSocket-Accept:");
    if (!accept_hdr) return -1;
    accept_hdr += strlen("Sec-WebSocket-Accept:");
    while (*accept_hdr == ' ') accept_hdr++;
    char accept_val[128];
    size_t i = 0;
    while (*accept_hdr && *accept_hdr != '\r' && i + 1 < sizeof(accept_val)) accept_val[i++] = *accept_hdr++;
    accept_val[i] = '\0';

    char concat[128];
    snprintf(concat, sizeof(concat), "%s%s", key_b64, WS_GUID);
    unsigned char sha1[EVP_MAX_MD_SIZE];
    unsigned int sha1_len = 0;
    EVP_Digest(concat, strlen(concat), sha1, &sha1_len, EVP_sha1(), NULL);
    char expected_b64[64];
    b64_encode(sha1, (int)sha1_len, expected_b64, sizeof(expected_b64));

    if (strcmp(accept_val, expected_b64) != 0) {
        LOGW("ws handshake: Sec-WebSocket-Accept mismatch");
        return -1;
    }
    return 0;
}

static void *conn_thread_main(void *arg) {
    conn_arg_t *a = (conn_arg_t *)arg;
    token_cfg_t *cfg = &a->cfg;

    SSL_CTX *ssl_ctx = NULL;
    if (cfg->use_tls) {
        ssl_ctx = SSL_CTX_new(TLS_client_method());
        SSL_CTX_set_verify(ssl_ctx, SSL_VERIFY_PEER, NULL);
        SSL_CTX_set_default_verify_paths(ssl_ctx);
    }

    while (!a->stop) {
        LOGI("[%s] connecting to %s:%d%s", cfg->package_name, cfg->host, cfg->port, cfg->path);
        int fd = tcp_connect(cfg->host, cfg->port);
        if (fd < 0) {
            LOGW("[%s] tcp connect failed, retrying in %ds", cfg->package_name, RECONNECT_DELAY_SEC);
            sleep(RECONNECT_DELAY_SEC);
            continue;
        }
        set_recv_timeout(fd, RECV_TIMEOUT_SEC);

        io_ctx_t io = { .fd = fd, .ssl = NULL };
        SSL *ssl = NULL;
        if (cfg->use_tls) {
            ssl = SSL_new(ssl_ctx);
            SSL_set_fd(ssl, fd);
            SSL_set_tlsext_host_name(ssl, cfg->host);
            if (SSL_connect(ssl) != 1) {
                LOGW("[%s] TLS handshake failed", cfg->package_name);
                SSL_free(ssl);
                close(fd);
                sleep(RECONNECT_DELAY_SEC);
                continue;
            }
            io.ssl = ssl;
        }

        if (ws_handshake(&io, cfg->host, cfg->path) != 0) {
            LOGW("[%s] websocket handshake failed", cfg->package_name);
            if (ssl) { SSL_shutdown(ssl); SSL_free(ssl); }
            close(fd);
            sleep(RECONNECT_DELAY_SEC);
            continue;
        }
        LOGI("[%s] connected", cfg->package_name);
        a->connected = 1;

        time_t last_ping = time(NULL);
        unsigned char payload[MAX_LINE];
        while (!a->stop) {
            size_t payload_len = 0;
            int opcode = 0;
            int rc = ws_read_frame(&io, payload, sizeof(payload) - 1, &payload_len, &opcode);

            if (rc == -2) {
                /* recv timeout - just a chance to check the ping clock/stop flag */
            } else if (rc != 0) {
                LOGW("[%s] connection lost", cfg->package_name);
                break;
            } else if (opcode == 0x1) { /* text frame */
                payload[payload_len] = '\0';
                if (strcmp((char *)payload, "pong") == 0) {
                    /* keepalive ack, nothing to do */
                } else {
                    char title[256] = "", body[1024] = "", data_json[MAX_LINE] = "{}";
                    char type[32] = "";
                    json_find_string((char *)payload, "type", type, sizeof(type));
                    if (strcmp(type, "push") == 0) {
                        json_find_string((char *)payload, "title", title, sizeof(title));
                        json_find_string((char *)payload, "body", body, sizeof(body));
                        json_find_raw_object((char *)payload, "data", data_json, sizeof(data_json));

                        char line[MAX_LINE];
                        snprintf(line, sizeof(line),
                            "{\"package_name\":\"%s\",\"title\":\"%s\",\"body\":\"%s\",\"data_json\":%s}",
                            cfg->package_name, title, body,
                            data_json[0] ? data_json : "\"{}\"");
                        bridge_broadcast(line);
                    }
                }
            } else if (opcode == 0x9) { /* ping from server */
                ws_send_pong(&io, payload, payload_len);
            } else if (opcode == 0x8) { /* close */
                LOGI("[%s] server closed connection", cfg->package_name);
                break;
            }

            time_t now = time(NULL);
            if (now - last_ping >= PING_INTERVAL_SEC) {
                if (ws_send_text(&io, "ping") != 0) break;
                last_ping = now;
            }
        }

        a->connected = 0;
        if (ssl) { SSL_shutdown(ssl); SSL_free(ssl); }
        close(fd);
        if (!a->stop) sleep(RECONNECT_DELAY_SEC);
    }

    if (ssl_ctx) SSL_CTX_free(ssl_ctx);
    free(a);
    return NULL;
}

/* ---------- config directory watcher: spawns/stops conn threads ---------- */

typedef struct tracked {
    char filename[256];
    conn_arg_t *arg; /* thread checks arg->stop; we never join (detached) */
    struct tracked *next;
} tracked_t;

static tracked_t *g_tracked = NULL;
static pthread_mutex_t g_tracked_mutex = PTHREAD_MUTEX_INITIALIZER;

static void spawn_for_file(const char *dir, const char *filename) {
    token_cfg_t cfg;
    if (load_token_cfg(dir, filename, &cfg) != 0) return;

    conn_arg_t *arg = calloc(1, sizeof(conn_arg_t));
    arg->cfg = cfg;
    arg->stop = 0;

    pthread_t tid;
    if (pthread_create(&tid, NULL, conn_thread_main, arg) != 0) {
        LOGE("pthread_create failed for %s", filename);
        free(arg);
        return;
    }
    pthread_detach(tid);

    tracked_t *t = calloc(1, sizeof(tracked_t));
    strncpy(t->filename, filename, sizeof(t->filename) - 1);
    t->arg = arg;

    pthread_mutex_lock(&g_tracked_mutex);
    t->next = g_tracked;
    g_tracked = t;
    pthread_mutex_unlock(&g_tracked_mutex);

    LOGI("spawned connection thread for %s (package=%s)", filename, cfg.package_name);
}

static int is_conf_file(const char *name) {
    size_t len = strlen(name);
    return len > 5 && strcmp(name + len - 5, ".conf") == 0;
}

static void rescan_dir(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) {
        LOGW("config dir %s not accessible: %s", dir, strerror(errno));
        return;
    }

    /* mark-and-sweep against the tracked list */
    char seen[64][256];
    int seen_count = 0;

    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (!is_conf_file(ent->d_name)) continue;
        if (seen_count < 64) strncpy(seen[seen_count++], ent->d_name, 255);

        pthread_mutex_lock(&g_tracked_mutex);
        int already = 0;
        for (tracked_t *t = g_tracked; t; t = t->next) {
            if (strcmp(t->filename, ent->d_name) == 0) { already = 1; break; }
        }
        pthread_mutex_unlock(&g_tracked_mutex);

        if (!already) spawn_for_file(dir, ent->d_name);
    }
    closedir(d);

    /* stop threads whose config file disappeared */
    pthread_mutex_lock(&g_tracked_mutex);
    tracked_t **pp = &g_tracked;
    while (*pp) {
        tracked_t *t = *pp;
        int still_present = 0;
        for (int i = 0; i < seen_count; i++) {
            if (strcmp(seen[i], t->filename) == 0) { still_present = 1; break; }
        }
        if (!still_present) {
            LOGI("config %s removed, stopping its connection", t->filename);
            t->arg->stop = 1; /* thread notices within RECV_TIMEOUT_SEC and exits */
            *pp = t->next;
            free(t);
        } else {
            pp = &t->next;
        }
    }
    pthread_mutex_unlock(&g_tracked_mutex);
}

/* Builds the JSON line sent back in response to a "STATUS" request on the
 * bridge control socket: daemon version plus one {package_name,connected}
 * entry per currently tracked (i.e. config file present) app. */
static void build_status_json(char *out, size_t out_sz) {
    char apps_buf[4096];
    size_t apps_len = 0;
    apps_buf[0] = '\0';

    pthread_mutex_lock(&g_tracked_mutex);
    int first = 1;
    for (tracked_t *t = g_tracked; t; t = t->next) {
        char entry[384];
        int n = snprintf(entry, sizeof(entry), "%s{\"package_name\":\"%s\",\"connected\":%s}",
            first ? "" : ",", t->arg->cfg.package_name, t->arg->connected ? "true" : "false");
        if (n > 0 && apps_len + (size_t)n + 1 < sizeof(apps_buf)) {
            memcpy(apps_buf + apps_len, entry, (size_t)n);
            apps_len += (size_t)n;
            apps_buf[apps_len] = '\0';
            first = 0;
        }
    }
    pthread_mutex_unlock(&g_tracked_mutex);

    snprintf(out, out_sz, "{\"type\":\"status\",\"daemon_version\":\"%s\",\"apps\":[%s]}",
        IRISD_VERSION, apps_buf);
}

static void *watch_thread_main(void *arg) {
    const char *dir = (const char *)arg;
    rescan_dir(dir);

    int ifd = inotify_init1(0);
    if (ifd < 0) {
        LOGE("inotify_init1 failed: %s - config dir changes need a daemon restart", strerror(errno));
        return NULL;
    }
    if (inotify_add_watch(ifd, dir,
            IN_CREATE | IN_DELETE | IN_CLOSE_WRITE | IN_MOVED_TO | IN_MOVED_FROM) < 0) {
        LOGE("inotify_add_watch(%s) failed: %s", dir, strerror(errno));
        close(ifd);
        return NULL;
    }

    char buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
    for (;;) {
        ssize_t len = read(ifd, buf, sizeof(buf));
        if (len <= 0) continue;
        /* Don't bother parsing individual events - any change just triggers
         * a full rescan, which is cheap at the scale this runs at (a
         * handful of apps per device, not thousands). */
        rescan_dir(dir);
    }
    return NULL;
}

/* ---------- bridge client reader thread (IrisBridgeService -> daemon commands) ----------
 * Each accepted bridge client also gets a reader thread so it can issue
 * "STATUS\n" and get back one JSON line describing daemon_version plus
 * per-app connected state - this is how the app UI tells a genuinely
 * running irisd (and its live per-app state) apart from a stale/foreign
 * process that merely happens to be listening on the same socket name. */

typedef struct {
    int fd;
} bridge_reader_arg_t;

static void *bridge_client_reader_main(void *arg) {
    int fd = ((bridge_reader_arg_t *)arg)->fd;
    free(arg);

    char line[256];
    size_t len = 0;
    for (;;) {
        char c;
        ssize_t r = read(fd, &c, 1);
        if (r <= 0) break;
        if (c == '\n') {
            line[len] = '\0';
            if (strcmp(line, "STATUS") == 0) {
                char status[4096];
                build_status_json(status, sizeof(status));
                bridge_send_to(fd, status);
            }
            len = 0;
        } else if (len + 1 < sizeof(line)) {
            line[len++] = c;
        }
    }

    bridge_remove_client(fd);
    LOGI("bridge client disconnected (fd=%d)", fd);
    return NULL;
}

/* ---------- local control socket (daemon -> IrisBridgeService clients) ---------- */

static int create_bridge_listen_socket(void) {
#ifdef ANDROID_BUILD
    /* init created and bound this socket for us per the "socket" line in
     * iris_daemon.rc; its fd is passed via ANDROID_SOCKET_<name>. */
    const char *fdstr = getenv("ANDROID_SOCKET_iris_daemon");
    if (!fdstr) {
        LOGE("ANDROID_SOCKET_iris_daemon not set - was this started by init with the right .rc?");
        return -1;
    }
    int fd = atoi(fdstr);
    listen(fd, 5); /* no-op if init already put it in the listening state */
    return fd;
#else
    const char *path = getenv("IRIS_BRIDGE_SOCKET_PATH");
    if (!path) path = "/tmp/iris_daemon.sock";
    unlink(path);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        LOGE("bind(%s) failed: %s", path, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 5) < 0) {
        close(fd);
        return -1;
    }
    LOGI("bridge control socket listening at %s (standalone/dev mode)", path);
    return fd;
#endif
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);

    const char *config_dir = getenv("IRIS_CONFIG_DIR");
    if (!config_dir) config_dir = (argc > 1) ? argv[1] : "/data/misc/iris/tokens";

    SSL_library_init();
    SSL_load_error_strings();

    int listen_fd = create_bridge_listen_socket();
    if (listen_fd < 0) {
        LOGE("could not set up bridge control socket, exiting");
        return 1;
    }

    pthread_t watch_tid;
    pthread_create(&watch_tid, NULL, watch_thread_main, (void *)config_dir);

    LOGI("irisd started, watching %s for app registrations", config_dir);

    for (;;) {
        int client_fd = accept(listen_fd, NULL, NULL);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            LOGE("accept() failed: %s", strerror(errno));
            break;
        }
        bridge_add_client(client_fd);
        LOGI("bridge client connected (fd=%d)", client_fd);

        bridge_reader_arg_t *ra = malloc(sizeof(bridge_reader_arg_t));
        ra->fd = client_fd;
        pthread_t reader_tid;
        if (pthread_create(&reader_tid, NULL, bridge_client_reader_main, ra) == 0) {
            pthread_detach(reader_tid);
        } else {
            LOGE("pthread_create failed for bridge reader (fd=%d)", client_fd);
            free(ra);
        }
    }

    return 0;
}
