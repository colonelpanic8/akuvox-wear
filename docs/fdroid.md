# F-Droid distribution

SmartPlus Wear uses a self-hosted F-Droid binary repository on GitHub Pages.
It indexes the production-signed phone APKs attached to GitHub Releases without
rebuilding or re-signing them.

- Landing page: <https://colonelpanic8.github.io/akuvox-wear/>
- Repository address: `https://colonelpanic8.github.io/akuvox-wear/fdroid/repo`

The Wear OS APK has the same application ID and signing certificate but is a
separate download on the GitHub Releases page. It is not indexed in the phone
repository.

## Publication

`.github/workflows/fdroid-repo.yml` runs when a release is published and can
also be dispatched manually. It downloads recent `smartplus-wear-phone.apk`
release assets, stops at any Android signing-key boundary, builds a signed
F-Droid index, and deploys it with a fingerprint-bearing landing page.

GitHub Pages must use **GitHub Actions** as its source. These repository secrets
must contain the stable production signer used for both APKs and the index:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

## Local build

Install `fdroidserver`, then provide the same signing identity:

```sh
FDROID_KEYSTORE_FILE=/path/to/release.keystore \
FDROID_KEY_ALIAS=akuvox-wear \
FDROID_KEYSTORE_PASSWORD=... \
FDROID_KEY_PASSWORD=... \
FDROID_APK_DIR=/path/to/release-apks \
./scripts/fdroid/build-repo.sh
```

The generated repository lands in `target/fdroid`.

## Non-free components

This is a self-hosted binary repository, not an application submitted to the
official F-Droid.org repository. The phone APK contains proprietary Akuvox
SmartPlus code and Google Play services, and door access depends on Akuvox's
proprietary cloud. The listing declares `NonFreeDep` and `NonFreeNet` and makes
these limitations explicit to users.
