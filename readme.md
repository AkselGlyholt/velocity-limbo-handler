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
* 🎮 **MC Versions:** 1.8 → 1.21+
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
default = "lobby"
limbo = "limbo"

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

VLH 1.9.0 exposes a Java 21 API for moving players into limbo atomically, applying owner-scoped
player or server holds, retargeting managed players, reading immutable snapshots, and observing
lifecycle events. The API is in-memory and intended for plugins running on the same Velocity proxy.

Consumer plugins must declare VLH as a required Velocity dependency:

```java
@Plugin(
    id = "my-plugin",
    dependencies = @Dependency(id = "velocity-limbo-handler")
)
public final class MyPlugin { }
```

Use the API artifact as `compileOnly`/`provided`. Do **not** shade or relocate it: the implementation
and API classes are already embedded in the installed VLH plugin JAR.

### Gradle

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.AkselGlyholt.velocity-limbo-handler:velocity-limbo-handler-api:v1.9.0")
}
```

### Maven

```xml
<repository>
  <id>jitpack.io</id>
  <url>https://jitpack.io</url>
</repository>

<dependency>
  <groupId>com.github.AkselGlyholt.velocity-limbo-handler</groupId>
  <artifactId>velocity-limbo-handler-api</artifactId>
  <version>v1.9.0</version>
  <scope>provided</scope>
</dependency>
```

### Basic use

```java
VelocityLimboApi api = VelocityLimboApi.get(proxyServer);
LimboController limbo = api.controllerFor(this); // validated loaded plugin instance

HoldResult deployHold = limbo.holdServer("survival", new HoldRequest("rolling deploy"));

limbo.enterLimbo(player,
        EnterRequest.currentServer().withInitialHold(new HoldRequest("awaiting profile")))
    .thenAccept(result -> logger.info("Limbo entry: " + result));
```

Each controller can release only its own opaque leases. The first player hold removes that player
from the queue; releasing the final hold reevaluates permissions and appends them to the back of the
appropriate `BYPASS`, `PRIORITY`, or `NORMAL` tier. Server holds preserve queue positions and block
new attempts for everyone. Event objects are immutable and non-cancellable; query snapshots when
you need current queue positions.

---

## 🤝 Contributing

Pull requests are welcome! Just follow the style already in place.
Check `CONTRIBUTING.md` for details.

---

## 📖 License

Licensed under **GPL-3.0** — free to use, modify, and share under the same license.
