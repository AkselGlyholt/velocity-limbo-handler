package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class KickListener {

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        List<String> excludedReasons = VelocityLimboHandler.getConfigManager().getExcludedKickReasons();
        if (excludedReasons.isEmpty()) {
            return;
        }

        Optional<Component> reasonOptional = event.getServerKickReason();
        if (reasonOptional.isEmpty()) {
            return;
        }

        String plainReason = PlainTextComponentSerializer.plainText().serialize(reasonOptional.get())
                .toLowerCase(Locale.ROOT);

        for (String excludedReason : excludedReasons) {
            if (!plainReason.contains(excludedReason)) {
                continue;
            }

            Utility.logDebug(() -> String.format(
                    "%s was kicked from %s with an excluded reason - disconnecting instead of routing to Limbo",
                    event.getPlayer().getUsername(), event.getServer().getServerInfo().getName()));
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reasonOptional.get()));
            return;
        }
    }
}
