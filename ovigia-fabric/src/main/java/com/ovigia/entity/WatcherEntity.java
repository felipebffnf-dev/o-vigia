package com.ovigia.entity;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
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
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * O Vigia:
 *  - copia a skin do jogador que ele escolhe como alvo;
 *  - fica PARADO enquanto alguém olha pra ele e avança quando você desvia o olhar;
 *  - persegue sem parar, e às vezes surge atrás de você (quando você não está olhando);
 *  - manda mensagens falsas no chat e toca sons atrás de você;
 *  - aparece de dia e de noite.
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

	/** Sons quando ele te VÊ pela primeira vez (ou volta a te ver). */
	private static final SoundEvent[] SIGHT_SOUNDS = {
			SoundEvents.ENTITY_ENDERMAN_SCREAM,
			SoundEvents.ENTITY_WARDEN_ANGRY,
			SoundEvents.ENTITY_GHAST_SCREAM,
			SoundEvents.ENTITY_ENDERMAN_STARE
	};

	// Resposta ao chat
	private int replyTicks = 0;
	private UUID replyPlayer = null;
	private String replyText = "";

	// Cópia dos movimentos do jogador
	private boolean prevTargetOnGround = true;
	private boolean prevTargetSwinging = false;

	// Controle de "te vi"
	private int sightCooldown = 0;
	private int ticksSinceSight = 1000;

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
			// Até o nome igual ao do jogador.
			this.setCustomName(Text.literal(player.getEntityName()));
			this.setCustomNameVisible(true);
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

		// Age como um jogador: copia agachar, correr, pular, bater e o equipamento do alvo.
		LivingEntity mimicTarget = this.getTarget();
		if (mimicTarget instanceof PlayerEntity tp) {
			mimicPlayer(tp);
		} else if (this.isSneaking() || this.isSprinting()) {
			this.setSneaking(false);
			this.setSprinting(false);
		}

		// Te viu? Faz um som assustador (só pra você) e avisa na tela.
		if (this.sightCooldown > 0) this.sightCooldown--;
		LivingEntity tgt = this.getTarget();
		boolean sees = tgt instanceof ServerPlayerEntity && this.canSee(tgt) && this.distanceTo(tgt) < 48.0f;
		if (sees) {
			if (this.ticksSinceSight > 60 && this.sightCooldown <= 0) {
				onSpotted((ServerPlayerEntity) tgt);
				this.sightCooldown = 300;
			}
			this.ticksSinceSight = 0;
		} else if (this.ticksSinceSight < 10000) {
			this.ticksSinceSight++;
		}

		// Responde ao chat depois de uma pequena pausa (como se estivesse "digitando").
		if (this.replyTicks > 0 && --this.replyTicks == 0) {
			sendReply();
		}

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

	}

	// ---------- Copiar o jogador ----------

	private void mimicPlayer(PlayerEntity tp) {
		// Agacha quando o jogador agacha; corre quando ele corre (ou quando está longe).
		this.setSneaking(tp.isSneaking());
		boolean chasingFar = this.distanceTo(tp) > 6.0f && !tp.isSneaking();
		this.setSprinting(tp.isSprinting() || chasingFar);

		// Pula quando o jogador pula.
		if (this.prevTargetOnGround && !tp.isOnGround() && this.isOnGround() && tp.fallDistance < 0.5f) {
			this.jump();
		}
		this.prevTargetOnGround = tp.isOnGround();

		// Balança o braço quando o jogador bate ou minera.
		boolean swinging = tp.handSwinging;
		if (swinging && !this.prevTargetSwinging) {
			this.swingHand(Hand.MAIN_HAND);
		}
		this.prevTargetSwinging = swinging;

		// Veste e segura o mesmo que o jogador.
		if (this.age % 20 == 0) {
			copyEquipment(tp);
		}
	}

	private void copyEquipment(PlayerEntity tp) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack src = tp.getEquippedStack(slot);
			ItemStack cur = this.getEquippedStack(slot);
			if (!ItemStack.areEqual(src, cur)) {
				ItemStack copy = src.copy();
				if (!copy.isEmpty()) copy.setCount(1);
				this.equipStack(slot, copy);
			}
			this.setEquipmentDropChance(slot, 0.0f); // não dropa o equipamento
		}
	}

	// ---------- Te viu ----------

	private void onSpotted(ServerPlayerEntity sp) {
		SoundEvent s = SIGHT_SOUNDS[this.random.nextInt(SIGHT_SOUNDS.length)];
		playSoundTo(sp, s, 1.8f, 0.5f + this.random.nextFloat() * 0.3f, this.getPos());
		playSoundTo(sp, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.7f, sp.getPos());
		sp.sendMessage(Text.literal("Ele viu você.").formatted(Formatting.DARK_RED), true);
	}

	// ---------- Responder o chat ----------

	/** Chamado quando um jogador por perto fala no chat. */
	public void queueReply(ServerPlayerEntity sp, String text) {
		if (this.replyTicks > 0) return;
		if (this.random.nextInt(100) < 15) return; // às vezes só... ouve.
		this.replyPlayer = sp.getUuid();
		this.replyText = text == null ? "" : text;
		this.replyTicks = 30 + this.random.nextInt(50);
	}

	private void sendReply() {
		if (this.getServer() == null || this.replyPlayer == null) return;
		ServerPlayerEntity sp = this.getServer().getPlayerManager().getPlayer(this.replyPlayer);
		if (sp == null) return;
		String reply = buildReply(sp.getEntityName(), this.replyText, this.distanceTo(sp));
		int style = this.random.nextInt(10);
		if (style < 7) {
			chat(sp, "Vigia", reply);
		} else if (style < 9) {
			whisper(sp, reply);
		} else {
			chat(sp, sp.getEntityName(), reply); // responde com o SEU nome
		}
		playSoundTo(sp, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSER, 0.7f, 0.7f, this.getPos());
	}

	private String pick(String... options) {
		return options[this.random.nextInt(options.length)];
	}

	private static boolean has(String m, Set<String> words, String... keys) {
		for (String k : keys) {
			if (k.contains(" ") ? m.contains(k) : words.contains(k)) return true;
		}
		return false;
	}

	private String buildReply(String me, String raw, double dist) {
		String m = raw.toLowerCase(Locale.ROOT);
		Set<String> words = new HashSet<>();
		for (String w : m.split("[^\\p{L}]+")) if (!w.isEmpty()) words.add(w);

		if (has(m, words, "socorro", "help", "ajuda"))
			return pick("ninguém vai te ajudar.", "ninguém está ouvindo. só eu.", "grite mais alto.");
		if (has(m, words, "vai embora", "sai daqui", "me deixa", "pare", "para"))
			return pick("eu não vou embora.", "foi você que entrou no meu mundo.", "eu nunca paro.");
		if (has(m, words, "mate", "matar", "vou te", "bater"))
			return pick("tente.", "eu já estou morto, " + me + ".", "você não me mata olhando pro chão.");
		if (has(m, words, "quem", "o que é", "oq é", "que é você"))
			return pick("eu sou o que olha quando você não olha.", "você já sabe quem eu sou.", "a última coisa que você vai ver.");
		if (has(m, words, "onde", "cadê", "cade"))
			return dist < 15 ? "perto. mais perto do que você pensa." : pick("atrás de você.", "onde você não está olhando.");
		if (has(m, words, "medo", "assustado", "assusta"))
			return pick("bom.", "eu sinto o seu medo daqui.", "continue com medo.");
		if (has(m, words, "vigia", "watcher"))
			return pick("você disse meu nome.", "diga de novo.");
		if (has(m, words, "oi", "olá", "ola", "eae", "e aí", "e ai", "hello", "hi"))
			return "oi, " + me + ". eu estava te esperando.";
		if (m.contains("?"))
			return pick("não faça perguntas cujas respostas você não quer.", "você já sabe a resposta.", "talvez.", "olhe para trás e descubra.");

		String echo = raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
		return pick("\"" + echo + "\"... eu ouvi.", "continue falando. eu gosto de ouvir.",
				"ninguém vai responder além de mim.", me + "... " + me + "... " + me + "...");
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
