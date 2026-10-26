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
   English stays here as the source and matches site/i18n/en.json's js.<key>
   (scripts/site-i18n-check.py holds the two together), and a translated page carries its strings
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
     the AppImage, the tarball and, from 1.26.0, the macOS DMG; the other kinds stay only so the previous
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
    { id: 'mac-dmg', label: tmT('kind_mac_dmg', 'macOS DMG'), test: /macos-arm64\.dmg$/i },
    { id: 'sums', label: tmT('kind_checksums', 'Checksums'), test: /^sha256sums/i, quiet: true }
  ];

  var NAMES = {
    android: 'Android',
    'android-tv': 'Android TV',
    windows: 'Windows',
    macos: 'macOS',
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
     android-tv, macos, ios) overrides it, for checking each layout by hand.
     A browser on a Mac says Intel whatever the chip is, so this cannot tell an
     Apple Silicon Mac from an Intel one: every macOS line says Apple Silicon.
     Android TV browsers rarely say so, so a television mostly reads as a phone,
     which lands on the same universal APK anyway. */
  function detectOs() {
    try {
      var forced = /[?&]os=([a-z-]+)/.exec(window.location.search);
      if (forced) { return forced[1] === 'mac' ? 'macos' : forced[1]; }
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
      return nav.maxTouchPoints > 1 ? 'ios' : 'macos';
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
     the request, so the page is in its final order while GitHub is answering.
     macOS goes first now too when the build drew its card (latest.json named a
     DMG); otherwise it waits for the answer, and only a release with a DMG
     shows the card (see showMac). */
  function arrange() {
    if (!document.getElementById('platforms')) { return; }
    /* The filled button is drawn from the first paint (js-only), so it goes again here where
       there is no file of this system's to point it at. A Mac keeps it until the release says. */
    if (os !== 'macos' && !(os && document.getElementById(os))) { el.osBtn.hidden = true; }
    if (os === 'ios') {
      el.osNote.textContent = tmT('os_note_ios_only', 'There is no iPhone or iPad app: open the web app (a beta) at web.tmplayer.org and add it to the home screen. The files below are for Android phones, Android TV and computers.');
      el.osNote.hidden = false;
      return;
    }
    /* A Mac waits for the release only when the build did not know of a DMG: when it did, the
       card is in the page already, and the note and the first place are given now rather than
       when GitHub answers, which pushed the whole list down by the note's height. */
    if (os === 'macos') {
      var mac = document.getElementById('macos');
      if (mac && !mac.hidden) { macNote(true); putFirst('macos'); }
      return;
    }
    putFirst(os);
  }

  function putFirst(id) {
    var card = id && document.getElementById(id);
    if (!card || !el.platforms.insertBefore) { return; }
    el.platforms.insertBefore(card, el.platforms.firstChild);
    card.className += ' is-yours';
    var badge = card.querySelector('.platform-yours');
    if (badge) { badge.hidden = false; }
    var row = primaryRow(id);
    if (row) { row.className += ' pick'; }
    el.osBtn.href = '#' + id;
    el.osLabel.textContent = tmT('download_for', 'Download for {os}', { os: NAMES[id] });
    el.osBtn.hidden = false;
  }

  /* The macOS card, its jump link and, for a Mac, the first place and the
     filled button, once the release is known to carry the DMG. A release
     without one leaves the card hidden, and a Mac visitor is told so. The
     note always names Apple Silicon, since the browser cannot tell. */
  function showMac(release) {
    var card = document.getElementById('macos');
    if (!card) { return; }
    var has = !!findAsset(release, 'mac-dmg');
    if (has) {
      card.hidden = false;
      Array.prototype.forEach.call(document.querySelectorAll('[data-mac-jump]'), function (a) { a.hidden = false; });
    }
    if (os !== 'macos' || !document.getElementById('platforms')) { return; }
    if (!has) { el.osBtn.hidden = true; }
    macNote(has);
    if (has && card.className.indexOf('is-yours') < 0) { putFirst('macos'); }
  }

  function macNote(has) {
    el.osNote.textContent = has
      ? tmT('os_note_mac_preview', 'The macOS version is a preview for Macs with Apple Silicon (M1 and later) and macOS 26 or newer. It does not run on Intel Macs.')
      : tmT('os_note_mac', 'There is no macOS version. Everything below is for Android, Windows and Linux.');
    el.osNote.hidden = false;
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
        day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC'
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
        macos: 'mac-dmg',
        linux: 'linux-appimage'
      }[os];
    }

    /* A Mac gets the DMG only when this release has one, and the label says Apple Silicon,
       because an Intel Mac reads the same to a browser and the DMG will not run there. The
       download page hands over the file, with the requirement right under the button; the home
       page goes to the macOS card on the download page in the reader's language, where the
       requirement and the first launch are explained. */
    if (os === 'macos') {
      var dmg = findAsset(release, 'mac-dmg');
      var downloads = document.querySelector('a[href$="/download/"]');
      var page = downloads ? downloads.getAttribute('href') : '/download/';
      if (dmg) {
        el.osBtn.href = dmg.browser_download_url;
        el.osBtn.title = dmg.name;
        el.osLabel.textContent = tmT('download_version_mac', 'Download {version} for Macs with Apple Silicon', { version: tag });
        // The hero has room for a short label only. It opens the macOS card rather than the file,
        // so the requirement is read before anything is downloaded.
        el.heroBtn.href = page + '#macos';
        el.heroBtn.title = tmT('os_note_mac_preview', 'The macOS version is a preview for Macs with Apple Silicon (M1 and later) and macOS 26 or newer. It does not run on Intel Macs.');
        el.heroLabel.textContent = tmT('download_version_for', 'Download {version} for {os}', { version: tag, os: NAMES[os] });
      } else {
        el.heroBtn.href = page;
        el.heroLabel.textContent = tmT('download_version_other_os', 'Download {version} for Android, Windows or Linux', { version: tag });
      }
      return;
    }

    /* Windows leads with the PowerShell command, which installs without the SmartScreen prompt the
       unsigned MSI raises, so the button opens the steps rather than handing over the installer.
       The download page scrolls to its Windows card; the home page goes to the install steps in
       the reader's own language, through the page's own localized install link. */
    if (os === 'windows') {
      var install = document.querySelector('a[href$="/install/"]');
      el.osBtn.href = '#windows';
      el.osBtn.removeAttribute('title');
      el.heroBtn.href = (install ? install.getAttribute('href') : '/install/') + '#windows';
      el.heroBtn.removeAttribute('title');
      el.heroLabel.textContent = tmT('install_version_for', 'Install {version} for {os}', { version: tag, os: NAMES[os] });
      el.osLabel.textContent = el.heroLabel.textContent;
      return;
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
    el.heroLabel.textContent = os === 'ios'
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
    showMac(release);
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

  /* The site's own update feed, latest.json, names the newest release and the
     exact URL of every file in it. It is served from this domain, so neither
     GitHub's hourly limit nor a slow API stands between a visitor and the file.
     Used when the API fails; it has no download counts and no older releases,
     which is all that is missing. */
  function fromFeed(feed) {
    if (!feed || !feed.version || !feed.assets) { return null; }
    var assets = [];
    for (var id in feed.assets) {
      if (!Object.prototype.hasOwnProperty.call(feed.assets, id)) { continue; }
      var a = feed.assets[id];
      if (!a || !a.url) { continue; }
      assets.push({
        name: decodeURIComponent(String(a.url).split('/').pop()),
        browser_download_url: a.url,
        size: a.size
      });
    }
    if (assets.length === 0) { return null; }
    return {
      tag_name: 'v' + feed.version,
      published_at: feed.published,
      html_url: feed.releaseUrl || LATEST_PAGE,
      body: feed.notes || '',
      assets: assets
    };
  }

  function feed(otherwise) {
    fetch('/latest.json', { cache: 'no-cache' }).then(function (res) {
      if (!res.ok) { throw new Error('feed-' + res.status); }
      return res.json();
    }).then(function (data) {
      var release = fromFeed(data);
      if (!release) { throw new Error('feed-shape'); }
      renderLatest(release);
    }).catch(function () {
      otherwise();
    });
  }

  function fail(reason) {
    if (typeof fetch === 'function') {
      feed(function () { showFailure(reason); });
      return;
    }
    showFailure(reason);
  }

  function showFailure(reason) {
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
      /* Test versions (GitHub pre-releases, vX.Y.Z-beta.N) stay off the page while any release is
         listed: they are for people who opted in from the app, not for a first download. */
      var rest = (stable.length ? stable : published).filter(function (r) { return r !== latest; }).slice(0, 6);

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
  /* The home page has only the hero button, which needs the version and the file, and the feed
     has both from this domain. GitHub's list (download counts, older releases) is for the
     download page; asking it here put a second origin and a second of waiting into every home
     page load. The API stays the fallback for a feed that cannot be read. */
  if (!document.getElementById('release-card') && typeof fetch === 'function') {
    feed(load);
  } else {
    load();
  }
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

  /* Anything already on screen when the page loads is shown as it is, never hidden first: fading
     in what the reader can already see only delays the largest paint, which is what search
     engines time. Only blocks further down wait for the scroll.

     Which blocks those are is the first answer of an observer with no margin, not a measurement:
     reading a box here, while the page is still being built, made the browser lay the whole page
     out early just to answer (Lighthouse's forced reflow, up to 150 ms on a slow phone). The
     observer answers after the browser's own layout, so a block off screen is hidden before
     anyone can scroll to it, and a block on screen is never touched. */
  var later = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (entry.isIntersecting) {
        entry.target.className += ' is-in';
        later.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -8% 0px', threshold: 0.05 });

  var first = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      first.unobserve(entry.target);
      if (!entry.isIntersecting) {
        entry.target.className += ' reveal-wait';
        later.observe(entry.target);
      }
    });
  });

  Array.prototype.forEach.call(targets, function (node) { first.observe(node); });
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
     alone otherwise. First read in the first frame, not while the page is
     still being built (see the end of this function). */
  var viewportW = -1;
  var viewportH = 800;

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
  /* The first pass waits until the browser has drawn the page once. Run here, while the page is
     still being built, its reads of the scroll position and the showcase's box made the browser
     lay everything out early (Lighthouse's forced reflow); after the first paint the layout is
     already done and reading it costs nothing. A page opened at the top needs none of it. */
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(function () { setTimeout(frame, 0); });
  } else {
    setTimeout(frame, 0);
  }
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
  if (!nodes.length) { return; }
  if (typeof fetch !== 'function') { settleSkeletons(false); return; }

  /* Exact up to a thousand, because at this size the exact figure is the more
     persuasive one, and 1.2k after that. */
  function compact(n) {
    if (n < 1000) { return String(n); }
    var k = n / 1000;
    return (k >= 10 ? Math.round(k) : Math.round(k * 10) / 10) + 'k';
  }

  /* Kept for the tab's session, so moving between pages neither refetches nor flashes the
     skeleton again, and a reader browsing the site spends one of the 60 hourly calls, not ten. */
  var KEY = 'tm-repo-counts';
  var cached = null;
  try {
    var saved = JSON.parse(sessionStorage.getItem(KEY) || 'null');
    if (saved && Date.now() - saved.at < 3600000) { cached = saved.counts; }
  } catch (e) { /* storage blocked: fetch every time */ }

  function settleSkeletons(gotStars) {
    Array.prototype.forEach.call(document.querySelectorAll('[data-count-skeleton]'), function (node) {
      node.className = node.className.replace(/\s*is-loading/g, '');
      if (!gotStars) { node.hidden = true; }
    });
  }

  /* The figures are decoration, so they wait until the page has loaded and the browser is idle,
     and ask at low priority: on a slow phone the call to a second origin was in the page's
     critical chain, ahead of things the reader came for. A count kept from earlier in the
     session is shown at once. */
  function whenIdle(run) {
    var go = function () {
      if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback(run, { timeout: 2000 });
      } else {
        setTimeout(run, 200);
      }
    };
    if (document.readyState === 'complete') { go(); } else { window.addEventListener('load', go); }
  }

  function ask() {
    return new Promise(whenIdle).then(function () {
      return fetch(API, { headers: { 'Accept': 'application/vnd.github+json' }, priority: 'low' });
    });
  }

  (cached ? Promise.resolve(cached) : ask().then(function (res) {
    if (!res.ok) { throw new Error('http-' + res.status); }
    return res.json();
  }).then(function (data) {
    var fresh = {
      stars: data.stargazers_count,
      forks: data.forks_count,
      watchers: data.subscribers_count
    };
    try { sessionStorage.setItem(KEY, JSON.stringify({ at: Date.now(), counts: fresh })); } catch (e) { /* fine */ }
    return fresh;
  })).then(function (counts) {
    Array.prototype.forEach.call(nodes, function (node) {
      var value = counts[node.getAttribute('data-count')];
      if (typeof value !== 'number') { return; }
      node.textContent = compact(value);
      node.hidden = false;
      /* A link whose name is set by aria-label names the figure it shows too, so what a screen
         reader says contains what a sighted reader sees ("TMPlayer on GitHub (29)"). */
      var named = node.closest ? node.closest('a[aria-label]') : null;
      if (named) {
        if (!named.hasAttribute('data-label')) { named.setAttribute('data-label', named.getAttribute('aria-label')); }
        named.setAttribute('aria-label', named.getAttribute('data-label') + ' (' + node.textContent + ')');
      }
      /* The label around the figure ("stars", "forks") is hidden with it, so a
         missing number never leaves a bare word behind. */
      var wrap = node.parentNode;
      if (wrap && wrap.getAttribute && wrap.getAttribute('data-count-wrap') !== null) {
        wrap.hidden = false;
      }
    });
    settleSkeletons(typeof counts.stars === 'number');
  }).catch(function () {
    /* The controls are links to GitHub with or without a count; only the skeleton goes. */
    settleSkeletons(false);
  });
})();

