package com.ovigia.client;

import com.ovigia.entity.WatcherEntity;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

public class WatcherRenderer extends MobEntityRenderer<WatcherEntity, PlayerEntityModel<WatcherEntity>> {
	private static final Identifier TEXTURE = new Identifier("ovigia", "textures/entity/watcher.png");
	private static final Identifier EYES = new Identifier("ovigia", "textures/entity/watcher_eyes.png");

	public WatcherRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		// Olhos brancos brilhantes por cima da skin copiada: o detalhe que entrega.
		this.addFeature(new EyesFeatureRenderer<WatcherEntity, PlayerEntityModel<WatcherEntity>>(this) {
			@Override
			public RenderLayer getEyesTexture() {
				return RenderLayer.getEyes(EYES);
			}
		});
	}

	@Override
	public Identifier getTexture(WatcherEntity entity) {
		UUID id = entity.getMimicUuid().orElse(null);
		if (id == null) return TEXTURE;
		ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			PlayerListEntry entry = handler.getPlayerListEntry(id);
			if (entry != null) return entry.getSkinTexture();
		}
		return DefaultSkinHelper.getTexture(id);
	}

	@Override
	protected void scale(WatcherEntity entity, MatrixStack matrices, float amount) {
		// Um pouco maior que o jogador: quase igual, mas errado.
		matrices.scale(1.1f, 1.1f, 1.1f);
	}
}
