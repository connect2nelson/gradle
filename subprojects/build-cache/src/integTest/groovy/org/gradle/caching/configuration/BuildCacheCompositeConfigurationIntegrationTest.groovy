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

package org.gradle.caching.configuration

import org.gradle.caching.internal.FinalizeBuildCacheConfigurationBuildOperationType
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.TestBuildCache
import org.gradle.internal.operations.trace.BuildOperationTrace
import spock.lang.Issue
/**
 * Tests build cache configuration within composite builds and buildSrc.
 */
class BuildCacheCompositeConfigurationIntegrationTest extends AbstractIntegrationSpec {

    def operations = new BuildOperationsFixture(executer, testDirectoryProvider)

    def setup() {
        executer.beforeExecute {
            withBuildCacheEnabled()
        }
    }

    def "can configure with settings.gradle"() {
        def mainCache = new TestBuildCache(file("main-cache"))
        def buildSrcCache = new TestBuildCache(file("buildSrc-cache"))
        def i1Cache = new TestBuildCache(file("i1-cache"))
        def i1BuildSrcCache = new TestBuildCache(file("i1-buildSrc-cache"))
        def i2Cache = new TestBuildCache(file("i2-cache"))
        def i3Cache = new TestBuildCache(file("i3-cache"))

        settingsFile << mainCache.localCacheConfiguration() << """
            includeBuild "i1"
            includeBuild "i2"
        """

        file("buildSrc/settings.gradle") << buildSrcCache.localCacheConfiguration()
        file("i1/settings.gradle") << i1Cache.localCacheConfiguration()
        file("i1/buildSrc/settings.gradle") << i1BuildSrcCache.localCacheConfiguration()
        file("i2/settings.gradle") << i2Cache.localCacheConfiguration()

        buildFile << customTaskCode("root")
        file("buildSrc/build.gradle") << customTaskCode("buildSrc") << """
            build.dependsOn customTask
        """
        file("i1/build.gradle") << customTaskCode("i1")
        file("i1/buildSrc/build.gradle") << customTaskCode("i1:buildSrc") << """
            build.dependsOn customTask
        """
        file("i2/build.gradle") << customTaskCode("i2") << """

            task gradleBuild(type: GradleBuild) {
                dir = "../i3"
                tasks = ["customTask"]

                // Trace fixture doesn't work well with GradleBuild, turn it off 
                startParameter.systemPropertiesArgs["$BuildOperationTrace.SYSPROP"] = "false"
            }
            
            customTask.dependsOn gradleBuild
        """
        file("i3/settings.gradle") << i3Cache.localCacheConfiguration()
        file("i3/build.gradle") << customTaskCode("i3")

        buildFile << """
            task all { dependsOn gradle.includedBuilds*.task(':customTask'), tasks.customTask } 
        """

        expect:
        succeeds "all", "-i"

        and:
        i1Cache.assertEmpty()
        i1BuildSrcCache.assertEmpty()
        i2Cache.assertEmpty()
        mainCache.listCacheFiles().size() == 4 // root, i1, i1BuildSrc, i2

        buildSrcCache.listCacheFiles().size() == 1
        i3Cache.listCacheFiles().size() == 1

        and:
        result.assertOutputContains "Using local directory build cache for build ':buildSrc' (location = ${buildSrcCache.cacheDir}, targetSize = 5 GB)."
        result.assertOutputContains "Using local directory build cache for build ':i2:i3' (location = ${i3Cache.cacheDir}, targetSize = 5 GB)."
        result.assertOutputContains "Using local directory build cache for the root build (location = ${mainCache.cacheDir}, targetSize = 5 GB)."

        and:
        def finalizeOps = operations.all(FinalizeBuildCacheConfigurationBuildOperationType)
        finalizeOps.size() == 2
        def pathToCacheDirMap = finalizeOps.collectEntries {
            [
                it.details.buildPath,
                new File(it.result.local.config.location as String)
            ]
        } as Map<String, File>

        pathToCacheDirMap == [
            ":": mainCache.cacheDir,
            ":buildSrc": buildSrcCache.cacheDir
        ]
    }

    @Issue("https://github.com/gradle/gradle/issues/4216")
    def "build cache service is closed only after all included builds are finished"() {
        def localCache = new TestBuildCache(file("local-cache"))

        buildTestFixture.withBuildInSubDir()
        ['i1', 'i2'].each {
            multiProjectBuild(it, ['first', 'second']) {
                buildFile << """
                gradle.startParameter.setTaskNames(['build'])
                println gradle
                allprojects {
                    apply plugin: 'java-library'
                }
                """
            }
        }

        settingsFile << localCache.localCacheConfiguration() << """
            includeBuild "i1"
            includeBuild "i2"
        """
        buildFile << """             
            apply plugin: 'java'
            println gradle
            gradle.startParameter.setTaskNames(['build'])
            processResources {
                dependsOn gradle.includedBuild('i1').task(':processResources')
                dependsOn gradle.includedBuild('i2').task(':processResources')
            }
        """

        expect:
        succeeds '--parallel'

        when:
        ['i1', 'i2'].each {
            file("$it/src/test/java/DummyTest.java") << "public class DummyTest {}"
        }
        then:
        succeeds '--parallel'
    }

    private static String customTaskCode(String val = "foo") {
        """
            @CacheableTask
            class CustomTask extends DefaultTask {
                @Input
                String val

                @OutputFile
                File outputFile = new File(temporaryDir, "output.txt")

                @TaskAction
                void generate() {
                    outputFile.text = val
                }
            }

            task customTask(type: CustomTask) { val = "$val" }
        """
    }
}
