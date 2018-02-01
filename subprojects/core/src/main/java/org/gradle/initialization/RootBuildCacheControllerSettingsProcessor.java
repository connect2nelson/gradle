/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.initialization;

import org.gradle.StartParameter;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.caching.configuration.internal.BuildCacheConfigurationInternal;
import org.gradle.caching.internal.controller.RootBuildCacheConfigurationRef;

public class RootBuildCacheControllerSettingsProcessor implements SettingsProcessor {

    private final SettingsProcessor delegate;

    public RootBuildCacheControllerSettingsProcessor(SettingsProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public SettingsInternal process(GradleInternal gradle, SettingsLocation settingsLocation, ClassLoaderScope buildRootClassLoaderScope, StartParameter startParameter) {
        SettingsInternal settings = delegate.process(gradle, settingsLocation, buildRootClassLoaderScope, startParameter);

        // The strategy for sharing build cache configuration across included builds in a composite,
        // requires that the cache configuration be finalized
        // before configuring them. This achieves that.

        if (gradle.getParent() == null) {
            BuildCacheConfigurationInternal rootConfiguration = gradle.getServices().get(BuildCacheConfigurationInternal.class);
            RootBuildCacheConfigurationRef rootConfigurationRef = gradle.getServices().get(RootBuildCacheConfigurationRef.class);
            rootConfigurationRef.set(rootConfiguration);
        }

        return settings;

    }
}
