/* TMPlayer site: pulls release data from the public GitHub API.
   No auth, no token. Unauthenticated calls are limited to 60 per hour per IP,
   so every failure path has to end somewhere useful rather than in a spinner.

   Why the API and not a plain link: the release files carry the version in
   their names (TMPlayer-1.18.0-windows-x64.msi), so GitHub's stable
   releases/latest/download/<name> form would need a name that never changes.
   The markup therefore links every row to the releases/latest page, which
   always works, and this script swaps in the file itself once it has read the
   release list. */

/* Text this script writes, in the page's language.

   Every sentence below goes through tmT(key, english, values). The
   English stays here as the source: scripts/build-site-i18n.py reads these calls
   into site/i18n/en.json as js.<key>, and a translated page carries its strings
   in a <script id="tm-strings"> block, so this file is the same for every
   language and nothing extra is fetched. A key missing from that block falls
   back to the English beside it. {name} placeholders are filled in last, so a
   translation can move them wherever its grammar wants them. */
var tmLocale = (document.documentElement.getAttribute('lang') || 'en').replace(/^en$/, 'en-GB');
var tmT = (function () {
  var strings = {};
  try {
    var block = document.getElementById('tm-strings');
    if (block) { strings = JSON.parse(block.textContent) || {}; }
  } catch (e) {}
  return function (key, english, vars) {
    var text = Object.prototype.hasOwnProperty.call(strings, key) ? strings[key] : english;
    if (vars) {
      text = text.replace(/\{(\w+)\}/g, function (all, name) {
        return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : all;
      });
    }
    return text;
  };
})();

