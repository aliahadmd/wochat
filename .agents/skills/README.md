# Agent skills

Skill definitions used by coding agents working in this repository. Canonical
content lives here; `.claude/skills/` holds symlinks into it, so one copy serves
every agent rather than each keeping its own.

## Where these came from

Most are from Google's [android/skills](https://github.com/android/skills)
(Apache-2.0), vendored at commit `6685cac` (2026-08-17). Vendored rather than
fetched on demand so the guidance is pinned and reviewable in git — a skill is
instructions an agent will act on, and those should change only in a commit
somebody can read.

`gemma-dev`, `perfetto-sql`, `perfetto-trace-analysis` and
`android-jetpack-compose` predate that and are not in the upstream repository;
they are left as they are.

## What was deliberately left out

Not everything upstream applies here. Skipped, with reasons that should be
re-checked if the app changes:

- `play/*` — 1.3 MB of Play Store billing, Engage SDK and policy guidance.
  Offmind ships through GitHub releases and sells nothing.
- `camera/camerax` — no camera use.
- `identity/verified-email` — no accounts; the app is deliberately offline.
- `media/media3-cast-integration` — casting via Media3. Voice uses `AudioRecord`
  and `AudioTrack` directly, and there is nothing to cast.

## Updating

    git clone --depth 1 https://github.com/android/skills.git /tmp/android-skills
    cp -R /tmp/android-skills/<category>/<skill> .agents/skills/<skill>

Then confirm `.claude/skills/<skill>` still points at it.
