# 🧲 Lodestone Chunk Loaders

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Turn any Lodestone into a powerful chunk loader.**

Lodestone Chunk Loaders is a server-side Fabric mod that transforms vanilla Lodestonesinto persistent chunk loaders. Players sacrifice **Recovery Compasses** to charge a Lodestone with offline loading time. Once charged, the chunk stays loaded even while the owner is offline — but the clock is always ticking.

---

## ❓ Why This Mod?

Vanilla Minecraft has a couple of workarounds for keeping chunks loaded, but neither holds up on a real server:

- **Ender Pearl stasis chambers** — The ender pearl is attached to the player entity. When that player logs out, the pearl despawns and the chamber breaks.
- **Portal-based chunk loaders** — These rely on a nether portal staying in a loaded chunk *before* they can load other chunks. On a server restart, no chunks are pre-loaded, so the whole chain collapses and never bootstraps itself.

This mod gives you a **reliable, intentional, and server-friendly** alternative: charge a Lodestone and it stays loaded — through logouts, through restarts, until the time runs out.

---

## ✨ Current Features

- **Lodestone-Based Chunk Loading** — Right-click any Lodestone with a **Recovery Compass** to charge it. Each compass adds **12 hours** of real-time offline chunk loading.
- **Owner-Aware** — Chunks are **only drained** when the owning player is **offline**. When the owner logs in, the clock pauses automatically.
- **Live Compass Lore** — Link a regular Compass to the Lodestone and it will display a live countdown (`Chunk Anchor (PlayerName): 11h 42m remaining`) that updates every 2 seconds in your inventory.
- **Multi-Charge Support** — Stack multiple Recovery Compasses on the same Lodestone to extend loading time. 
- **Auto-Cleanup on Expiry** — When a Lodestone's time runs out, force-loading is automatically revoked and the anchor is cleaned up.
- **Break Protection** — Destroying a Lodestone removes its chunk anchor and revokes force-loading gracefully, preventing ghost tickets.
- **Server-Side Only** — No client mod required. Vanilla clients connect without needing to install anything.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

> *This mod was developed with AI assistance. If you encounter bugs or unexpected behavior, please open an issue on GitHub with your server logs and a description of what happened.*
