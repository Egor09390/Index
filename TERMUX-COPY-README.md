# Termux COPY / COPY1

This branch is a build harness, **not a true fork**. GitHub Actions clones the original [Termux app](https://github.com/termux/termux-app) at commit `8629e632fcb95da272221be327db653fb24befe9`, applies [the patch](./termux-copy-buttons.patch), builds a debug APK for Android 7+, uploads it as an artifact, and creates a GitHub prerelease.

**COPY**: output after the most recent pasted block of commands. Echoes of pasted commands and the final prompt are removed heuristically, but cannot be distinguished perfectly from program output. Gboard is detected via multi-character IME commits; behaviour differs between keyboards.

**COPY1**: full *retained* main terminal history, including the commands. Standard Termux scrollback retains 2,000 lines, configurable up to 50,000 through `terminal-transcript-rows` in `~/.termux/termux.properties`. Older evicted lines are not recoverable. Clipboard transfers above 450KB UTF-8 are refused rather than truncated. A custom `extra-keys` property can override the new default buttons (HOME and PGDN are replaced).

Go to **Actions** to see builds, and **Releases** to download a successful APK. Gboard reads the Android system clipboard, but it can decide how long to keep entries.