/* The menu, on a phone.

   At 1080px and under the pages do not fit beside the mark and the controls, so they
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

  /* Turning a tablet on its side can put the layout back over 1080px, where the
     links belong in the bar again and a card left open would be a card floating
     under a row that already lists them. */
  window.addEventListener('resize', function () {
    if (open() && window.innerWidth > 1080) { set(false); }
  });

  set(false);
})();

/* Languages: the header picker and the suggestion bar.

   The layout (src/layouts/Base.astro) writes a <script id="tm-langs"> block into every
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

/* Keep Android Open: the close button of the notice bar that
   the layout (src/components/KaoNotice.astro) writes into every page. Closing it is remembered
   under one key for every page and language; the head script reads that key
   before the first paint. */
(function () {
  'use strict';

  var notice = document.getElementById('kao');
  if (!notice) { return; }
  var root = document.documentElement;
  var KEY = 'tm-kao-closed';
  Array.prototype.forEach.call(notice.querySelectorAll('[data-kao-close]'), function (button) {
    button.addEventListener('click', function () {
      try { localStorage.setItem(KEY, '1'); } catch (e) {}
      root.className += ' kao-off';
      var main = document.getElementById('main');
      if (main && document.activeElement === button) {
        main.setAttribute('tabindex', '-1');
        main.focus({ preventScroll: true });
      }
    });
  });

})();


