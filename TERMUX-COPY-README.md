# Termux COPY / FULL

This branch is a build harness, **not a true fork**. GitHub Actions clones the original [Termux app](https://github.com/termux/termux-app) at commit `8629e632fcb95da272221be327db653fb24befe9`, applies [the patch](./termux-copy-buttons.patch), builds only an ARM64-v8a debug APK for Android 7+, uploads it as an artifact, and creates a GitHub prerelease.

**COPY**: output after the most recent pasted block of commands. Echoes of pasted commands and the final prompt are removed heuristically, but cannot be distinguished perfectly from program output. Gboard is detected via multi-character IME commits; behaviour differs between keyboards.

The **FULL** key copies the retained main terminal history, including commands. It joins lines wrapped by the display just as manual selection does. A transcript of up to 19,000 UTF-16 units is copied in one go. For longer transcripts, tap **FULL** and paste the first part, then tap **FULL** again and paste the next part until the notification shows the last part. Each part stays below Gboard's 20,000-character limit. Paste at the end of the previous part to reconstruct the transcript exactly. A new queue starts from the current history after the last part has been copied.

Swipe up on **FULL** to share the full transcript as a `.txt` file. Use this option if the destination app does not accept long clipboard entries. Standard Termux scrollback retains 2,000 lines, configurable up to 50,000 through `terminal-transcript-rows` in `~/.termux/termux.properties`. A custom `extra-keys` property can override the default buttons.

Go to **Actions** to see builds, and **Releases** to download a successful APK. Gboard reads the Android system clipboard, but it can decide how long to keep entries.

Tap the round ivory button at the upper left to open the sessions menu. Press and hold a session, then tap **Delete** in the light dialog to close the session and remove it from the list. Tap outside the button to dismiss the dialog.

**ABI target:** only `arm64-v8a` is enabled in the patched app/build.gradle; universal, arm32, x86 and x86_64 outputs are disabled. Intended for 64-bit ARM devices such as Galaxy S23. The existing Termux app on the phone cannot be updated with this build if Android rejects its different APK signing certificate. Installing this build does not transfer root access or data automatically.

The default toolbar has one row with `CTRL COPY FULL` on the left and empty space on the right. Swipe up on `FULL` for the `TXT` action.