(function () {
  'use strict';

  var OWNER = 'dracu-lah';
  var REPO = 'TMPlayer';
  var API = 'https://api.github.com/repos/' + OWNER + '/' + REPO + '/releases?per_page=10';
  var RELEASES_PAGE = 'https://github.com/' + OWNER + '/' + REPO + '/releases';
  var LATEST_PAGE = RELEASES_PAGE + '/latest';
  var TIMEOUT_MS = 9000;

  /* Every file a release can carry, matched loosely on the end of its name so a
     version in the middle never matters. The label is what the previous
     releases list calls it. A release now carries the universal APK, the MSI,
     the AppImage and the tarball; the other kinds stay only so the previous
     releases list still names the files older releases had (x86_64 and per-ABI
     APKs, the Windows zip, the deb, the rpm and the Flatpak). */
  var KINDS = [
    { id: 'apk-universal', label: tmT('kind_universal_apk', 'Universal APK'), test: /universal\.apk$/i },
    { id: 'apk-arm64', label: 'arm64-v8a', test: /arm64-v8a\.apk$/i },
    { id: 'apk-armv7', label: 'armeabi-v7a', test: /armeabi-v7a\.apk$/i },
    { id: 'apk-x86_64', label: 'x86_64', test: /x86_64\.apk$/i },
    { id: 'win-msi', label: tmT('kind_windows_installer', 'Windows installer'), test: /windows-x64\.msi$/i },
    { id: 'win-zip', label: tmT('kind_windows_zip', 'Windows zip'), test: /windows-x64-portable\.zip$/i },
    { id: 'linux-deb', label: 'deb', test: /_amd64\.deb$/i },
    { id: 'linux-rpm', label: 'rpm', test: /\.x86_64\.rpm$/i },
    { id: 'linux-appimage', label: 'AppImage', test: /\.appimage$/i },
    { id: 'linux-flatpak', label: 'Flatpak', test: /\.flatpak$/i },
    { id: 'linux-tar', label: tmT('kind_linux_tarball', 'Linux tarball'), test: /linux-x64\.tar\.(gz|xz)$/i },
    { id: 'sums', label: tmT('kind_checksums', 'Checksums'), test: /^sha256sums/i, quiet: true }
  ];

  var NAMES = {
    android: 'Android',
    'android-tv': 'Android TV',
    windows: 'Windows',
    linux: 'Linux'
  };

  var el = {
    version: document.getElementById('release-version'),
    date: document.getElementById('release-date'),
    status: document.getElementById('release-status'),
    notesWrap: document.getElementById('release-notes-wrap'),
    notes: document.getElementById('release-notes'),
    prevWrap: document.getElementById('previous-wrap'),
    prevList: document.getElementById('previous-list'),
    card: document.getElementById('release-card'),
    platforms: document.getElementById('platforms'),
    osBtn: document.getElementById('os-download'),
    osLabel: document.getElementById('os-download-label'),
    osNote: document.getElementById('os-note'),
    heroBtn: document.getElementById('hero-download'),
    heroLabel: document.getElementById('hero-download-label')
  };

  /* The site is several pages, and only two of them ask GitHub anything: the
     home page has the hero button and the download page has the platforms. A
     page with neither wants no request at all. */
  if (!el.version && !el.heroBtn) { return; }

  /* On the page that has one but not the other, every node that is missing
     becomes a detached stand-in, so the rendering below writes to it as usual
     and nothing it writes reaches the document. */
  for (var key in el) {
    if (Object.prototype.hasOwnProperty.call(el, key) && !el[key]) {
      el[key] = document.createElement('span');
    }
  }

  /* ---------- which system is this ---------- */

  /* A guess from the user agent, used only to choose an order and a button: every
     platform stays on the page whatever it says. ?os=windows (or linux, android,
     android-tv, mac, ios) overrides it, for checking each layout by hand.
     Android TV browsers rarely say so, so a television mostly reads as a phone,
     which lands on the same universal APK anyway. */
  function detectOs() {
    try {
      var forced = /[?&]os=([a-z-]+)/.exec(window.location.search);
      if (forced) { return forced[1]; }
    } catch (e) {}
    var nav = window.navigator || {};
    var ua = String(nav.userAgent || '');
    var platform = String((nav.userAgentData && nav.userAgentData.platform) || nav.platform || '');
    if (/Android/i.test(ua)) {
      return /\b(TV|AFT[A-Z0-9]*|BRAVIA|SmartTV|GoogleTV|Chromecast|AndroidTV)\b/i.test(ua) ? 'android-tv' : 'android';
    }
    if (/iPhone|iPad|iPod/i.test(ua)) { return 'ios'; }
    if (/Win/i.test(platform) || /Windows/i.test(ua)) { return 'windows'; }
    if (/Mac/i.test(platform) || /Macintosh/i.test(ua)) {
      // An iPad asks for the desktop site and says Macintosh; it has a touch screen.
      return nav.maxTouchPoints > 1 ? 'ios' : 'mac';
    }
    if (/CrOS/i.test(ua)) { return null; }
    if (/Linux|X11/i.test(platform + ' ' + ua)) { return 'linux'; }
    return null;
  }

  var os = detectOs();

  function primaryRow(osId) {
    var card = document.getElementById(osId);
    if (!card || !card.querySelector) { return null; }
    return card.querySelector('[data-primary]');
  }

  /* The visitor's platform goes first and gets the filled button. Done before
     the request, so the page is in its final order while GitHub is answering. */
  function arrange() {
    if (!document.getElementById('platforms')) { return; }
    if (os === 'mac' || os === 'ios') {
      el.osNote.textContent = os === 'mac'
        ? tmT('os_note_mac', 'There is no macOS version. Everything below is for Android, Windows and Linux.')
        : tmT('os_note_ios', 'There is no iPhone or iPad version. Everything below is for Android, Windows and Linux.');
      el.osNote.hidden = false;
      return;
    }
    var card = os && document.getElementById(os);
    if (!card || !el.platforms.insertBefore) { return; }
    el.platforms.insertBefore(card, el.platforms.firstChild);
    card.className += ' is-yours';
    var badge = card.querySelector('.platform-yours');
    if (badge) { badge.hidden = false; }
    var row = primaryRow(os);
    if (row) { row.className += ' pick'; }
    el.osBtn.href = '#' + os;
    el.osLabel.textContent = tmT('download_for', 'Download for {os}', { os: NAMES[os] });
    el.osBtn.hidden = false;
  }

  /* ---------- small helpers ---------- */

  function make(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text != null) { node.textContent = text; }
    return node;
  }

  function link(href, className, text) {
    var a = make('a', className, text);
    a.href = href;
    a.rel = 'noopener';
    return a;
  }

  function formatSize(bytes) {
    if (typeof bytes !== 'number' || !isFinite(bytes) || bytes <= 0) { return ''; }
    var mb = bytes / (1024 * 1024);
    if (mb >= 1024) { return (mb / 1024).toFixed(2) + ' GB'; }
    return mb.toFixed(1) + ' MB';
  }

  /* GitHub counts every fetch of a release asset. For an app outside any store
     that is the only install figure that exists, so it goes on the page rather
     than in a dashboard: it is public data either way. */
  function formatCount(n) {
    if (typeof n !== 'number' || !isFinite(n) || n < 0) { return ''; }
    var number = n.toLocaleString(tmLocale);
    return n === 1
      ? tmT('downloads_one', '{count} download', { count: number })
      : tmT('downloads_other', '{count} downloads', { count: number });
  }

  function formatDate(iso) {
    if (!iso) { return ''; }
    var d = new Date(iso);
    if (isNaN(d.getTime())) { return ''; }
    try {
      return new Intl.DateTimeFormat(tmLocale, {
        day: 'numeric', month: 'long', year: 'numeric'
      }).format(d);
    } catch (e) {
      return d.toISOString().slice(0, 10);
    }
  }

  function clear(node) {
    while (node.firstChild) { node.removeChild(node.firstChild); }
  }

  function kindOf(asset) {
    var name = String((asset && asset.name) || '');
    for (var i = 0; i < KINDS.length; i++) {
      if (KINDS[i].test.test(name)) { return KINDS[i]; }
    }
    return null;
  }

  function findAsset(release, kindId) {
    var assets = (release && release.assets) || [];
    for (var i = 0; i < assets.length; i++) {
      var kind = kindOf(assets[i]);
      if (kind && kind.id === kindId) { return assets[i]; }
    }
    return null;
  }

  /* The files a person installs: APKs and desktop packages, not the checksums,
     the licence files or the source archives. */
  function appAssets(release) {
    return ((release && release.assets) || []).filter(function (a) {
      var kind = kindOf(a);
      return kind && !kind.quiet;
    });
  }

  function releaseDownloads(release) {
    return appAssets(release).reduce(function (total, asset) {
      return total + (asset.download_count || 0);
    }, 0);
  }

  /* ---------- rendering ---------- */

  function settled() {
    if (el.card && el.card.removeAttribute) { el.card.removeAttribute('aria-busy'); }
  }

  function showStatus(kind, lines) {
    settled();
    el.status.hidden = false;
    el.status.className = 'status' + (kind === 'error' ? ' error' : '');
    clear(el.status);
    lines.forEach(function (line) {
      var p = make('p', line.muted ? 'muted small' : null);
      if (line.text) { p.appendChild(document.createTextNode(line.text)); }
      if (line.link) {
        p.appendChild(link(line.link.href, null, line.link.text));
        if (line.after) { p.appendChild(document.createTextNode(line.after)); }
      }
      el.status.appendChild(p);
    });
  }

  /* Each row in the markup names the kind of file it offers. It gets the file's
     own link, size, count and name; a kind the release lacks keeps its link to
     the release page and says so. */
  function fillRows(release, tag) {
    var rows = document.querySelectorAll('[data-asset]');
    Array.prototype.forEach.call(rows, function (row) {
      var asset = findAsset(release, row.getAttribute('data-asset'));
      var dl = row.querySelector('[data-dl]');
      var size = row.querySelector('[data-size]');
      var files = row.querySelectorAll('[data-file]');
      if (!asset) {
        row.className += ' missing';
        if (size) { size.textContent = tmT('not_in_release', 'Not in {version}', { version: tag }); }
        if (dl) {
          dl.href = release.html_url || LATEST_PAGE;
          var label = dl.querySelector('span');
          if (label) { label.textContent = tmT('on_github', 'On GitHub'); }
        }
        return;
      }
      if (dl) {
        dl.href = asset.browser_download_url;
        dl.setAttribute('aria-label', tmT('download_file', 'Download {file}', { file: asset.name }));
      }
      if (size) {
        var bits = [formatSize(asset.size)];
        if (asset.download_count) { bits.push(formatCount(asset.download_count)); }
        size.textContent = bits.filter(Boolean).join(', ');
      }
      Array.prototype.forEach.call(files, function (f) { f.textContent = asset.name; });
    });

    var sums = document.querySelectorAll('[data-asset-link]');
    Array.prototype.forEach.call(sums, function (a) {
      var asset = findAsset(release, a.getAttribute('data-asset-link'));
      if (!asset) { return; }
      a.href = asset.browser_download_url;
      var f = a.querySelector('[data-file]');
      if (f) { f.textContent = asset.name; }
    });
  }

  /* The filled button on the download page and the hero on the home page both
     point at the visitor's first-choice file, when this release has it. */
  function fillButtons(release, tag) {
    var row = os && NAMES[os] ? primaryRow(os) : null;
    var kindId = row ? row.getAttribute('data-asset') : null;

    // The home page has no rows to read, so it carries the same choice here.
    if (!kindId && os && NAMES[os]) {
      kindId = {
        android: 'apk-universal',
        'android-tv': 'apk-universal',
        windows: 'win-msi',
        linux: 'linux-appimage'
      }[os];
    }

    var asset = kindId ? findAsset(release, kindId) : null;
    if (asset) {
      el.osBtn.href = asset.browser_download_url;
      el.heroBtn.href = asset.browser_download_url;
      var what = kindOf(asset).label;
      el.heroLabel.textContent = tmT('download_version_for', 'Download {version} for {os}', { version: tag, os: NAMES[os] });
      el.osLabel.textContent = el.heroLabel.textContent;
      el.osBtn.title = asset.name;
      el.heroBtn.title = asset.name + ' (' + what + ')';
      return;
    }

    // No guess, or a guess this release has no file for: the download page.
    el.heroBtn.href = '/download/';
    el.heroLabel.textContent = os === 'mac' || os === 'ios'
      ? tmT('download_version_other_os', 'Download {version} for Android, Windows or Linux', { version: tag })
      : tmT('download_version', 'Download {version}', { version: tag });
  }

  function renderLatest(release) {
    settled();
    var tag = release.tag_name || release.name || tmT('latest', 'Latest');
    el.version.textContent = tag;
    var when = formatDate(release.published_at || release.created_at);
    var total = releaseDownloads(release);
    el.date.textContent = total ? when + ', ' + formatCount(total) : when;

    if (appAssets(release).length === 0) {
      showStatus('error', [
        { text: tmT('no_files_yet', 'This release has no files attached yet. The build may still be running.') },
        { link: { href: release.html_url || RELEASES_PAGE, text: tmT('open_on_github', 'Open {version} on GitHub', { version: tag }) } }
      ]);
    } else {
      el.status.hidden = true;
    }

    fillRows(release, tag);
    fillButtons(release, tag);

    /* Release notes are untrusted text from the API. textContent only. */
    var body = (release.body || '').trim();
    if (body) {
      el.notes.textContent = body;
      el.notesWrap.hidden = false;
    }
  }

  function renderPrevious(releases) {
    if (releases.length === 0) { return; }
    clear(el.prevList);

    releases.forEach(function (release) {
      var li = make('li', 'prev-item');

      var head = make('div', 'prev-head');
      head.appendChild(link(release.html_url || RELEASES_PAGE, 'prev-tag', release.tag_name || release.name || tmT('release', 'Release')));
      var when = formatDate(release.published_at || release.created_at);
      var total = releaseDownloads(release);
      head.appendChild(make('span', 'prev-date', total ? when + ', ' + formatCount(total) : when));
      li.appendChild(head);

      var assets = appAssets(release);
      if (assets.length) {
        var list = make('ul', 'prev-assets');
        assets.forEach(function (asset) {
          var item = make('li');
          var a = link(asset.browser_download_url, null, kindOf(asset).label);
          a.setAttribute('aria-label', tmT('download_file', 'Download {file}', { file: asset.name }));
          item.appendChild(a);
          list.appendChild(item);
        });
        li.appendChild(list);
      }

      el.prevList.appendChild(li);
    });

    el.prevWrap.hidden = false;
  }

  function fail(reason) {
    settled();
    el.version.textContent = tmT('unavailable', 'Unavailable');
    el.date.textContent = '';

    // One sentence with the link inside it, so a translation can put the link where it reads.
    var around = tmT('fail_downloads', 'The downloads are still there: every button below opens {link}, where the files are listed by name.').split('{link}');
    showStatus('error', [
      { text: reason },
      {
        text: around[0],
        link: { href: LATEST_PAGE, text: tmT('fail_link', 'the latest release on GitHub') },
        after: around[1] || ''
      }
    ]);

    el.heroBtn.href = '/download/';
    el.heroLabel.textContent = tmT('go_to_downloads', 'Go to the downloads');
  }

  function noReleases() {
    settled();
    el.version.textContent = tmT('not_released', 'Not released yet');
    el.date.textContent = '';
    showStatus('error', [
      { text: tmT('no_release', 'No release has been published yet. You can still build the app from source.') },
      { link: { href: 'https://github.com/' + OWNER + '/' + REPO, text: tmT('build_instructions', 'Read the build instructions on GitHub') } }
    ]);
    el.heroBtn.href = 'https://github.com/' + OWNER + '/' + REPO;
    el.heroLabel.textContent = tmT('view_project', 'View the project on GitHub');
  }

  /* ---------- fetch ---------- */

  function load() {
    if (typeof fetch !== 'function') {
      fail(tmT('fail_browser', 'This browser cannot load the release list.'));
      return;
    }

    var controller = null;
    var signal;
    if (typeof AbortController === 'function') {
      controller = new AbortController();
      signal = controller.signal;
    }
    var timer = setTimeout(function () {
      if (controller) { controller.abort(); }
    }, TIMEOUT_MS);

    fetch(API, {
      headers: { 'Accept': 'application/vnd.github+json' },
      signal: signal
    }).then(function (res) {
      clearTimeout(timer);
      if (res.status === 403 || res.status === 429) {
        throw new Error('rate-limited');
      }
      if (!res.ok) {
        throw new Error('http-' + res.status);
      }
      return res.json();
    }).then(function (data) {
      if (!Array.isArray(data)) { throw new Error('shape'); }

      var published = data.filter(function (r) { return r && !r.draft; });
      if (published.length === 0) {
        noReleases();
        return;
      }

      var stable = published.filter(function (r) { return !r.prerelease; });
      var latest = stable.length ? stable[0] : published[0];
      var rest = published.filter(function (r) { return r !== latest; }).slice(0, 6);

      renderLatest(latest);
      renderPrevious(rest);
    }).catch(function (err) {
      clearTimeout(timer);
      var message = String((err && err.message) || '');
      if (message === 'rate-limited') {
        fail(tmT('fail_rate', 'GitHub is rate-limiting this network. Its public API allows 60 requests an hour per address, and this one has used them up.'));
      } else if (err && err.name === 'AbortError') {
        fail(tmT('fail_timeout', 'GitHub did not answer in time.'));
      } else {
        fail(tmT('fail_load', 'The release list could not be loaded from GitHub.'));
      }
    });
  }

  arrange();
  load();
})();

