package software.bernie.geckolib3.model;

import java.util.Collections;

import javax.annotation.Nullable;

import com.eliotlash.molang.MolangParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.util.NativeUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.IAnimatableModel;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.exception.GeckoLibException;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.model.provider.GeoModelProvider;
import software.bernie.geckolib3.model.provider.IAnimatableModelProvider;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.MolangUtils;

public abstract class AnimatedGeoModel<T extends IAnimatable> extends GeoModelProvider<T>
		implements IAnimatableModel<T>, IAnimatableModelProvider<T> {
	private final AnimationProcessor animationProcessor;
	private GeoModel currentModel;

	protected AnimatedGeoModel() {
		this.animationProcessor = new AnimationProcessor(this);
	}

	public void registerBone(GeoBone bone) {
		registerModelRenderer(bone);

		for (GeoBone childBone : bone.childBones) {
			registerBone(childBone);
		}
	}

	@Override
	public void setLivingAnimations(T entity, Integer uniqueID, @Nullable AnimationEvent customPredicate) {
		// Each animation has it's own collection of animations (called the
		// EntityAnimationManager), which allows for multiple independent animations
		AnimationData manager = entity.getFactory().getOrCreateAnimationData(uniqueID);
		if (manager.startTick == null) {
			manager.startTick = getCurrentTick();
		}

		if (!Minecraft.getInstance().isGamePaused() || manager.shouldPlayWhilePaused) {
			manager.tick = (getCurrentTick() - manager.startTick);
			double gameTick = manager.tick;
			double deltaTicks = gameTick - lastGameTickTime;
			seekTime += deltaTicks;
			lastGameTickTime = gameTick;
		}

		AnimationEvent<T> predicate;
		if (customPredicate == null) {
			predicate = new AnimationEvent<T>(entity, 0, 0, 0, false, Collections.emptyList());
		} else {
			predicate = customPredicate;
		}

		predicate.animationTick = seekTime;
		animationProcessor.preAnimationSetup(predicate.getAnimatable(), seekTime);
		if (!this.animationProcessor.getModelRendererList().isEmpty()) {
			animationProcessor.tickAnimation(entity, uniqueID, seekTime, predicate, GeckoLibCache.getInstance().parser,
					shouldCrashOnMissing);
		}
	}

	@Override
	public AnimationProcessor getAnimationProcessor() {
		return this.animationProcessor;
	}

	public void registerModelRenderer(IBone modelRenderer) {
		animationProcessor.registerModelRenderer(modelRenderer);
	}

	@Override
	public Animation getAnimation(String name, IAnimatable animatable) {
		AnimationFile animation = GeckoLibCache.getInstance().getAnimations()
				.get(this.getAnimationFileLocation((T) animatable));
		if (animation == null) {
			throw new GeckoLibException(this.getAnimationFileLocation((T) animatable),
					"Could not find animation file. Please double check name.");
		}
		return animation.getAnimation(name);
	}

	@Override
	public GeoModel getModel(ResourceLocation location) {
		GeoModel model = super.getModel(location);
		if (model == null) {
			throw new GeckoLibException(location,
					"Could not find model. If you are getting this with a built mod, please just restart your game.");
		}
		if (model != currentModel) {
			this.animationProcessor.clearModelRendererList();
			for (GeoBone bone : model.topLevelBones) {
				registerBone(bone);
			}
			this.currentModel = model;
		}
		return model;
	}

	@Override
	public void setMolangQueries(IAnimatable animatable, double currentTick) {
		MolangParser parser = GeckoLibCache.getInstance().parser;
		Minecraft minecraftInstance = Minecraft.getInstance();

		parser.setValue("query.actor_count", minecraftInstance.world.getCountLoadedEntities());
		parser.setValue("query.time_of_day", MolangUtils.normalizeTime(minecraftInstance.world.getDayTime()));
		parser.setValue("query.moon_phase", minecraftInstance.world.getMoonPhase());

		if (animatable instanceof Entity) {
			parser.setValue("query.distance_from_camera", minecraftInstance.gameRenderer.getActiveRenderInfo()
					.getProjectedView().distanceTo(((Entity) animatable).getPositionVec()));
			parser.setValue("query.is_on_ground", MolangUtils.booleanToFloat(((Entity) animatable).isOnGround()));
			parser.setValue("query.is_in_water", MolangUtils.booleanToFloat(((Entity) animatable).isInWater()));
			// Should probably check specifically whether it's in rain?
			parser.setValue("query.is_in_water_or_rain",
					MolangUtils.booleanToFloat(((Entity) animatable).isInWaterRainOrBubbleColumn()));

			if (animatable instanceof LivingEntity) {
				LivingEntity livingEntity = (LivingEntity) animatable;
				parser.setValue("query.health", livingEntity.getHealth());
				parser.setValue("query.max_health", livingEntity.getMaxHealth());
				parser.setValue("query.is_on_fire", MolangUtils.booleanToFloat(livingEntity.isBurning()));
				// Doesn't work for some reason?
				parser.setValue("query.on_fire_time", livingEntity.getFireTimer());

				Vector3d velocity = livingEntity.getMotion();
				float groundSpeed = MathHelper.sqrt((velocity.x * velocity.x) + (velocity.z * velocity.z));
				parser.setValue("query.ground_speed", groundSpeed);

				float yawSpeed = livingEntity.getYaw((float) currentTick)
						- livingEntity.getYaw((float) (currentTick - 0.1));
				parser.setValue("query.yaw_speed", yawSpeed);
			}
		}
	}

	@Override
	public double getCurrentTick() {
		return NativeUtil.getTime() * 20;
	}
}
