# ⚡ VelocityLimboHandler

🌀 A smart **limbo & queue handler** for [Velocity](https://velocitypowered.com)  
Keeps your players connected, calm, and coming back even when backend servers crash.

---

![GitHub release](https://img.shields.io/github/v/release/akselglyholt/velocity-limbo-handler?style=for-the-badge)
![GitHub downloads](https://img.shields.io/github/downloads/akselglyholt/velocity-limbo-handler/total?style=for-the-badge\&color=blue)
![License](https://img.shields.io/github/license/akselglyholt/velocity-limbo-handler?style=for-the-badge\&color=green)
![Modrinth](https://img.shields.io/modrinth/dt/velocity-limbo-handler?style=for-the-badge\&logo=modrinth\&label=Modrinth%20Downloads)

---

## ✨ Why VelocityLimboHandler?

* 🚦 **Per-Server Smart Queue** – players only wait for the server they were on, not behind others stuck elsewhere
* 📢 **Queue Updates** – automatic position notifications keep players informed
* 🔒 **Protected Limbo** – blocks unwanted commands to prevent bypasses
* 🛠️ **Maintenance Support** – respects whitelist & bypass permissions
* 🤝 **Authentication Ready** – seamless integration with authentication plugins such as LibreLogin or nLogin

👉 Full setup & advanced features in the [Wiki](../../wiki).

Made to work with [LOOHP's Limbo](https://github.com/LOOHP/Limbo) server, but any other server is also fine.

---

## 🧩 Compatibility

* 🖥️ **Proxy:** Velocity (all recent versions)
* 🎮 **MC Versions:** All versions
* 📜 **License:** GPL-3.0

---

## 🚀 Quick Install

1. Grab the latest `.jar` from [Releases](https://github.com/akselglyholt/velocity-limbo-handler/releases).
2. Drop it into your Velocity `plugins` folder.
3. Restart the proxy (config files will generate).
4. Adjust `config.yml` + `messages.yml` to your liking.
5. Restart once more — done! 🎉

---

## ⚙️ Config Highlights

```yaml
# config.yml
limbo-name: "limbo"                  # The name of your limbo server
direct-connect-server: "lobby"       # Where to send direct connections
task-interval: 3000                  # Queue processing interval (milliseconds)
reconnect-batch-size: 8              # Maximum backend queues checked per processing run
queue-notify-interval: 30            # How often to tell players their position
queue-notify-batch-size: 64          # Maximum due notifications sent per dispatcher tick
disabled-commands: ["server","hub"]  # Commands blocked in limbo
```

The batch limits smooth work across scheduler ticks. Increase them for faster catch-up on very large
networks, or lower them to cap short CPU bursts on constrained proxies.

👉 Messages can be tweaked in `messages.yml` so your players see exactly what you want.

---

## 🖧 Proxy Setup Example

```toml
[servers]
lobby = "127.0.0.1:25566"
limbo = "127.0.0.1:25569"
# Keep normal logins on Lobby. VLH intercepts a loss of the current backend itself.
try = ["lobby", "limbo"]

[advanced]
# Required for VLH to recover players after an ungraceful backend disconnect.
failover-on-unexpected-server-disconnect = true

[forced-hosts]
"pvp.example.com" = ["pvp", "limbo"]
"build.example.com" = ["build", "limbo"]
```

---

## 🛡️ Permissions & Integrations

* Players get queued automatically, no setup required.
* Commands blocked per config.
* LibreLogin support ensures login/auth flow isn’t broken.
* Maintenance plugins are respected (bypass logic included).

---

## 🔌 Developer API v1

Starting with VLH 2.0.0, an in-process Java 21 API is available to Velocity plugins. Add
`velocity-limbo-handler-api` from JitPack as `compileOnly`/`provided`, use the same release tag as the
installed plugin, and declare `velocity-limbo-handler` as a required Velocity dependency. Never
shade or relocate the API.

Create your owner-scoped controller during or after `ProxyInitializeEvent`:

```java
VelocityLimboApi api = VelocityLimboApi.get(proxyServer);
LimboController limbo = api.controllerFor(this);
```

The [Developer API v1 wiki guide](../../wiki/Developer-API-v1) contains dependency snippets,
complete hold and release examples, result semantics, snapshots, event timing, threading rules, and
compatibility guidance.

---

## 🤝 Contributing

Pull requests are welcome! Just follow the style already in place.
Check `CONTRIBUTING.md` for details.

---

## 📖 License

Licensed under **GPL-3.0** — free to use, modify, and share under the same license.