/* Theme control.

   The system theme is the default and stays the default: nothing is written to
   storage until the reader presses the button, and the stylesheet follows
   prefers-color-scheme for as long as data-theme is absent. Pressing the
   button sets the attribute, which flips every custom property at once, and
   remembers the choice. The head carries a tiny copy of the read so the
   stored theme is applied before the first paint. */
(function () {
  'use strict';

  var KEY = 'tm-theme';
  var root = document.documentElement;
  var button = document.getElementById('theme-toggle');
  if (!button) { return; }

  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try {
      var t = localStorage.getItem(KEY);
      return (t === 'light' || t === 'dark') ? t : null;
    } catch (e) { return null; }
  }

  function showing() {
    var chosen = root.getAttribute('data-theme');
    if (chosen === 'light' || chosen === 'dark') { return chosen; }
    return (media && media.matches) ? 'dark' : 'light';
  }

  /* The address bar and task switcher follow the page, which they cannot do
     from the two media-scoped meta tags once a choice overrides the system. */
  function paintChrome(theme) {
    var colour = theme === 'dark' ? '#0B0C0E' : '#FFFFFF';
    var tags = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < tags.length; i++) {
      tags[i].setAttribute('content', colour);
      tags[i].removeAttribute('media');
    }
  }

  function label() {
    var next = showing() === 'dark' ? 'light' : 'dark';
    var text = next === 'dark'
      ? tmT('theme_to_dark', 'Switch to dark theme')
      : tmT('theme_to_light', 'Switch to light theme');
    button.setAttribute('aria-label', text);
    button.setAttribute('title', text);
  }

  /* The phone screenshots exist in both of the app's own themes, and the page
     shows whichever one matches the page. Markup does the work for a reader who
     has chosen nothing: the <source> carries the light file behind a
     prefers-color-scheme media query and the <img> carries the dark one, so a
     browser fetches exactly one of the two and no script has to run.

     What that markup cannot do is follow the toggle, because the button changes
     an attribute and the media query only knows about the system. Pressing it
     therefore rewrites the media attribute to "all" or "none", which is a
     picture the browser re-evaluates on the spot. The television shots are in
     here too now: the app's ten-foot layout has a light theme of its own, and
     a black panel on a white page was a photograph of something the reader had
     just asked not to see.

     The hero's phone is a video rather than a picture, and a <video> has no
     media-query switch to lean on: a <source media> inside one is only read
     once, when the element is first laid out, and never re-evaluated. So that
     one is swapped by hand, and it is the only shot on the page that needs a
     script to be right on arrival. */
  function paintShots(theme) {
    var sources = document.querySelectorAll('source[data-theme-src]');
    for (var i = 0; i < sources.length; i++) {
      sources[i].media = theme === 'light' ? 'all' : 'none';
    }
    var videos = document.querySelectorAll('video[data-theme-src]');
    for (var j = 0; j < videos.length; j++) {
      var video = videos[j];
      var wanted = theme === 'light'
        ? video.getAttribute('data-theme-src')
        : video.getAttribute('data-dark-src');
      // Compared before assigning: setting src to what it already is restarts
      // the clip, and the hero would jump every time the toggle is pressed.
      if (wanted && video.getAttribute('src') !== wanted) {
        video.setAttribute('src', wanted);
        video.load();
        var playing = video.play();
        if (playing && typeof playing.catch === 'function') { playing.catch(function () {}); }
      }
    }
  }

  function apply(next) {
    root.setAttribute('data-theme', next);
    try { localStorage.setItem(KEY, next); } catch (e) {}
    paintChrome(next);
    paintShots(next);
    label();
  }

  /* The incoming theme arrives as a circle opening out of the button itself.

     The View Transitions API takes a picture of the page before and after the
     attribute flips, and the stylesheet then reveals the new picture through a
     growing clip-path. The radius is the distance to the furthest corner, so
     the circle has covered the window by the time it stops. Anything that
     cannot do this, which is a browser without the API or a reader who has
     asked for less motion, simply gets the swap. */
  function reveal(next) {
    var reduced = window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced || typeof document.startViewTransition !== 'function') {
      apply(next);
      return;
    }

    var box = button.getBoundingClientRect();
    var x = box.left + box.width / 2;
    var y = box.top + box.height / 2;
    var radius = Math.hypot(
      Math.max(x, window.innerWidth - x),
      Math.max(y, window.innerHeight - y)
    );
    root.style.setProperty('--theme-x', x + 'px');
    root.style.setProperty('--theme-y', y + 'px');
    root.style.setProperty('--theme-r', radius + 'px');

    document.startViewTransition(function () { apply(next); });
  }

  button.addEventListener('click', function () {
    reveal(showing() === 'dark' ? 'light' : 'dark');
  });

  /* With no explicit choice, follow the system if it changes under us. */
  if (media && typeof media.addEventListener === 'function') {
    media.addEventListener('change', function () {
      if (!stored()) { label(); }
    });
  }

  // The pictures follow the markup on their own, but the hero's video cannot,
  // so the shots are painted on arrival whether or not a theme was stored.
  if (stored()) { paintChrome(stored()); }
  paintShots(showing());
  label();

  /* With no stored choice the hero has to follow the system as it changes,
     which for everything else on the page is the media query's own job. */
  if (media && typeof media.addEventListener === 'function') {
    media.addEventListener('change', function () {
      if (!stored()) { paintShots(showing()); }
    });
  }
})();

