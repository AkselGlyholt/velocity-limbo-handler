# 🌐 VelocityLimboHandler | Fallback Server Handler for Velocity  
Effortlessly manage player reconnections with a structured queue system!  

[![Active Development](https://img.shields.io/badge/Maintenance%20Level-Actively%20Developed-brightgreen.svg)](https://gist.github.com/cheerfulstoic/d107229326a01ff0f333a1d3476e068d)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-brightgreen)](https://github.com/AkselGlyholt/velocity-limbo-handler/blob/main/LICENSE)
[![Download](https://img.shields.io/badge/Download-latest%20release-brightgreen)](https://github.com/AkselGlyholt/velocity-limbo-handler/releases/latest)  

## About
VelocityLimboHandler is a simple yet powerful **Velocity proxy plugin** that manages player fallback handling when a server becomes unavailable. Instead of leaving players stranded in Limbo, it systematically reconnects them to their last known server or a designated lobby.

- **Queue-based Reconnection** – Ensures players are reconnected in an orderly manner.
- **Customizable** – Configure the limbo and fallback settings to fit your needs.
- **Supports Any Limbo Server** – Optimized for [LOOHP's Limbo](https://github.com/LOOHP/Limbo), but adaptable to others.

---

## Installation
1. [Download the latest release](https://github.com/AkselGlyholt/velocity-limbo-handler/releases/latest).
2. Place it in the `plugins` folder of your **Velocity proxy**.
3. Restart the proxy to generate the configuration files.
4. Configure the **proxy, Limbo server, and plugin** as needed.
5. Restart the proxy once again.

---

## Configuration
After installation, configure the plugin via:
`plugins/velocity-limbo-handler/config.yml`

### **Required Settings:**
* **`limbo-name`** (default: `Limbo`) – Name of the Limbo server in Velocity.  
* **`direct-connect-server`** (default: `default`) – The server where direct connections should be sent.  

### **Optional Settings:**
* **`task-interval`** (default: `3`) – Time (seconds) between connection attempts.  
* **`queue-notify-interval`** (default: `30`) – How often players are notified of their queue position.  
* **`disabled-commands`** (default: `["server", "lobby", "hub"]` – Commands that won't work inside the limbo server.

* `messages.yml` is now editable for all in-plugin feedback texts
* Maintenance integration is automatic if you're using a compatiable plugin. (Check Wiki)
* Disable commands inside Limbo to prevent exploitation via `/server`


---

## Setting Up the Proxy & Limbo  
VelocityLimboHandler does not provide a built-in fallback system, so you'll need to configure your Velocity proxy to send players to Limbo when their target server is unavailable.

**Example Proxy Configuration (try orders):**
```toml
[servers]
  try = ["default", "limbo"]
```

**Example Using Forced Hosts:**
```toml
[forced-hosts]
  "build.example.com" = ["build", "limbo"]
  "pvp.example.com" = ["pvp", "limbo"]
```

---

## Todo
* [ ] Priority Queue with permissions
* [x] Per server queue
* [x] Maintenance plugin integration

---

## License  
This plugin is open-source under the **GPLv3 License**.

Need help or have suggestions? Feel free to open an issue on GitHub! 🚀