/* The download bar on a phone (src/components/GetBar.astro). It comes up only once the hero or the
   page head has scrolled out of view, so it is never there on arrival, and goes again when the
   reader scrolls back up to the page's own buttons. The stylesheet draws it only at phone width;
   the width is checked here too, so the page never gets the room at its foot without the bar.
   Closing it is remembered under one key for every page and language. A browser without
   IntersectionObserver or storage simply never shows it. */
(function () {
  'use strict';

  var bar = document.getElementById('get-bar');
  // On the home page the hero's words and its button, not the devices under them.
  var head = document.querySelector('main .hero-inner, main .page-head');
  if (!bar || !head || !window.IntersectionObserver || !window.matchMedia) { return; }
  var KEY = 'tm-get-closed';
  try {
    if (localStorage.getItem(KEY)) { return; }
  } catch (e) { return; }

  var root = document.documentElement;
  var phone = window.matchMedia('(max-width: 699px)');
  var past = false;

  function paint() {
    var on = past && phone.matches;
    bar.classList.toggle('is-on', on);
    root.classList.toggle('get-bar-on', on);
  }

  bar.hidden = false;
  var observer = new IntersectionObserver(function (entries) {
    var entry = entries[entries.length - 1];
    past = !entry.isIntersecting && entry.boundingClientRect.bottom <= 0;
    paint();
  });
  observer.observe(head);
  if (phone.addEventListener) { phone.addEventListener('change', paint); }

  bar.querySelector('[data-get-close]').addEventListener('click', function () {
    try { localStorage.setItem(KEY, '1'); } catch (e) {}
    observer.disconnect();
    past = false;
    paint();
    bar.hidden = true;
    // The button that had the focus is gone, so the focus goes to the start of the content
    // rather than back to the top of the document.
    var main = document.getElementById('main');
    if (main) {
      main.setAttribute('tabindex', '-1');
      main.focus({ preventScroll: true });
    }
  });
})();


