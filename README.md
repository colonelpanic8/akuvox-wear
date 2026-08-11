# SmartPlus Wear Bridge

This project produces a modified Akuvox SmartPlus phone app and a matching Wear OS unlock app. The phone build retains SmartPlus's normal login, building setup, and door data under a separate Android application ID, `com.ivanmalison.akuvoxwear`. An injected service receives an unlock request from the paired watch and asks SmartPlus's own authenticated request layer to open the first compatible favorite door.

The watch does not need Wi-Fi or LTE. Its request travels over the Wear OS Data Layer to the paired phone, and the phone uses its Internet connection for the SmartPlus request.

## Status

The repackaging pipeline has successfully rebuilt SmartPlus 7.50.0003 as a single signed APK with its native-library split merged in. Static verification confirms that:

- the phone package is renamed to `com.ivanmalison.akuvoxwear`;
- the injected Wear listener is present;
- the original login activities remain present;
- the phone and watch APKs use the same signing certificate;
- both APK signatures and phone APK alignment are valid; and
- the bridge payload has no duplicate classes with this SmartPlus version.

A real phone/watch/building unlock is still required before calling the integration proven. A modified signature or changed package name can affect Firebase push notifications, Google-backed features, or any server-side integrity checks even if normal login and unlock work.

## Build from the installed phone app

Use APKs pulled from the target phone when possible. This guarantees that the native-library architecture matches the phone.

```sh
nix-shell --run './scripts/pull-installed-smartplus.sh'
nix-shell --run './scripts/repackage.sh vendor/installed-smartplus'
```

An XAPK containing the base APK and a native-library split also works:

```sh
nix-shell --run './scripts/repackage.sh /path/to/SmartPlus.xapk'
```

Outputs:

- `dist/smartplus-wear-phone.apk`
- `dist/smartplus-wear-watch.apk`

For public releases, set `ANDROID_KEYSTORE_FILE` (or
`ANDROID_KEYSTORE_BASE64`), `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD`. Without those values,
the script intentionally falls back to the local Android debug key for device
testing only.

The proprietary APK and generated decompilation are local build inputs and are deliberately excluded from Git. The script decompiles the entire base APK into a temporary directory, applies the package and bridge changes, merges native libraries, rebuilds it, and removes the temporary source afterward.

## Install and use

1. Install the rebuilt phone APK:

   ```sh
   adb install -r dist/smartplus-wear-phone.apk
   ```

2. Open **SmartPlus Wear** on the phone and log in through the normal SmartPlus UI. This clone has separate app storage, so it cannot reuse the original app's login automatically.
3. In the cloned app, mark the desired remote-unlock relay as a favorite. The bridge selects the first favorite conventional door whose relay type is `relay` or `security_relay`.
4. Enable ADB debugging on the watch, connect it, and install the watch APK:

   ```sh
   adb -s WATCH_SERIAL install -r dist/smartplus-wear-watch.apk
   ```

5. Keep the watch paired to the phone. Open **Unlock** on the watch and press the button.

   For one-tap access from the watch face, add the **SmartPlus Unlock** tile to
   the Wear OS tile carousel. Its Unlock button opens the companion activity
   and sends the request immediately.

The original Play Store SmartPlus app and the clone can coexist because they have different package IDs. Their sessions and local settings are independent.

## How it works

```text
Wear button
    -> encrypted Wear Data Layer message
    -> injected service in the cloned SmartPlus phone process
    -> first compatible favorite relay from SmartPlus's database
    -> SmartPlus's existing authenticated open-door request
    -> result returned to the watch
```

No SmartPlus username, password, token, server address, door MAC, or relay configuration is stored by this project. Login and token refresh remain SmartPlus's responsibility.

## Limitations

- The bridge currently targets the reverse-engineered internals of SmartPlus 7.50.0003. Future versions may rename or change those classes.
- Only native architectures present in the supplied APK splits can be included. Pulling the installed APK set from the target phone is the safest input.
- The selected favorite must be a conventional remote-unlock relay (`type == 1001`), not a Bluetooth-only or third-party smart lock.
- Rebuilding on another machine uses that machine's Android debug key. Phone and watch builds must always be signed by the same key, and updates must reuse the original build key.
- Akuvox's original signing key is unavailable, so features restricted to its certificate cannot be preserved.

Use this only with a building and account you are authorized to access.

## F-Droid repository

Production-signed phone releases are published through a self-hosted F-Droid
repository at `https://colonelpanic8.github.io/akuvox-wear/fdroid/repo`. See
[`docs/fdroid.md`](docs/fdroid.md) for the release, signing, and GitHub Pages
setup. This is not an official F-Droid.org package and contains the non-free
components described above.