/* Scroll reveal. Opt-in only: the class that hides the elements is added by
   this script, so with JavaScript off, or with reduced motion asked for,
   everything stays visible and nothing is lost. */
(function () {
  'use strict';

  if (typeof IntersectionObserver !== 'function' || !document.documentElement.classList) {
    return;
  }
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return;
  }

  var targets = document.querySelectorAll('[data-reveal]');
  if (!targets.length) { return; }

  document.documentElement.className += ' reveal-ready';

  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) {
        entry.target.className += ' is-in';
        observer.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });

  Array.prototype.forEach.call(targets, function (node) {
    observer.observe(node);
  });
})();

/* Top app bar and hero parallax. Both are decoration: the header still works
   without the class, and the devices sit exactly where the layout puts them
   when --shift is never written. Reduced motion skips the parallax and keeps
   the header behaviour, which is a state change rather than an animation. */
(function () {
  'use strict';

  var header = document.querySelector('.site-header');
  var showcase = document.querySelector('.showcase');
  var toTop = document.getElementById('to-top');
  var reduced = window.matchMedia
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var ticking = false;

  /* The height the parallax measures against, held still on purpose. A phone
     browser hides its address bar as you scroll down and brings it back as you
     scroll up, and innerHeight changes by fifty-odd pixels each time it does.
     Read live, that height put a step into the middle of a smooth scroll: the
     devices jumped, settled, and jumped back on the way up. It is refreshed
     when the width changes, which is a rotation or a real resize, and left
     alone otherwise. */
  var viewportW = window.innerWidth || 0;
  var viewportH = window.innerHeight || 800;

  function measure() {
    var w = window.innerWidth || 0;
    if (w !== viewportW) {
      viewportW = w;
      viewportH = window.innerHeight || 800;
    }
  }

  function frame() {
    ticking = false;
    var y = window.pageYOffset || document.documentElement.scrollTop || 0;

    if (header) {
      if (y > 8) {
        if (header.className.indexOf('is-stuck') === -1) { header.className += ' is-stuck'; }
      } else {
        header.className = header.className.replace(/\s*is-stuck/g, '');
      }
    }

    /* Past the first screen the button is there; within the last stretch of the
       page it goes away again, because that is exactly where the footer's links
       and legal text are and a floating circle on top of them helps nobody. */
    if (toTop) {
      var doc = document.documentElement;
      var remaining = doc.scrollHeight - (y + (window.innerHeight || 800));
      var wanted = y > 600 && remaining > 140;
      if (wanted) {
        if (toTop.className.indexOf('is-on') === -1) { toTop.className += ' is-on'; }
      } else {
        toTop.className = toTop.className.replace(/\s*is-on/g, '');
      }
    }

    /* Not while the reader is pinched in. A pinch moves the visual viewport
       without moving the layout viewport, but the browser still reports scroll,
       so every finger movement rewrote --shift and the two devices slid about
       under a magnifying glass. Firefox on a phone was the worst of it: the
       handset is the one with the larger travel, and it wandered across the
       television while being looked at closely. Zoomed in, the parallax holds
       whatever value it had when the pinch started. */
    var zoomed = window.visualViewport && window.visualViewport.scale > 1.01;

    if (showcase && !reduced && !zoomed) {
      measure();
      var box = showcase.getBoundingClientRect();
      var height = viewportH;
      /* Minus one when the showcase is below the fold, plus one when it is
         above it, so the two devices separate gently as the page moves. */
      var shift = ((height - box.top) / (height + box.height)) * 2 - 1;
      shift = Math.max(-1, Math.min(1, shift));
      showcase.style.setProperty('--shift', shift.toFixed(3));
    }
  }

  function onScroll() {
    if (ticking) { return; }
    ticking = true;
    if (typeof requestAnimationFrame === 'function') {
      requestAnimationFrame(frame);
    } else {
      frame();
    }
  }

  /* Smooth where the browser can, an instant jump where it cannot, and an
     instant jump for a reader who has asked for less motion. */
  if (toTop) {
    toTop.addEventListener('click', function () {
      try {
        window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' });
      } catch (e) {
        window.scrollTo(0, 0);
      }
    });
  }

  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll, { passive: true });
  /* And one more when the pinch ends, so the devices take up their place again
     rather than waiting for the next scroll to notice. */
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', onScroll, { passive: true });
  }
  frame();
})();