/* What comes next, on the download page (src/components/download/NextSteps.astro). A click on a
   download that hands over a file, rather than the release page it links to until the release
   list has loaded, opens the panel under that row, or under the page head's button, with the steps
   for that platform. The download itself goes ahead as usual: the file is an attachment, so the
   page stays where it is and the panel is there when the reader looks back. */
(function () {
  'use strict';

  var panel = document.getElementById('next-steps');
  if (!panel || !panel.closest) { return; }
  var heading = document.getElementById('next-steps-h');
  var FILE = /\/releases\/download\//;

  /* A row's platform is its card. The page head's button is the reader's own system, the card with
     the "your device" badge; failing that, the kind of file says it. */
  function platformOf(link) {
    var card = link.closest('.platform');
    if (card) { return card.id; }
    var yours = document.querySelector('.platform .platform-yours:not([hidden])');
    if (yours) { return yours.closest('.platform').id; }
    var href = link.getAttribute('href') || '';
    if (/\.apk$/i.test(href)) { return 'android'; }
    if (/\.msi$/i.test(href)) { return 'windows'; }
    if (/\.dmg$/i.test(href)) { return 'macos'; }
    if (/\.appimage$/i.test(href)) { return 'linux'; }
    return null;
  }

  function open(link) {
    var id = platformOf(link);
    var block = id ? panel.querySelector('[data-next="' + id + '"]') : null;
    if (!block) { return; }
    Array.prototype.forEach.call(panel.querySelectorAll('[data-next]'), function (node) {
      node.hidden = node !== block;
    });
    var card = link.closest('.platform');
    var list = card ? card.querySelector('.abi-list') : null;
    var actions = link.closest('.page-actions');
    var anchor = list || actions;
    if (!anchor) { return; }
    anchor.parentNode.insertBefore(panel, anchor.nextSibling);
    // A heading one level under the place it lands: the platform card's h3, or the page's h1.
    heading.setAttribute('aria-level', list ? '4' : '2');
    panel.hidden = false;
  }

  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('a[data-dl], #os-download') : null;
    if (link && FILE.test(link.href)) { open(link); }
  });
})();


