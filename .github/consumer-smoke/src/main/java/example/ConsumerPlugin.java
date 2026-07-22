package example;

import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.VelocityLimboApi;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

@Plugin(id = "vlh-consumer-smoke", name = "VLH Consumer Smoke", version = "1.0.0",
        dependencies = @Dependency(id = "velocity-limbo-handler"))
public final class ConsumerPlugin {
    private final ProxyServer proxy;
    private LimboController controller;

    @Inject
    public ConsumerPlugin(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        controller = VelocityLimboApi.get(proxy).controllerFor(this);
    }

    public void pause(String serverName) {
        if (controller == null) {
            throw new IllegalStateException("VLH controller is not available before proxy initialization");
        }
        controller.holdServer(serverName, new HoldRequest("consumer smoke test"));
    }
}
