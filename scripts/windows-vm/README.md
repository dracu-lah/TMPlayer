# Windows VM for MSI pre-checks (P6 spike)

Builds the Windows MSI and runs CI's install checks on this Linux machine before a push or a
release, in a Windows VM: [dockur/windows](https://github.com/dockur/windows) (QEMU with KVM) under
rootless podman. Plan and status: [docs/agents/CI-LOCAL-PLAN.md](../../docs/agents/CI-LOCAL-PLAN.md).

```sh
scripts/windows-vm/up.sh          # first time: downloads and installs Windows, then waits for it
scripts/windows-vm/build-msi.sh   # dist/TMPlayer-1.0.1-local.0-windows-x64.msi, installed over 1.0.0,
                                  # self-tested and uninstalled inside the VM
scripts/windows-vm/down.sh        # shut it down; the installed disk stays for the next up.sh
```

Every script takes `--dry-run` (checks the host, prints the commands, starts nothing) and `--help`.

## Licence: pre-checks only

The VM runs Microsoft's **evaluation** Windows Server 2025 (the ISO dockur downloads from
Microsoft; the evaluation runs 180 days, then the disk has to be made again with
`down.sh --purge`). An evaluation copy is for evaluating and testing, so this VM is for pre-checks
only. **The MSI that goes into a release keeps coming from GitHub's Windows runner**
(`release.yml`), whatever this VM builds. Nothing here is signed or uploaded.

## What it costs

| | |
| --- | --- |
| Disk | about 40 GB once installed (64 GB virtual disk, filled as used, plus the 7.6 GB ISO), under `~/.cache/tmplayer/windows-vm/storage`. `up.sh` wants 60 GB free before the first install, 10 GB after. |
| Memory | 8 GB for the VM (`RAM_SIZE`). `up.sh` refuses to start with less than 12 GB available (`--force` skips that). |
| CPU | 4 cores (`CPU_CORES`). |
| First boot | 20 to 40 min: the ISO download, the unattended install, then `oem/setup.ps1` (JDK 21, .NET 3.5 for WiX, the job runner). Later boots take a minute or two. |
| First build | longer than CI's: Gradle, WiX and the Android SDK packages download into the VM once. Later builds reuse `C:\work`. |

## How it works

- `up.sh` starts the container with `/dev/kvm`, `/dev/net/tun` and `NET_ADMIN`, the screen on
  `http://127.0.0.1:8006` and RDP on `127.0.0.1:3389` (both local only). It makes a local account
  `tmplayer` with a random password, kept in `~/.cache/tmplayer/windows-vm/credentials` (mode 600).
- `oem/` is copied to `C:\OEM` and dockur runs `install.bat` once, at the end of the install:
  `setup.ps1` adds Defender exclusions, .NET 3.5, Temurin JDK 21, an Android SDK folder with this
  machine's accepted licences (Gradle fetches the packages), and the scheduled task that runs
  `runner.ps1` at every boot.
- The runner talks to the host only through the shared folder (`~/.cache/tmplayer/windows-vm/shared`,
  `\\host.lan\Data` or `Z:` in Windows): it writes `vm/heartbeat`, and runs each `jobs/<id>.ps1` once
  with its output in `<id>.log` and its exit code in `<id>.exit`. No SSH, no extra ports.
- `build-msi.sh` copies the working tree (what git tracks and untracked files it does not ignore,
  never `local.properties`) to the share, queues `build-msi.ps1` as a job and follows its log. In
  the VM the job mirrors the source to `C:\work\TMPlayer` and runs the steps of
  `.github/actions/desktop-windows`: `:desktop:test`, the older MSI (1.0.0), the new MSI and app
  image, the bundled C++ runtime check, install old, install new, exactly one product under the
  upgrade code at the expected version, `selftest.ps1` on the installed launcher; then an uninstall
  that must leave no product and no launcher. The MSI comes back to `dist/`, the msiexec logs to
  `dist/windows-logs/`.

## What is proven and what is not

See the Progress section of the plan. In short, as of 2026-11-18: the scripts pass `bash -n`,
shellcheck and their dry runs; the VM has not been booted on this machine yet (8 GB of memory was
available, the gate is 12 GB). Unproven until the first real run: dockur under rootless podman
here, the share path as the scheduled task sees it, AutoLogon not being needed by the runner, and
the Android SDK packages downloading inside the VM.

macOS and iOS are not done in VMs (Apple's licence allows macOS only on Apple hardware): they stay
on GitHub's macOS runners.