/* File names in commands and instructions. A release's files carry the version in their names, so
   the markup can only say TMPlayer-<version>-x86_64.AppImage, and a reader who copies that line
   gets a shell error, or a stray file made by the < and >. An element marked
   data-name="linux-x64-appimage" (a key of the assets in latest.json) gets the real name from the
   update feed on this site, which costs no GitHub request; data-sha="..." shows that file's
   SHA-256 to compare against. Without the feed the placeholder stays, as before. */
(function () {
  'use strict';

  var names = document.querySelectorAll('[data-name]');
  var sums = document.querySelectorAll('[data-sha]');
  if ((!names.length && !sums.length) || !window.fetch) { return; }

  fetch('/latest.json', { cache: 'no-cache' }).then(function (response) {
    if (!response.ok) { throw new Error(String(response.status)); }
    return response.json();
  }).then(function (feed) {
    var assets = (feed && feed.assets) || {};
    function fileOf(kind) {
      // The MSI is "windows-x64-msi-v2" from 1.26.1 (scripts/write-latest-json.py).
      var asset = assets[kind] || assets[kind + '-v2'];
      if (!asset || !asset.url) { return null; }
      var name = String(asset.url).split('/').pop();
      // Only a plain file name goes into the page, never a path or anything else.
      return /^[\w.+-]+$/.test(name) ? name : null;
    }
    Array.prototype.forEach.call(names, function (node) {
      var name = fileOf(node.getAttribute('data-name'));
      if (name) { node.textContent = name; }
    });
    Array.prototype.forEach.call(sums, function (node) {
      var kind = node.getAttribute('data-sha');
      var name = fileOf(kind);
      var entry = assets[kind] || assets[kind + '-v2'];
      var sha = entry && entry.sha256;
      if (!name || !/^[0-9a-f]{64}$/i.test(sha || '')) { return; }
      node.textContent = tmT('sha_expected', 'For {file}, the result should be:', { file: name }) + ' ';
      var code = document.createElement('code');
      code.className = 'sha';
      code.textContent = sha.toLowerCase();
      node.appendChild(code);
      node.hidden = false;
    });
  }).catch(function () {});
})();

/* Copy buttons on the command blocks (the PowerShell line, adb, chmod and the rest), and on any
   button marked data-copy="text" (the link on the support page). A command block copies when
   clicked anywhere, unless the reader has just selected part of it to copy by hand; the icon turns
   to a tick for a moment, and a polite live region says Copied to a screen reader, since a changed
   label alone is not read out. When the browser refuses, the text is left selected and the reader
   is told to copy it by hand, rather than the click silently doing nothing. Added here, so with
   scripting off the block stays plain text to select. */
(function () {
  'use strict';

  var blocks = document.querySelectorAll('pre.abi-cmd, pre.code');
  var links = document.querySelectorAll('button[data-copy]');
  if (!blocks.length && !links.length) { return; }
  var COPY = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path fill="currentColor" d="M9 2h9a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h1V5a3 3 0 0 1 3-3Zm0 2a1 1 0 0 0-1 1v11a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V5a1 1 0 0 0-1-1H9ZM6 8H5v12h10v-1H9a3 3 0 0 1-3-3V8Z"/></svg>';
  var TICK = '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path fill="currentColor" d="M20.7 5.3a1 1 0 0 1 0 1.4l-11 11a1 1 0 0 1-1.4 0l-5-5a1 1 0 1 1 1.4-1.4L9 15.6 19.3 5.3a1 1 0 0 1 1.4 0Z"/></svg>';
  var label = tmT('copy_command', 'Copy');
  var done = tmT('copied', 'Copied');
  var refused = tmT('copy_failed', 'Your browser did not allow copying. The text is selected: copy it from there.');

  var live = document.createElement('p');
  live.className = 'sr-only';
  live.setAttribute('aria-live', 'polite');
  document.body.appendChild(live);
  var liveTimer = null;
  function say(text) {
    // Emptied first, so the same words twice in a row are still read out.
    live.textContent = '';
    clearTimeout(liveTimer);
    liveTimer = setTimeout(function () { live.textContent = text; }, 60);
  }

  // The clipboard API first; where it is missing or refused (an old browser, a page not served
  // over HTTPS, a permission prompt dismissed), the older select and copy way.
  function write(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).catch(function () { return fallback(text); });
    }
    return fallback(text);
  }

  function fallback(text) {
    return new Promise(function (resolve, reject) {
      var area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) {}
      document.body.removeChild(area);
      if (ok) { resolve(); } else { reject(); }
    });
  }

  function selectAll(node) {
    try {
      var range = document.createRange();
      range.selectNodeContents(node);
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    } catch (e) {}
  }

  Array.prototype.forEach.call(blocks, function (pre) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy-btn';
    button.setAttribute('aria-label', label);
    button.title = label;
    button.innerHTML = COPY;
    pre.classList.add('copyable');
    pre.appendChild(button);
    var timer = null;

    function copy() {
      var code = pre.querySelector('code') || pre;
      var text = code.textContent.replace(/\s+$/, '');
      write(text).then(function () {
        button.innerHTML = TICK;
        button.classList.add('copied');
        button.setAttribute('aria-label', done);
        button.title = done;
        say(done);
        clearTimeout(timer);
        timer = setTimeout(function () {
          button.innerHTML = COPY;
          button.classList.remove('copied');
          button.setAttribute('aria-label', label);
          button.title = label;
        }, 1800);
      }, function () {
        selectAll(code);
        button.title = refused;
        say(refused);
      });
    }

    button.addEventListener('click', function (event) {
      event.stopPropagation();
      copy();
    });
    pre.addEventListener('click', function () {
      var selection = window.getSelection && window.getSelection();
      if (selection && !selection.isCollapsed && pre.contains(selection.anchorNode)) { return; }
      copy();
    });
  });

  // A text button: its words change to Copied for a moment, and back.
  Array.prototype.forEach.call(links, function (button) {
    var words = button.querySelector('[data-copy-label]') || button;
    var idle = words.textContent;
    var timer = null;
    button.addEventListener('click', function () {
      var text = button.getAttribute('data-copy');
      write(text).then(function () {
        words.textContent = done;
        say(done);
        clearTimeout(timer);
        timer = setTimeout(function () { words.textContent = idle; }, 1800);
      }, function () {
        window.prompt(refused, text);
      });
    });
  });
})();


