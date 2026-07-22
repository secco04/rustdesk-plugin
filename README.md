# RustDesk Plugin for LobiShell (Unofficial)

This is a **standalone Android project** — open `rustdesk-plugin/` as its own Android Studio
project. It is NOT a module of the main LobiShell app.

Not affiliated with, endorsed by, or officially connected to RustDesk / Purslane Ltd
(rustdesk.com) in any way. "RustDesk" is used here only to accurately describe protocol
compatibility.

## What this is

A remote-desktop plugin exposing RustDesk connectivity to LobiShell's `RUSTDESK` connection type
via a single AIDL bound service. **applicationId:** `de.lobianco.saftssh.rustdesk`.

This plugin **vendors RustDesk's own open-source client core**
([github.com/rustdesk/rustdesk](https://github.com/rustdesk/rustdesk)), licensed AGPL-3.0. As a
combined work incorporating AGPL-3.0-licensed code, this program is likewise licensed under the
GNU Affero General Public License — see [`LICENSE`](LICENSE) for the full text and copyright
notice.

Source for this plugin itself is this repository, kept public per the AGPL's network-use source
disclosure requirement.

## Why a separate plugin, not part of the main app

The main LobiShell app is closed-source and paid; AGPL-3.0 code cannot be bundled into it
directly. This plugin is its own free/open-source app that the main app binds to at runtime — the
same pattern used by LobiShell's other protocol plugins (Mosh, the Linux userland, RDP/VNC).
