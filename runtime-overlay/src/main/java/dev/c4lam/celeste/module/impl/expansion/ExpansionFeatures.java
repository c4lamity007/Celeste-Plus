package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.module.ModuleManager;

public final class ExpansionFeatures {
    private ExpansionFeatures() {
    }

    public static void register(ModuleManager modules) {
        modules.register(new AutoHeadHitterFeature());
        modules.register(new AutoFireworkFeature());
        modules.register(new AutoToolFeature());
        modules.register(new KeepSprintFeature());
        modules.register(new AutoMlgFeature());
        modules.register(new WebBreakerFeature());
        modules.register(new CoverUpFeature());
        modules.register(new TrapSaveFeature());
    }
}
