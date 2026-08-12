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

  /* The current release is universal. Older split releases remain readable below. */
  var BUILDS = [
    {
      id: 'universal',
      label: 'Universal APK',
      note: 'One file for every supported device, television or phone. No chip type to look up.'
    },
    {
      id: 'armeabi-v7a',
      label: 'armeabi-v7a',
      note: 'Mi TV Stick, most sticks sold between 2018 and 2021, and older phones. Start here.'
    },
    {
      id: 'arm64-v8a',
      label: 'arm64-v8a',
      note: 'Chromecast with Google TV, Nvidia Shield, newer 64-bit boxes, and every current phone.'
    },
    {
      id: 'x86_64',
      label: 'x86_64',
      note: 'Emulators and the handful of x86 Android TV boxes.'
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
    card: document.getElementById('release-card'),
    skeleton: document.getElementById('abi-skeleton'),
    heroBtn: document.getElementById('hero-download'),
    heroLabel: document.getElementById('hero-download-label')
  };

  /* The site is several pages now, and only two of them ask GitHub anything:
     the home page has the hero button and the download page has the card. A
     page with neither wants no request at all. */
  if (!el.version && !el.heroBtn) { return; }

  /* On the page that has one but not the other, every node that is missing
     becomes a detached stand-in. The rendering below then writes to it exactly
     as it always did, and nothing it writes reaches the document, which is a
     good deal cheaper than a guard on every line that touches el. */
  for (var key in el) {
    if (Object.prototype.hasOwnProperty.call(el, key) && !el[key]) {
      el[key] = document.createElement('span');
    }
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

  /* GitHub counts every fetch of a release asset. For a sideloaded app that is
     the only install figure that exists, so it goes on the page rather than in
     a dashboard: it is public data either way. */
  function formatCount(n) {
    if (typeof n !== 'number' || !isFinite(n) || n < 0) { return ''; }
    return n.toLocaleString('en-GB') + (n === 1 ? ' download' : ' downloads');
  }

  function releaseDownloads(release) {
    return apkAssets(release).reduce(function (total, asset) {
      return total + (asset.download_count || 0);
    }, 0);
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

  /* Assets are named TMPlayer-<version>-<build>.apk, but match loosely so a
     rename upstream does not silently empty this list. */
  function findApk(release, build) {
    var assets = (release && release.assets) || [];
    for (var i = 0; i < assets.length; i++) {
      var name = String(assets[i].name || '').toLowerCase();
      if (name.slice(-4) === '.apk' && name.indexOf(build) !== -1) {
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

  function assetLabel(asset) {
    var name = String((asset && asset.name) || '').toLowerCase();
    for (var i = 0; i < BUILDS.length; i++) {
      if (name.indexOf(BUILDS[i].id) !== -1) { return BUILDS[i].label; }
    }
    return asset.name;
  }

  /* ---------- rendering ---------- */

  /* The card stops pretending. Called on every path out of the fetch, including the ones that
     fail, because a skeleton left behind after an error is a page that never finished loading. */
  function settled() {
    el.skeleton.hidden = true;
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

  function renderLatest(release) {
    settled();
    var tag = release.tag_name || release.name || 'Latest';
    el.version.textContent = tag;
    var when = formatDate(release.published_at || release.created_at);
    var total = releaseDownloads(release);
    el.date.textContent = total ? when + ', ' + formatCount(total) : when;

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

    var universal = findApk(release, 'universal');
    var builds = universal ? [BUILDS[0]] : BUILDS.slice(1);
    var primary = universal || findApk(release, 'armeabi-v7a') || found[0];

    builds.forEach(function (build) {
      var asset = findApk(release, build.id);
      if (!asset) { return; }
      var recommended = asset === primary;

      var li = make('li', 'abi' + (recommended ? ' pick' : ''));

      var text = make('div', 'abi-text');
      var name = make('p', 'abi-name');
      name.appendChild(document.createTextNode(build.label));
      if (recommended) {
        name.appendChild(make('span', 'badge', universal ? 'One file' : 'Most TV sticks'));
      }
      text.appendChild(name);
      text.appendChild(make('p', 'abi-note', build.note));
      li.appendChild(text);

      var get = make('div', 'abi-get');
      get.appendChild(make('span', 'abi-size', formatSize(asset.size)));
      if (asset.download_count) {
        get.appendChild(make('span', 'abi-size', formatCount(asset.download_count)));
      }
      var a = link(asset.browser_download_url, 'dl');
      a.appendChild(downloadIcon());
      a.appendChild(make('span', null, 'Download'));
      a.setAttribute('aria-label', 'Download ' + asset.name);
      get.appendChild(a);
      li.appendChild(get);

      el.abiList.appendChild(li);

      if (recommended && el.heroBtn) {
        el.heroBtn.href = asset.browser_download_url;
        el.heroLabel.textContent = universal
          ? 'Download ' + tag + ' universal APK'
          : 'Download ' + tag + ' for armeabi-v7a';
      }
    });

    /* No known build matched: fall back to whatever APK is attached. */
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

        if (asset === primary && el.heroBtn) {
          el.heroBtn.href = asset.browser_download_url;
          el.heroLabel.textContent = 'Download ' + tag;
        }
      });
    }

    el.abiList.hidden = false;
    el.abiCallout.hidden = !universal;

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
      var when = formatDate(release.published_at || release.created_at);
      var total = releaseDownloads(release);
      head.appendChild(make('span', 'prev-date', total ? when + ', ' + formatCount(total) : when));
      li.appendChild(head);

      var assets = apkAssets(release);
      if (assets.length) {
        var list = make('ul', 'prev-assets');
        assets.forEach(function (asset) {
          var item = make('li');
          var label = assetLabel(asset);
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
    settled();
    el.version.textContent = 'Unavailable';
    el.date.textContent = '';
    el.abiList.hidden = true;
    el.abiCallout.hidden = true;

    showStatus('error', [
      { text: reason },
      {
        text: 'The downloads are still there. ',
        link: { href: RELEASES_PAGE, text: 'Open the releases page on GitHub' },
        after: ' and download the universal APK.'
      }
    ]);

    if (el.heroBtn) {
      el.heroBtn.href = RELEASES_PAGE;
      el.heroLabel.textContent = 'Get the APK from GitHub';
    }
  }

  function noReleases() {
    settled();
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
    button.setAttribute('aria-label', 'Switch to ' + next + ' theme');
    button.setAttribute('title', 'Switch to ' + next + ' theme');
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

    if (showcase && !reduced) {
      var box = showcase.getBoundingClientRect();
      var height = window.innerHeight || 800;
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
    button.setAttribute('aria-label', next ? 'Close menu' : 'Menu');
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
