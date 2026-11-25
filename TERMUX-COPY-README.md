# Termux COPY / FULL

This branch is a build harness, **not a true fork**. GitHub Actions clones the original [Termux app](https://github.com/termux/termux-app) at commit `8629e632fcb95da272221be327db653fb24befe9`, applies [the patch](./termux-copy-buttons.patch), builds only an ARM64-v8a debug APK for Android 7+, uploads it as an artifact, and creates a GitHub prerelease.

**COPY** copies output after the most recent pasted block of commands. It skips blank input and repeated trailing prompts caused by empty Enter presses. When there is nothing to copy, the existing clipboard stays intact. Command echoes are filtered heuristically.

The **FULL** key copies the retained main terminal history, including commands. It joins lines wrapped by the display just as manual selection does. A transcript of up to 19,000 UTF-16 units is copied in one go. For longer transcripts, the first tap saves the entire snapshot as `termux-transcript-*.txt` in **Downloads** and copies the first part. Tap **FULL** again for each remaining part. Copy progress and the saved filename appear inside the Termux window. A new queue starts after the last part has been copied. On Android 9 and earlier, writing to Downloads requires storage access.

Swipe up on **FULL** to share the full transcript as a `.txt` file. Use this option if the destination app does not accept long clipboard entries. Standard Termux scrollback retains 2,000 lines, configurable up to 50,000 through `terminal-transcript-rows` in `~/.termux/termux.properties`. A custom `extra-keys` property can override the default buttons.

Go to **Actions** to see builds, and **Releases** to download a successful APK. Gboard reads the Android system clipboard, but it can decide how long to keep entries.

With Gboard selected, the terminal defaults to character-based input so Gboard can show its recent clipboard suggestion above the keyboard. In Gboard, enable **Show recently copied text and images in suggestions bar** for the shortcut. An explicit `enforce-char-based-input` value in `termux.properties` takes precedence.

Tap the white arrow at the upper left to open the sessions menu. The list shows session numbers. Tap the bin on a session's row to close that session.

**ABI target:** only `arm64-v8a` is enabled in the patched app/build.gradle; universal, arm32, x86 and x86_64 outputs are disabled. Intended for 64-bit ARM devices such as Galaxy S23. The existing Termux app on the phone cannot be updated with this build if Android rejects its different APK signing certificate. Installing this build does not transfer root access or data automatically.

The default toolbar shows `CTRL`, `COPY` and `FULL` in equal-width columns, using [Geist Pixel Square](https://github.com/vercel/geist-font/tree/v1.9.2/fonts/GeistPixel). Its SIL Open Font License is bundled in the APK. Swipe up on `FULL` for the `TXT` action.
