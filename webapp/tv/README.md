# TMPlayer on LG and Samsung TVs

Two thin "hosted" wrappers around the TV build of the web player. Each package holds only a tiny
`index.html` (plain ES5, runs on Chromium 68) that checks the address answers and then opens it.
If it does not answer, the screen shows "Cannot reach TMPlayer" with Try again (OK retries, Back
closes the app). The words come from `core/src/commonMain/resources/i18n/en.json` at package time.

| Folder | What |
| --- | --- |
| `shell/index.html` | The template, filled in by `package.sh` |
| `webos/` | `appinfo.json` and the 80 and 130 px icons for LG webOS |
| `tizen/` | `config.xml` and the 512 px icon for Samsung Tizen (5.5 and later) |
| `package.sh` | Builds `out/TMPlayer-tv.ipk` and `out/TMPlayer-tv.wgt` |

## 1. Serve the app and build the packages

On this computer, in `webapp/`:

```sh
pnpm build:tv                                  # the TV build, into dist-tv/ (again after every change)
pnpm serve:tv                                  # serves it on port 8092, leave it running
```

Fedora Workstation's firewall already lets other devices reach ports 1025 to 65535, 8092 among
them (`firewall-cmd --list-ports`). On a stricter setup: `sudo firewall-cmd --add-port=8092/tcp`.

The TV build is the same app made for the TVs' older engines (Chromium 68 on webOS 5, 79 on
webOS 6, 69 and up on Tizen 2020): code lowered to Chromium 68 with core-js, a classic Telegram
worker, mtcute's wasm lowered by Binaryen, CSS fallbacks from `legacy-css.ts`. It is opened over
plain http from this computer, which is not a secure context, so it runs without the Service
Worker and without `crypto.subtle` (the two-step password goes through `@noble/hashes` there).

Then, in `webapp/tv/`:

```sh
./package.sh                                   # http://10.27.27.27:8092/
./package.sh http://192.168.1.20:8092/         # another address, if the computer's IP changes
```

The address is written into the package, so a new IP means a new package. `ares-package` is fetched
with `npx` into the npm cache, nothing is installed globally. Output goes to `webapp/tv/out/`
(ignored by git): `TMPlayer-tv.ipk` (LG), `TMPlayer-tv.wgt` (Samsung, unsigned), and `out/tizen/`
(the Samsung files as a folder, which is what you sign).

The TV and this computer must be on the same network. Test from the TV's browser first if unsure:
open `http://10.27.27.27:8092/`.

## 2. LG webOS

**On the TV (once):**

1. Make an LG developer account at webostv.developer.lge.com and sign in on the TV (LG account).
2. Install **Developer Mode** from the LG Content Store and open it.
3. Sign in, switch **Dev Mode Status** on, switch **Key Server** on, and let the TV restart.
4. Open Developer Mode again. Note the TV's IP, and the **passphrase** shown under Key Server.

**On this computer.** `ares` is the webOS CLI, run through `npx` so nothing is installed globally.
Paste this function into the shell once:

```sh
ares() { local c="$1"; shift; npx --yes --package @webos-tools/cli "ares-$c" "$@"; }
```

```sh
ares setup-device --add tv --info "{'host':'TV_IP','port':'9922','username':'prisoner'}"
ares novacom --device tv --getkey              # asks for the passphrase from the TV screen
ares device-info --device tv                   # should print the TV's model
ares install --device tv webapp/tv/out/TMPlayer-tv.ipk
ares launch --device tv org.tmplayer.tv
```

TMPlayer now sits in the TV's app list. To remove it: `ares install --device tv --remove org.tmplayer.tv`.

**Renewing.** Developer Mode turns itself off after a while (the Developer Mode app shows the time
left). Open the app and press **Extend Session** before it runs out, or the apps stop launching.
Installed apps stay put after a renewal.

**Alternatives.**

- **webOS Dev Manager** (github.com/webosbrew/dev-manager-desktop, AppImage for Linux): add the TV
  with the same IP and passphrase, then install the `.ipk` with a click. Works on stock TVs in
  Developer Mode.
- **Homebrew Channel** is for rooted TVs only. It does not need the renewal, but rooting depends on
  the TV's firmware and is a bigger step. Only worth it if the session limit annoys you.

