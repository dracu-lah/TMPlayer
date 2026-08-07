/* TMPlayer site: pulls release data from the public GitHub API.
   No auth, no token. Unauthenticated calls are limited to 60 per hour per IP,
   so every failure path has to end somewhere useful rather than in a spinner. */
(function () {
  'use strict';

  var OWNER = 'dracu-lah';
  var REPO = 'TMPlayer';
  var API = 'https://api.github.com/repos/' + OWNER + '/' + REPO + '/releases?per_page=10';
  var RELEASES_PAGE = 'https://github.com/' + OWNER + '/' + REPO + '/releases';
  var TIMEOUT_MS = 9000;

  /* One APK per ABI. armeabi-v7a first: it is what most TV sticks run. */
  var ABIS = [
    {
      id: 'armeabi-v7a',
      note: 'Mi TV Stick, and most sticks sold between 2018 and 2021. Start here.',
      recommended: true
    },
    {
      id: 'arm64-v8a',
      note: 'Chromecast with Google TV, Nvidia Shield, and newer 64-bit boxes.',
      recommended: false
    },
    {
      id: 'x86_64',
      note: 'Emulators and the handful of x86 Android TV boxes.',
      recommended: false
    }
  ];

  var el = {
    version: document.getElementById('release-version'),
    date: document.getElementById('release-date'),
    status: document.getElementById('release-status'),
    abiList: document.getElementById('abi-list'),
    abiCallout: document.getElementById('abi-callout'),
    notesWrap: document.getElementById('release-notes-wrap'),
    notes: document.getElementById('release-notes'),
    prevWrap: document.getElementById('previous-wrap'),
    prevList: document.getElementById('previous-list'),
    heroBtn: document.getElementById('hero-download'),
    heroLabel: document.getElementById('hero-download-label')
  };

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

  function formatDate(iso) {
    if (!iso) { return ''; }
    var d = new Date(iso);
    if (isNaN(d.getTime())) { return ''; }
    try {
      return new Intl.DateTimeFormat('en-GB', {
        day: 'numeric', month: 'long', year: 'numeric'
      }).format(d);
    } catch (e) {
      return d.toISOString().slice(0, 10);
    }
  }

  function clear(node) {
    while (node.firstChild) { node.removeChild(node.firstChild); }
  }

  function downloadIcon() {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('width', '15');
    svg.setAttribute('height', '15');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('fill', 'currentColor');
    path.setAttribute('d', 'M12 3a1 1 0 0 1 1 1v9.59l3.3-3.3a1 1 0 1 1 1.4 1.42l-5 5a1 1 0 0 1-1.4 0l-5-5a1 1 0 1 1 1.4-1.42l3.3 3.3V4a1 1 0 0 1 1-1Zm-7 15a1 1 0 0 1 1-1h12a1 1 0 1 1 0 2H6a1 1 0 0 1-1-1Z');
    svg.appendChild(path);
    return svg;
  }

  /* Assets are named TMPlayer-<version>-<abi>.apk, but match loosely so a
     rename upstream does not silently empty this list. */
  function findApk(release, abi) {
    var assets = (release && release.assets) || [];
    for (var i = 0; i < assets.length; i++) {
      var name = String(assets[i].name || '').toLowerCase();
      if (name.slice(-4) === '.apk' && name.indexOf(abi) !== -1) {
        return assets[i];
      }
    }
    return null;
  }

  function apkAssets(release) {
    return ((release && release.assets) || []).filter(function (a) {
      return String(a.name || '').toLowerCase().slice(-4) === '.apk';
    });
  }

  /* ---------- rendering ---------- */

  function showStatus(kind, lines) {
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

  function renderLatest(release) {
    var tag = release.tag_name || release.name || 'Latest';
    el.version.textContent = tag;
    el.date.textContent = formatDate(release.published_at || release.created_at);

    var found = apkAssets(release);
    if (found.length === 0) {
      showStatus('error', [
        { text: 'This release has no APK attached yet. The build may still be running.' },
        { link: { href: release.html_url || RELEASES_PAGE, text: 'Open ' + tag + ' on GitHub' } }
      ]);
      return;
    }

    el.status.hidden = true;
    clear(el.abiList);

    ABIS.forEach(function (abi) {
      var asset = findApk(release, abi.id);
      if (!asset) { return; }

      var li = make('li', 'abi' + (abi.recommended ? ' pick' : ''));

      var text = make('div', 'abi-text');
      var name = make('p', 'abi-name');
      name.appendChild(document.createTextNode(abi.id));
      if (abi.recommended) {
        name.appendChild(make('span', 'badge', 'Most TV sticks'));
      }
      text.appendChild(name);
      text.appendChild(make('p', 'abi-note', abi.note));
      li.appendChild(text);

      var get = make('div', 'abi-get');
      get.appendChild(make('span', 'abi-size', formatSize(asset.size)));
      var a = link(asset.browser_download_url, 'dl');
      a.appendChild(downloadIcon());
      a.appendChild(make('span', null, 'Download'));
      a.setAttribute('aria-label', 'Download ' + asset.name);
      get.appendChild(a);
      li.appendChild(get);

      el.abiList.appendChild(li);

      if (abi.recommended && el.heroBtn) {
        el.heroBtn.href = asset.browser_download_url;
        el.heroLabel.textContent = 'Download ' + tag + ' for armeabi-v7a';
      }
    });

    /* No ABI matched the expected names: fall back to whatever is attached. */
    if (!el.abiList.firstChild) {
      found.forEach(function (asset) {
        var li = make('li', 'abi');
        var text = make('div', 'abi-text');
        text.appendChild(make('p', 'abi-name', asset.name));
        li.appendChild(text);
        var get = make('div', 'abi-get');
        get.appendChild(make('span', 'abi-size', formatSize(asset.size)));
        var a = link(asset.browser_download_url, 'dl');
        a.appendChild(downloadIcon());
        a.appendChild(make('span', null, 'Download'));
        get.appendChild(a);
        li.appendChild(get);
        el.abiList.appendChild(li);
      });
    }

    el.abiList.hidden = false;
    el.abiCallout.hidden = false;

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
      var tagLink = link(release.html_url || RELEASES_PAGE, 'prev-tag', release.tag_name || release.name || 'Release');
      head.appendChild(tagLink);
      head.appendChild(make('span', 'prev-date', formatDate(release.published_at || release.created_at)));
      li.appendChild(head);

      var assets = apkAssets(release);
      if (assets.length) {
        var list = make('ul', 'prev-assets');
        assets.forEach(function (asset) {
          var item = make('li');
          var label = asset.name;
          for (var i = 0; i < ABIS.length; i++) {
            if (label.toLowerCase().indexOf(ABIS[i].id) !== -1) { label = ABIS[i].id; break; }
          }
          var a = link(asset.browser_download_url, null, label);
          a.setAttribute('aria-label', 'Download ' + asset.name);
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
    el.version.textContent = 'Unavailable';
    el.date.textContent = '';
    el.abiList.hidden = true;
    el.abiCallout.hidden = true;

    showStatus('error', [
      { text: reason },
      {
        text: 'The downloads are still there. ',
        link: { href: RELEASES_PAGE, text: 'Open the releases page on GitHub' },
        after: ' and pick the armeabi-v7a APK, which is what most TV sticks need.'
      }
    ]);

    if (el.heroBtn) {
      el.heroBtn.href = RELEASES_PAGE;
      el.heroLabel.textContent = 'Get the APK from GitHub';
    }
  }

  function noReleases() {
    el.version.textContent = 'Not released yet';
    el.date.textContent = '';
    el.abiList.hidden = true;
    el.abiCallout.hidden = true;
    showStatus('error', [
      { text: 'No release has been published yet. You can still build the app from source.' },
      { link: { href: 'https://github.com/' + OWNER + '/' + REPO, text: 'Read the build instructions on GitHub' } }
    ]);
    if (el.heroBtn) {
      el.heroBtn.href = 'https://github.com/' + OWNER + '/' + REPO;
      el.heroLabel.textContent = 'View the project on GitHub';
    }
  }

  /* ---------- fetch ---------- */

  function load() {
    if (typeof fetch !== 'function') {
      fail('This browser cannot load the release list.');
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
        fail('GitHub is rate-limiting this network. Its public API allows 60 requests an hour per address, and this one has used them up.');
      } else if (err && err.name === 'AbortError') {
        fail('GitHub did not answer in time.');
      } else {
        fail('The release list could not be loaded from GitHub.');
      }
    });
  }

  load();
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