/* The Buy Me a Coffee card (src/components/CoffeeCard.astro), a link straight to the page. It comes in
   shortly after the page has loaded, so it is never part of the first paint, and then stays on screen
   (user, 2026-10-20: shown always, no longer stepping aside for the footer or the page's own offer);
   it is fixed, so it shifts nothing. The x hides it for this page view only: nothing is stored, so
   the next page or a reload shows it again (user, 2026-10-18). */
(function () {
  'use strict';

  var card = document.getElementById('bmc-float');
  if (!card) { return; }
  var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var gone = false;

  function show() {
    if (gone) { return; }
    card.hidden = false;
    // One frame with the card in place and still transparent, so the slide and fade run.
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { card.classList.add('is-on'); });
    });
  }

  function whenLoaded() {
    var go = function () { setTimeout(show, reduced ? 300 : 700); };
    if (document.readyState === 'complete') { go(); } else { window.addEventListener('load', go); }
  }

  card.querySelector('[data-bmc-hide]').addEventListener('click', function () {
    gone = true;
    card.classList.remove('is-on');
    setTimeout(function () { card.hidden = true; }, reduced ? 200 : 450);
    var main = document.getElementById('main');
    if (main) {
      main.setAttribute('tabindex', '-1');
      main.focus({ preventScroll: true });
    }
  });

  whenLoaded();
})();

/* The home page's stats row counts up once, as it comes into view (an IntersectionObserver, no
   layout reads). The final figures are already in the HTML; under reduced motion, or without
   the observer, they simply stay. Each figure is drawn in the page's own short form of a number,
   as the build drew it (lib/stats.ts). */
(function () {
  'use strict';

  var nodes = document.querySelectorAll('[data-count-to]');
  if (!nodes.length || !window.IntersectionObserver || typeof Intl === 'undefined') { return; }
  if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) { return; }
  var fmt;
  try {
    fmt = new Intl.NumberFormat(document.documentElement.lang || 'en', { notation: 'compact', maximumFractionDigits: 1 });
  } catch (e) { return; }

  function run(node) {
    var live = node.querySelector('.stat-live');
    var to = Number(node.getAttribute('data-count-to'));
    if (!live || !(to > 0)) { return; }
    var done = live.textContent;
    var start = null;
    var span = 1100;
    function frame(now) {
      if (start === null) { start = now; }
      var p = Math.min(1, (now - start) / span);
      // Ease out, so the figure slows into its final value.
      var eased = 1 - Math.pow(1 - p, 3);
      live.textContent = p < 1 ? fmt.format(Math.round(to * eased)) : done;
      if (p < 1) { requestAnimationFrame(frame); }
    }
    requestAnimationFrame(frame);
  }

  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) { return; }
      observer.unobserve(entry.target);
      run(entry.target);
    });
  }, { threshold: 0.6 });
  Array.prototype.forEach.call(nodes, function (node) { observer.observe(node); });
})();

/* The support links open the Buy Me a Coffee page over the site, in CoffeeDialog's <dialog>
   (user, 2026-10-20). Their widget page takes a second or more to arrive, so it is fetched ahead of
   the click (user, 2026-10-20, "pre inject"): once the page has loaded, the first sign of a person
   (a scroll, a touch, a key, the pointer moving) loads the frame into the closed dialog, and
   pointing at or focusing a support link does so at once. A test runner that never touches the
   page never loads it, and neither does a visitor who asked to save data. Until the frame has
   loaded, the dialog shows the cup on Buy Me a Coffee's yellow. A middle click, a modified click
   or a browser without <dialog> follows the link as before. Esc closes it natively; so do the x
   and the backdrop. */