/* Repository counts, for the star control in the header and the support band.
   One more call to the same unauthenticated API as the release card, so a page
   that shows neither never makes it. Every figure is hidden until a number
   arrives: a rate-limited reader gets the button and the licence, which is all
   the page actually needs to work. */
(function () {
  'use strict';

  var API = 'https://api.github.com/repos/dracu-lah/TMPlayer';
  var nodes = document.querySelectorAll('[data-count]');
  if (!nodes.length || typeof fetch !== 'function') { return; }

  /* Exact up to a thousand, because at this size the exact figure is the more
     persuasive one, and 1.2k after that. */
  function compact(n) {
    if (n < 1000) { return String(n); }
    var k = n / 1000;
    return (k >= 10 ? Math.round(k) : Math.round(k * 10) / 10) + 'k';
  }

  fetch(API, { headers: { 'Accept': 'application/vnd.github+json' } }).then(function (res) {
    if (!res.ok) { throw new Error('http-' + res.status); }
    return res.json();
  }).then(function (data) {
    var counts = {
      stars: data.stargazers_count,
      forks: data.forks_count,
      watchers: data.subscribers_count
    };
    Array.prototype.forEach.call(nodes, function (node) {
      var value = counts[node.getAttribute('data-count')];
      if (typeof value !== 'number') { return; }
      node.textContent = compact(value);
      node.hidden = false;
      /* The label around the figure ("stars", "forks") is hidden with it, so a
         missing number never leaves a bare word behind. */
      var wrap = node.parentNode;
      if (wrap && wrap.getAttribute && wrap.getAttribute('data-count-wrap') !== null) {
        wrap.hidden = false;
      }
    });
  }).catch(function () {
    /* Nothing to do. The controls are links to GitHub with or without a count. */
  });
})();

