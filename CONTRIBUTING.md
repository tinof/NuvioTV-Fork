# Contributing to Nuvio Arc

This repo, [tinof/NuvioTV-Fork](https://github.com/tinof/NuvioTV-Fork), is a fork of Nuvio Arc
([ysosrs123/NuvioTV-Fork](https://github.com/ysosrs123/NuvioTV-Fork)). Its `perf/shield-pass` branch holds a
performance pass for the NVIDIA SHIELD TV Pro. It is a personal build: Issues are turned off and there is no
support channel. Please don't report problems from this build to Nuvio Arc or to Nuvio either. The guide below
comes from Nuvio Arc and is about Nuvio Arc itself.

Thanks for wanting to help. Nuvio Arc is my hobby fork of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), a test bench
for new ideas, and it's a one-person project. That shapes how contributions work here.

## Bugs and ideas

Use Nuvio Arc's [Issues](https://github.com/ysosrs123/NuvioTV-Fork/issues) and pick **Bug report** or **Idea**. I read
every issue, but it's a hobby, so I can't promise a reply or a fix.

For a bug, the most useful things are your box, the Nuvio Arc version (Settings > About), what you were playing and the
source type, and what happened. For playback problems, a photo of Settings > Advanced > **Source & hardware** taken right
after it happened helps a lot.

Please don't report problems from Nuvio Arc to the official Nuvio GitHub or Discord. They can't help with changes they
didn't make.

## Pull requests

Small pull requests are welcome:

- bug fixes, with a short note on how to reproduce the bug and how you tested the fix
- translation fixes

For anything bigger, a new feature or a change to how something looks or works, please open an **Idea** issue first so
we can talk about it before you put the time in.

A few things to know:

- Review can take a while, and not every pull request will be merged.
- In this fork, pull requests go against `main`; the "PR Full Debug Build" workflow
  (`.github/workflows/pr-full-debug-build.yml`) then builds and tests them on its own.
- Keep each pull request to one change, and include before and after screenshots for anything you can see.
- If your change would suit every Nuvio user, it's probably better sent to the
  [official app](https://github.com/NuvioMedia/NuvioTV) instead, so everyone gets it.
- Nuvio Arc follows the official app closely and merges its releases regularly, so changes to shared code may need
  updating later.

## Licence

Nuvio Arc is GPL-3.0 like NuvioTV. By sending a pull request you agree that your change is released under the same
licence.
