// Loads a runtime folder's libmpv (and libmediampv) the way the desktop app does, by absolute path
// with RUNPATH=$ORIGIN finding the rest, then either plays a file or prints what the build offers.
//
//   smoke play <runtime dir> <video> [subtitle]   decode 30 frames with vo=null, ao=null; with a
//                                                 subtitle, vf=sub renders it through libass
//   smoke info <runtime dir>                      versions, configuration, option and property
//                                                 names and decoders, for diffing a rebuilt
//                                                 runtime against the shipped one (the option
//                                                 names carry every compiled-in vo, ao and hwdec)
//
// Declares the few libmpv calls it needs instead of including client.h, so it builds with a plain
// C compiler and no mpv headers. Exit 0 only when every step worked.
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct mpv_handle mpv_handle;
typedef struct {
  int event_id;
  int error;
  uint64_t reply_userdata;
  void *data;
} mpv_event;
typedef struct {
  int reason;
  int error;
} mpv_event_end_file;

enum { EV_SHUTDOWN = 1, EV_END_FILE = 7, EV_FILE_LOADED = 8 };
enum { END_EOF = 0, END_STOP = 2, END_QUIT = 3, END_ERROR = 4 };

static unsigned long (*api_version)(void);
static mpv_handle *(*create)(void);
static int (*set_option_string)(mpv_handle *, const char *, const char *);
static int (*initialize)(mpv_handle *);
static int (*command)(mpv_handle *, const char **);
static mpv_event *(*wait_event)(mpv_handle *, double);
static char *(*get_property_string)(mpv_handle *, const char *);
static void (*mpv_free)(void *);
static void (*terminate_destroy)(mpv_handle *);
static const char *(*error_string)(int);

static void *sym(void *lib, const char *name) {
  void *p = dlsym(lib, name);
  if (!p) {
    fprintf(stderr, "FAIL: %s missing: %s\n", name, dlerror());
    exit(1);
  }
  return p;
}

static void *open_lib(const char *dir, const char *name) {
  char path[4096];
  snprintf(path, sizeof path, "%s/%s", dir, name);
  void *lib = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!lib) {
    fprintf(stderr, "FAIL: dlopen %s: %s\n", path, dlerror());
    exit(1);
  }
  return lib;
}

static void load(const char *dir) {
  void *lib = open_lib(dir, "libmpv.so.2");
  api_version = sym(lib, "mpv_client_api_version");
  create = sym(lib, "mpv_create");
  set_option_string = sym(lib, "mpv_set_option_string");
  initialize = sym(lib, "mpv_initialize");
  command = sym(lib, "mpv_command");
  wait_event = sym(lib, "mpv_wait_event");
  get_property_string = sym(lib, "mpv_get_property_string");
  mpv_free = sym(lib, "mpv_free");
  terminate_destroy = sym(lib, "mpv_terminate_destroy");
  error_string = sym(lib, "mpv_error_string");
  // The JNI wrapper resolves its JNI calls through the JNIEnv table, so it loads without a JVM.
  open_lib(dir, "libmediampv.so");
}

static void opt(mpv_handle *h, const char *name, const char *value) {
  int r = set_option_string(h, name, value);
  if (r < 0) {
    fprintf(stderr, "FAIL: option %s=%s: %s\n", name, value, error_string(r));
    exit(1);
  }
}

static void print_property(mpv_handle *h, const char *name) {
  char *v = get_property_string(h, name);
  printf("%s: %s\n", name, v ? v : "(unavailable)");
  if (v) mpv_free(v);
}

static int info(void) {
  printf("client api: %lu.%lu\n", api_version() >> 16, api_version() & 0xffff);
  mpv_handle *h = create();
  if (!h || initialize(h) < 0) {
    fprintf(stderr, "FAIL: mpv_initialize\n");
    return 1;
  }
  print_property(h, "mpv-version");
  print_property(h, "ffmpeg-version");
  print_property(h, "libass-version");
  print_property(h, "mpv-configuration");
  char *options = get_property_string(h, "options");
  char *properties = get_property_string(h, "property-list");
  if (!options || !properties) {
    fprintf(stderr, "FAIL: options or property-list unavailable\n");
    return 1;
  }
  printf("== options\n");
  for (char *t = strtok(options, ","); t; t = strtok(NULL, ",")) printf("%s\n", t);
  printf("== properties\n");
  for (char *t = strtok(properties, ","); t; t = strtok(NULL, ",")) printf("%s\n", t);
  mpv_free(options);
  mpv_free(properties);
  print_property(h, "hwdec-interop");
  print_property(h, "decoder-list");
  terminate_destroy(h);
  return 0;
}

static int play(const char *video, const char *subtitle) {
  mpv_handle *h = create();
  if (!h) return 1;
  opt(h, "config", "no");
  opt(h, "vo", "null");
  opt(h, "ao", "null");
  opt(h, "hwdec", "no");
  opt(h, "frames", "30");
  opt(h, "terminal", "yes");
  opt(h, "msg-level", "all=warn,cplayer=info");
  if (subtitle) {
    opt(h, "sub-files", subtitle);
    opt(h, "vf", "sub");
  }
  int r = initialize(h);
  if (r < 0) {
    fprintf(stderr, "FAIL: mpv_initialize: %s\n", error_string(r));
    return 1;
  }
  const char *cmd[] = {"loadfile", video, NULL};
  r = command(h, cmd);
  if (r < 0) {
    fprintf(stderr, "FAIL: loadfile: %s\n", error_string(r));
    return 1;
  }
  int loaded = 0, ok = 0;
  for (;;) {
    mpv_event *e = wait_event(h, 30);
    if (e->event_id == 0) {
      fprintf(stderr, "FAIL: no event for 30 s\n");
      break;
    }
    if (e->event_id == EV_FILE_LOADED) {
      loaded = 1;
      print_property(h, "video-codec");
      print_property(h, "audio-codec-name");
      print_property(h, "current-tracks/sub/codec");
    }
    if (e->event_id == EV_END_FILE) {
      mpv_event_end_file *end = e->data;
      printf("end: reason %d, error %d (%s)\n", end->reason, end->error,
             end->error ? error_string(end->error) : "none");
      ok = loaded && end->reason == END_EOF && end->error == 0;
      break;
    }
    if (e->event_id == EV_SHUTDOWN) break;
  }
  terminate_destroy(h);
  printf(ok ? "PASS: decoded 30 frames\n" : "FAIL: playback did not reach the end\n");
  return ok ? 0 : 1;
}

int main(int argc, char **argv) {
  if (argc >= 3 && strcmp(argv[1], "info") == 0) {
    load(argv[2]);
    return info();
  }
  if (argc >= 4 && strcmp(argv[1], "play") == 0) {
    load(argv[2]);
    return play(argv[3], argc >= 5 ? argv[4] : NULL);
  }
  fprintf(stderr, "usage: smoke play <runtime dir> <video> [subtitle] | smoke info <runtime dir>\n");
  return 2;
}