/* The menu, on a phone.

   Below 900px the pages do not fit beside the mark and the two controls, so they
   move into a card the button opens. The button is drawn only when this script
   has run, which the js class on the root says: without it the stylesheet leaves
   the links on a second row, where they still work. */
(function () {
  'use strict';

  var button = document.getElementById('menu-btn');
  var panel = document.getElementById('site-nav');
  if (!button || !panel) { return; }

  function open() { return button.getAttribute('aria-expanded') === 'true'; }

  function set(next) {
    button.setAttribute('aria-expanded', next ? 'true' : 'false');
    button.setAttribute('aria-label', next ? tmT('menu_close', 'Close menu') : tmT('menu', 'Menu'));
    if (next) {
      if (panel.className.indexOf('is-open') === -1) { panel.className += ' is-open'; }
    } else {
      panel.className = panel.className.replace(/\s*is-open/g, '');
    }
  }

  button.addEventListener('click', function (event) {
    event.preventDefault();
    set(!open());
  });

  /* A tap anywhere else closes it, which is what a card hanging off a bar should
     do. The header itself is excluded, or the press that opened it would close
     it again on the way back up. */
  document.addEventListener('click', function (event) {
    if (!open()) { return; }
    var node = event.target;
    while (node) {
      if (node === button || node === panel) { return; }
      node = node.parentNode;
    }
    set(false);
  });

  document.addEventListener('keydown', function (event) {
    if (open() && (event.key === 'Escape' || event.key === 'Esc')) {
      set(false);
      button.focus();
    }
  });

  /* Turning a phone on its side can put the layout back over 900px, where the
     links belong in the bar again and a card left open would be a card floating
     under a row that already lists them. */
  window.addEventListener('resize', function () {
    if (open() && window.innerWidth > 900) { set(false); }
  });

  set(false);
})();