(function () {
  'use strict';

  var dialog = document.getElementById('bmc-dialog');
  if (!dialog || typeof dialog.showModal !== 'function') { return; }
  var frame = dialog.querySelector('.bmc-dialog-frame');
  var opener = null;

  function warm() {
    if (frame.getAttribute('src')) { return; }
    frame.addEventListener('load', function () { dialog.classList.add('is-loaded'); }, { once: true });
    frame.setAttribute('src', frame.getAttribute('data-src'));
  }

  var saveData = navigator.connection && (navigator.connection.saveData || /2g/.test(navigator.connection.effectiveType || ''));
  if (!saveData && document.querySelector('a[data-bmc]')) {
    var signs = ['scroll', 'pointermove', 'touchstart', 'keydown', 'wheel'];
    var onSign = function () {
      signs.forEach(function (type) { window.removeEventListener(type, onSign, true); });
      var later = function () { setTimeout(warm, 0); };
      if (typeof window.requestIdleCallback === 'function') { window.requestIdleCallback(later, { timeout: 2000 }); } else { later(); }
    };
    var listen = function () {
      signs.forEach(function (type) { window.addEventListener(type, onSign, { capture: true, passive: true }); });
    };
    if (document.readyState === 'complete') { listen(); } else { window.addEventListener('load', listen); }
  }
  ['pointerover', 'focusin', 'touchstart'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      if (e.target.closest && e.target.closest('a[data-bmc]')) { warm(); }
    }, { capture: true, passive: true });
  });

  document.addEventListener('click', function (e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) { return; }
    var link = e.target.closest && e.target.closest('a[data-bmc]');
    if (!link) { return; }
    e.preventDefault();
    warm();
    opener = link;
    dialog.showModal();
  });

  dialog.querySelector('[data-bmc-close]').addEventListener('click', function () { dialog.close(); });
  // A click on the backdrop lands on the dialog itself, outside its box.
  dialog.addEventListener('click', function (e) { if (e.target === dialog) { dialog.close(); } });
  dialog.addEventListener('close', function () {
    if (opener && document.contains(opener)) { opener.focus({ preventScroll: true }); }
    opener = null;
  });
})();


/* The header's hover pill, over 1080 px: one pill behind the page links that slides to the link
   under the pointer and fades when the pointer leaves the bar. The boxes are read on the pointer's
   arrival, never while the page is being built. Without it the links keep their own hover. */
(function () {
  'use strict';

  var nav = document.getElementById('site-nav');
  if (!nav || !window.matchMedia || !nav.classList) { return; }
  var wide = window.matchMedia('(min-width: 1081px)');
  var pill = document.createElement('span');
  pill.className = 'nav-pill';
  pill.setAttribute('aria-hidden', 'true');
  nav.insertBefore(pill, nav.firstChild);

  function moveTo(link) {
    if (!wide.matches) { return; }
    nav.classList.add('has-pill');
    pill.style.width = link.offsetWidth + 'px';
    pill.style.height = link.offsetHeight + 'px';
    pill.style.transform = 'translate(' + link.offsetLeft + 'px, ' + link.offsetTop + 'px)';
    pill.classList.add('is-on');
  }

  Array.prototype.forEach.call(nav.querySelectorAll('a'), function (link) {
    link.addEventListener('pointerenter', function (event) { if (event.pointerType === 'mouse') { moveTo(link); } });
    link.addEventListener('focus', function () { moveTo(link); });
  });
  nav.addEventListener('pointerleave', function () { pill.classList.remove('is-on'); });
  nav.addEventListener('focusout', function (event) {
    if (!nav.contains(event.relatedTarget)) { pill.classList.remove('is-on'); }
  });
})();

/* The features page's "What it does" on a phone: the first tiles of each group, and a button for
   the rest. The stylesheet folds the rest only when this script runs (the js class) and the group
   is not opened, so every tile is in the page and shows without script. */
(function () {
  'use strict';

  var groups = document.querySelectorAll('[data-feat-group]');
  Array.prototype.forEach.call(groups, function (group) {
    var button = group.querySelector('[data-feat-more]');
    if (!button || !group.querySelector('.feat-extra')) { return; }
    button.hidden = false;
    button.addEventListener('click', function () {
      group.classList.add('is-all');
      button.hidden = true;
      var first = group.querySelector('.feat-extra p');
      if (first) { first.setAttribute('tabindex', '-1'); first.focus({ preventScroll: true }); }
    });
  });
})();

/* The install page's platform tiles: the visitor's own system gets a ring, a class and nothing
   else, so the row never moves. The same reading of the browser as the download page's. */
