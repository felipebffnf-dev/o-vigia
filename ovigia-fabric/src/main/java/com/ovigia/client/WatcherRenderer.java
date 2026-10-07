package com.ovigia.client;

import com.ovigia.entity.WatcherEntity;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

public class WatcherRenderer extends MobEntityRenderer<WatcherEntity, PlayerEntityModel<WatcherEntity>> {
	private static final Identifier TEXTURE = new Identifier("ovigia", "textures/entity/watcher.png");

	public WatcherRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
		// Mostra a armadura e o item na mão copiados do jogador.
		this.addFeature(new ArmorFeatureRenderer<>(this,
				new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
				new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
				ctx.getModelManager()));
		this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
	}

	@Override
	public void render(WatcherEntity entity, float yaw, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light) {
		// Postura de agachado igual à do jogador.
		this.getModel().sneaking = entity.isInSneakingPose();
		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
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