/* Languages: the header picker and the suggestion bar.

   scripts/build-site-i18n.py writes a <script id="tm-langs"> block into every
   page once a second language is published: the language this page is in, and
   for every published language its URL for this page and the suggestion in
   that language's own words. With one language the block is absent and none of
   this runs.

   Nothing ever redirects. A reader whose browser lists a published language
   ahead of the one on screen gets a bar offering that language, in it. Following
   the link, choosing from the picker or pressing "Stay" is remembered, and the
   bar does not come back. */
(function () {
  'use strict';

  var KEY = 'tm-lang';
  var info = null;
  try {
    var block = document.getElementById('tm-langs');
    if (block) { info = JSON.parse(block.textContent); }
  } catch (e) {}
  if (!info || !info.langs || info.langs.length < 2) { return; }

  function remember(code) {
    try { localStorage.setItem(KEY, code); } catch (e) {}
  }
  function remembered() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }

  /* The picker is a <details>: it opens by itself, and this only closes it on
     a click elsewhere or on Escape, the way a menu is expected to behave. */
  var picker = document.querySelector('.lang-picker');
  if (picker) {
    document.addEventListener('click', function (event) {
      if (picker.open && !picker.contains(event.target)) { picker.open = false; }
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && picker.open) {
        picker.open = false;
        var summary = picker.querySelector('summary');
        if (summary) { summary.focus(); }
      }
    });
  }

  Array.prototype.forEach.call(document.querySelectorAll('[data-lang-pick]'), function (a) {
    a.addEventListener('click', function () { remember(a.getAttribute('data-lang-pick')); });
  });

  if (remembered()) { return; }

  /* The first language in the browser's list that the site has, matched on the
     language alone, so es-MX finds Español and zh-CN finds 简体中文. */
  function base(tag) { return String(tag || '').toLowerCase().split('-')[0]; }
  var wanted = null;
  var prefs = (navigator.languages && navigator.languages.length)
    ? navigator.languages : [navigator.language || ''];
  for (var i = 0; i < prefs.length && !wanted; i++) {
    for (var j = 0; j < info.langs.length; j++) {
      if (base(info.langs[j].tag) === base(prefs[i])) { wanted = info.langs[j]; break; }
    }
  }
  if (!wanted || wanted.code === info.current) { return; }

  var bar = document.createElement('div');
  bar.className = 'lang-suggest';
  bar.setAttribute('lang', wanted.tag);
  bar.setAttribute('role', 'region');
  bar.setAttribute('aria-label', wanted.name);
  if (wanted.dir) { bar.setAttribute('dir', wanted.dir); }

  var text = document.createElement('p');
  text.textContent = wanted.suggest + ' ';
  var go = document.createElement('a');
  go.href = wanted.href;
  go.hreflang = wanted.code;
  go.textContent = wanted.go;
  go.addEventListener('click', function () { remember(wanted.code); });
  text.appendChild(go);

  var stay = document.createElement('button');
  stay.type = 'button';
  stay.textContent = wanted.dismiss;
  stay.addEventListener('click', function () {
    remember(info.current);
    bar.parentNode.removeChild(bar);
  });

  bar.appendChild(text);
  bar.appendChild(stay);
  var skip = document.querySelector('.skip');
  document.body.insertBefore(bar, skip ? skip.nextSibling : document.body.firstChild);
})();

