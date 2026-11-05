# Lightscreen 2.5 — Windows autostart fix

This repository hosts the Windows build of [Lightscreen 2.5](https://github.com/ckaiser/Lightscreen/tree/v2.5) with a targeted fix for autostart commands containing spaces in the executable path.

The modified `tools/os.cpp` is based on upstream tag `v2.5` (GPL-2.0-or-later). The Windows release workflow checks out upstream Lightscreen, replaces this file with the patched version, builds with Qt 5.15.2 and publishes both runnable Windows binaries and the complete source code (including third-party submodules).

**The release is experimental until the first Windows build and sign-in test pass.** This patch does not override Windows Startup Apps being disabled by the user or a policy.

## Fix

The original Windows Run registry value was an unquoted path. This breaks launching Lightscreen when the executable lives inside a folder containing spaces, such as `C:\Program Files (x86)\Lightscreen\lightscreen.exe`.

This build writes:

```text
"C:\Program Files (x86)\Lightscreen\lightscreen.exe" -h
```

using `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, with a check for registry errors. Administrative privileges are not required.

## Upstream attribution

Lightscreen by Christian Kaiser and contributors, licensed GPL-2.0-or-later; see [upstream](https://github.com/ckaiser/Lightscreen) and included `LICENSE` in source archives. The fix is independently maintained and not an official upstream release.
