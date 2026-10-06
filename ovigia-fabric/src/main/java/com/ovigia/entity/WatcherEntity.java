package com.ovigia.entity;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * O Vigia:
 *  - copia a skin do jogador que ele escolhe como alvo;
 *  - fica PARADO enquanto alguém olha pra ele e avança quando você desvia o olhar;
 *  - persegue sem parar, e às vezes surge atrás de você (quando você não está olhando);
 *  - manda mensagens falsas no chat e toca sons atrás de você;
 *  - some ao amanhecer.
 */
public class WatcherEntity extends HostileEntity {

	private static final TrackedData<Optional<UUID>> MIMIC =
			DataTracker.registerData(WatcherEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

	private static final SoundEvent[] SCARE_SOUNDS = {
			SoundEvents.ENTITY_WARDEN_NEARBY_CLOSER,
			SoundEvents.ENTITY_WARDEN_HEARTBEAT,
			SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
			SoundEvents.BLOCK_GRAVEL_STEP,
			SoundEvents.ENTITY_ENDERMAN_STARE,
			SoundEvents.ENTITY_GHAST_SCREAM
	};

	public WatcherEntity(EntityType<? extends HostileEntity> type, World world) {
		super(type, world);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 60.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.36)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 7.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(MIMIC, Optional.empty());
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		getMimicUuid().ifPresent(id -> nbt.putUuid("MimicUuid", id));
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.containsUuid("MimicUuid")) {
			this.dataTracker.set(MIMIC, Optional.of(nbt.getUuid("MimicUuid")));
		}
	}

	/** UUID do jogador cuja skin está sendo copiada (vazio = aparência original). */
	public Optional<UUID> getMimicUuid() {
		return this.dataTracker.get(MIMIC);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(1, new SwimGoal(this));
		this.goalSelector.add(2, new FreezeWhenWatchedGoal(this));
		this.goalSelector.add(3, new MeleeAttackGoal(this, 1.3, true));
		this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.6));
		this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 32.0f));
		this.goalSelector.add(8, new LookAroundGoal(this));
		this.targetSelector.add(1, new RevengeGoal(this));
		// Sem exigir linha de visão: ele te sente de longe.
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, 10, false, false, null));
	}

	@Override
	public void setTarget(LivingEntity target) {
		LivingEntity old = this.getTarget();
		super.setTarget(target);
		if (target instanceof PlayerEntity player && old != target && !this.getWorld().isClient) {
			// Ao escolher a vítima, assume a skin dela.
			this.dataTracker.set(MIMIC, Optional.of(player.getUuid()));
			if (player instanceof ServerPlayerEntity sp) {
				sp.sendMessage(Text.translatable("commands.message.display.incoming",
						Text.literal("Vigia"), Text.literal("achei você, " + sp.getEntityName() + ".")
				).formatted(Formatting.GRAY, Formatting.ITALIC), false);
				playSoundTo(sp, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f, sp.getPos());
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) return;

		// A cada segundo: aura de escuridão, sustos e mensagens falsas.
		if (this.age % 20 == 0) {
			for (PlayerEntity p : this.getWorld().getEntitiesByClass(PlayerEntity.class,
					this.getBoundingBox().expand(64.0), pl -> !pl.isSpectator())) {
				double dist = this.distanceTo(p);
				if (dist <= 12.0) {
					p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 160, 0, false, false, true));
				}
				if (p instanceof ServerPlayerEntity sp) {
					if (this.random.nextInt(20) == 0) sendFakeMessage(sp, dist);
					if (this.random.nextInt(14) == 0) soundBehind(sp);
				}
			}
		}

		// Às vezes aparece atrás de quem ele persegue (só se ninguém estiver olhando).
		LivingEntity target = this.getTarget();
		if (target != null && this.age % 80 == 0 && this.random.nextInt(3) == 0
				&& this.distanceTo(target) > 20.0 && !this.isBeingWatched()) {
			tryAppearBehind(target);
		}

		// Some ao amanhecer.
		if (this.age > 200 && this.getWorld().isDay()) {
			this.discard();
		}
	}

	// ---------- Mensagens falsas ----------

	private void sendFakeMessage(ServerPlayerEntity sp, double dist) {
		String me = sp.getEntityName();
		if (dist < 10.0) {
			// Bem perto: mensagens mais diretas.
			switch (this.random.nextInt(4)) {
				case 0 -> chat(sp, "Vigia", "estou logo atrás de você.");
				case 1 -> whisper(sp, "não se vire.");
				case 2 -> chat(sp, me, "ele está aqui");
				default -> chat(sp, "Vigia", "agora.");
			}
			return;
		}
		switch (this.random.nextInt(10)) {
			case 0 -> chat(sp, me, "olha pra trás.");
			case 1 -> chat(sp, "Vigia", "eu não preciso piscar.");
			case 2 -> chat(sp, "Vigia", me + ", você está sozinho aqui.");
			case 3 -> chat(sp, "Vigia", "não pare de me olhar.");
			case 4 -> chat(sp, "Vigia", "eu já estou usando a sua pele.");
			case 5 -> whisper(sp, "atrás de você.");
			case 6 -> sp.sendMessage(Text.translatable("multiplayer.player.joined", "Herobrine")
					.formatted(Formatting.YELLOW), false);
			case 7 -> sp.sendMessage(Text.translatable("multiplayer.player.left", me)
					.formatted(Formatting.YELLOW), false);
			case 8 -> chat(sp, "Lucas_BR", "gente saiam do mundo agora");
			default -> sp.sendMessage(Text.literal("Vigia entrou no seu mundo.")
					.formatted(Formatting.DARK_RED), false);
		}
	}

	private static void chat(ServerPlayerEntity sp, String from, String msg) {
		sp.sendMessage(Text.translatable("chat.type.text", Text.literal(from), Text.literal(msg)), false);
	}

	private static void whisper(ServerPlayerEntity sp, String msg) {
		sp.sendMessage(Text.translatable("commands.message.display.incoming",
				Text.literal("Vigia"), Text.literal(msg)).formatted(Formatting.GRAY, Formatting.ITALIC), false);
	}

	// ---------- Sons ----------

	/** Toca um som só para o jogador, vindo de trás dele. */
	private void soundBehind(ServerPlayerEntity sp) {
		Vec3d back = sp.getRotationVec(1.0f).multiply(1, 0, 1).normalize().multiply(-5);
		Vec3d pos = sp.getPos().add(back);
		SoundEvent s = SCARE_SOUNDS[this.random.nextInt(SCARE_SOUNDS.length)];
		playSoundTo(sp, s, 0.8f, 0.6f + this.random.nextFloat() * 0.4f, pos);
	}

	private void playSoundTo(ServerPlayerEntity sp, SoundEvent s, float vol, float pitch, Vec3d at) {
		sp.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(s), SoundCategory.AMBIENT,
				at.x, at.y, at.z, vol, pitch, this.random.nextLong()));
	}

	// ---------- Aparecer atrás ----------

	private void tryAppearBehind(LivingEntity target) {
		Vec3d back = target.getRotationVec(1.0f).multiply(1, 0, 1).normalize().multiply(-14);
		if (back.lengthSquared() < 1.0) return;
		BlockPos base = BlockPos.ofFloored(target.getX() + back.x, target.getY(), target.getZ() + back.z);
		BlockPos spot = findStandSpot(base);
		if (spot == null) return;

		Vec3d old = this.getPos();
		float oldYaw = this.getYaw();
		this.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, oldYaw, 0.0f);
		if (!this.getWorld().isSpaceEmpty(this) || this.isBeingWatched()) {
			this.refreshPositionAndAngles(old.x, old.y, old.z, oldYaw, 0.0f);
		} else {
			this.getNavigation().stop();
		}
	}

	private BlockPos findStandSpot(BlockPos base) {
		World w = this.getWorld();
		for (int dy = 3; dy >= -6; dy--) {
			BlockPos p = base.add(0, dy, 0);
			if (w.getBlockState(p).isAir() && w.getBlockState(p.up()).isAir()
					&& w.getBlockState(p.down()).isSolidBlock(w, p.down())) {
				return p;
			}
		}
		return null;
	}

	// ---------- Observado? ----------

	/** Algum jogador (não espectador) está com ele no centro da mira? */
	public boolean isBeingWatched() {
		for (PlayerEntity p : this.getWorld().getEntitiesByClass(PlayerEntity.class,
				this.getBoundingBox().expand(64.0), pl -> !pl.isSpectator())) {
			Vec3d look = p.getRotationVec(1.0f).normalize();
			Vec3d to = new Vec3d(this.getX() - p.getX(), this.getEyeY() - p.getEyeY(), this.getZ() - p.getZ());
			double dist = to.length();
			if (dist < 0.5) continue;
			double dot = look.dotProduct(to.normalize());
			double threshold = Math.max(0.85, 1.0 - 0.4 / dist);
			if (dot > threshold && p.canSee(this)) return true;
		}
		return false;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.ENTITY_ENDERMAN_STARE;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_PHANTOM_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_PHANTOM_DEATH;
	}

	@Override
	public float getSoundPitch() {
		return 0.5f;
	}

	/** Trava movimento enquanto o Vigia é observado. */
	static class FreezeWhenWatchedGoal extends Goal {
		private final WatcherEntity mob;

		FreezeWhenWatchedGoal(WatcherEntity mob) {
			this.mob = mob;
			this.setControls(EnumSet.of(Control.MOVE, Control.JUMP));
		}

		@Override
		public boolean canStart() {
			return this.mob.getTarget() != null && this.mob.isBeingWatched();
		}

		@Override
		public boolean shouldContinue() {
			return this.canStart();
		}

		@Override
		public void start() {
			this.mob.getNavigation().stop();
		}
	}
}
