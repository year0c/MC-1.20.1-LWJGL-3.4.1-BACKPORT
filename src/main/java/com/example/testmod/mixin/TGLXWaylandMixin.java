package com.example.testmod.mixin;

import com.mojang.blaze3d.platform.GLX;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.LongSupplier;

@Mixin(GLX.class)
public class TGLXWaylandMixin {

    @Inject(method = "_initGlfw", at = @At(value = "INVOKE", target = "org/lwjgl/glfw/GLFW.glfwInit()Z", shift = At.Shift.BEFORE))
    private static void setWaylandPlatform(CallbackInfoReturnable<LongSupplier> cir) {
        if (System.getenv("WAYLAND_DISPLAY") != null
                && GLFW.glfwPlatformSupported(GLFW.GLFW_PLATFORM_WAYLAND)) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
        }
    }
}   