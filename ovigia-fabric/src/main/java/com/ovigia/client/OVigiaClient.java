package com.ovigia.client;

import com.ovigia.OVigia;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class OVigiaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(OVigia.WATCHER, WatcherRenderer::new);
	}
}
