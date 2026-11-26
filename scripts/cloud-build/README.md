# Cloud builds of the Shield APK

The Shield APK (`fullDebug` or `fullBenchmark`, arm64-v8a) builds on a GitHub-hosted runner of the
private repo `tinof/NuvioTV-Builds`, so the Mac no longer runs Gradle. The build repo holds no code: its
workflow fetches this public fork at one commit and runs that commit's `ci_build.sh`.

Hard rule: the APK carries the DTS-HD writer (`dcaxll`), so no such binary may reach this public repo,
its artifacts, releases or logs. The job runs only when the repository is `tinof/NuvioTV-Builds` and
private, the artifact lives one day, and the FFmpeg asset holds plain FFmpeg n7.0.2 libraries (the
writer compiles from `ffmpeg-decoder-downmix/src/main/jni` of the checked-out commit).

## Files

- `shield-build.yml`: the build repo's workflow. This copy is the source; it sits outside
  `.github/workflows`, so it never runs here. `scripts/shield_cloud_setup.py workflow` pushes the
  committed version (`HEAD:scripts/cloud-build/shield-build.yml`) to the build repo.
- `ci_build.sh`: the build recipe. It strips the Mac's paths from the `local.properties` secret, masks
  the credentials, downloads and checks the pinned FFmpeg asset, installs the NDK and CMake the build
  files name, builds `libnuvio_rpu.so` with `scripts/build_rpu_builder.sh`, runs Gradle with the
  arguments of `scripts/shield_build_install.sh`, then `sync.py protected-apk --stamp` and
  `scripts/verify_native_apk.py`. It stages the APK, the rpu stamp and `build-info.json`.
- `ffmpeg-asset.json`: the pin of the FFmpeg asset (release tag, asset name, sha256, arm64-v8a library
  hashes). `scripts/shield_cloud_setup.py ffmpeg` writes it; commit it. Without it every build stops
  with a message that names that command.

## A build by hand

The commit must be pushed to `origin`; the runner fetches it anonymously from GitHub.

```sh
commit="$(git rev-parse HEAD)"
id="manual-$(date +%Y%m%d%H%M%S)"
gh workflow run shield-build.yml -R tinof/NuvioTV-Builds --ref main -f commit="$commit" -f request_id="$id" -f variant=Debug
gh run list -R tinof/NuvioTV-Builds -w shield-build.yml -L 3   # the run title names "$id"
gh run watch <run-id> -R tinof/NuvioTV-Builds
gh run download <run-id> -R tinof/NuvioTV-Builds -n "shield-apk-$id" -D ".local/cloud-builds/$id"
```

Other inputs: `-f variant=Benchmark`; `-f debuggable=true -f suffix=.shielddbg` (Debug only);
`-f version_code=… -f version_name=…`. The defaults are those of `scripts/shield_build_install.sh`.
`build-info.json` names the commit, the request id, the inputs, the APK's sha256, package and
certificate, the FFmpeg asset, the property key names (never values), the toolchain and the minutes.

## Limits

- Runner: 2 vCPU, 8 GB RAM, about 14 GB free disk. Gradle gets `-Xmx3g` with the Kotlin compiler in
  process; the workflow frees disk when less than 20 GB is free. Timeout 90 minutes.
- Caches hold downloads only: Gradle dependencies (setup-java), Cargo crates keyed on `Cargo.lock`, and
  cargo-ndk. No build output is cached.
- The FFmpeg asset goes stale when the FFmpeg tag, the configure flags in
  `ffmpeg-decoder-downmix/src/main/jni/build_ffmpeg.sh` or the NDK change. Rebuild the libraries on the
  Mac, run `scripts/shield_cloud_setup.py ffmpeg` again (a new sha gets a new tag) and commit the pin.
