package example;

import com.akselglyholt.velocitylimbohandler.api.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.VelocityLimboApi;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

@Plugin(id = "vlh-consumer-smoke", name = "VLH Consumer Smoke", version = "1.0.0",
        dependencies = @Dependency(id = "velocity-limbo-handler"))
public final class ConsumerPlugin {
    private final LimboController controller;

    public ConsumerPlugin(ProxyServer proxy) {
        controller = VelocityLimboApi.get(proxy).controllerFor(this);
    }

    public void pause(String serverName) {
        controller.holdServer(serverName, new HoldRequest("consumer smoke test"));
    }
}
