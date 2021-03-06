/*
 * Copyright 2020 the original author or authors.
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

package gradlebuild

import org.gradle.api.tasks.testing.Test


val propagatedEnvironmentVariables = listOf(
    // Obviously necessary
    "HOME",
    "SHELL",
    "TERM",

    // Otherwise Windows complains "Unrecognized Windows Sockets error: 10106"
    "SystemRoot",
    "OS",

    // For Android tests
    "ANDROID_HOME",

    // Used by Visual Studio
    "USERNAME",
    "USER",
    "USERDOMAIN",
    "USERPROFILE",
    "LOCALAPPDATA",

    // Used by Gradle test infrastructure
    "REPO_MIRROR_URL",

    // temp dir
    "TMPDIR",
    "TMP",
    "TEMP",

    // Seems important on Windows
    "ALLUSERSPROFILE",
    "PUBLIC",
    "windir"
)


val credentialsKeywords = listOf(
    "api_key",
    "access_key",
    "apikey",
    "accesskey",
    "password",
    "token",
    "credential",
    "auth"
)


fun Test.filterEnvironmentVariables() {
    environment = System.getenv().entries.mapNotNull(::sanitize).toMap()
    environment.forEach { (key, value) ->
        if (credentialsKeywords.any { key.contains(it, true) }) {
            throw IllegalArgumentException("Found sensitive data in filtered environment variables: $key")
        }
    }
}


private
fun sanitize(entry: MutableMap.MutableEntry<String, String>): Pair<String, String>? {
    return when {
        entry.key in propagatedEnvironmentVariables -> entry.key to entry.value
        entry.key.startsWith("LC_") -> entry.key to entry.value
        entry.key.startsWith("LANG") -> entry.key to entry.value
        entry.key.startsWith("JDK_") -> entry.key to entry.value
        entry.key.startsWith("JRE_") -> entry.key to entry.value

        // Visual Studio installation info
        entry.key.startsWith("VS") -> entry.key to entry.value
        entry.key.startsWith("CommonProgram") -> entry.key to entry.value
        entry.key.startsWith("ProgramFiles") -> entry.key to entry.value
        entry.key.startsWith("ProgramData") -> entry.key to entry.value

        entry.key.equals("Path", true) -> entry.key to entry.value
        else -> null
    }
}