(function () {
  'use strict';

  var tiles = document.querySelectorAll('.install-pick-row a[data-os]');
  if (!tiles.length) { return; }
  var nav = window.navigator || {};
  var ua = String(nav.userAgent || '');
  var platform = String((nav.userAgentData && nav.userAgentData.platform) || nav.platform || '');
  var os = null;
  if (/Android/i.test(ua)) {
    os = /\b(TV|AFT[A-Z0-9]*|BRAVIA|SmartTV|GoogleTV|Chromecast|AndroidTV)\b/i.test(ua) ? 'android-tv' : 'android';
  } else if (/iPhone|iPad|iPod/i.test(ua)) {
    os = null;
  } else if (/Win/i.test(platform) || /Windows/i.test(ua)) {
    os = 'windows';
  } else if (/Mac/i.test(platform) || /Macintosh/i.test(ua)) {
    os = nav.maxTouchPoints > 1 ? null : 'macos';
  } else if (/Linux|X11/i.test(platform + ' ' + ua) && !/CrOS/i.test(ua)) {
    os = 'linux';
  }
  if (!os) { return; }
  Array.prototype.forEach.call(tiles, function (tile) {
    if (tile.getAttribute('data-os') === os) { tile.classList.add('is-yours'); }
  });
})();

/* The download page's device tabs. The head's inline script has already chosen one before the
   first paint (html[data-dl-tab]) and the stylesheet shows only that device's card. This adds the
   tab roles, the selection on a press or the arrow keys, and follows the address's #id, so every
   link from another page (/download/#windows and the rest) still lands on its device. The address
   is replaced rather than pushed, so the page does not jump to the card. */
(function () {
  'use strict';

  var root = document.documentElement;
  var list = document.getElementById('dl-tabs');
  if (!list || !root.getAttribute('data-dl-tab')) { return; }
  var tabs = Array.prototype.slice.call(list.querySelectorAll('a[data-tab]'));

  function shown() { return tabs.filter(function (tab) { return !tab.hidden; }); }
  function idOf(tab) { return tab.getAttribute('data-tab'); }

  list.setAttribute('role', 'tablist');
  tabs.forEach(function (tab) {
    var id = idOf(tab);
    tab.setAttribute('role', 'tab');
    tab.id = 'tab-' + id;
    tab.setAttribute('aria-controls', id);
    var panel = document.getElementById(id);
    if (panel) { panel.setAttribute('role', 'tabpanel'); }
  });

  function select(id, focus) {
    var match = shown().filter(function (tab) { return idOf(tab) === id; })[0];
    if (!match) { return false; }
    root.setAttribute('data-dl-tab', id);
    tabs.forEach(function (tab) {
      var on = tab === match;
      tab.setAttribute('aria-selected', on ? 'true' : 'false');
      tab.setAttribute('tabindex', on ? '0' : '-1');
    });
    if (focus) { match.focus(); }
    return true;
  }

  select(root.getAttribute('data-dl-tab'), false);
  // From here on a change of tab cross-fades (styles.css); the card drawn with the page did not.
  root.classList.add('dl-tabs-live');

  list.addEventListener('click', function (event) {
    var tab = event.target.closest ? event.target.closest('a[data-tab]') : null;
    if (!tab || event.ctrlKey || event.metaKey || event.shiftKey || event.button) { return; }
    event.preventDefault();
    if (select(idOf(tab), false) && window.history && history.replaceState) {
      history.replaceState(null, '', '#' + idOf(tab));
    }
  });

  list.addEventListener('keydown', function (event) {
    var visible = shown();
    var at = visible.indexOf(document.activeElement);
    if (at < 0) { return; }
    var next = null;
    if (event.key === 'ArrowRight' || event.key === 'ArrowDown') { next = visible[(at + 1) % visible.length]; }
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') { next = visible[(at - 1 + visible.length) % visible.length]; }
    else if (event.key === 'Home') { next = visible[0]; }
    else if (event.key === 'End') { next = visible[visible.length - 1]; }
    if (!next) { return; }
    event.preventDefault();
    select(idOf(next), true);
    if (window.history && history.replaceState) { history.replaceState(null, '', '#' + idOf(next)); }
  });

  window.addEventListener('hashchange', function () { select(location.hash.slice(1), false); });
})();

/* The info button beside "Support TMPlayer" is a <details> shown as a popover: it opens and
   closes on its own, and this closes it on a click elsewhere or on Escape, like the language
   picker. */
(function () {
  var tips = document.querySelectorAll('.support-why');
  if (!tips.length) { return; }
  document.addEventListener('click', function (event) {
    Array.prototype.forEach.call(tips, function (tip) {
      if (tip.open && !tip.contains(event.target)) { tip.open = false; }
    });
  });
  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') { return; }
    Array.prototype.forEach.call(tips, function (tip) {
      if (!tip.open) { return; }
      tip.open = false;
      var summary = tip.querySelector('summary');
      if (summary) { summary.focus(); }
    });
  });
})();
