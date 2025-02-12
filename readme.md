# VelocityLimboHandler
A simple plugin for handling your fallback server.

This is a velocity plugin for standalone Limbo servers under your network.
It handles automatic reconnecting to the players previous server or to the lobby server.

It handles reconnection with a queue, so players return in a systematic order, so no players get stuck in the limbo server forever

I developed this plugin to work with [Limbo by LOOHP](https://github.com/LOOHP/Limbo), but it should work with any Limbo server. But you might have to set up your limbo server differently depending on how it handles players and reconnection.


## Installation
1. Download the [latest release](https://github.com/AkselGlyholt/velocity-limbo-handler/releases/latest).
2. Upload it into the plugins directory of your instance of Velocity.
3. Restart the proxy to load the plugin and create the configuration files.
4. Configure the proxy, limbo server and plugin as outlined below.
5. Restart the proxy once again

**NOTE:** It's very much recommend you disable `/server` command inside the limbo server, with your permissions manager. I recommend [LuckPerms](https://luckperms.net/) for this, because of their [context](https://luckperms.net/wiki/Usage#context) function

## Configuration
**NOTE:** It's very much recommend you disable `/server` command inside the limbo server, with your permissions manager. I recommend [LuckPerms](https://luckperms.net/) for this, because of their [context](https://luckperms.net/wiki/Usage#context) function

### Proxy/Limbo
VelocityLimboHandler does not implement a Fallback server so you will need to configure one yourself and add it to your velocity network.
I recommend using [Limbo by LOOHP](https://github.com/LOOHP/Limbo), as I developed this plugin with that server in mind, but any standalone server should work fine!

After you've set up the server and added it to your velocity network you will need to add it to your connection order, so that if the player couldn't connect to the first server they will be sent to the limbo server

Example using the regular try orders:
```toml
[servers]
  ...
  try = ["default", "limbo"]
```

Example of using forced hosts
```toml
[forced-hosts]
  "build.example.com" = ["build", "limbo"]
  "pvp.example.com" = ["pvp", "limbo"]
```

### Plugin
After following the installation guide, you can configure the plugin file
`plugins/velocity-limbo-handler/config.yml`

#### Required
##### limbo-name
* **Default:** Limbo
* **Description:** Name of the limbo server you want this plugin to target

##### direct-connect-server
* **Default:** default
* **Description:** Name of the server players should be connected to if they directly connect to the server and were sent to the limbo server

#### Optional
##### task-interval
* **Default:** 3
* **Description:** How often the plugin should check if it's possible to connect to the server again in seconds

##### queue-notify-interval
* **Default:** 30
* **Description:** How often the player should be notified of their position in the queue