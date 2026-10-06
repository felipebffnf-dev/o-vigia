package com.ovigia;

import com.ovigia.entity.WatcherEntity;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/** Faz o Vigia mais próximo responder quando um jogador fala no chat. */
public class VigiaChat {
	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			WatcherEntity nearest = null;
			double best = Double.MAX_VALUE;
			for (WatcherEntity w : sender.getServerWorld().getEntitiesByClass(
					WatcherEntity.class, sender.getBoundingBox().expand(96.0), e -> true)) {
				double d = w.squaredDistanceTo(sender);
				if (d < best) {
					best = d;
					nearest = w;
				}
			}
			if (nearest != null) {
				nearest.queueReply(sender, message.getSignedContent());
			}
		});
	}
}