/* Keep Android Open: the close and share buttons of the notice that
   scripts/build-site-i18n.py writes into every page. Closing it is remembered
   under one key for every page and language; the head script reads that key
   before the first paint. On a phone the card is fixed to the bottom, so the
   page is padded by its height for as long as it shows. */
(function () {
  'use strict';

  var notice = document.getElementById('kao');
  if (!notice) { return; }
  var root = document.documentElement;
  var KEY = 'tm-kao-closed';
  var sheet = notice.querySelector('.kao-sheet');

  function measure() {
    if (sheet && sheet.offsetHeight) {
      root.style.setProperty('--kao-h', sheet.offsetHeight + 'px');
    }
  }
  measure();
  if (sheet && window.ResizeObserver) {
    new ResizeObserver(measure).observe(sheet);
  } else {
    window.addEventListener('resize', measure);
  }

  Array.prototype.forEach.call(notice.querySelectorAll('[data-kao-close]'), function (button) {
    button.addEventListener('click', function () {
      try { localStorage.setItem(KEY, '1'); } catch (e) {}
      root.className += ' kao-off';
      root.style.removeProperty('--kao-h');
      var main = document.getElementById('main');
      if (main && document.activeElement === button) {
        main.setAttribute('tabindex', '-1');
        main.focus({ preventScroll: true });
      }
    });
  });

  var share = notice.querySelector('[data-kao-share]');
  if (!share) { return; }
  var status = notice.querySelector('.kao-copied');
  var url = share.getAttribute('data-url');
  var text = share.getAttribute('data-text');
  var timer = null;

  function copied() {
    if (!status) { return; }
    status.textContent = share.getAttribute('data-copied');
    clearTimeout(timer);
    timer = setTimeout(function () { status.textContent = ''; }, 2500);
  }
  function fallback() {
    var field = document.createElement('textarea');
    field.value = url;
    field.setAttribute('readonly', '');
    field.style.position = 'fixed';
    field.style.opacity = '0';
    document.body.appendChild(field);
    field.select();
    try { if (document.execCommand('copy')) { copied(); } } catch (e) {}
    document.body.removeChild(field);
  }
  function copy() {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(url).then(copied, fallback);
    } else {
      fallback();
    }
  }

  share.addEventListener('click', function () {
    if (navigator.share) {
      navigator.share({ title: 'Keep Android Open', text: text, url: url }).catch(function (e) {
        if (!e || e.name !== 'AbortError') { copy(); }
      });
    } else {
      copy();
    }
  });
})();
