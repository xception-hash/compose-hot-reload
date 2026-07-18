package dev.hotreload.fixture;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Marks the app project so this composite build is evaluated through an applied plugin. */
public final class BuildLogicPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("fixtureBuildLogicApplied", true);
    }
}