## 3. Samsung Tizen

**On the TV (once):** Smart Hub, **Apps**, press `1 2 3 4 5` on the remote, switch **Developer
mode** on, type **10.27.27.27** as the host PC IP, press OK, then restart the TV. Note the TV's IP.

**Recommended route: Tizen Studio command line tools on Linux.** It is Samsung's own toolchain, it
is the one that can make the certificate that Samsung TVs need, and it is updated with the TV
firmware. Recent TVs refuse a package signed with the sample certificate: it must be signed by a
Samsung **distributor certificate that contains your TV's DUID**. The Docker images
(`georift/install-jellyfin-tizen` is built for Jellyfin only; `vitalets/tizen-webos-sdk` is a
general SDK image) ship a sample certificate that only the older TVs and the emulator accept, and
they do not make a Samsung certificate for you (none was found that does), so you would need one from
Tizen Studio's Certificate Manager anyway. Apps2Samsung is a Windows tool and is not an option here.

1. Install Tizen Studio from developer.samsung.com/smarttv/develop/getting-started/setting-up-sdk/installing-tv-sdk.html
   (the Ubuntu/Linux `.bin`; needs a JDK 17 or newer and `libwebkit2gtk`). Run it with
   `--no-java-check` if the JDK check complains. Add `~/tizen-studio/tools` and
   `~/tizen-studio/tools/ide/bin` to `PATH` (that gives `sdb` and `tizen`).
2. In **Package Manager** (`~/tizen-studio/package-manager/package-manager.bin`) install
   **TV Extensions** and **Samsung Certificate Extension** (under Extension SDK).
3. Connect to the TV and read its DUID:
   ```sh
   sdb connect TV_IP
   sdb devices                                  # lists TV_IP:26101 with its model
   sdb -s TV_IP:26101 capability | grep -i duid
   ```
4. Make the certificate once, in the GUI (the certificate step has no supported command line):
   open `~/tizen-studio/ide/TizenStudio.sh`, **Tools, Certificate Manager**, **+**, choose
   **Samsung**, **TV**, make a new author certificate, sign in with a Samsung account when asked,
   pick the distributor privilege (Public), paste the TV's DUID, and finish. The profile is saved as,
   say, `TMPlayerTV`. A Samsung account is free; the certificate is valid for about a year, and a
   new TV means a new certificate (add its DUID).
5. Sign and install. Signing from the staging folder is the supported way (the `.wgt` file from
   `package.sh` is the same files, unsigned, for anyone who prefers to sign it some other way):
   ```sh
   cd webapp/tv
   tizen package -t wgt -s TMPlayerTV -o out/signed -- out/tizen
   tizen install -n out/signed/TMPlayer.wgt -t TV_IP:26101
   tizen run -p TMPlayerTV.TMPlayer -t TV_IP:26101
   ```
   Check the exact file name in `out/signed/`. An `install failed[118, -12]` or "Invalid certificate
   chain" means the certificate does not hold this TV's DUID; make it again with the right one.
   `install failed[118, -11]`, "Author certificate not match", means a copy signed with another
   certificate is already there: `tizen uninstall -p TMPlayerTV -t TV_IP:26101`, then install again.
6. TMPlayer appears under **Apps, Downloaded**. Developer mode and the host IP only matter while
   installing; the app keeps working after the TV restarts. Turn Developer mode off again if you like.

**Debugging on Samsung:** `sdb -s TV_IP:26101 shell 0 debug TMPlayerTV.TMPlayer` prints a port; open
`http://TV_IP:PORT` in Chromium to get the TV page's dev tools.

## Notes

- Back (webOS key code 461, Tizen 10009) closes the app from the "Cannot reach" screen. Inside the
  hosted web app, Back is handled by the web app itself.
- On Samsung the wrapper registers the media and colour keys before it opens the web app, and the
  web app registers them again itself (`samsungKeys` in `src/tv/index.tsx`), as the wrapper's
  request may not carry over to the hosted page.
- Back on the web app's Home does not close the app yet; the remote's Home key leaves it.
- The Samsung config allows navigation to the address's host and a CSP for its origin. If a TV
  shows a black screen after the check passes, the first thing to try is `*` in the
  `tizen:allow-navigation` line of `tizen/config.xml`.
